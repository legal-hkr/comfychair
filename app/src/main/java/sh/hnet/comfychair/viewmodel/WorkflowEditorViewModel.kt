package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.NodeAttributeEdits
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.ConnectionValidator
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.FieldMappingAnalyzer
import sh.hnet.comfychair.util.GraphMutationUtils
import sh.hnet.comfychair.util.GraphViewportCalculator
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.PrivacyUtils
import sh.hnet.comfychair.util.WorkflowJsonAnalyzer
import sh.hnet.comfychair.workflow.ConnectionDirection
import sh.hnet.comfychair.workflow.ConnectionModeState
import sh.hnet.comfychair.workflow.DiscardAction
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.GraphBounds
import sh.hnet.comfychair.workflow.GroupManager
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.MutableWorkflowGraph
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.NoteManager
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.PendingMappedLink
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowEdge
import sh.hnet.comfychair.workflow.WorkflowEditorUiState
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowGraphPreparation
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.WorkflowNote
import sh.hnet.comfychair.workflow.WorkflowParser
import sh.hnet.comfychair.workflow.WorkflowSerializer
import sh.hnet.comfychair.workflow.changedInputNames
import sh.hnet.comfychair.workflow.getEffectiveDefault
import sh.hnet.comfychair.workflow.nodeCategoryOf
import sh.hnet.comfychair.workflow.sortInputsForLayout
import sh.hnet.comfychair.workflow.workflowValueOf

/**
 * Events emitted by the Workflow Editor
 */
sealed class WorkflowEditorEvent {
    data class MappingConfirmed(val mappingsJson: String) : WorkflowEditorEvent()
    object MappingCancelled : WorkflowEditorEvent()
    data class WorkflowCreated(val workflowId: String) : WorkflowEditorEvent()
    data class WorkflowUpdated(val workflowId: String) : WorkflowEditorEvent()
    object CreateCancelled : WorkflowEditorEvent()
}

/**
 * ViewModel for the Workflow Editor screen
 */
class WorkflowEditorViewModel : ViewModel() {

    companion object {
        private const val TAG = "WorkflowEditor"
    }

    private val _uiState = MutableStateFlow(WorkflowEditorUiState())
    val uiState: StateFlow<WorkflowEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WorkflowEditorEvent>()
    val events: SharedFlow<WorkflowEditorEvent> = _events.asSharedFlow()

    private val parser = WorkflowParser()
    // Laid out for the proportions of the free canvas area (a phone held upright), once the screen gives them
    private var layoutAspect: Float? = null
    private val layoutEngine: WorkflowLayoutEngine
        get() = WorkflowLayoutEngine(layoutAspect)

    // Use shared NodeTypeRegistry from ConnectionManager (populated on connection)
    private val nodeTypeRegistry: NodeTypeRegistry
        get() = ConnectionManager.nodeTypeRegistry

    /**
     * Values that took a link in this editing session, by node and input: Disconnect brings them back. Not saved – the
     * workflow's format has no place for a linked input's value – so after saving, Disconnect gives the node's default.
     */
    private val keptValues = mutableMapOf<Pair<String, String>, Any>()

    init {
        // The server's node definitions load in the background after connecting: a workflow opened before they
        // arrived gets its definitions (dropdowns, missing nodes, sockets' types) once they're there
        viewModelScope.launch {
            ConnectionManager.modelCache.map { it.isLoaded }.distinctUntilChanged().collect { loaded ->
                val graph = _uiState.value.graph
                if (loaded && graph != null) {
                    _uiState.value = _uiState.value.copy(nodeDefinitions = buildNodeDefinitionsMap(graph))
                }
            }
        }
    }

    private var canvasWidth: Float = 0f
    private var canvasHeight: Float = 0f
    // What floats over the canvas (the top row, the toolbar) and the margin at the sides, in px
    private var chromeTop: Float = 0f
    private var chromeBottom: Float = 0f
    private var sideMargin: Float = 0f
    // Until the user moves the view, it follows the layout: a graph laid out again as it settles (notes measured when
    // first drawn, the screen's proportions arriving) is fitted again
    private var viewFollowsLayout = true
    // Node values: the canvas area left free by the top row and the values sheet, in px
    private var valuesViewportTop: Float = 0f
    private var valuesSheetHeight: Float = 0f
    private var currentWorkflowId: String? = null
    private var workflowValuesStorage: WorkflowValuesStorage? = null

    // Fit mode for reset zoom button toggle
    private enum class FitMode { FIT_ALL, FIT_WIDTH, FIT_HEIGHT }
    private var lastFitMode: FitMode = FitMode.FIT_ALL

    // Mutable graph for editing operations
    private var mutableGraph: MutableWorkflowGraph? = null
    private var nodeIdCounter: Int = 0
    private var groupIdCounter: Int = 0

    // Original graph before editing (for discard/restore)
    private var originalGraph: WorkflowGraph? = null
    private var originalBounds: GraphBounds? = null

    // Guard against re-initialization (e.g., Activity recreation on theme change)
    private var isInitialized = false

    /**
     * Initialize the editor with a workflow ID (view mode)
     */
    fun initialize(context: Context, workflowId: String?, workflowJson: String?) {
        // Skip if already initialized (e.g., Activity recreation on theme change)
        if (isInitialized) {
            DebugLogger.d(TAG, "initialize: Already initialized, skipping reload")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // Set up storage for node attribute edits
        workflowValuesStorage = WorkflowValuesStorage(context)
        currentWorkflowId = workflowId

        // Fetch object_info first for edge type resolution, then parse workflow
        fetchObjectInfo { _ ->
            // Continue regardless of success - edges will use default color if fetch failed
            try {
                val jsonContent: String
                val name: String
                val description: String
                var workflowType: WorkflowType? = null
                var isBuiltIn = false

                if (workflowJson != null) {
                    // Direct JSON content provided
                    jsonContent = workflowJson
                    name = "Workflow Preview"
                    description = ""
                } else if (workflowId != null) {
                    // Load from WorkflowManager
                    WorkflowManager.ensureInitialized(context)
                    val workflow = WorkflowManager.getWorkflowById(workflowId)

                    if (workflow == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = context.getString(R.string.error_workflow_not_found)
                        )
                        return@fetchObjectInfo
                    }

                    jsonContent = workflow.jsonContent
                    name = workflow.name
                    description = workflow.description
                    workflowType = workflow.type
                    isBuiltIn = workflow.isBuiltIn
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.error_no_workflow_specified)
                    )
                    return@fetchObjectInfo
                }

                // Parse the workflow
                var graph = parser.parse(jsonContent, name, description)

                // Populate node outputs from registry
                graph = populateNodeOutputs(graph)

                // Layout the graph
                graph = layoutEngine.layoutGraph(graph)

                // Resolve edge types for colored connections
                graph = resolveEdgeTypes(graph)

                // Calculate bounds
                val bounds = layoutEngine.calculateBounds(graph)

                // Build node definitions map
                val nodeDefinitions = buildNodeDefinitionsMap(graph)

                // Load existing node attribute edits
                val existingEdits = loadNodeAttributeEdits()

                _uiState.value = _uiState.value.copy(
                    graph = graph,
                    workflowName = name,
                    isLoading = false,
                    errorMessage = null,
                    graphBounds = bounds,
                    isFieldMappingMode = false,
                    nodeDefinitions = nodeDefinitions,
                    nodeAttributeEdits = existingEdits,
                    // Store workflow metadata for later use in edit mode
                    editingWorkflowId = workflowId,
                    editingWorkflowType = workflowType,
                    originalWorkflowName = name,
                    originalWorkflowDescription = description,
                    viewingWorkflowIsBuiltIn = isBuiltIn,
                    // Sync manual placement mode from graph
                    isManualPlacementMode = graph.isManualPlacementMode
                )
                isInitialized = true
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load workflow: ${e.message}"
                )
            }
        }
    }

    /**
     * Initialize the editor for field mapping mode (new workflow upload)
     */
    fun initializeForMapping(
        jsonContent: String,
        name: String,
        description: String,
        mappingState: WorkflowMappingState
    ) {
        // Skip if already initialized (e.g., Activity recreation on theme change)
        if (isInitialized) {
            DebugLogger.d(TAG, "initializeForMapping: Already initialized, skipping reload")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // Fetch object_info first for edge type resolution, then parse workflow
        fetchObjectInfo { _ ->
            // Continue regardless of success - edges will use default color if fetch failed
            try {
                // Parse the workflow
                var graph = parser.parse(jsonContent, name, description)

                // Populate node outputs from registry
                graph = populateNodeOutputs(graph)

                // Layout the graph
                graph = layoutEngine.layoutGraph(graph)

                // Resolve edge types for colored connections
                graph = resolveEdgeTypes(graph)

                // Calculate bounds
                val bounds = layoutEngine.calculateBounds(graph)

                // Calculate highlighted nodes from mapping state
                val highlightedNodes = calculateHighlightedNodes(mappingState)

                _uiState.value = _uiState.value.copy(
                    graph = graph,
                    workflowName = name,
                    isLoading = false,
                    errorMessage = null,
                    graphBounds = bounds,
                    nodeDefinitions = buildNodeDefinitionsMap(graph),
                    isFieldMappingMode = true,
                    isImportMapping = true,
                    mappingState = mappingState,
                    selectedNodeIds = emptySet(),  // Ensure selection is clear in mapping mode
                    selectedNoteIds = emptySet(),
                    highlightedNodeIds = highlightedNodes,
                    canConfirmMapping = mappingState.allRequiredFieldsMapped
                )
                isInitialized = true
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load workflow: ${e.message}"
                )
            }
        }
    }

    /**
     * Initialize the editor for creating a new workflow from scratch.
     * Creates an empty mutable graph and enters edit mode automatically.
     */
    fun initializeForCreation(context: Context) {
        // Skip if already initialized (e.g., Activity recreation on theme change)
        if (isInitialized) {
            DebugLogger.d(TAG, "initializeForCreation: Already initialized, skipping reload")
            return
        }

        DebugLogger.i(TAG, "initializeForCreation: Starting create mode")
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // Fetch object_info first for node definitions
        fetchObjectInfo { success ->
            DebugLogger.d(TAG, "initializeForCreation: fetchObjectInfo success=$success")
            // Create empty graph
            val emptyGraph = WorkflowGraph(
                name = "",
                description = "",
                nodes = emptyList(),
                edges = emptyList(),
                templateVariables = emptySet()
            )

            mutableGraph = MutableWorkflowGraph.fromImmutable(emptyGraph)
            nodeIdCounter = 1
            groupIdCounter = 1

            _uiState.value = _uiState.value.copy(
                graph = emptyGraph,
                workflowName = "",
                isLoading = false,
                errorMessage = null,
                isCreateMode = true,
                isEditMode = true,  // Start in edit mode
                hasUnsavedChanges = false,
                graphBounds = GraphBounds()
            )
            isInitialized = true
        }
    }

    /**
     * Initialize the editor for editing an existing user workflow's structure.
     * Loads the workflow, enters edit mode, but preserves existing metadata.
     * Save flow skips the save dialog and updates the existing file.
     */
    fun initializeForEditingExisting(context: Context, workflowId: String) {
        // Skip if already initialized (e.g., Activity recreation on theme change)
        if (isInitialized) {
            DebugLogger.d(TAG, "initializeForEditingExisting: Already initialized, skipping reload")
            return
        }

        DebugLogger.i(TAG, "initializeForEditingExisting: Loading workflow $workflowId")
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        workflowValuesStorage = WorkflowValuesStorage(context)
        currentWorkflowId = workflowId

        fetchObjectInfo { _ ->
            try {
                WorkflowManager.ensureInitialized(context)
                val workflow = WorkflowManager.getWorkflowById(workflowId)

                if (workflow == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.error_workflow_not_found)
                    )
                    return@fetchObjectInfo
                }

                // Built-in workflows cannot be edited
                if (workflow.isBuiltIn) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.error_cannot_edit_builtin)
                    )
                    return@fetchObjectInfo
                }

                // Parse the workflow
                var graph = parser.parse(workflow.jsonContent, workflow.name, workflow.description)
                graph = populateNodeOutputs(graph)
                graph = layoutEngine.layoutGraph(graph)
                graph = resolveEdgeTypes(graph)
                val bounds = layoutEngine.calculateBounds(graph)

                // Initialize mutable graph for editing
                mutableGraph = MutableWorkflowGraph.fromImmutable(graph)
                nodeIdCounter = graph.nodes.mapNotNull { it.id.toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
                groupIdCounter = graph.groups.maxOfOrNull { it.id }?.plus(1) ?: 1

                _uiState.value = _uiState.value.copy(
                    graph = graph,
                    workflowName = workflow.name,
                    isLoading = false,
                    errorMessage = null,
                    graphBounds = bounds,
                    // Every way into the editor needs them: value sockets, dropdowns and missing nodes depend on them
                    nodeDefinitions = buildNodeDefinitionsMap(graph),
                    // Edit-existing specific flags
                    isEditExistingMode = true,
                    isEditMode = true,  // Start in edit mode
                    editingWorkflowId = workflowId,
                    editingWorkflowType = workflow.type,
                    originalWorkflowName = workflow.name,
                    originalWorkflowDescription = workflow.description,
                    hasUnsavedChanges = false,
                    // Sync manual placement mode from graph
                    isManualPlacementMode = graph.isManualPlacementMode
                )
                isInitialized = true

                DebugLogger.i(TAG, "initializeForEditingExisting: Loaded workflow ${Obfuscator.workflowName(workflow.name)}, type=${workflow.type}")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "initializeForEditingExisting: Failed to load workflow: ${Obfuscator.error(e)}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load workflow: ${e.message}"
                )
            }
        }
    }

    /**
     * Calculate which nodes should be highlighted based on current mapping state
     */
    private fun calculateHighlightedNodes(mappingState: WorkflowMappingState): Set<String> {
        val highlighted = mutableSetOf<String>()
        mappingState.fieldMappings.forEach { fieldMapping ->
            fieldMapping.candidates.forEach { candidate ->
                highlighted.add(candidate.nodeId)
            }
        }
        return highlighted
    }

    /**
     * Handle node tap in field mapping mode
     */
    fun onNodeTapped(nodeId: String) {
        val state = _uiState.value
        if (!state.isFieldMappingMode) return
        val mappingState = state.mappingState ?: return

        // Find which field(s) have this node as a candidate
        val affectedMappings = mappingState.fieldMappings.filter { fieldMapping ->
            fieldMapping.candidates.any { it.nodeId == nodeId }
        }

        if (affectedMappings.isEmpty()) return

        // With a current field, only its candidates count; assigning one moves on to the next unmapped field
        val selectedFieldKey = state.selectedFieldKey
        if (selectedFieldKey != null) {
            val matchingField = affectedMappings.find { it.field.fieldKey == selectedFieldKey } ?: return
            val candidateIndex = matchingField.candidates.indexOfFirst { it.nodeId == nodeId }
            if (candidateIndex >= 0) {
                updateFieldMapping(matchingField.field.fieldKey, candidateIndex)
                selectNextField()
            }
            return
        }

        // Otherwise, select the first affected field
        val firstField = affectedMappings.first()
        val candidateIndex = firstField.candidates.indexOfFirst { it.nodeId == nodeId }
        if (candidateIndex >= 0) {
            updateFieldMapping(firstField.field.fieldKey, candidateIndex)
        }
    }

    /**
     * Update the selected candidate for a field. An input fills one field: another field mapped to the same input
     * (node and input) is cleared. Other inputs of the same node keep their fields (width and height of one node).
     */
    fun updateFieldMapping(fieldKey: String, candidateIndex: Int) {
        val state = _uiState.value
        val mappingState = state.mappingState ?: return

        // The input being selected
        val targetField = mappingState.fieldMappings.find { it.field.fieldKey == fieldKey }
        val selected = targetField?.candidates?.getOrNull(candidateIndex)

        val updatedMappings = mappingState.fieldMappings.map { fieldMapping ->
            val other = fieldMapping.selectedCandidate
            when {
                // Update the target field with new selection
                fieldMapping.field.fieldKey == fieldKey -> {
                    fieldMapping.copy(selectedCandidateIndex = candidateIndex)
                }
                // Clear another field mapped to this same input (set to -1 = unmapped)
                selected != null && other != null &&
                    other.nodeId == selected.nodeId && other.inputKey == selected.inputKey -> {
                    fieldMapping.copy(selectedCandidateIndex = -1)
                }
                else -> fieldMapping
            }
        }

        val newMappingState = mappingState.copy(fieldMappings = updatedMappings)

        _uiState.value = _uiState.value.copy(
            mappingState = newMappingState,
            canConfirmMapping = newMappingState.allRequiredFieldsMapped
        )
    }

    /**
     * Clear the mapping for a single field (sets selectedCandidateIndex to -1).
     */
    fun clearFieldMapping(fieldKey: String) {
        val state = _uiState.value
        val mappingState = state.mappingState ?: return

        val updatedMappings = mappingState.fieldMappings.map { fieldMapping ->
            if (fieldMapping.field.fieldKey == fieldKey) {
                fieldMapping.copy(selectedCandidateIndex = -1)
            } else {
                fieldMapping
            }
        }

        val newMappingState = mappingState.copy(fieldMappings = updatedMappings)

        _uiState.value = _uiState.value.copy(
            mappingState = newMappingState,
            canConfirmMapping = newMappingState.allRequiredFieldsMapped
        )
    }

    /**
     * Clear all field mappings.
     */
    fun clearAllMappings() {
        val state = _uiState.value
        val mappingState = state.mappingState ?: return

        val updatedMappings = mappingState.fieldMappings.map { fieldMapping ->
            fieldMapping.copy(selectedCandidateIndex = -1)
        }

        val newMappingState = mappingState.copy(fieldMappings = updatedMappings)

        _uiState.value = _uiState.value.copy(
            mappingState = newMappingState,
            canConfirmMapping = newMappingState.allRequiredFieldsMapped
        )
    }

    /** All fields, required first (the All fields sheet). */
    fun allMappingFields(): List<FieldMappingState> =
        _uiState.value.mappingState?.fieldMappings?.sortedBy { if (it.isRequiredField) 0 else 1 } ?: emptyList()

    /** The fields the mapping card walks: those with a node to choose, required first. */
    fun orderedMappingFields(): List<FieldMappingState> = allMappingFields().filter { it.candidates.isNotEmpty() }

    /**
     * Make the next unmapped field current (after the current one, wrapping around); stays put when all are mapped.
     */
    /**
     * After assigning a field: the next one in the list, mapped or not (the user goes through them in order; jumping
     * to the next unmapped one skipped the fields mapped on import). The last field stays selected.
     */
    fun selectNextField() {
        val fields = orderedMappingFields()
        val current = fields.indexOfFirst { it.field.fieldKey == _uiState.value.selectedFieldKey }
        val next = fields.getOrNull(current + 1) ?: return
        selectFieldForMapping(next.field.fieldKey)
    }

    /** Where mapping starts: the first field that still needs an input. */
    fun selectNextUnmappedField() {
        val fields = orderedMappingFields()
        if (fields.isEmpty()) return
        val current = fields.indexOfFirst { it.field.fieldKey == _uiState.value.selectedFieldKey }
        val next = (1..fields.size).map { fields[(current + it).mod(fields.size)] }.firstOrNull { !it.isMapped }
        selectFieldForMapping((next ?: fields.getOrNull(current) ?: fields.first()).field.fieldKey)
    }

    /**
     * Back while mapping fields during a save from the editor: back to editing the structure, nothing lost.
     */
    fun returnFromSaveMapping() {
        pendingSaveData = null
        _uiState.value = _uiState.value.copy(
            isFieldMappingMode = false,
            mappingState = null,
            selectedFieldKey = null,
            highlightedNodeIds = emptySet(),
            canConfirmMapping = false,
            isEditMode = true
        )
    }

    /**
     * Select a field to highlight its candidates
     */
    fun selectFieldForMapping(fieldKey: String?) {
        val state = _uiState.value
        val mappingState = state.mappingState ?: return

        val candidateNodeIds = if (fieldKey != null) {
            val fieldMapping = mappingState.fieldMappings.find { it.field.fieldKey == fieldKey }
            fieldMapping?.candidates?.map { it.nodeId }?.toSet() ?: emptySet()
        } else {
            // Show all candidates when no field selected
            calculateHighlightedNodes(mappingState)
        }

        _uiState.value = _uiState.value.copy(
            selectedFieldKey = fieldKey,
            highlightedNodeIds = candidateNodeIds
        )
    }

    /**
     * Get the final field mappings as JSON for returning via Intent
     * Only includes fields that are actually mapped (required + optionally mapped fields)
     */
    fun getFinalMappingsJson(): String? {
        val mappingState = _uiState.value.mappingState ?: return null
        if (!mappingState.allRequiredFieldsMapped) return null

        val json = JSONObject()
        mappingState.fieldMappings.forEach { fieldMapping ->
            val selectedCandidate = fieldMapping.selectedCandidate ?: return@forEach
            json.put(fieldMapping.field.fieldKey, JSONObject().apply {
                put("nodeId", selectedCandidate.nodeId)
                put("inputKey", selectedCandidate.inputKey)
            })
        }
        return json.toString()
    }

    /**
     * Confirm the mapping and emit event (for upload flow - returns mappings to calling activity)
     */
    fun confirmMapping() {
        DebugLogger.i(TAG, "confirmMapping: Confirming mapping for upload flow")
        val mappingsJson = getFinalMappingsJson()
        if (mappingsJson != null) {
            DebugLogger.d(TAG, "confirmMapping: Emitting MappingConfirmed event")
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.MappingConfirmed(mappingsJson))
            }
        } else {
            DebugLogger.w(TAG, "confirmMapping: No mappings JSON available")
        }
    }

    /**
     * Cancel mapping and emit event
     */
    fun cancelMapping() {
        viewModelScope.launch {
            _events.emit(WorkflowEditorEvent.MappingCancelled)
        }
    }

    // ========== Create Mode Methods ==========

    /**
     * Check if there are unsaved changes that would be lost
     */
    fun hasUnsavedWork(): Boolean {
        val state = _uiState.value

        // Check the hasUnsavedChanges flag (set when nodes are added/deleted/rewired)
        if (state.hasUnsavedChanges) return true

        // For create mode, also check if any nodes exist
        if (state.isCreateMode) {
            val graph = mutableGraph?.toImmutable() ?: return false
            return graph.nodes.isNotEmpty()
        }

        return false
    }

    /**
     * Show discard confirmation dialog with specified action
     */
    fun showDiscardConfirmation(action: DiscardAction = DiscardAction.EXIT_EDIT_MODE) {
        _uiState.value = _uiState.value.copy(
            showDiscardConfirmation = true,
            discardAction = action
        )
    }

    /**
     * Dismiss discard confirmation dialog
     */
    fun dismissDiscardConfirmation() {
        _uiState.value = _uiState.value.copy(showDiscardConfirmation = false)
    }

    /**
     * Confirm discard - action depends on discardAction state
     */
    fun confirmDiscard() {
        val action = _uiState.value.discardAction
        _uiState.value = _uiState.value.copy(showDiscardConfirmation = false)

        when (action) {
            DiscardAction.EXIT_EDIT_MODE -> exitEditMode()
            DiscardAction.CLOSE_EDITOR -> {
                viewModelScope.launch {
                    _events.emit(WorkflowEditorEvent.CreateCancelled)
                }
            }
        }
    }

    /**
     * Handle exit edit mode button - show confirmation if unsaved changes exist,
     * otherwise just exit edit mode. This returns to view mode but stays in editor.
     */
    fun handleExitEditModeWithConfirmation() {
        if (hasUnsavedWork()) {
            showDiscardConfirmation(DiscardAction.EXIT_EDIT_MODE)
        } else {
            exitEditMode()
        }
    }

    /**
     * Handle close button in create mode - show confirmation if work exists,
     * then close the editor.
     */
    fun handleCreateModeClose() {
        if (hasUnsavedWork()) {
            showDiscardConfirmation(DiscardAction.CLOSE_EDITOR)
        } else {
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.CreateCancelled)
            }
        }
    }

    /**
     * Handle close button in edit-existing mode.
     * Shows discard confirmation if there are unsaved changes, then closes editor.
     */
    fun handleEditExistingModeClose() {
        if (hasUnsavedWork()) {
            showDiscardConfirmation(DiscardAction.CLOSE_EDITOR)
        } else {
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.CreateCancelled)
            }
        }
    }

    /**
     * Show save dialog when Done is pressed in create mode.
     * For edit-existing mode, skips the dialog and proceeds directly to validation.
     * Auto-detects workflow type from graph nodes.
     */
    fun showSaveDialog(context: Context) {
        val state = _uiState.value
        val hasExistingWorkflow = state.editingWorkflowId != null
        DebugLogger.i(TAG, "showSaveDialog: hasExistingWorkflow=$hasExistingWorkflow")
        val graph = mutableGraph?.toImmutable()
        if (graph == null) {
            DebugLogger.w(TAG, "showSaveDialog: No mutable graph available")
            return
        }
        if (graph.nodes.isEmpty()) {
            DebugLogger.w(TAG, "showSaveDialog: Cannot save empty workflow")
            return
        }

        DebugLogger.d(TAG, "showSaveDialog: Graph has ${graph.nodes.size} nodes, ${graph.edges.size} edges")

        // For existing workflow, skip the dialog and proceed directly to validation
        if (hasExistingWorkflow) {
            DebugLogger.i(TAG, "showSaveDialog: Existing workflow, skipping dialog")
            proceedWithEditExistingSave(context)
            return
        }

        // Auto-detect type from nodes
        val detectedType = detectWorkflowTypeFromGraph(graph)
        DebugLogger.d(TAG, "showSaveDialog: Detected type=$detectedType")

        _uiState.value = _uiState.value.copy(
            showSaveDialog = true,
            saveDialogSelectedType = detectedType,
            saveDialogName = "",
            saveDialogDescription = "",
            saveDialogNameError = null,
            isSaveValidating = false
        )
    }

    /**
     * Cancel save dialog
     */
    fun cancelSaveDialog() {
        _uiState.value = _uiState.value.copy(
            showSaveDialog = false,
            saveDialogSelectedType = null,
            saveDialogName = "",
            saveDialogDescription = "",
            saveDialogNameError = null,
        )
    }

    /**
     * Update save dialog type selection
     */
    fun onSaveDialogTypeSelected(type: WorkflowType) {
        _uiState.value = _uiState.value.copy(
            saveDialogSelectedType = type
        )
    }

    /**
     * Update save dialog name
     */
    fun onSaveDialogNameChange(name: String) {
        _uiState.value = _uiState.value.copy(
            saveDialogName = name,
            saveDialogNameError = null
        )
    }

    /**
     * Update save dialog description
     */
    fun onSaveDialogDescriptionChange(description: String) {
        _uiState.value = _uiState.value.copy(
            saveDialogDescription = description,
        )
    }

    /**
     * Dismiss missing nodes dialog
     */
    fun dismissMissingNodesDialog() {
        _uiState.value = _uiState.value.copy(showMissingNodesDialog = false)
    }

    /**
     * Dismiss missing fields dialog
     */
    fun dismissMissingFieldsDialog() {
        _uiState.value = _uiState.value.copy(showMissingFieldsDialog = false)
    }

    /**
     * Detect workflow type by analyzing nodes in the graph.
     */
    private fun detectWorkflowTypeFromGraph(graph: WorkflowGraph): WorkflowType? {
        return WorkflowJsonAnalyzer.detectWorkflowTypeFromGraph(graph)
    }

    // Data to hold pending save information
    private data class PendingSaveData(
        val name: String,
        val description: String,
        val type: WorkflowType,
        val graph: WorkflowGraph
    )
    private var pendingSaveData: PendingSaveData? = null

    /**
     * Proceed with save - validates and enters mapping mode.
     */
    fun proceedWithSave(context: Context) {
        DebugLogger.i(TAG, "proceedWithSave: Starting validation")
        val state = _uiState.value
        val graph = mutableGraph?.toImmutable()
        if (graph == null) {
            DebugLogger.e(TAG, "proceedWithSave: No mutable graph available")
            return
        }
        val selectedType = state.saveDialogSelectedType
        if (selectedType == null) {
            DebugLogger.e(TAG, "proceedWithSave: No workflow type selected")
            return
        }
        val name = state.saveDialogName.trim()
        val description = state.saveDialogDescription.trim()

        DebugLogger.d(TAG, "proceedWithSave: name=${Obfuscator.workflowName(name)}, type=$selectedType, nodes=${graph.nodes.size}")

        // Validate name format
        val nameError = WorkflowManager.validateWorkflowName(name)
        if (nameError != null) {
            DebugLogger.w(TAG, "proceedWithSave: Name validation failed: $nameError")
            _uiState.value = _uiState.value.copy(
                saveDialogNameError = nameError
            )
            return
        }

        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
            DebugLogger.w(TAG, "proceedWithSave: Duplicate name detected")
            _uiState.value = _uiState.value.copy(saveDialogNameError = context.getString(R.string.error_name_taken))
            return
        }

        // Start validation
        _uiState.value = _uiState.value.copy(isSaveValidating = true)
        DebugLogger.d(TAG, "proceedWithSave: Validating nodes on server")

        // Validate nodes exist on server
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            DebugLogger.e(TAG, "proceedWithSave: No connection to server")
            _uiState.value = _uiState.value.copy(
                isSaveValidating = false,
                saveDialogNameError = context.getString(R.string.error_no_connection)
            )
            return
        }

        client.fetchAllNodeTypes { availableNodes ->
            viewModelScope.launch {
                if (availableNodes == null) {
                    DebugLogger.e(TAG, "proceedWithSave: Failed to fetch available nodes")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        saveDialogNameError = context.getString(R.string.error_workflow_fetch_nodes_failed)
                    )
                    return@launch
                }

                DebugLogger.d(TAG, "proceedWithSave: Server has ${availableNodes.size} node types")

                val workflowClassTypes = graph.nodes
                    .map { it.classType }
                    .toSet()
                val missingNodes = workflowClassTypes.filter { it !in availableNodes }

                if (missingNodes.isNotEmpty()) {
                    DebugLogger.w(TAG, "proceedWithSave: Missing nodes: $missingNodes")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                DebugLogger.d(TAG, "proceedWithSave: All nodes validated, creating field mappings")

                // Create field mapping state from graph
                val mappingState = createFieldMappingStateFromGraph(context, graph, selectedType)

                // Check if all REQUIRED fields can be mapped (optional fields can be unmapped)
                if (!mappingState.allRequiredFieldsMapped) {
                    val unmappedFieldNames = mappingState.unmappedRequiredFields.map { it.displayName }
                    DebugLogger.w(TAG, "proceedWithSave: Unmapped required fields: $unmappedFieldNames")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingFieldsDialog = true,
                        missingFields = unmappedFieldNames
                    )
                    return@launch
                }

                DebugLogger.i(TAG, "proceedWithSave: Validation passed, entering mapping mode")

                // Store pending save data and enter mapping mode
                pendingSaveData = PendingSaveData(
                    name = name,
                    description = description,
                    type = selectedType,
                    graph = graph
                )

                _uiState.value = _uiState.value.copy(
                    isSaveValidating = false,
                    showSaveDialog = false,
                    isFieldMappingMode = true,
                    mappingState = mappingState,
                    isEditMode = false,  // Exit edit mode
                    selectedNodeIds = emptySet(),  // Clear selection when entering mapping mode
                    selectedNoteIds = emptySet(),
                    canConfirmMapping = mappingState.allRequiredFieldsMapped,
                    highlightedNodeIds = calculateHighlightedNodes(mappingState)
                )
            }
        }
    }

    /**
     * Proceed with saving an edited existing workflow.
     * Skips save dialog since we already have metadata, uses stored workflow type.
     */
    private fun proceedWithEditExistingSave(context: Context) {
        DebugLogger.i(TAG, "proceedWithEditExistingSave: Starting validation for edit-existing mode")
        val state = _uiState.value
        val graph = mutableGraph?.toImmutable()
        if (graph == null) {
            DebugLogger.e(TAG, "proceedWithEditExistingSave: No mutable graph available")
            return
        }
        val workflowType = state.editingWorkflowType
        if (workflowType == null) {
            DebugLogger.e(TAG, "proceedWithEditExistingSave: No workflow type available")
            return
        }

        DebugLogger.d(TAG, "proceedWithEditExistingSave: type=$workflowType, nodes=${graph.nodes.size}")

        // Start validation
        _uiState.value = _uiState.value.copy(isSaveValidating = true)

        // Validate nodes exist on server
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            DebugLogger.e(TAG, "proceedWithEditExistingSave: No connection to server")
            _uiState.value = _uiState.value.copy(
                isSaveValidating = false,
                showMissingNodesDialog = true,
                missingNodes = listOf("No connection to server")
            )
            return
        }

        client.fetchAllNodeTypes { availableNodes ->
            viewModelScope.launch {
                if (availableNodes == null) {
                    DebugLogger.e(TAG, "proceedWithEditExistingSave: Failed to fetch available nodes")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingNodesDialog = true,
                        missingNodes = listOf("Failed to fetch available nodes")
                    )
                    return@launch
                }

                DebugLogger.d(TAG, "proceedWithEditExistingSave: Server has ${availableNodes.size} node types")

                val workflowClassTypes = graph.nodes
                    .map { it.classType }
                    .toSet()
                val missingNodes = workflowClassTypes.filter { it !in availableNodes }

                if (missingNodes.isNotEmpty()) {
                    DebugLogger.w(TAG, "proceedWithEditExistingSave: Missing nodes: $missingNodes")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                DebugLogger.d(TAG, "proceedWithEditExistingSave: All nodes validated, creating field mappings")

                // Create field mapping state from graph
                val mappingState = createFieldMappingStateFromGraph(context, graph, workflowType)

                // Check if all REQUIRED fields can be mapped (optional fields can be unmapped)
                if (!mappingState.allRequiredFieldsMapped) {
                    val unmappedFieldNames = mappingState.unmappedRequiredFields.map { it.displayName }
                    DebugLogger.w(TAG, "proceedWithEditExistingSave: Unmapped required fields: $unmappedFieldNames")
                    _uiState.value = _uiState.value.copy(
                        isSaveValidating = false,
                        showMissingFieldsDialog = true,
                        missingFields = unmappedFieldNames
                    )
                    return@launch
                }

                DebugLogger.i(TAG, "proceedWithEditExistingSave: Validation passed, entering mapping mode")

                // Store pending save data and enter mapping mode
                pendingSaveData = PendingSaveData(
                    name = state.originalWorkflowName,
                    description = state.originalWorkflowDescription,
                    type = workflowType,
                    graph = graph
                )

                _uiState.value = _uiState.value.copy(
                    isSaveValidating = false,
                    isFieldMappingMode = true,
                    mappingState = mappingState,
                    isEditMode = false,  // Exit edit mode
                    selectedNodeIds = emptySet(),  // Clear selection when entering mapping mode
                    selectedNoteIds = emptySet(),
                    canConfirmMapping = mappingState.allRequiredFieldsMapped,
                    highlightedNodeIds = calculateHighlightedNodes(mappingState)
                )
            }
        }
    }

    /**
     * Create field mapping state by analyzing graph nodes.
     * Delegates to FieldMappingAnalyzer utility.
     */
    private fun createFieldMappingStateFromGraph(
        context: Context,
        graph: WorkflowGraph,
        type: WorkflowType
    ): WorkflowMappingState {
        return FieldMappingAnalyzer.createFieldMappingState(context, graph, type, nodeTypeRegistry)
    }

    /**
     * Confirm mapping and save the workflow (for create mode and edit-existing mode)
     */
    fun confirmMappingAndSave(context: Context) {
        DebugLogger.i(TAG, "confirmMappingAndSave: Starting save process")
        val state = _uiState.value
        val pending = pendingSaveData
        if (pending == null) {
            DebugLogger.e(TAG, "confirmMappingAndSave: No pending save data")
            return
        }
        val mappingState = state.mappingState
        if (mappingState == null) {
            DebugLogger.e(TAG, "confirmMappingAndSave: No mapping state")
            return
        }

        DebugLogger.d(TAG, "confirmMappingAndSave: Saving ${Obfuscator.workflowName(pending.name)} as ${pending.type}, editingWorkflowId=${state.editingWorkflowId}")

        // Convert mappings to the format expected by WorkflowManager
        val fieldMappings = mutableMapOf<String, Pair<String, String>>()
        mappingState.fieldMappings.forEach { fieldMapping ->
            val selectedCandidate = fieldMapping.selectedCandidate ?: return@forEach
            fieldMappings[fieldMapping.field.fieldKey] = Pair(
                selectedCandidate.nodeId,
                selectedCandidate.inputKey
            )
            DebugLogger.d(TAG, "confirmMappingAndSave: Field '${fieldMapping.field.fieldKey}' -> node ${selectedCandidate.nodeId}, input '${selectedCandidate.inputKey}'")
        }

        // Serialize graph to JSON (with wrapper to include groups)
        DebugLogger.d(TAG, "confirmMappingAndSave: Serializing graph with ${pending.graph.nodes.size} nodes, ${pending.graph.groups.size} groups")
        val jsonContent = serializer.serializeWithWrapper(pending.graph)
        DebugLogger.d(TAG, "confirmMappingAndSave: JSON length=${jsonContent.length}")

        // Save using WorkflowManager - update if editing existing workflow, create if new
        val result = if (state.editingWorkflowId != null) {
            DebugLogger.i(TAG, "confirmMappingAndSave: Calling WorkflowManager.updateUserWorkflowWithMapping for id=${state.editingWorkflowId}")
            WorkflowManager.updateUserWorkflowWithMapping(
                workflowId = state.editingWorkflowId,
                jsonContent = jsonContent,
                fieldMappings = fieldMappings
            )
        } else {
            DebugLogger.i(TAG, "confirmMappingAndSave: Calling WorkflowManager.addUserWorkflowWithMapping")
            WorkflowManager.addUserWorkflowWithMapping(
                name = pending.name,
                description = pending.description,
                jsonContent = jsonContent,
                type = pending.type,
                fieldMappings = fieldMappings
            )
        }

        if (result.isSuccess) {
            val savedWorkflow = result.getOrThrow()
            DebugLogger.i(TAG, "confirmMappingAndSave: Workflow saved successfully with id=${savedWorkflow.id}")
            // Return to view mode instead of exiting
            returnToViewMode(savedWorkflow.id, context)
            // Emit event to notify Activity (triggers list refresh in caller)
            viewModelScope.launch {
                if (state.editingWorkflowId != null) {
                    _events.emit(WorkflowEditorEvent.WorkflowUpdated(savedWorkflow.id))
                } else {
                    _events.emit(WorkflowEditorEvent.WorkflowCreated(savedWorkflow.id))
                }
            }
        } else {
            val error = result.exceptionOrNull()
            DebugLogger.e(TAG, "confirmMappingAndSave: Save failed: ${Obfuscator.error(error)}")
            viewModelScope.launch {
                _events.emit(WorkflowEditorEvent.CreateCancelled)
            }
        }
    }

    /**
     * Check if node type registry is populated (data loaded by ConnectionManager on connection).
     * Calls the callback with true if registry is available, false otherwise.
     */
    private fun fetchObjectInfo(callback: (success: Boolean) -> Unit) {
        // Node type registry is now populated by ConnectionManager on connection
        // Just check if it's available
        callback(nodeTypeRegistry.isPopulated())
    }

    /**
     * Resolve edge types using the node type registry.
     * Adds slotType to each edge based on the source node's output type.
     */
    private fun resolveEdgeTypes(graph: WorkflowGraph): WorkflowGraph =
        WorkflowGraphPreparation.resolveEdgeTypes(graph, nodeTypeRegistry)

    /**
     * Populate node outputs using the node type registry.
     * Adds output types to each node based on its class type.
     */
    private fun populateNodeOutputs(graph: WorkflowGraph): WorkflowGraph =
        WorkflowGraphPreparation.populateNodeOutputs(graph, nodeTypeRegistry)

    /**
     * Set the canvas size for proper centering calculations
     */
    /** Where a node's values leave the graph visible: below the top row, above the values sheet. */
    fun setValuesViewport(topPx: Float, sheetHeightPx: Float) {
        valuesViewportTop = topPx
        valuesSheetHeight = sheetHeightPx
    }

    fun setCanvasSize(width: Float, height: Float) {
        if (canvasWidth != width || canvasHeight != height) {
            canvasWidth = width
            canvasHeight = height
            // Fitted on first load, and again while the view still follows the layout
            if (viewFollowsLayout) fitToScreen()
        }
    }

    /**
     * What floats over the canvas and the side margin (px), and the proportions of the area left free, known from the
     * window before the canvas exists. A graph laid out automatically before they came, and not shown yet, is laid out
     * again for them.
     */
    fun setViewport(topInset: Float, bottomInset: Float, sideMargin: Float, freeAspect: Float) {
        chromeTop = topInset
        chromeBottom = bottomInset
        this.sideMargin = sideMargin
        if (freeAspect <= 0f) return
        val previous = layoutAspect
        if (previous != null && kotlin.math.abs(previous - freeAspect) / freeAspect < 0.02f) return
        layoutAspect = freeAspect
        val graph = _uiState.value.graph ?: return
        if (graph.isManualPlacementMode || mutableGraph != null || !viewFollowsLayout) return
        val layouted = layoutEngine.layoutGraph(graph)
        _uiState.value = _uiState.value.copy(graph = layouted, graphBounds = layoutEngine.calculateBounds(layouted))
        refitIfFollowing()
    }

    /** Fit the graph again after it was laid out anew, unless the user has moved the view since it opened. */
    private fun refitIfFollowing() {
        if (viewFollowsLayout && canvasWidth > 0f && canvasHeight > 0f) fitToScreen()
    }

    /** The canvas less what floats over it and the side margins: where fitting puts the graph. */
    private fun freeArea() = GraphViewportCalculator.FreeArea(
        left = sideMargin,
        top = chromeTop,
        right = canvasWidth - sideMargin,
        bottom = canvasHeight - chromeBottom
    )

    /**
     * Update scale and offset from pan/zoom gestures
     */
    fun onTransform(scale: Float, offset: Offset) {
        viewFollowsLayout = false
        _uiState.value = _uiState.value.copy(
            scale = scale.coerceIn(0.2f, 3f),
            offset = offset
        )
    }

    /**
     * Set the zoom scale
     */
    fun setScale(scale: Float) {
        _uiState.value = _uiState.value.copy(
            scale = scale.coerceIn(0.2f, 3f)
        )
    }

    /**
     * Set the offset (pan position)
     */
    fun setOffset(offset: Offset) {
        _uiState.value = _uiState.value.copy(offset = offset)
    }

    /**
     * Zoom in by 20%, centered on the canvas center
     */
    fun zoomIn() {
        zoomTowardCenter(1.2f)
    }

    /**
     * Zoom out by 20%, centered on the canvas center
     */
    fun zoomOut() {
        zoomTowardCenter(1f / 1.2f)
    }

    /**
     * Set zoom to exactly 100%, centered on the canvas center
     */
    fun setZoom100() {
        val currentScale = _uiState.value.scale
        if (currentScale != 1f) {
            zoomTowardCenter(1f / currentScale)
        }
    }

    /**
     * Zoom by a factor while keeping the canvas center stationary
     */
    private fun zoomTowardCenter(zoomFactor: Float) {
        viewFollowsLayout = false
        val center = Offset(canvasWidth / 2, canvasHeight / 2)
        val transform = GraphViewportCalculator.zoomTowardPoint(
            focusPoint = center,
            currentScale = _uiState.value.scale,
            currentOffset = _uiState.value.offset,
            zoomFactor = zoomFactor
        )
        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
    }

    /**
     * Fit the entire graph into the free area, centred
     */
    private fun fitToScreen() {
        val transform = GraphViewportCalculator.fitToScreen(_uiState.value.graphBounds, freeArea()) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_ALL
    }

    /**
     * Fit the graph width to the free area, showing the top of the graph when it's taller
     */
    private fun fitToWidth() {
        val transform = GraphViewportCalculator.fitToWidth(_uiState.value.graphBounds, freeArea()) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_WIDTH
    }

    /**
     * Fit the graph height to the free area, showing the left side of the graph when it's wider
     */
    private fun fitToHeight() {
        val transform = GraphViewportCalculator.fitToHeight(_uiState.value.graphBounds, freeArea()) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_HEIGHT
    }

    /**
     * Toggle fit mode - switches between fit to height and fit to width.
     * Initial view is "fit all", then toggles between height and width.
     */
    fun resetView() {
        viewFollowsLayout = false
        when (lastFitMode) {
            FitMode.FIT_ALL -> fitToHeight()
            FitMode.FIT_HEIGHT -> fitToWidth()
            FitMode.FIT_WIDTH -> fitToHeight()
        }
    }

    // ===========================================
    // Node Attribute Editing
    // ===========================================

    /**
     * Build a map of classType -> NodeTypeDefinition for all nodes in the graph.
     */
    private fun buildNodeDefinitionsMap(graph: WorkflowGraph): Map<String, NodeTypeDefinition> =
        WorkflowGraphPreparation.nodeDefinitions(graph, nodeTypeRegistry)

    /**
     * Load node attribute edits from storage.
     */
    private fun loadNodeAttributeEdits(): Map<String, Map<String, Any>> {
        val workflowId = currentWorkflowId ?: run {
            DebugLogger.w(TAG, "loadNodeAttributeEdits: No workflowId")
            return emptyMap()
        }
        val storage = workflowValuesStorage ?: run {
            DebugLogger.w(TAG, "loadNodeAttributeEdits: No storage")
            return emptyMap()
        }
        val serverId = sh.hnet.comfychair.connection.ConnectionManager.currentServerId ?: run {
            DebugLogger.w(TAG, "loadNodeAttributeEdits: No serverId (ConnectionManager.currentServerId is null)")
            return emptyMap()
        }

        val values = storage.loadValues(serverId, workflowId)
        val editsJson = values?.nodeAttributeEdits ?: run {
            DebugLogger.d(TAG, "loadNodeAttributeEdits: No saved edits for server=$serverId, workflow=$workflowId")
            return emptyMap()
        }

        val edits = NodeAttributeEdits.fromJson(editsJson).edits
        DebugLogger.d(TAG, "loadNodeAttributeEdits: Loaded ${edits.size} node edits for server=$serverId, workflow=$workflowId")
        return edits
    }

    /**
     * Handle node tap for editing (view mode only, not mapping mode).
     */
    fun onNodeTappedForEditing(node: WorkflowNode) {
        val state = _uiState.value

        // Don't allow editing in mapping mode
        if (state.isFieldMappingMode) return
        // The view moves to the node: from now on the user's
        viewFollowsLayout = false

        // Only save current scale and offset if NOT already editing
        // This preserves the original view state when switching between nodes
        val savedScale = if (state.isEditingNode) state.savedScaleBeforeEditing else state.scale
        val savedOffset = if (state.isEditingNode) state.savedOffsetBeforeEditing else state.offset

        // The node goes into the free area above the values sheet (spec: upper part of the screen), fitted to it
        val padding = 48f
        val top = valuesViewportTop + padding
        val bottom = (canvasHeight - valuesSheetHeight - padding).coerceAtLeast(top + 1f)
        val availableWidth = canvasWidth - padding * 2
        val availableHeight = bottom - top
        val scaleToFitWidth = if (node.width > 0) availableWidth / node.width else 1f
        val scaleToFitHeight = if (node.height > 0) availableHeight / node.height else 1f
        val newScale = minOf(scaleToFitWidth, scaleToFitHeight).coerceIn(0.5f, 2.0f)
        val newOffsetX = canvasWidth / 2 - (node.x + node.width / 2) * newScale
        val newOffsetY = (top + bottom) / 2 - (node.y + node.height / 2) * newScale

        // Compute editable input names (as editableInputNames() in workflow/NodeValues.kt)
        val nodeDefinition = state.nodeDefinitions[node.classType]
        val editableInputNames = node.inputs.filter { (name, value) ->
            // Skip connections
            if (value is InputValue.Connection) return@filter false

            // Skip template variables and other values filled in at generation time
            if (value is InputValue.Literal && TemplateKeyRegistry.isAppManagedValue(value.value)) return@filter false

            // Skip force-input fields
            val definition = nodeDefinition?.inputs?.find { it.name == name }
            if (definition?.forceInput == true) return@filter false

            true
        }.keys

        // Pre-populate edits for empty values with their effective defaults
        val currentEdits = state.nodeAttributeEdits.toMutableMap()
        val nodeEdits = currentEdits[node.id]?.toMutableMap() ?: mutableMapOf()
        var editsAdded = false

        editableInputNames.forEach { inputName ->
            // Skip if already has an edit
            if (nodeEdits.containsKey(inputName)) return@forEach

            val inputValue = node.inputs[inputName]
            val rawValue = (inputValue as? InputValue.Literal)?.value

            // If value is null or empty string, set to effective default
            if (rawValue == null || rawValue == "") {
                val inputDefinition = nodeDefinition?.inputs?.find { it.name == inputName }
                val defaultValue = inputDefinition?.getEffectiveDefault()
                if (defaultValue != null) {
                    nodeEdits[inputName] = defaultValue
                    editsAdded = true
                }
            }
        }

        val updatedEdits = if (editsAdded) {
            currentEdits[node.id] = nodeEdits
            currentEdits
        } else {
            state.nodeAttributeEdits
        }

        _uiState.value = state.copy(
            isEditingNode = true,
            selectedNodeForEditing = node,
            editableInputNames = editableInputNames,
            savedScaleBeforeEditing = savedScale,
            savedOffsetBeforeEditing = savedOffset,
            scale = newScale,
            offset = Offset(newOffsetX, newOffsetY),
            nodeAttributeEdits = updatedEdits
        )
    }

    /**
     * Dismiss the node editor and restore previous view.
     */
    fun dismissNodeEditor() {
        val state = _uiState.value

        // Save edits before closing
        saveNodeAttributeEdits()

        // Restore previous scale and offset
        val restoredScale = state.savedScaleBeforeEditing ?: state.scale
        val restoredOffset = state.savedOffsetBeforeEditing ?: state.offset

        _uiState.value = state.copy(
            isEditingNode = false,
            selectedNodeForEditing = null,
            editableInputNames = emptySet(),
            savedScaleBeforeEditing = null,
            savedOffsetBeforeEditing = null,
            scale = restoredScale,
            offset = restoredOffset
        )
    }

    /**
     * Update a node attribute value.
     */
    fun updateNodeAttribute(nodeId: String, inputName: String, value: Any) {
        val state = _uiState.value
        val currentEdits = state.nodeAttributeEdits.toMutableMap()

        val nodeEdits = currentEdits[nodeId]?.toMutableMap() ?: mutableMapOf()
        nodeEdits[inputName] = value
        currentEdits[nodeId] = nodeEdits

        _uiState.value = state.copy(nodeAttributeEdits = currentEdits)
    }

    /**
     * Reset a node attribute to the workflow's own value (the node's default when the workflow leaves it empty, kept as
     * an edit so the canvas shows it).
     */
    fun resetNodeAttribute(nodeId: String, inputName: String) {
        val state = _uiState.value
        val node = state.graph?.nodes?.find { it.id == nodeId } ?: return
        val definition = state.nodeDefinitions[node.classType]?.inputs?.find { it.name == inputName }
        val raw = (node.inputs[inputName] as? InputValue.Literal)?.value
        val nodeEdits = state.nodeAttributeEdits[nodeId]?.toMutableMap() ?: mutableMapOf()
        val default = workflowValueOf(node, definition, inputName)
        if ((raw == null || raw == "") && default != null) nodeEdits[inputName] = default else nodeEdits.remove(inputName)
        val currentEdits = state.nodeAttributeEdits.toMutableMap()
        if (nodeEdits.isEmpty()) currentEdits.remove(nodeId) else currentEdits[nodeId] = nodeEdits
        _uiState.value = state.copy(nodeAttributeEdits = currentEdits)
    }

    /** Reset every changed value of a node to the workflow's. */
    fun resetNode(nodeId: String) {
        val state = _uiState.value
        val node = state.graph?.nodes?.find { it.id == nodeId } ?: return
        changedInputNames(node, state.nodeDefinitions[node.classType], state.nodeAttributeEdits[nodeId])
            .forEach { resetNodeAttribute(nodeId, it) }
    }

    /**
     * Rename a node (change its title/display name).
     */
    fun renameNode(nodeId: String, newTitle: String) {
        val state = _uiState.value
        val graph = state.graph ?: return

        // Find the node and create a renamed copy
        val updatedNodes = graph.nodes.map { node ->
            if (node.id == nodeId) {
                node.copy(title = newTitle)
            } else {
                node
            }
        }

        val updatedGraph = graph.copy(nodes = updatedNodes)

        // Update selected node if it's the one being renamed
        val updatedSelectedNode = state.selectedNodeForEditing?.let { selected ->
            if (selected.id == nodeId) {
                selected.copy(title = newTitle)
            } else {
                selected
            }
        }

        // Also update mutableGraph so the change is persisted when saving
        mutableGraph?.let { mGraph ->
            val nodeIndex = mGraph.nodes.indexOfFirst { it.id == nodeId }
            if (nodeIndex >= 0) {
                val oldNode = mGraph.nodes[nodeIndex]
                mGraph.nodes[nodeIndex] = oldNode.copy(title = newTitle)
            }
        }

        _uiState.value = state.copy(
            graph = updatedGraph,
            selectedNodeForEditing = updatedSelectedNode,
            hasUnsavedChanges = true
        )
    }

    /**
     * Rename a group (change its title).
     */
    fun renameGroup(groupId: Int, newTitle: String) {
        val graph = mutableGraph ?: return

        // Use GroupManager to rename the group
        val renamed = GroupManager.renameGroup(graph, groupId, newTitle)
        if (!renamed) return

        // Update the immutable graph state
        updateGraphState()

        _uiState.value = _uiState.value.copy(hasUnsavedChanges = true)
    }

    // ========== Note Operations ==========

    /**
     * Add a new note to the workflow.
     */
    fun addNote() {
        val graph = mutableGraph ?: return

        // Create note using NoteManager
        NoteManager.createNote(graph)

        // Re-layout to position the new note
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

        // Update bounds
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        _uiState.value = _uiState.value.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            hasUnsavedChanges = true
        )
    }

    /**
     * Add a new note in manual placement mode, positioned at the view center.
     * Calculates view center from stored canvas dimensions and current transform.
     */
    fun addNoteManual() {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        if (!state.isEditMode) return

        // Generate unique note ID
        val existingIds = graph.notes.map { it.id }.toSet()
        var newId = 1
        while (newId in existingIds) {
            newId++
        }

        // Calculate note dimensions (estimate - will be measured during drawing)
        val noteWidth = WorkflowLayoutEngine.NODE_WIDTH
        val rawNoteHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT + WorkflowLayoutEngine.NOTE_BODY_PADDING
        val noteHeight = WorkflowLayoutEngine.snapHeightToGrid(rawNoteHeight)

        // Calculate view center in graph coordinates
        val viewCenterX = (canvasWidth / 2 - state.offset.x) / state.scale
        val viewCenterY = (canvasHeight / 2 - state.offset.y) / state.scale

        // Calculate note position at view center, snapped to grid
        val noteX = viewCenterX - noteWidth / 2
        val noteY = viewCenterY - noteHeight / 2
        val (snappedX, snappedY) = WorkflowLayoutEngine.snapToGrid(noteX, noteY)

        // Create the new note with position already set
        val newNote = WorkflowNote(
            id = newId,
            title = "Note",
            content = "",
            x = snappedX,
            y = snappedY,
            width = noteWidth,
            height = noteHeight,
            hasManualPosition = true  // Mark as manually positioned
        )

        // Add to graph
        graph.notes.add(newNote)

        // Update graph (no re-layout needed since we're in manual mode)
        val updatedGraph = graph.toImmutable()
        val newBounds = layoutEngine.calculateBounds(updatedGraph)

        // Update UI state
        _uiState.value = state.copy(
            graph = updatedGraph,
            graphBounds = newBounds,
            hasUnsavedChanges = true
        )

        DebugLogger.d(TAG, "addNoteManual: created note $newId at ($snappedX, $snappedY)")
    }

    /**
     * Rename a note (change its title).
     */
    fun renameNote(noteId: Int, newTitle: String) {
        val graph = mutableGraph ?: return

        val renamed = NoteManager.renameNote(graph, noteId, newTitle)
        if (!renamed) return

        updateGraphState()
        _uiState.value = _uiState.value.copy(hasUnsavedChanges = true)
    }

    /**
     * Update a note's content.
     */
    fun updateNoteContent(noteId: Int, newContent: String) {
        val graph = mutableGraph ?: return

        val updated = NoteManager.updateNoteContent(graph, noteId, newContent)
        if (!updated) return

        // Re-layout since note height may have changed
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

        // Update bounds
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        _uiState.value = _uiState.value.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            hasUnsavedChanges = true
        )
    }

    /**
     * Delete a note by ID.
     */
    fun deleteNote(noteId: Int) {
        val graph = mutableGraph ?: return

        val deleted = NoteManager.deleteNote(graph, noteId)
        if (!deleted) return

        updateGraphState()
        _uiState.value = _uiState.value.copy(hasUnsavedChanges = true)
    }

    /**
     * Get a note by ID.
     */
    fun getNote(noteId: Int): sh.hnet.comfychair.workflow.WorkflowNote? {
        return mutableGraph?.let { NoteManager.getNote(it, noteId) }
    }

    /**
     * Toggle the bypass state of a node.
     * Bypassed nodes (mode=4) are skipped during execution but connections pass through.
     */
    fun toggleNodeBypass(nodeId: String, bypassed: Boolean) {
        DebugLogger.d(TAG, "toggleNodeBypass: nodeId=$nodeId, bypassed=$bypassed")
        val state = _uiState.value
        val graph = state.graph ?: return

        val newMode = if (bypassed) 4 else 0

        // Find the node and create an updated copy
        val updatedNodes = graph.nodes.map { node ->
            if (node.id == nodeId) {
                DebugLogger.d(TAG, "toggleNodeBypass: Changing mode from ${node.mode} to $newMode for node ${node.id}")
                node.copy(mode = newMode)
            } else {
                node
            }
        }

        val updatedGraph = graph.copy(nodes = updatedNodes)

        // Get the updated node for the side sheet
        val updatedSelectedNode = state.selectedNodeForEditing?.let { selected ->
            if (selected.id == nodeId) {
                selected.copy(mode = newMode)
            } else {
                selected
            }
        }

        // Also update mutableGraph so the change is persisted when saving
        mutableGraph?.let { mGraph ->
            val nodeIndex = mGraph.nodes.indexOfFirst { it.id == nodeId }
            if (nodeIndex >= 0) {
                val oldNode = mGraph.nodes[nodeIndex]
                mGraph.nodes[nodeIndex] = oldNode.copy(mode = newMode)
                DebugLogger.d(TAG, "toggleNodeBypass: Updated mutableGraph")
            }
        }

        _uiState.value = state.copy(
            graph = updatedGraph,
            selectedNodeForEditing = updatedSelectedNode,
            hasUnsavedChanges = mutableGraph != null  // Only mark unsaved if we can actually save
        )
    }

    /**
     * Save node attribute edits to storage.
     */
    private fun saveNodeAttributeEdits() {
        val workflowId = currentWorkflowId ?: run {
            DebugLogger.w(TAG, "saveNodeAttributeEdits: No workflowId, skipping save")
            return
        }
        val storage = workflowValuesStorage ?: run {
            DebugLogger.w(TAG, "saveNodeAttributeEdits: No storage, skipping save")
            return
        }
        val serverId = sh.hnet.comfychair.connection.ConnectionManager.currentServerId ?: run {
            DebugLogger.w(TAG, "saveNodeAttributeEdits: No serverId (ConnectionManager.currentServerId is null), skipping save")
            return
        }
        val edits = _uiState.value.nodeAttributeEdits

        if (edits.isEmpty()) {
            // Clear the edits from storage
            val currentValues = storage.loadValues(serverId, workflowId)
            if (currentValues != null && currentValues.nodeAttributeEdits != null) {
                storage.saveValues(serverId, workflowId, currentValues.copy(nodeAttributeEdits = null))
                DebugLogger.d(TAG, "saveNodeAttributeEdits: Cleared edits for server=$serverId, workflow=$workflowId")
            }
            return
        }

        val editsJson = NodeAttributeEdits(edits).toJson()

        val currentValues = storage.loadValues(serverId, workflowId)
        if (currentValues != null) {
            storage.saveValues(serverId, workflowId, currentValues.copy(nodeAttributeEdits = editsJson))
        } else {
            // Create new values with just the edits
            storage.saveValues(
                serverId,
                workflowId,
                sh.hnet.comfychair.model.WorkflowValues(
                    nodeAttributeEdits = editsJson
                )
            )
        }
        DebugLogger.d(TAG, "saveNodeAttributeEdits: Saved ${edits.size} node edits for server=$serverId, workflow=$workflowId")
    }

    /**
     * Get edits for a specific node.
     */
    fun getEditsForNode(nodeId: String): Map<String, Any> {
        return _uiState.value.nodeAttributeEdits[nodeId] ?: emptyMap()
    }

    // ===========================================
    // Graph Editing Mode (Add/Delete/Duplicate Nodes)
    // ===========================================

    /**
     * Enter edit mode - creates a mutable copy of the graph for editing.
     * Saves the original graph and bounds for restoration on discard.
     */
    fun enterEditMode() {
        val state = _uiState.value
        val graph = state.graph ?: return

        // Save original state for restoration on discard
        originalGraph = graph
        originalBounds = state.graphBounds

        // Initialize mutable graph
        mutableGraph = MutableWorkflowGraph.fromImmutable(graph)

        // Initialize node ID counter based on existing IDs
        nodeIdCounter = graph.nodes.mapNotNull { it.id.toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        groupIdCounter = graph.groups.maxOfOrNull { it.id }?.plus(1) ?: 1

        _uiState.value = state.copy(
            isEditMode = true,
            selectedNodeIds = emptySet(),
            selectedNoteIds = emptySet(),
            hasUnsavedChanges = false
        )
    }

    /**
     * Exit edit mode - discards unsaved changes and restores original graph.
     */
    fun exitEditMode() {
        // Restore original graph and bounds
        val restoredGraph = originalGraph
        val restoredBounds = originalBounds

        // Clear mutable state
        mutableGraph = null
        originalGraph = null
        originalBounds = null

        _uiState.value = _uiState.value.copy(
            graph = restoredGraph ?: _uiState.value.graph,
            graphBounds = restoredBounds ?: _uiState.value.graphBounds,
            isEditMode = false,
            selectedNodeIds = emptySet(),
            selectedNoteIds = emptySet(),
            hasUnsavedChanges = false,
            showNodeBrowser = false,
            nodeInsertPosition = null,
            connectionModeState = null
        )
    }

    /**
     * Return to view mode after saving a workflow.
     * Reloads the workflow from storage to get the fresh saved state.
     */
    private fun returnToViewMode(workflowId: String, context: Context) {
        DebugLogger.i(TAG, "returnToViewMode: Reloading workflow $workflowId")

        // Reload workflow from storage to get fresh saved state
        val workflow = WorkflowManager.getWorkflowById(workflowId)
        if (workflow == null) {
            DebugLogger.e(TAG, "returnToViewMode: Workflow not found")
            return
        }

        // Parse and layout the workflow
        var graph = parser.parse(workflow.jsonContent, workflow.name, workflow.description)
        graph = populateNodeOutputs(graph)
        graph = layoutEngine.layoutGraph(graph)
        graph = resolveEdgeTypes(graph)
        val bounds = layoutEngine.calculateBounds(graph)

        // Clear mutable state
        mutableGraph = null
        originalGraph = null
        originalBounds = null
        pendingSaveData = null

        // Update UI state to view mode
        _uiState.value = _uiState.value.copy(
            graph = graph,
            workflowName = workflow.name,
            graphBounds = bounds,
            isEditMode = false,
            isFieldMappingMode = false,
            mappingState = null,
            selectedNodeIds = emptySet(),
            selectedNoteIds = emptySet(),
            hasUnsavedChanges = false,
            showNodeBrowser = false,
            nodeInsertPosition = null,
            connectionModeState = null,
            highlightedNodeIds = emptySet(),
            // Update workflow metadata for subsequent edits
            editingWorkflowId = workflowId,
            editingWorkflowType = workflow.type,
            originalWorkflowName = workflow.name,
            originalWorkflowDescription = workflow.description,
            // Sync manual placement mode from graph
            isManualPlacementMode = graph.isManualPlacementMode
        )

        DebugLogger.i(TAG, "returnToViewMode: Now in view mode for workflow ${Obfuscator.workflowName(workflow.name)}")
    }

    /**
     * Toggle node selection.
     */
    fun toggleNodeSelection(nodeId: String) {
        val state = _uiState.value
        if (!state.isEditMode) return

        val newSelection = if (nodeId in state.selectedNodeIds) {
            state.selectedNodeIds - nodeId
        } else {
            state.selectedNodeIds + nodeId
        }

        _uiState.value = state.copy(selectedNodeIds = newSelection)
    }

    /**
     * Select a single node (replaces current selection).
     */
    fun selectNode(nodeId: String) {
        val state = _uiState.value
        if (!state.isEditMode) return

        _uiState.value = state.copy(selectedNodeIds = setOf(nodeId))
    }

    /**
     * Clear node and note selection.
     */
    fun clearSelection() {
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedNodeIds = emptySet(),
            selectedNoteIds = emptySet()
        )
    }

    /**
     * Toggle note selection.
     */
    fun toggleNoteSelection(noteId: Int) {
        val state = _uiState.value
        if (!state.isEditMode) return

        val newSelection = if (noteId in state.selectedNoteIds) {
            state.selectedNoteIds - noteId
        } else {
            state.selectedNoteIds + noteId
        }

        _uiState.value = state.copy(selectedNoteIds = newSelection)
    }

    /**
     * Delete selected nodes and notes with their connected edges.
     */
    fun deleteSelectedNodes() {
        val state = _uiState.value
        val graph = mutableGraph ?: return
        if (!state.isEditMode) return
        if (state.selectedNodeIds.isEmpty() && state.selectedNoteIds.isEmpty()) return

        // Delete selected nodes
        if (state.selectedNodeIds.isNotEmpty()) {
            GraphMutationUtils.deleteNodes(graph, state.selectedNodeIds)
        }

        // Delete selected notes
        for (noteId in state.selectedNoteIds) {
            NoteManager.deleteNote(graph, noteId)
        }

        // Re-layout graph after deletion to fill gaps
        relayoutGraph()

        _uiState.value = _uiState.value.copy(
            selectedNodeIds = emptySet(),
            selectedNoteIds = emptySet(),
            hasUnsavedChanges = true
        )
    }

    /**
     * Duplicate selected nodes with offset position.
     * Internal edges between selected nodes are also duplicated.
     * Triggers auto-layout to prevent node overlaps.
     */
    fun duplicateSelectedNodes() {
        val state = _uiState.value
        val graph = mutableGraph ?: return
        if (!state.isEditMode || state.selectedNodeIds.isEmpty()) return

        val result = GraphMutationUtils.duplicateNodes(
            graph = graph,
            nodeIds = state.selectedNodeIds,
            idGenerator = { (nodeIdCounter++).toString() }
        )

        // Re-layout to prevent overlaps
        relayoutGraph()

        // Update selection to the new nodes
        _uiState.value = _uiState.value.copy(
            selectedNodeIds = result.newNodeIds,
            hasUnsavedChanges = true
        )
    }

    /**
     * Generate a unique node ID.
     */
    private fun generateUniqueNodeId(): String {
        return (nodeIdCounter++).toString()
    }

    /**
     * Create a group from the currently selected nodes and/or notes.
     * Requires at least 1 item (node or note) to be selected.
     */
    fun createGroupFromSelection() {
        val state = _uiState.value
        val graph = mutableGraph ?: return
        val totalSelected = state.selectedNodeIds.size + state.selectedNoteIds.size
        if (!state.isEditMode || totalSelected < 1) return

        // Convert note IDs to member format (note:X) and combine with node IDs
        val noteMemberIds = state.selectedNoteIds.map { WorkflowNote.noteIdToMemberId(it) }.toSet()
        val allMemberIds = state.selectedNodeIds + noteMemberIds

        val newGroup = GroupManager.createGroup(
            graph = graph,
            memberIds = allMemberIds,
            title = "Group",
            idGenerator = { groupIdCounter++ }
        ) ?: return

        _uiState.value = _uiState.value.copy(
            graph = graph.toImmutable(),
            hasUnsavedChanges = true
        )

        // Trigger relayout to recalculate group bounds
        relayoutGraph()
    }

    /**
     * Check if a node is inside a group (for enabling/disabling ungroup action).
     * Uses explicit memberNodeIds if available, otherwise falls back to position detection.
     */
    fun isSelectedNodeInGroup(): Boolean {
        val graph = mutableGraph ?: return false
        val state = _uiState.value
        if (state.selectedNodeIds.isEmpty()) return false

        return GroupManager.isAnyNodeInGroup(graph, state.selectedNodeIds)
    }

    /**
     * Remove selected nodes from the group they belong to.
     * If the group has fewer than 2 members remaining, the group is dissolved entirely.
     */
    fun ungroupSelectedNode() {
        val state = _uiState.value
        val graph = mutableGraph ?: return
        if (!state.isEditMode || state.selectedNodeIds.isEmpty()) return

        val modified = GroupManager.removeNodesFromGroups(graph, state.selectedNodeIds)
        if (!modified) return

        _uiState.value = _uiState.value.copy(
            graph = graph.toImmutable(),
            hasUnsavedChanges = true
        )

        // Trigger relayout to recalculate group bounds
        relayoutGraph()
    }

    /**
     * Update the immutable graph from the mutable graph and recalculate bounds.
     */
    private fun updateGraphState() {
        val graph = mutableGraph ?: return

        val immutableGraph = graph.toImmutable()
        val bounds = layoutEngine.calculateBounds(immutableGraph)

        _uiState.value = _uiState.value.copy(
            graph = immutableGraph,
            graphBounds = bounds
        )
    }

    /**
     * Re-layout the entire graph. Call this after structural changes (add/delete nodes)
     * or when note heights change to recalculate positions.
     */
    fun relayoutGraph() {
        // Use mutableGraph if in edit mode, otherwise use immutable graph from state
        val currentGraph = mutableGraph?.toImmutable() ?: _uiState.value.graph ?: return

        // Re-layout the entire graph
        val layoutedGraph = layoutEngine.layoutGraph(currentGraph)

        // Update mutable graph if in edit mode
        if (mutableGraph != null) {
            mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)
        }

        // Recalculate bounds
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        _uiState.value = _uiState.value.copy(
            graph = layoutedGraph,
            graphBounds = newBounds
        )
        refitIfFollowing()
    }

    // ========== Manual Placement Mode ==========

    /**
     * Toggle manual placement mode for this workflow.
     * If currently enabled, shows confirmation dialog before disabling.
     * If currently disabled, enables immediately.
     */
    fun toggleManualPlacementMode() {
        val state = _uiState.value
        val graph = mutableGraph ?: return
        if (!state.isEditMode) return

        if (state.isManualPlacementMode) {
            // Switching OFF - show confirmation dialog
            _uiState.value = state.copy(showDisableManualPlacementConfirmation = true)
        } else {
            // Switching ON - immediate
            applyManualPlacementMode(true)
        }
    }

    /**
     * Confirm disabling manual placement mode after user confirms in dialog.
     * Clears all manual positions and runs auto-layout.
     */
    fun confirmDisableManualPlacement() {
        _uiState.value = _uiState.value.copy(showDisableManualPlacementConfirmation = false)
        applyManualPlacementMode(false)
    }

    /**
     * Cancel disabling manual placement mode (dismiss dialog).
     */
    fun cancelDisableManualPlacement() {
        _uiState.value = _uiState.value.copy(showDisableManualPlacementConfirmation = false)
    }

    /**
     * Apply manual placement mode change.
     * When disabling, clears all manual positions and runs auto-layout.
     */
    private fun applyManualPlacementMode(enabled: Boolean) {
        val graph = mutableGraph ?: return

        graph.isManualPlacementMode = enabled

        if (!enabled) {
            // Clear all positions and run auto-layout
            WorkflowLayoutEngine.clearManualPositions(graph)
            val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())
            mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)
            // Preserve the manual placement mode setting after re-creating the graph
            mutableGraph?.isManualPlacementMode = false
            val newBounds = layoutEngine.calculateBounds(layoutedGraph)

            _uiState.value = _uiState.value.copy(
                graph = layoutedGraph,
                graphBounds = newBounds,
                isManualPlacementMode = false,
                hasUnsavedChanges = true
            )
        } else {
            _uiState.value = _uiState.value.copy(
                graph = graph.toImmutable(),
                isManualPlacementMode = true,
                hasUnsavedChanges = true
            )
        }
    }

    /**
     * Show the node browser for adding new nodes.
     */
    fun showNodeBrowser(insertPosition: Offset) {
        _uiState.value = _uiState.value.copy(
            showNodeBrowser = true,
            nodeInsertPosition = insertPosition
        )
    }

    /**
     * Hide the node browser.
     */
    fun hideNodeBrowser() {
        _uiState.value = _uiState.value.copy(
            showNodeBrowser = false,
            nodeInsertPosition = null
        )
    }

    /**
     * Check if the node type registry has node definitions available.
     */
    fun hasNodeDefinitions(): Boolean = nodeTypeRegistry.isPopulated()

    /**
     * Get all available node types from the registry.
     */
    fun getAvailableNodeTypes(): List<NodeTypeDefinition> {
        return nodeTypeRegistry.getAllNodeTypes()
    }

    /**
     * Get node types grouped by category.
     */
    fun getNodeTypesByCategory(): Map<String, List<NodeTypeDefinition>> {
        return nodeTypeRegistry.getNodeTypesByCategory()
    }


    /**
     * Add a new node from a NodeTypeDefinition at the current insert position.
     */
    fun addNode(nodeType: NodeTypeDefinition) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        if (!state.isEditMode) return

        // Create inputs map from the node definition
        // Use LinkedHashMap to preserve insertion order: literals first, then connection slots
        val inputs = linkedMapOf<String, InputValue>()

        // First pass: add all literal (editable) inputs
        nodeType.inputs.forEach { inputDef ->
            val isConnectionType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

            if (!isConnectionType && !inputDef.forceInput) {
                // Literal input: add with default value
                inputs[inputDef.name] = InputValue.Literal(inputDef.getEffectiveDefault())
            }
        }

        // Second pass: add all connection-type inputs (slots)
        nodeType.inputs.forEach { inputDef ->
            val isConnectionType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

            if (isConnectionType || inputDef.forceInput) {
                // Connection-type input: add as unconnected slot
                inputs[inputDef.name] = InputValue.UnconnectedSlot(inputDef.type)
            }
        }

        // Categorize the node based on class type
        val category = nodeCategoryOf(nodeType.classType)

        // Calculate proper node dimensions using the same logic as WorkflowLayoutEngine
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val rawNodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )
        val nodeHeight = WorkflowLayoutEngine.snapHeightToGrid(rawNodeHeight)

        // Create the new node (position will be set by layout engine)
        val newNode = WorkflowNode(
            id = generateUniqueNodeId(),
            classType = nodeType.classType,
            title = nodeType.classType,  // Use classType as initial title
            category = category,
            inputs = sortInputsForLayout(inputs),
            outputs = nodeType.outputs,  // Populate outputs from NodeTypeDefinition
            templateInputKeys = emptySet(),  // New nodes have no template keys
            x = 0f,  // Will be set by layout
            y = 0f,  // Will be set by layout
            width = nodeWidth,
            height = nodeHeight
        )

        // Add to graph
        graph.nodes.add(newNode)

        // Re-layout the entire graph (orphan nodes will be placed at the end)
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())

        // Update mutable graph from layouted result
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

        // Update graph bounds
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        // Update UI state (include node definition for immediate dropdown support)
        _uiState.value = _uiState.value.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            selectedNodeIds = setOf(newNode.id),
            hasUnsavedChanges = true,
            showNodeBrowser = false,
            nodeInsertPosition = null,
            // The whole graph's, not only the new node's: a map holding just the new type marked every other node
            // as missing from the server
            nodeDefinitions = buildNodeDefinitionsMap(layoutedGraph) + (nodeType.classType to nodeType)
        )
    }

    // ===========================================
    // Connection Mode (Tap-Based Connection Editing)
    // ===========================================

    /**
     * Enter connection mode when an output slot is tapped.
     * Calculates valid input slots and highlights them.
     */
    fun enterConnectionMode(outputSlot: SlotPosition) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        if (!state.isEditMode) return

        // Get the source node
        val sourceNode = graph.nodes.find { it.id == outputSlot.nodeId } ?: return

        // Get output type from registry
        val outputType = nodeTypeRegistry.getOutputType(sourceNode.classType, outputSlot.outputIndex)

        // Calculate all valid input slots
        val validInputs = calculateValidInputSlots(
            sourceNodeId = sourceNode.id,
            outputType = outputType,
            graph = graph
        )

        // Update the output slot with resolved type
        val resolvedOutputSlot = outputSlot.copy(slotType = outputType)

        _uiState.value = state.copy(
            connectionModeState = ConnectionModeState(
                direction = ConnectionDirection.OUTPUT_TO_INPUT,
                sourceSlot = resolvedOutputSlot,
                validTargetSlots = validInputs
            )
        )
    }

    /**
     * Enter reverse connection mode when an input slot is tapped.
     * Calculates valid output slots and highlights them.
     */
    fun enterReverseConnectionMode(inputSlot: SlotPosition) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        if (!state.isEditMode) return

        // Get the target node
        val targetNode = graph.nodes.find { it.id == inputSlot.nodeId } ?: return

        // Get input type - prefer from slot, fallback to registry
        val inputType = inputSlot.slotType
            ?: nodeTypeRegistry.getInputType(targetNode.classType, inputSlot.slotName)

        // Calculate all valid output slots
        val validOutputs = calculateValidOutputSlots(
            targetNodeId = targetNode.id,
            inputType = inputType,
            graph = graph
        )

        // Update the input slot with resolved type
        val resolvedInputSlot = inputSlot.copy(slotType = inputType)

        _uiState.value = state.copy(
            connectionModeState = ConnectionModeState(
                direction = ConnectionDirection.INPUT_TO_OUTPUT,
                sourceSlot = resolvedInputSlot,
                validTargetSlots = validOutputs
            )
        )
    }

    /**
     * Exit connection mode without making a connection.
     */
    fun exitConnectionMode() {
        _uiState.value = _uiState.value.copy(connectionModeState = null)
    }

    /**
     * Connect to a target slot when tapped in connection mode.
     * Handles both directions:
     * - OUTPUT_TO_INPUT: targetSlot is an input, sourceSlot is an output
     * - INPUT_TO_OUTPUT: targetSlot is an output, sourceSlot is an input
     */
    fun connectToTarget(targetSlot: SlotPosition) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        val connectionState = state.connectionModeState ?: return

        // Determine which slot is output and which is input based on direction
        val (outputSlot, inputSlot) = when (connectionState.direction) {
            ConnectionDirection.OUTPUT_TO_INPUT -> connectionState.sourceSlot to targetSlot
            ConnectionDirection.INPUT_TO_OUTPUT -> targetSlot to connectionState.sourceSlot
        }

        // Remove any existing edge to this input (inputs can only have one connection)
        graph.edges.removeAll { edge ->
            edge.targetNodeId == inputSlot.nodeId && edge.targetInputName == inputSlot.slotName
        }

        // Create new edge (always output -> input)
        val newEdge = WorkflowEdge(
            sourceNodeId = outputSlot.nodeId,
            targetNodeId = inputSlot.nodeId,
            sourceOutputIndex = outputSlot.outputIndex,
            targetInputName = inputSlot.slotName,
            slotType = outputSlot.slotType
        )
        graph.edges.add(newEdge)

        // Update the target node's input to be a Connection type
        val targetNode = graph.nodes.find { it.id == inputSlot.nodeId }
        val valueTookLink = targetNode?.inputs?.get(inputSlot.slotName) is InputValue.Literal
        if (targetNode != null) {
            // A value taking a link: its typed value comes back on Disconnect (for this editing session). A value the app
            // filled ({{placeholder}}) doesn't: linking ends that mapping, and Disconnect gives the node's default
            (targetNode.inputs[inputSlot.slotName] as? InputValue.Literal)
                ?.takeUnless { TemplateKeyRegistry.isAppManagedValue(it.value) }
                ?.let { keptValues[targetNode.id to inputSlot.slotName] = it.value }
            val updatedInputs = targetNode.inputs.toMutableMap()
            updatedInputs[inputSlot.slotName] = InputValue.Connection(
                sourceNodeId = outputSlot.nodeId,
                outputIndex = outputSlot.outputIndex
            )
            // Update the node in place; a value that took a link moves to the node's linked inputs
            val nodeIndex = graph.nodes.indexOfFirst { it.id == targetNode.id }
            if (nodeIndex >= 0) {
                graph.nodes[nodeIndex] = targetNode.copy(inputs = sortInputsForLayout(updatedInputs))
            }
        }

        _uiState.value = _uiState.value.copy(
            connectionModeState = null,
            pendingMappedLink = null,
            hasUnsavedChanges = true
        )
        // A value row that became a link changes the node's rows: sizes and positions follow; a link between sockets
        // leaves the layout as it was
        if (valueTookLink) relayoutGraph() else updateGraphState()
    }

    /**
     * A tapped target of the pending connection: connects, unless the input is a value the app fills from a
     * generation-screen field – then the editor asks first ([pendingMappedLink]).
     */
    fun requestConnectToTarget(targetSlot: SlotPosition, fieldNameOf: (String) -> String) {
        val graph = mutableGraph ?: return
        val connection = _uiState.value.connectionModeState ?: return
        val (outputSlot, inputSlot) = when (connection.direction) {
            ConnectionDirection.OUTPUT_TO_INPUT -> connection.sourceSlot to targetSlot
            ConnectionDirection.INPUT_TO_OUTPUT -> targetSlot to connection.sourceSlot
        }
        val fieldKey = mappedFieldOf(inputSlot)
        if (fieldKey == null) {
            connectToTarget(targetSlot)
            return
        }
        val sourceTitle = graph.nodes.find { it.id == outputSlot.nodeId }?.title.orEmpty()
        _uiState.value = _uiState.value.copy(
            pendingMappedLink = PendingMappedLink(targetSlot, inputSlot.slotName, fieldNameOf(fieldKey), sourceTitle)
        )
    }

    /** The app's field a value input is filled from (its `{{placeholder}}` or a kept mapping), or null. */
    private fun mappedFieldOf(inputSlot: SlotPosition): String? {
        val graph = mutableGraph ?: return null
        val value = (graph.nodes.find { it.id == inputSlot.nodeId }?.inputs?.get(inputSlot.slotName) as? InputValue.Literal)?.value
            ?: return null
        return (value as? String)?.let { Regex("""\{\{(\w+)\}\}""").matchEntire(it)?.groupValues?.get(1) }
            ?: graph.mappedFields[inputSlot.nodeId to inputSlot.slotName]
    }

    /**
     * Add a node for a socket (its menu): the node browser filtered to nodes that fit it. For a value the app fills,
     * the editor asks first, as when linking it to an existing node.
     */
    fun requestAddNodeFor(slot: SlotPosition, fieldNameOf: (String) -> String) {
        val fieldKey = if (slot.isOutput) null else mappedFieldOf(slot)
        if (fieldKey == null) {
            startLongPressConnection(slot)
        } else {
            _uiState.value = _uiState.value.copy(
                pendingMappedLink = PendingMappedLink(slot, slot.slotName, fieldNameOf(fieldKey), sourceTitle = "", addNode = true)
            )
        }
    }

    /** Disconnect everything an output feeds (its menu). */
    fun disconnectOutput(nodeId: String, outputIndex: Int) {
        val graph = mutableGraph ?: return
        graph.edges.filter { it.sourceNodeId == nodeId && it.sourceOutputIndex == outputIndex }
            .forEach { deleteConnection(it.targetNodeId, it.targetInputName) }
    }

    /**
     * Link after the question: the value stops being filled by the app. Takes the question it answers: the dialog
     * closes (clearing [pendingMappedLink]) before it confirms.
     */
    fun confirmMappedLink(pending: PendingMappedLink) {
        if (pending.addNode) {
            // The node browser next; the mapping is removed when the new node is linked
            _uiState.value = _uiState.value.copy(pendingMappedLink = null)
            startLongPressConnection(pending.target)
            return
        }
        val graph = mutableGraph
        // Linking ends a mapping kept apart from the value too
        graph?.let { g ->
            val connection = _uiState.value.connectionModeState
            val inputSlot = if (connection?.direction == ConnectionDirection.INPUT_TO_OUTPUT) connection.sourceSlot else pending.target
            g.mappedFields = g.mappedFields - (inputSlot.nodeId to inputSlot.slotName)
        }
        connectToTarget(pending.target)
    }

    /** Cancel the question: the connection still waits. */
    fun dismissMappedLink() {
        _uiState.value = _uiState.value.copy(pendingMappedLink = null)
    }

    /**
     * Calculate all valid input slots for a given output.
     * Delegates to ConnectionValidator utility.
     */
    private fun calculateValidInputSlots(
        sourceNodeId: String,
        outputType: String?,
        graph: MutableWorkflowGraph
    ): List<SlotPosition> {
        return ConnectionValidator.calculateValidInputSlots(
            graph = graph,
            sourceNodeId = sourceNodeId,
            outputType = outputType,
            nodeTypeRegistry = nodeTypeRegistry
        )
    }

    /**
     * Calculate all valid output slots for a given input.
     * Delegates to ConnectionValidator utility.
     */
    private fun calculateValidOutputSlots(
        targetNodeId: String,
        inputType: String?,
        graph: MutableWorkflowGraph
    ): List<SlotPosition> {
        return ConnectionValidator.calculateValidOutputSlots(
            graph = graph,
            targetNodeId = targetNodeId,
            inputType = inputType,
            nodeTypeRegistry = nodeTypeRegistry
        )
    }

    // ===========================================
    // Long-Press Connection Mode (Filtered Node Browser)
    // ===========================================

    /**
     * Start long-press connection mode when an output slot is long-pressed.
     * Opens the filtered node browser showing only compatible nodes.
     */
    fun startLongPressConnection(sourceSlot: SlotPosition) {
        val state = _uiState.value
        if (!state.isEditMode) return

        _uiState.value = state.copy(
            longPressSourceSlot = sourceSlot,
            showCompatibleNodeBrowser = true,
            showNodeBrowser = false  // Use filtered browser instead
        )
    }

    /**
     * Cancel long-press connection mode.
     */
    fun cancelLongPressConnection() {
        _uiState.value = _uiState.value.copy(
            longPressSourceSlot = null,
            showCompatibleNodeBrowser = false,
            showInputSelectionDialog = false,
            inputSelectionNodeType = null,
            inputSelectionCompatibleInputs = emptyList(),
            showOutputSelectionDialog = false,
            outputSelectionNodeType = null,
            outputSelectionCompatibleOutputs = emptyList()
        )
    }

    /**
     * Handle node selection from the filtered browser.
     * If multiple compatible inputs exist, shows input selection dialog.
     * If single compatible input, completes connection immediately.
     */
    fun selectNodeForConnection(nodeType: NodeTypeDefinition) {
        val sourceSlot = _uiState.value.longPressSourceSlot ?: return
        val sourceType = sourceSlot.slotType ?: return

        // Find compatible inputs (connection types only, not literals)
        val compatibleInputs = nodeType.inputs.filter { input ->
            isConnectionType(input.type) &&
            ConnectionValidator.isTypeCompatible(sourceType, input.type)
        }

        when (compatibleInputs.size) {
            0 -> addUnconnected(nodeType)
            1 -> completeConnection(nodeType, compatibleInputs.first())
            else -> {
                // Show input selection dialog
                _uiState.value = _uiState.value.copy(
                    showCompatibleNodeBrowser = false,
                    showInputSelectionDialog = true,
                    inputSelectionNodeType = nodeType,
                    inputSelectionCompatibleInputs = compatibleInputs
                )
            }
        }
    }

    /**
     * Handle input selection from the dialog.
     */
    /**
     * A node that can't take the held socket (its compatibility filter was removed in the node browser): added on its
     * own, as from Add node.
     */
    private fun addUnconnected(nodeType: NodeTypeDefinition) {
        cancelLongPressConnection()
        if (_uiState.value.isManualPlacementMode) addNodeManual(nodeType) else addNode(nodeType)
    }

    fun selectInputForConnection(input: InputDefinition) {
        val nodeType = _uiState.value.inputSelectionNodeType ?: return
        completeConnection(nodeType, input)
    }

    /**
     * Handle node selection from the filtered browser for reverse connection.
     * When user long-pressed an INPUT slot and selected a node to add.
     * If multiple compatible outputs exist, shows output selection dialog.
     * If single compatible output, completes connection immediately.
     */
    fun selectNodeForReverseConnection(nodeType: NodeTypeDefinition) {
        val sourceSlot = _uiState.value.longPressSourceSlot ?: return
        val inputType = sourceSlot.slotType ?: return

        // Find compatible outputs on the selected node
        val compatibleOutputs = nodeType.outputs.filter { output ->
            ConnectionValidator.isTypeCompatible(output.type, inputType)
        }

        when (compatibleOutputs.size) {
            0 -> addUnconnected(nodeType)
            1 -> completeReverseConnection(nodeType, compatibleOutputs.first())
            else -> {
                // Show output selection dialog
                _uiState.value = _uiState.value.copy(
                    showCompatibleNodeBrowser = false,
                    showOutputSelectionDialog = true,
                    outputSelectionNodeType = nodeType,
                    outputSelectionCompatibleOutputs = compatibleOutputs
                )
            }
        }
    }

    /**
     * Handle output selection from the dialog.
     */
    fun selectOutputForConnection(output: OutputSlot) {
        val nodeType = _uiState.value.outputSelectionNodeType ?: return
        completeReverseConnection(nodeType, output)
    }

    /**
     * Complete the reverse connection by adding a new node and creating an edge.
     * The new node's output connects TO the long-pressed input slot.
     */
    private fun completeReverseConnection(nodeType: NodeTypeDefinition, sourceOutput: OutputSlot) {
        val targetInputSlot = _uiState.value.longPressSourceSlot ?: run {
            DebugLogger.e(TAG, "completeReverseConnection: targetInputSlot is null")
            return
        }
        val graph = mutableGraph ?: run {
            DebugLogger.e(TAG, "completeReverseConnection: mutableGraph is null")
            return
        }
        val state = _uiState.value
        if (!state.isEditMode) {
            DebugLogger.e(TAG, "completeReverseConnection: not in edit mode")
            return
        }

        DebugLogger.d(TAG, "completeReverseConnection: targetInput=${targetInputSlot.nodeId}:${targetInputSlot.slotName} (${targetInputSlot.slotType})")
        DebugLogger.d(TAG, "completeReverseConnection: sourceOutput=${sourceOutput.name} (${sourceOutput.type})")
        DebugLogger.d(TAG, "completeReverseConnection: nodeType=${nodeType.classType}")

        // 1. Create inputs map - all inputs are unconnected for the new node
        val inputs = linkedMapOf<String, InputValue>()

        // First pass: add all literal (editable) inputs
        nodeType.inputs.forEach { inputDef ->
            val isConnType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")
            if (!isConnType && !inputDef.forceInput) {
                inputs[inputDef.name] = InputValue.Literal(inputDef.getEffectiveDefault())
            }
        }

        // Second pass: add connection-type inputs (all unconnected)
        nodeType.inputs.forEach { inputDef ->
            val isConnType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")
            if (isConnType || inputDef.forceInput) {
                inputs[inputDef.name] = InputValue.UnconnectedSlot(inputDef.type)
            }
        }

        // 2. Categorize and calculate dimensions
        val category = nodeCategoryOf(nodeType.classType)
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val rawNodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )
        val nodeHeight = WorkflowLayoutEngine.snapHeightToGrid(rawNodeHeight)

        // 3. Create the new node with a unique ID
        val newNodeId = generateUniqueNodeId()
        val newNode = WorkflowNode(
            id = newNodeId,
            classType = nodeType.classType,
            title = nodeType.classType,
            category = category,
            inputs = sortInputsForLayout(inputs),
            outputs = nodeType.outputs,
            templateInputKeys = emptySet(),
            x = 0f,
            y = 0f,
            width = nodeWidth,
            height = nodeHeight
        )

        DebugLogger.d(TAG, "completeReverseConnection: created newNode id=$newNodeId")

        // 4. Add node to graph
        graph.nodes.add(newNode)

        // 5. Find output index for the selected output
        val outputIndex = nodeType.outputs.indexOfFirst { it.name == sourceOutput.name }

        // 6. Create edge from new node's output TO the target input (replacing the link it had)
        graph.edges.removeAll { edge -> edge.targetNodeId == targetInputSlot.nodeId && edge.targetInputName == targetInputSlot.slotName }
        val newEdge = WorkflowEdge(
            sourceNodeId = newNodeId,
            sourceOutputIndex = outputIndex,
            targetNodeId = targetInputSlot.nodeId,
            targetInputName = targetInputSlot.slotName,
            slotType = sourceOutput.type
        )
        graph.edges.add(newEdge)

        // 7. Update target node's input to be a Connection type
        val targetNode = graph.nodes.find { it.id == targetInputSlot.nodeId }
        if (targetNode != null) {
            // A value taking the link: kept for Disconnect, as when linking an existing node
            (targetNode.inputs[targetInputSlot.slotName] as? InputValue.Literal)
                ?.takeUnless { TemplateKeyRegistry.isAppManagedValue(it.value) }
                ?.let { keptValues[targetNode.id to targetInputSlot.slotName] = it.value }
            graph.mappedFields = graph.mappedFields - (targetNode.id to targetInputSlot.slotName)
            val updatedInputs = targetNode.inputs.toMutableMap()
            updatedInputs[targetInputSlot.slotName] = InputValue.Connection(
                sourceNodeId = newNodeId,
                outputIndex = outputIndex
            )
            val nodeIndex = graph.nodes.indexOfFirst { it.id == targetNode.id }
            if (nodeIndex >= 0) {
                graph.nodes[nodeIndex] = targetNode.copy(inputs = sortInputsForLayout(updatedInputs))
            }
        }

        DebugLogger.d(TAG, "completeReverseConnection: added edge: ${newEdge.sourceNodeId}:${newEdge.sourceOutputIndex} -> ${newEdge.targetNodeId}:${newEdge.targetInputName}")

        // 8. Re-layout the graph (single pass)
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

        // 9. Update graph bounds
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        // 10. Clear long-press state and update UI
        _uiState.value = _uiState.value.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            selectedNodeIds = setOf(newNodeId),
            longPressSourceSlot = null,
            showCompatibleNodeBrowser = false,
            showOutputSelectionDialog = false,
            outputSelectionNodeType = null,
            outputSelectionCompatibleOutputs = emptyList(),
            connectionModeState = null,
            hasUnsavedChanges = true,
            // The whole graph's definitions, with the new node's: none of them is "missing"
            nodeDefinitions = buildNodeDefinitionsMap(layoutedGraph) + (nodeType.classType to nodeType)
        )

        DebugLogger.d(TAG, "completeReverseConnection: DONE")
    }

    /**
     * Complete the connection by adding a new node and creating an edge.
     * This inlines the node creation logic (instead of calling addNode) to:
     * 1. Avoid double layout passes
     * 2. Properly track the new node's ID
     * 3. Set up the connection before the layout
     */
    private fun completeConnection(nodeType: NodeTypeDefinition, targetInput: InputDefinition) {
        val sourceSlot = _uiState.value.longPressSourceSlot ?: run {
            DebugLogger.e(TAG, "completeConnection: sourceSlot is null")
            return
        }
        val graph = mutableGraph ?: run {
            DebugLogger.e(TAG, "completeConnection: mutableGraph is null")
            return
        }
        val state = _uiState.value
        if (!state.isEditMode) {
            DebugLogger.e(TAG, "completeConnection: not in edit mode")
            return
        }

        DebugLogger.d(TAG, "completeConnection: sourceSlot=${sourceSlot.nodeId}:${sourceSlot.outputIndex} (${sourceSlot.slotType})")
        DebugLogger.d(TAG, "completeConnection: targetInput=${targetInput.name} (${targetInput.type})")
        DebugLogger.d(TAG, "completeConnection: nodeType=${nodeType.classType}")

        // 1. Create inputs map - same logic as addNode but with the target input as Connection
        val inputs = linkedMapOf<String, InputValue>()

        // First pass: add all literal (editable) inputs
        nodeType.inputs.forEach { inputDef ->
            val isConnType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")
            if (!isConnType && !inputDef.forceInput) {
                inputs[inputDef.name] = InputValue.Literal(inputDef.getEffectiveDefault())
            }
        }

        // Second pass: add connection-type inputs
        nodeType.inputs.forEach { inputDef ->
            val isConnType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")
            if (isConnType || inputDef.forceInput) {
                // Check if this is the target input we're connecting to
                if (inputDef.name == targetInput.name) {
                    // Set up as connected to the source slot
                    inputs[inputDef.name] = InputValue.Connection(
                        sourceNodeId = sourceSlot.nodeId,
                        outputIndex = sourceSlot.outputIndex
                    )
                    DebugLogger.d(TAG, "completeConnection: setting ${inputDef.name} as Connection to ${sourceSlot.nodeId}:${sourceSlot.outputIndex}")
                } else {
                    inputs[inputDef.name] = InputValue.UnconnectedSlot(inputDef.type)
                }
            }
        }

        // 2. Categorize and calculate dimensions
        val category = nodeCategoryOf(nodeType.classType)
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val rawNodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )
        val nodeHeight = WorkflowLayoutEngine.snapHeightToGrid(rawNodeHeight)

        // 3. Create the new node with a unique ID
        val newNodeId = generateUniqueNodeId()
        val newNode = WorkflowNode(
            id = newNodeId,
            classType = nodeType.classType,
            title = nodeType.classType,
            category = category,
            inputs = sortInputsForLayout(inputs),
            outputs = nodeType.outputs,
            templateInputKeys = emptySet(),
            x = 0f,
            y = 0f,
            width = nodeWidth,
            height = nodeHeight
        )

        DebugLogger.d(TAG, "completeConnection: created newNode id=$newNodeId, inputs=${newNode.inputs.keys}")
        DebugLogger.d(TAG, "completeConnection: newNode input[${targetInput.name}] is ${newNode.inputs[targetInput.name]?.let { it::class.simpleName }}")

        // 4. Add node to graph
        graph.nodes.add(newNode)

        // 5. Create edge from source output to new node's input
        val newEdge = WorkflowEdge(
            sourceNodeId = sourceSlot.nodeId,
            sourceOutputIndex = sourceSlot.outputIndex,
            targetNodeId = newNodeId,
            targetInputName = targetInput.name,
            slotType = sourceSlot.slotType
        )
        graph.edges.add(newEdge)
        DebugLogger.d(TAG, "completeConnection: added edge: ${newEdge.sourceNodeId}:${newEdge.sourceOutputIndex} -> ${newEdge.targetNodeId}:${newEdge.targetInputName}")
        DebugLogger.d(TAG, "completeConnection: total edges: ${graph.edges.size}")

        // 6. Re-layout the graph (single pass)
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

        DebugLogger.d(TAG, "completeConnection: edges after layout: ${layoutedGraph.edges.size}")
        layoutedGraph.edges.forEach { edge ->
            DebugLogger.d(TAG, "  edge: ${edge.sourceNodeId}:${edge.sourceOutputIndex} -> ${edge.targetNodeId}:${edge.targetInputName}")
        }

        // Verify the new node has the connection
        val verifyNode = layoutedGraph.nodes.find { it.id == newNodeId }
        DebugLogger.d(TAG, "completeConnection: verify node input[${targetInput.name}] is ${verifyNode?.inputs?.get(targetInput.name)?.let { it::class.simpleName }}")

        // 7. Update graph bounds
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        // 8. Clear long-press state and update UI
        _uiState.value = _uiState.value.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            selectedNodeIds = setOf(newNodeId),
            longPressSourceSlot = null,
            showCompatibleNodeBrowser = false,
            showInputSelectionDialog = false,
            inputSelectionNodeType = null,
            inputSelectionCompatibleInputs = emptyList(),
            connectionModeState = null,
            hasUnsavedChanges = true,
            // The whole graph's definitions, with the new node's: none of them is "missing"
            nodeDefinitions = buildNodeDefinitionsMap(layoutedGraph) + (nodeType.classType to nodeType)
        )

        DebugLogger.d(TAG, "completeConnection: DONE")
    }

    /**
     * Check if an input type is a connection type (not a literal).
     */
    private fun isConnectionType(type: String): Boolean {
        return type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")
    }

    /**
     * Disconnect an input (Edit structure). A value in ComfyUI (INT, FLOAT, STRING, BOOLEAN, a list) becomes a value
     * row again, with the value it had before it was linked in this session, otherwise the node's default; a
     * connection-only input (MODEL, IMAGE…) stays as a free socket.
     */
    fun deleteConnection(nodeId: String, inputName: String) {
        val graph = mutableGraph ?: return
        if (!_uiState.value.isEditMode) return
        val removed = graph.edges.removeAll { edge -> edge.targetNodeId == nodeId && edge.targetInputName == inputName }
        val nodeIndex = graph.nodes.indexOfFirst { it.id == nodeId }
        if (!removed || nodeIndex < 0) return

        val targetNode = graph.nodes[nodeIndex]
        val inputDef = nodeTypeRegistry.getNodeDefinition(targetNode.classType)?.inputs?.find { it.name == inputName }
        val type = inputDef?.type ?: nodeTypeRegistry.getInputType(targetNode.classType, inputName)
        val isValue = inputDef != null && !inputDef.forceInput && (type?.uppercase() in VALUE_INPUT_TYPES || inputDef.options != null)
        val restored = if (isValue) {
            val kept = keptValues.remove(nodeId to inputName)
            InputValue.Literal(kept ?: inputDef?.default ?: defaultForType(type))
        } else {
            InputValue.UnconnectedSlot(type ?: "*")
        }
        graph.nodes[nodeIndex] = targetNode.copy(inputs = sortInputsForLayout(targetNode.inputs + (inputName to restored)))

        _uiState.value = _uiState.value.copy(hasUnsavedChanges = true)
        // A value row back: the node's rows change, sizes and positions follow
        if (isValue) relayoutGraph() else updateGraphState()
    }

    private fun defaultForType(type: String?): Any = when (type?.uppercase()) {
        "INT" -> 0
        "FLOAT" -> 0.0
        "BOOLEAN" -> false
        else -> ""
    }

    // ===========================================
    // Export/Save Workflow
    // ===========================================

    private val serializer = WorkflowSerializer()

    /**
     * Export the current workflow to JSON string.
     * Applies any pending edits before serialization.
     */
    fun exportToJson(): String? {
        val graph = mutableGraph?.toImmutable() ?: _uiState.value.graph ?: return null
        val edits = _uiState.value.nodeAttributeEdits

        // Apply edits to graph
        val graphWithEdits = serializer.applyEdits(graph, edits)

        // Serialize to JSON
        return serializer.serialize(graphWithEdits, includeMetadata = true)
    }

    /**
     * Export the workflow with wrapper metadata (name, description).
     */
    fun exportToJsonWithWrapper(): String? {
        val graph = mutableGraph?.toImmutable() ?: _uiState.value.graph ?: return null
        val edits = _uiState.value.nodeAttributeEdits

        // Apply edits to graph
        val graphWithEdits = serializer.applyEdits(graph, edits)

        // Serialize with wrapper
        return serializer.serializeWithWrapper(graphWithEdits, includeMetadata = true)
    }

    /**
     * Copy the workflow JSON to clipboard.
     */
    fun copyToClipboard(context: Context): Boolean {
        val json = exportToJson() ?: return false

        try {
            PrivacyUtils.copySensitiveText(context, "Workflow JSON", json)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ===========================================
    // Manual Node Placement (Drag to Reposition)
    // ===========================================

    /**
     * Start dragging a node for manual repositioning.
     */
    fun startNodeDrag(nodeId: String) {
        DebugLogger.d(TAG, "startNodeDrag: nodeId=$nodeId")
        _uiState.value = _uiState.value.copy(
            draggingNodeId = nodeId,
            dragOffset = Offset.Zero
        )
    }

    /**
     * Update the drag offset while dragging a node or note.
     * Works for both node and note drags since they share the same dragOffset.
     */
    fun updateNodeDrag(delta: Offset) {
        val state = _uiState.value
        // Allow update if either a node or note is being dragged
        if (state.draggingNodeId == null && state.draggingNoteId == null) return

        _uiState.value = state.copy(
            dragOffset = state.dragOffset + delta
        )
    }

    /**
     * End dragging and snap the node to the grid.
     */
    fun endNodeDrag() {
        val state = _uiState.value
        val draggingId = state.draggingNodeId ?: return
        val graph = mutableGraph ?: return

        // Find the dragging node
        val nodeIndex = graph.nodes.indexOfFirst { it.id == draggingId }
        if (nodeIndex == -1) {
            cancelNodeDrag()
            return
        }

        val node = graph.nodes[nodeIndex]
        val newX = node.x + state.dragOffset.x
        val newY = node.y + state.dragOffset.y

        // Snap to grid
        val (snappedX, snappedY) = WorkflowLayoutEngine.snapToGrid(newX, newY)

        DebugLogger.d(TAG, "endNodeDrag: nodeId=$draggingId, from (${node.x}, ${node.y}) to ($snappedX, $snappedY)")

        // Update the node position and mark as having manual position
        val updatedNode = node.copy(
            x = snappedX,
            y = snappedY,
            hasManualPosition = true
        )
        graph.nodes[nodeIndex] = updatedNode

        // Recalculate bounds
        val layoutedGraph = graph.toImmutable()
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        // Update UI state - set justDroppedNodeId to skip animation
        _uiState.value = state.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            draggingNodeId = null,
            dragOffset = Offset.Zero,
            justDroppedNodeId = draggingId,
            hasUnsavedChanges = true
        )
    }

    /**
     * Cancel dragging without saving changes.
     */
    fun cancelNodeDrag() {
        _uiState.value = _uiState.value.copy(
            draggingNodeId = null,
            dragOffset = Offset.Zero
        )
    }

    /**
     * Clear the just-dropped node ID after the canvas has rendered it.
     */
    fun clearJustDroppedNode() {
        _uiState.value = _uiState.value.copy(justDroppedNodeId = null)
    }

    // ===========================================
    // Manual Note Placement (Drag to Reposition)
    // ===========================================

    /**
     * Start dragging a note for manual repositioning.
     */
    fun startNoteDrag(noteId: Int) {
        DebugLogger.d(TAG, "startNoteDrag: noteId=$noteId")
        _uiState.value = _uiState.value.copy(
            draggingNoteId = noteId,
            dragOffset = Offset.Zero
        )
    }

    /**
     * Update the drag offset while dragging a note.
     * Uses the same dragOffset as node drag since only one element can be dragged at a time.
     */
    fun updateNoteDrag(delta: Offset) {
        val state = _uiState.value
        if (state.draggingNoteId == null) return

        _uiState.value = state.copy(
            dragOffset = state.dragOffset + delta
        )
    }

    /**
     * End dragging a note and snap to the grid.
     */
    fun endNoteDrag() {
        val state = _uiState.value
        val draggingId = state.draggingNoteId ?: return
        val graph = mutableGraph ?: return

        // Find the dragging note
        val noteIndex = graph.notes.indexOfFirst { it.id == draggingId }
        if (noteIndex == -1) {
            cancelNoteDrag()
            return
        }

        val note = graph.notes[noteIndex]
        val newX = note.x + state.dragOffset.x
        val newY = note.y + state.dragOffset.y

        // Snap to grid
        val (snappedX, snappedY) = WorkflowLayoutEngine.snapToGrid(newX, newY)

        DebugLogger.d(TAG, "endNoteDrag: noteId=$draggingId, from (${note.x}, ${note.y}) to ($snappedX, $snappedY)")

        // Update the note position and mark as having manual position
        val updatedNote = note.copy(
            x = snappedX,
            y = snappedY,
            hasManualPosition = true
        )
        graph.notes[noteIndex] = updatedNote

        // Recalculate bounds
        val layoutedGraph = graph.toImmutable()
        val newBounds = layoutEngine.calculateBounds(layoutedGraph)

        // Update UI state - set justDroppedNoteId to skip animation
        _uiState.value = state.copy(
            graph = layoutedGraph,
            graphBounds = newBounds,
            draggingNoteId = null,
            dragOffset = Offset.Zero,
            justDroppedNoteId = draggingId,
            hasUnsavedChanges = true
        )
    }

    /**
     * Cancel dragging a note without saving changes.
     */
    fun cancelNoteDrag() {
        _uiState.value = _uiState.value.copy(
            draggingNoteId = null,
            dragOffset = Offset.Zero
        )
    }

    /**
     * Clear the just-dropped note ID after the canvas has rendered it.
     */
    fun clearJustDroppedNote() {
        _uiState.value = _uiState.value.copy(justDroppedNoteId = null)
    }

    /**
     * Add a new node in manual placement mode, positioned at the view center.
     * Calculates view center from stored canvas dimensions and current transform.
     */
    fun addNodeManual(nodeType: NodeTypeDefinition) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        if (!state.isEditMode) return

        // Create inputs map from the node definition (same as addNode)
        val inputs = linkedMapOf<String, InputValue>()

        // First pass: add all literal (editable) inputs
        nodeType.inputs.forEach { inputDef ->
            val isConnectionType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

            if (!isConnectionType && !inputDef.forceInput) {
                inputs[inputDef.name] = InputValue.Literal(inputDef.getEffectiveDefault())
            }
        }

        // Second pass: add all connection-type inputs (slots)
        nodeType.inputs.forEach { inputDef ->
            val isConnectionType = inputDef.type !in listOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

            if (isConnectionType || inputDef.forceInput) {
                inputs[inputDef.name] = InputValue.UnconnectedSlot(inputDef.type)
            }
        }

        val category = nodeCategoryOf(nodeType.classType)

        // Calculate proper node dimensions
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val rawNodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )
        val nodeHeight = WorkflowLayoutEngine.snapHeightToGrid(rawNodeHeight)

        // Calculate view center in graph coordinates
        val viewCenterX = (canvasWidth / 2 - state.offset.x) / state.scale
        val viewCenterY = (canvasHeight / 2 - state.offset.y) / state.scale

        // Calculate node position at view center, snapped to grid
        val nodeX = viewCenterX - nodeWidth / 2
        val nodeY = viewCenterY - nodeHeight / 2
        val (snappedX, snappedY) = WorkflowLayoutEngine.snapToGrid(nodeX, nodeY)

        // Create the new node with position already set
        val newNode = WorkflowNode(
            id = generateUniqueNodeId(),
            classType = nodeType.classType,
            title = nodeType.classType,
            category = category,
            inputs = sortInputsForLayout(inputs),
            outputs = nodeType.outputs,
            templateInputKeys = emptySet(),
            x = snappedX,
            y = snappedY,
            width = nodeWidth,
            height = nodeHeight,
            hasManualPosition = true  // Mark as manually positioned
        )

        // Add to graph
        graph.nodes.add(newNode)

        // Update graph (no re-layout needed since we're in manual mode)
        val updatedGraph = graph.toImmutable()
        val newBounds = layoutEngine.calculateBounds(updatedGraph)

        // Update UI state
        _uiState.value = state.copy(
            graph = updatedGraph,
            graphBounds = newBounds,
            selectedNodeIds = setOf(newNode.id),
            hasUnsavedChanges = true,
            showNodeBrowser = false,
            nodeInsertPosition = null,
            nodeDefinitions = buildNodeDefinitionsMap(updatedGraph) + (nodeType.classType to nodeType)
        )
    }
}

/** Input types that are values (widgets) in ComfyUI, apart from lists. */
private val VALUE_INPUT_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN")
