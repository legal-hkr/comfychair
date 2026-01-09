package sh.hnet.comfychair.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
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
import sh.hnet.comfychair.util.WorkflowJsonAnalyzer
import sh.hnet.comfychair.workflow.ConnectionDirection
import sh.hnet.comfychair.workflow.ConnectionModeState
import sh.hnet.comfychair.workflow.DiscardAction
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.GraphBounds
import sh.hnet.comfychair.workflow.GroupManager
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NoteManager
import sh.hnet.comfychair.workflow.getEffectiveDefault
import sh.hnet.comfychair.workflow.sortInputsForLayout
import sh.hnet.comfychair.workflow.MutableWorkflowGraph
import sh.hnet.comfychair.workflow.NodeCategory
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.WorkflowEdge
import sh.hnet.comfychair.workflow.WorkflowEditorUiState
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowGroup
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.WorkflowNote
import sh.hnet.comfychair.workflow.WorkflowParser
import sh.hnet.comfychair.workflow.WorkflowSerializer

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
    private val layoutEngine = WorkflowLayoutEngine()

    // Use shared NodeTypeRegistry from ConnectionManager (populated on connection)
    private val nodeTypeRegistry: NodeTypeRegistry
        get() = ConnectionManager.nodeTypeRegistry

    private var canvasWidth: Float = 0f
    private var canvasHeight: Float = 0f
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
                    viewingWorkflowIsBuiltIn = isBuiltIn
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
                    isFieldMappingMode = true,
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
                    // Edit-existing specific flags
                    isEditExistingMode = true,
                    isEditMode = true,  // Start in edit mode
                    editingWorkflowId = workflowId,
                    editingWorkflowType = workflow.type,
                    originalWorkflowName = workflow.name,
                    originalWorkflowDescription = workflow.description,
                    hasUnsavedChanges = false
                )
                isInitialized = true

                DebugLogger.i(TAG, "initializeForEditingExisting: Loaded workflow ${workflow.name}, type=${workflow.type}")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "initializeForEditingExisting: Failed to load workflow: ${e.message}")
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

        // If there's a selected field and this node is a candidate for it, select it
        val selectedFieldKey = state.selectedFieldKey
        if (selectedFieldKey != null) {
            val matchingField = affectedMappings.find { it.field.fieldKey == selectedFieldKey }
            if (matchingField != null) {
                val candidateIndex = matchingField.candidates.indexOfFirst { it.nodeId == nodeId }
                if (candidateIndex >= 0) {
                    updateFieldMapping(matchingField.field.fieldKey, candidateIndex)
                    return
                }
            }
        }

        // Otherwise, select the first affected field
        val firstField = affectedMappings.first()
        val candidateIndex = firstField.candidates.indexOfFirst { it.nodeId == nodeId }
        if (candidateIndex >= 0) {
            updateFieldMapping(firstField.field.fieldKey, candidateIndex)
        }
    }

    /**
     * Update the selected candidate for a field.
     * If the selected node was previously mapped to another field, clears that field's mapping.
     */
    fun updateFieldMapping(fieldKey: String, candidateIndex: Int) {
        val state = _uiState.value
        val mappingState = state.mappingState ?: return

        // Get the node being selected
        val targetField = mappingState.fieldMappings.find { it.field.fieldKey == fieldKey }
        val selectedNodeId = targetField?.candidates?.getOrNull(candidateIndex)?.nodeId

        val updatedMappings = mappingState.fieldMappings.map { fieldMapping ->
            when {
                // Update the target field with new selection
                fieldMapping.field.fieldKey == fieldKey -> {
                    fieldMapping.copy(selectedCandidateIndex = candidateIndex)
                }
                // Clear other field if it had this same node selected (set to -1 = unmapped)
                selectedNodeId != null &&
                fieldMapping.selectedCandidate?.nodeId == selectedNodeId -> {
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
            saveDialogDescriptionError = null,
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
            saveDialogDescriptionError = null
        )
    }

    /**
     * Update save dialog type selection
     */
    fun onSaveDialogTypeSelected(type: WorkflowType) {
        _uiState.value = _uiState.value.copy(
            saveDialogSelectedType = type,
            saveDialogTypeDropdownExpanded = false
        )
    }

    /**
     * Toggle save dialog type dropdown
     */
    fun onSaveDialogToggleTypeDropdown() {
        _uiState.value = _uiState.value.copy(
            saveDialogTypeDropdownExpanded = !_uiState.value.saveDialogTypeDropdownExpanded
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
            saveDialogDescriptionError = null
        )
    }

    /**
     * Dismiss duplicate name dialog
     */
    fun dismissDuplicateNameDialog() {
        _uiState.value = _uiState.value.copy(showDuplicateNameDialog = false)
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

        DebugLogger.d(TAG, "proceedWithSave: name='$name', type=$selectedType, nodes=${graph.nodes.size}")

        // Validate name format
        val nameError = WorkflowManager.validateWorkflowName(name)
        if (nameError != null) {
            DebugLogger.w(TAG, "proceedWithSave: Name validation failed: $nameError")
            _uiState.value = _uiState.value.copy(
                saveDialogNameError = nameError
            )
            return
        }

        // Validate description
        val descError = WorkflowManager.validateWorkflowDescription(description)
        if (descError != null) {
            DebugLogger.w(TAG, "proceedWithSave: Description validation failed: $descError")
            _uiState.value = _uiState.value.copy(
                saveDialogDescriptionError = descError
            )
            return
        }

        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
            DebugLogger.w(TAG, "proceedWithSave: Duplicate name detected")
            _uiState.value = _uiState.value.copy(showDuplicateNameDialog = true)
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
                        saveDialogNameError = context.getString(R.string.workflow_error_fetch_nodes_failed)
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

        DebugLogger.d(TAG, "confirmMappingAndSave: Saving '${pending.name}' as ${pending.type}, editingWorkflowId=${state.editingWorkflowId}")

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
            DebugLogger.e(TAG, "confirmMappingAndSave: Save failed: ${error?.message}")
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
    private fun resolveEdgeTypes(graph: WorkflowGraph): WorkflowGraph {
        if (!nodeTypeRegistry.isPopulated()) {
            return graph
        }

        val resolvedEdges = graph.edges.map { edge ->
            val sourceNode = graph.nodes.find { it.id == edge.sourceNodeId }
            val slotType = sourceNode?.let {
                nodeTypeRegistry.getOutputType(it.classType, edge.sourceOutputIndex)
            }
            edge.copy(slotType = slotType)
        }
        return graph.copy(edges = resolvedEdges)
    }

    /**
     * Populate node outputs using the node type registry.
     * Adds output types to each node based on its class type.
     */
    private fun populateNodeOutputs(graph: WorkflowGraph): WorkflowGraph {
        if (!nodeTypeRegistry.isPopulated()) {
            return graph
        }

        val nodesWithOutputs = graph.nodes.map { node ->
            val definition = nodeTypeRegistry.getNodeDefinition(node.classType)
            val outputs = definition?.outputs ?: emptyList()
            node.copy(outputs = outputs)
        }
        return graph.copy(nodes = nodesWithOutputs)
    }

    /**
     * Set the canvas size for proper centering calculations
     */
    fun setCanvasSize(width: Float, height: Float) {
        if (canvasWidth != width || canvasHeight != height) {
            canvasWidth = width
            canvasHeight = height
            // Auto-fit on first load
            if (_uiState.value.scale == 1f && _uiState.value.offset == Offset.Zero) {
                fitToScreen()
            }
        }
    }

    /**
     * Update scale and offset from pan/zoom gestures
     */
    fun onTransform(scale: Float, offset: Offset) {
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
     * Fit the entire graph to the screen (both width and height)
     */
    private fun fitToScreen() {
        val transform = GraphViewportCalculator.fitToScreen(
            bounds = _uiState.value.graphBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        ) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_ALL
    }

    /**
     * Fit the graph width to the screen, showing the top of the graph
     */
    private fun fitToWidth() {
        val transform = GraphViewportCalculator.fitToWidth(
            bounds = _uiState.value.graphBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        ) ?: return

        _uiState.value = _uiState.value.copy(
            scale = transform.scale,
            offset = transform.offset
        )
        lastFitMode = FitMode.FIT_WIDTH
    }

    /**
     * Fit the graph height to the screen, showing the left side of the graph
     */
    private fun fitToHeight() {
        val transform = GraphViewportCalculator.fitToHeight(
            bounds = _uiState.value.graphBounds,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        ) ?: return

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
    private fun buildNodeDefinitionsMap(graph: WorkflowGraph): Map<String, NodeTypeDefinition> {
        val definitions = mutableMapOf<String, NodeTypeDefinition>()
        graph.nodes.forEach { node ->
            if (node.classType !in definitions) {
                nodeTypeRegistry.getNodeDefinition(node.classType)?.let {
                    definitions[node.classType] = it
                }
            }
        }
        return definitions
    }

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

        // Only save current scale and offset if NOT already editing
        // This preserves the original view state when switching between nodes
        val savedScale = if (state.isEditingNode) state.savedScaleBeforeEditing else state.scale
        val savedOffset = if (state.isEditingNode) state.savedOffsetBeforeEditing else state.offset

        // Calculate available space
        // When already editing, canvasWidth already reflects the reduced size (side sheet is open)
        // When not editing, we need to account for side sheet that will take ~60% of width
        val padding = 48f
        val availableWidth = if (state.isEditingNode) {
            // Side sheet already open - canvasWidth is already the visible area
            canvasWidth - padding * 2
        } else {
            // Side sheet will open - account for it taking 60% of screen
            canvasWidth * 0.4f - padding * 2
        }
        val availableHeight = canvasHeight - padding * 2

        // Calculate scale to fit the node in available space
        val scaleToFitWidth = if (node.width > 0) availableWidth / node.width else 1f
        val scaleToFitHeight = if (node.height > 0) availableHeight / node.height else 1f
        val newScale = minOf(scaleToFitWidth, scaleToFitHeight)
            .coerceIn(0.5f, 2.0f)  // Limit zoom range

        // Calculate new offset to:
        // - Vertically center the node
        // - Horizontally align node to left (with padding)
        val nodeCenterY = node.y + node.height / 2
        val targetY = canvasHeight / 2
        val leftPadding = padding

        val newOffsetX = leftPadding - node.x * newScale
        val newOffsetY = targetY - nodeCenterY * newScale

        // Compute editable input names (same logic as NodeAttributeSideSheet.buildEditableInputs)
        val nodeDefinition = state.nodeDefinitions[node.classType]
        val editableInputNames = node.inputs.filter { (name, value) ->
            // Skip connections
            if (value is InputValue.Connection) return@filter false

            // Skip template variables
            if (value is InputValue.Literal) {
                val strValue = value.value.toString()
                if (strValue.contains("{{") && strValue.contains("}}")) return@filter false
            }

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
     * Reset a node attribute to its default value.
     */
    fun resetNodeAttribute(nodeId: String, inputName: String) {
        val state = _uiState.value
        val graph = state.graph ?: return
        val currentEdits = state.nodeAttributeEdits.toMutableMap()

        // Find the node to get its class type
        val node = graph.nodes.find { it.id == nodeId } ?: return

        // Get the node definition to find the effective default
        val nodeDefinition = state.nodeDefinitions[node.classType]
        val inputDefinition = nodeDefinition?.inputs?.find { it.name == inputName }

        // Get the effective default value
        val defaultValue = inputDefinition?.getEffectiveDefault()

        val nodeEdits = currentEdits[nodeId]?.toMutableMap() ?: mutableMapOf()

        if (defaultValue != null) {
            // Set the edit to the default value (so canvas shows default, not empty)
            nodeEdits[inputName] = defaultValue
            currentEdits[nodeId] = nodeEdits
        } else {
            // Fallback: remove the edit if no default available
            nodeEdits.remove(inputName)
            if (nodeEdits.isEmpty()) {
                currentEdits.remove(nodeId)
            } else {
                currentEdits[nodeId] = nodeEdits
            }
        }

        _uiState.value = state.copy(nodeAttributeEdits = currentEdits)
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
                DebugLogger.d(TAG, "toggleNodeBypass: Changing mode from ${node.mode} to $newMode for node '${node.title}'")
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
            originalWorkflowDescription = workflow.description
        )

        DebugLogger.i(TAG, "returnToViewMode: Now in view mode for workflow ${workflow.name}")
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
     * Categorize a node by its class type (same logic as WorkflowParser).
     */
    private fun categorizeNodeByClassType(classType: String): NodeCategory {
        return when {
            classType.contains("Loader", ignoreCase = true) -> NodeCategory.LOADER
            classType.contains("CLIPTextEncode", ignoreCase = true) ||
            classType.contains("TextEncode", ignoreCase = true) -> NodeCategory.ENCODER
            classType.contains("Sampler", ignoreCase = true) -> NodeCategory.SAMPLER
            classType.contains("EmptyLatent", ignoreCase = true) ||
            classType.contains("Empty") && classType.contains("Latent", ignoreCase = true) -> NodeCategory.LATENT
            classType.contains("LoadImage", ignoreCase = true) -> NodeCategory.INPUT
            classType.contains("Save", ignoreCase = true) ||
            classType.contains("Preview", ignoreCase = true) -> NodeCategory.OUTPUT
            classType.contains("Decode", ignoreCase = true) ||
            classType.contains("Encode", ignoreCase = true) ||
            classType.contains("Scale", ignoreCase = true) ||
            classType.contains("Create", ignoreCase = true) ||
            classType.contains("Sampling", ignoreCase = true) ||
            classType.contains("ModelMerge", ignoreCase = true) -> NodeCategory.PROCESS
            else -> NodeCategory.OTHER
        }
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
        val category = categorizeNodeByClassType(nodeType.classType)

        // Calculate proper node dimensions using the same logic as WorkflowLayoutEngine
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val nodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )

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
        val layoutEngine = WorkflowLayoutEngine()
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
            nodeDefinitions = _uiState.value.nodeDefinitions + (nodeType.classType to nodeType)
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
        if (targetNode != null) {
            val updatedInputs = targetNode.inputs.toMutableMap()
            updatedInputs[inputSlot.slotName] = InputValue.Connection(
                sourceNodeId = outputSlot.nodeId,
                outputIndex = outputSlot.outputIndex
            )
            // Update the node in place
            val nodeIndex = graph.nodes.indexOfFirst { it.id == targetNode.id }
            if (nodeIndex >= 0) {
                graph.nodes[nodeIndex] = targetNode.copy(inputs = updatedInputs)
            }
        }

        // Update UI state
        updateGraphState()
        _uiState.value = _uiState.value.copy(
            connectionModeState = null,
            hasUnsavedChanges = true
        )
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
            0 -> cancelLongPressConnection()  // Shouldn't happen if filtering works
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
            0 -> cancelLongPressConnection()  // Shouldn't happen if filtering works
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
        val category = categorizeNodeByClassType(nodeType.classType)
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val nodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )

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

        // 6. Create edge from new node's output TO the target input
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
            val updatedInputs = targetNode.inputs.toMutableMap()
            updatedInputs[targetInputSlot.slotName] = InputValue.Connection(
                sourceNodeId = newNodeId,
                outputIndex = outputIndex
            )
            val nodeIndex = graph.nodes.indexOfFirst { it.id == targetNode.id }
            if (nodeIndex >= 0) {
                graph.nodes[nodeIndex] = targetNode.copy(inputs = updatedInputs)
            }
        }

        DebugLogger.d(TAG, "completeReverseConnection: added edge: ${newEdge.sourceNodeId}:${newEdge.sourceOutputIndex} -> ${newEdge.targetNodeId}:${newEdge.targetInputName}")

        // 8. Re-layout the graph (single pass)
        val layoutEngine = WorkflowLayoutEngine()
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
            hasUnsavedChanges = true
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
        val category = categorizeNodeByClassType(nodeType.classType)
        val nodeWidth = WorkflowLayoutEngine.NODE_WIDTH
        val literalInputCount = inputs.count { (_, value) -> value is InputValue.Literal }
        val connectionInputCount = inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }
        val outputCount = nodeType.outputs.size
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        val contentHeight = (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) + connectionAreaHeight
        val nodeHeight = maxOf(
            WorkflowLayoutEngine.NODE_MIN_HEIGHT,
            WorkflowLayoutEngine.NODE_HEADER_HEIGHT + contentHeight + 16f
        )

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
        DebugLogger.d(TAG, "completeConnection: newNode input[${targetInput.name}]=${newNode.inputs[targetInput.name]}")

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
        val layoutEngine = WorkflowLayoutEngine()
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

        DebugLogger.d(TAG, "completeConnection: edges after layout: ${layoutedGraph.edges.size}")
        layoutedGraph.edges.forEach { edge ->
            DebugLogger.d(TAG, "  edge: ${edge.sourceNodeId}:${edge.sourceOutputIndex} -> ${edge.targetNodeId}:${edge.targetInputName}")
        }

        // Verify the new node has the connection
        val verifyNode = layoutedGraph.nodes.find { it.id == newNodeId }
        DebugLogger.d(TAG, "completeConnection: verify node input[${targetInput.name}]=${verifyNode?.inputs?.get(targetInput.name)}")

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
            hasUnsavedChanges = true
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
     * Delete a connection by removing the edge to a specific input.
     */
    fun deleteConnection(nodeId: String, inputName: String) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        if (!state.isEditMode) return

        // Find and remove the edge
        val removed = graph.edges.removeAll { edge ->
            edge.targetNodeId == nodeId && edge.targetInputName == inputName
        }

        if (removed) {
            // Revert the input back to a Literal value with default
            val targetNode = graph.nodes.find { it.id == nodeId }
            if (targetNode != null) {
                val nodeDefinition = nodeTypeRegistry.getNodeDefinition(targetNode.classType)
                val inputDef = nodeDefinition?.inputs?.find { it.name == inputName }
                val defaultValue = inputDef?.default ?: when (inputDef?.type) {
                    "INT" -> 0
                    "FLOAT" -> 0.0
                    "STRING" -> ""
                    "BOOLEAN" -> false
                    else -> ""
                }

                val updatedInputs = targetNode.inputs.toMutableMap()
                updatedInputs[inputName] = InputValue.Literal(defaultValue)

                val nodeIndex = graph.nodes.indexOfFirst { it.id == targetNode.id }
                if (nodeIndex >= 0) {
                    graph.nodes[nodeIndex] = targetNode.copy(inputs = updatedInputs)
                }
            }

            updateGraphState()
            _uiState.value = _uiState.value.copy(hasUnsavedChanges = true)
        }
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
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Workflow JSON", json)
            clipboard.setPrimaryClip(clip)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
