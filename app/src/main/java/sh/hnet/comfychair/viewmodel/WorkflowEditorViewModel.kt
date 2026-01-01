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
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.workflow.ConnectionModeState
import sh.hnet.comfychair.workflow.DiscardAction
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.GraphBounds
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.MutableWorkflowGraph
import sh.hnet.comfychair.workflow.NodeCategory
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.RequiredField
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowEdge
import sh.hnet.comfychair.workflow.WorkflowEditorUiState
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode
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

    // Original graph before editing (for discard/restore)
    private var originalGraph: WorkflowGraph? = null
    private var originalBounds: GraphBounds? = null

    /**
     * Initialize the editor with a workflow ID (view mode)
     */
    fun initialize(context: Context, workflowId: String?, workflowJson: String?) {
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
                    highlightedNodeIds = highlightedNodes,
                    canConfirmMapping = mappingState.allRequiredFieldsMapped
                )
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
        }
    }

    /**
     * Initialize the editor for editing an existing user workflow's structure.
     * Loads the workflow, enters edit mode, but preserves existing metadata.
     * Save flow skips the save dialog and updates the existing file.
     */
    fun initializeForEditingExisting(context: Context, workflowId: String) {
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
        val classTypes = graph.nodes.map { it.classType }.toSet()

        val hasVideoNodes = classTypes.any { classType ->
            classType.contains("CreateVideo", ignoreCase = true) ||
            classType.contains("VHS_VideoCombine", ignoreCase = true) ||
            classType.contains("VideoLinearCFGGuidance", ignoreCase = true)
        }

        val hasLoadImage = classTypes.any { it.equals("LoadImage", ignoreCase = true) }
        val hasInpaintingNodes = classTypes.any {
            it.contains("SetLatentNoiseMask", ignoreCase = true) ||
            it.contains("Inpaint", ignoreCase = true)
        }
        val hasImageEditingNodes = classTypes.any {
            it.contains("QwenImageEdit", ignoreCase = true)
        }
        val hasCheckpointLoader = classTypes.any { it.equals("CheckpointLoaderSimple", ignoreCase = true) }
        val hasUNETLoader = classTypes.any { it.equals("UNETLoader", ignoreCase = true) }

        return when {
            hasLoadImage && hasVideoNodes -> WorkflowType.ITV_UNET
            hasVideoNodes -> WorkflowType.TTV_UNET
            hasImageEditingNodes && hasUNETLoader -> WorkflowType.ITE_UNET
            hasInpaintingNodes && hasCheckpointLoader -> WorkflowType.ITI_CHECKPOINT
            hasInpaintingNodes && hasUNETLoader -> WorkflowType.ITI_UNET
            hasLoadImage && hasCheckpointLoader -> WorkflowType.ITI_CHECKPOINT
            hasLoadImage && hasUNETLoader -> WorkflowType.ITI_UNET
            hasCheckpointLoader -> WorkflowType.TTI_CHECKPOINT
            hasUNETLoader -> WorkflowType.TTI_UNET
            else -> null
        }
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

                val workflowClassTypes = graph.nodes.map { it.classType }.toSet()
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

                val workflowClassTypes = graph.nodes.map { it.classType }.toSet()
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
                    canConfirmMapping = mappingState.allRequiredFieldsMapped,
                    highlightedNodeIds = calculateHighlightedNodes(mappingState)
                )
            }
        }
    }

    /**
     * Create field mapping state by analyzing graph nodes.
     */
    private fun createFieldMappingStateFromGraph(
        context: Context,
        graph: WorkflowGraph,
        type: WorkflowType
    ): WorkflowMappingState {
        DebugLogger.d(TAG, "createFieldMappingStateFromGraph: Creating mappings for type=$type")

        // Get strictly required keys for this workflow type
        val strictlyRequiredKeys = TemplateKeyRegistry.getRequiredKeysForType(type)
        DebugLogger.d(TAG, "createFieldMappingStateFromGraph: Strictly required keys: $strictlyRequiredKeys")

        // Get optional keys, adjusted for workflow structure (DualCLIPLoader, BasicGuider)
        val optionalKeys = getOptionalKeysFromGraph(type, graph)
        DebugLogger.d(TAG, "createFieldMappingStateFromGraph: Optional keys: $optionalKeys")

        // Pre-compute prompt field mappings using graph tracing
        val promptMappings = createPromptFieldMappingsFromGraph(graph)
        DebugLogger.d(TAG, "createFieldMappingStateFromGraph: Prompt mappings - positive=${promptMappings["positive_text"]?.size ?: 0}, negative=${promptMappings["negative_text"]?.size ?: 0}")

        val fieldMappings = mutableListOf<FieldMappingState>()

        // Process strictly required keys (isRequired = true)
        for (fieldKey in strictlyRequiredKeys) {
            val candidates = findCandidatesForFieldInGraph(fieldKey, graph, promptMappings)
            val requiredField = FieldDisplayRegistry.createRequiredField(context, fieldKey, isRequired = true)

            fieldMappings.add(
                FieldMappingState(
                    field = requiredField,
                    candidates = candidates,
                    selectedCandidateIndex = if (candidates.isNotEmpty()) 0 else -1
                )
            )
        }

        // Process optional keys (isRequired = false)
        for (fieldKey in optionalKeys) {
            val candidates = findCandidatesForFieldInGraph(fieldKey, graph, promptMappings)
            val requiredField = FieldDisplayRegistry.createRequiredField(context, fieldKey, isRequired = false)

            fieldMappings.add(
                FieldMappingState(
                    field = requiredField,
                    candidates = candidates,
                    selectedCandidateIndex = if (candidates.isNotEmpty()) 0 else -1
                )
            )
        }

        return WorkflowMappingState(
            workflowType = type,
            fieldMappings = fieldMappings
        )
    }

    /**
     * Get optional keys for a workflow type, adjusted for graph structure.
     * Handles DualCLIPLoader (clip_name1/2 instead of clip_name) and BasicGuider (no CFG/negative).
     */
    private fun getOptionalKeysFromGraph(type: WorkflowType, graph: WorkflowGraph): Set<String> {
        val baseKeys = TemplateKeyRegistry.getOptionalKeysForType(type).toMutableSet()

        if (type == WorkflowType.TTI_UNET) {
            // Check for DualCLIPLoader (replaces clip_name with clip_name1 + clip_name2)
            val hasDualClip = graph.nodes.any { it.classType == "DualCLIPLoader" }
            if (hasDualClip) {
                baseKeys.remove("clip_name")
                baseKeys.add("clip_name1")
                baseKeys.add("clip_name2")
            }

            // Check for BasicGuider (no CFG, no negative prompt)
            val hasBasicGuider = graph.nodes.any { it.classType == "BasicGuider" }
            if (hasBasicGuider) {
                baseKeys.remove("cfg")
                baseKeys.remove("negative_text")
            }
        }

        return baseKeys
    }

    /**
     * Find candidates for a field in the graph.
     */
    private fun findCandidatesForFieldInGraph(
        fieldKey: String,
        graph: WorkflowGraph,
        promptMappings: Map<String, List<FieldCandidate>>
    ): List<FieldCandidate> {
        // Handle positive_text and negative_text with graph tracing
        if (fieldKey == "positive_text" || fieldKey == "negative_text") {
            return promptMappings[fieldKey] ?: emptyList()
        }

        // Find nodes that have this input key
        val directCandidates = mutableListOf<FieldCandidate>()

        for (node in graph.nodes) {
            node.inputs.forEach { (inputName, inputValue) ->
                if (TemplateKeyRegistry.doesInputKeyMatchField(fieldKey, inputName)) {
                    val currentValue = when (inputValue) {
                        is InputValue.Literal -> inputValue.value
                        else -> null
                    }

                    if (TemplateKeyRegistry.doesValueMatchPlaceholder(fieldKey, currentValue)) {
                        directCandidates.add(
                            FieldCandidate(
                                nodeId = node.id,
                                nodeName = node.title,
                                classType = node.classType,
                                inputKey = inputName,
                                currentValue = currentValue
                            )
                        )
                    }
                }
            }
        }
        return directCandidates
    }

    /**
     * Create field mappings for positive_text and negative_text using graph tracing.
     * Traces text encoding nodes to find which connects to "positive" vs "negative" sampler inputs.
     * Supports CLIPTextEncode (with "text" input) and other encoders like TextEncodeQwenImageEditPlus (with "prompt" input).
     */
    private fun createPromptFieldMappingsFromGraph(graph: WorkflowGraph): Map<String, List<FieldCandidate>> {
        val positiveTextCandidates = mutableListOf<FieldCandidate>()
        val negativeTextCandidates = mutableListOf<FieldCandidate>()

        // Text input keys to look for in text encoding nodes
        val textInputKeys = listOf("text", "prompt")

        // Find all text encoding nodes with text/prompt input
        data class TextEncoderNode(val node: WorkflowNode, val inputKey: String)
        val textEncoderNodes = graph.nodes.mapNotNull { node ->
            val matchingInputKey = textInputKeys.firstOrNull { node.inputs.containsKey(it) }
            if (matchingInputKey != null) {
                // Only include known text encoding node types
                val isTextEncoder = node.classType == "CLIPTextEncode" ||
                        node.classType.contains("TextEncode", ignoreCase = true) ||
                        node.classType.contains("Prompt", ignoreCase = true)
                if (isTextEncoder) TextEncoderNode(node, matchingInputKey) else null
            } else null
        }

        // For each text encoder, trace its output to find if it connects to positive or negative
        for ((node, inputKey) in textEncoderNodes) {
            val textInput = node.inputs[inputKey]
            val currentValue = when (textInput) {
                is InputValue.Literal -> textInput.value
                else -> null
            }

            val candidate = FieldCandidate(
                nodeId = node.id,
                nodeName = node.title,
                classType = node.classType,
                inputKey = inputKey,
                currentValue = currentValue
            )

            // Determine if this node connects to positive or negative input
            val connectionType = traceClipTextEncodeConnection(graph, node.id)

            when (connectionType) {
                "positive" -> positiveTextCandidates.add(0, candidate) // Add traced match first
                "negative" -> negativeTextCandidates.add(0, candidate) // Add traced match first
                else -> {
                    // Unknown connection - use title-based classification
                    val titleLower = node.title.lowercase()
                    when {
                        titleLower.contains("positive") -> positiveTextCandidates.add(candidate)
                        titleLower.contains("negative") -> negativeTextCandidates.add(candidate)
                        else -> {
                            // Add to both as fallback candidates
                            positiveTextCandidates.add(candidate)
                            negativeTextCandidates.add(candidate)
                        }
                    }
                }
            }
        }

        return mapOf(
            "positive_text" to positiveTextCandidates,
            "negative_text" to negativeTextCandidates
        )
    }

    /**
     * Trace a CLIPTextEncode node's output to find what type of connection it has.
     * Uses WorkflowGraph.edges to trace connections.
     * Returns "positive", "negative", or null if no connection found.
     */
    private fun traceClipTextEncodeConnection(graph: WorkflowGraph, clipNodeId: String): String? {
        // Find edges originating from this CLIPTextEncode node
        val outgoingEdges = graph.edges.filter { it.sourceNodeId == clipNodeId }

        for (edge in outgoingEdges) {
            val targetNode = graph.nodes.find { it.id == edge.targetNodeId } ?: continue
            val targetClassType = targetNode.classType

            // Check KSampler and KSamplerAdvanced nodes
            if (targetClassType == "KSampler" || targetClassType == "KSamplerAdvanced") {
                when (edge.targetInputName) {
                    "positive" -> return "positive"
                    "negative" -> return "negative"
                }
            }

            // For video workflows, check conditioning nodes
            if (targetClassType.contains("ImageToVideo", ignoreCase = true) ||
                targetClassType.contains("TextToVideo", ignoreCase = true)) {
                when (edge.targetInputName) {
                    "positive" -> return "positive"
                    "negative" -> return "negative"
                }
            }

            // Check BasicGuider nodes (Flux-style single conditioning)
            if (targetClassType == "BasicGuider") {
                if (edge.targetInputName == "conditioning") {
                    return "positive"  // BasicGuider only has positive conditioning
                }
            }

            // Check CFGGuider nodes
            if (targetClassType == "CFGGuider") {
                when (edge.targetInputName) {
                    "positive" -> return "positive"
                    "negative" -> return "negative"
                }
            }

            // Check SamplerCustom and SamplerCustomAdvanced
            if (targetClassType.startsWith("SamplerCustom")) {
                when (edge.targetInputName) {
                    "positive" -> return "positive"
                    "negative" -> return "negative"
                }
            }
        }

        return null
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

        // Serialize graph to JSON
        DebugLogger.d(TAG, "confirmMappingAndSave: Serializing graph with ${pending.graph.nodes.size} nodes")
        val jsonContent = serializer.serialize(pending.graph)
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
        val oldScale = _uiState.value.scale
        val newScale = (oldScale * zoomFactor).coerceIn(0.2f, 3f)
        val oldOffset = _uiState.value.offset

        // Center of the canvas
        val centerX = canvasWidth / 2
        val centerY = canvasHeight / 2

        // Adjust offset to keep the center point stationary
        val scaleChange = newScale / oldScale
        val newOffset = Offset(
            x = centerX - (centerX - oldOffset.x) * scaleChange,
            y = centerY - (centerY - oldOffset.y) * scaleChange
        )

        _uiState.value = _uiState.value.copy(
            scale = newScale,
            offset = newOffset
        )
    }

    /**
     * Fit the entire graph to the screen (both width and height)
     */
    private fun fitToScreen() {
        val bounds = _uiState.value.graphBounds
        if (bounds.width <= 0 || bounds.height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return
        }

        // Small top padding, reserve space for bottom toolbar
        val topPadding = 8f
        val bottomToolbarPadding = 100f
        val availableHeight = canvasHeight - topPadding - bottomToolbarPadding

        // Calculate scale to fit in available area
        val scaleX = canvasWidth / bounds.width
        val scaleY = availableHeight / bounds.height
        val scale = minOf(scaleX, scaleY, 1.5f) * 0.95f // 95% of fit, max 1.5x

        // Calculate offset - center horizontally, anchor to top vertically
        val scaledWidth = bounds.width * scale
        val offsetX = (canvasWidth - scaledWidth) / 2 - bounds.minX * scale
        val offsetY = topPadding - bounds.minY * scale

        _uiState.value = _uiState.value.copy(
            scale = scale,
            offset = Offset(offsetX, offsetY)
        )
        lastFitMode = FitMode.FIT_ALL
    }

    /**
     * Fit the graph width to the screen, showing the top of the graph
     */
    private fun fitToWidth() {
        val bounds = _uiState.value.graphBounds
        if (bounds.width <= 0 || bounds.height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return
        }

        // Small padding on sides
        val horizontalPadding = 16f
        val availableWidth = canvasWidth - horizontalPadding * 2

        // Calculate scale to fit width only
        val scale = minOf(availableWidth / bounds.width, 1.5f) // max 1.5x

        // Calculate offset - center horizontally, anchor to top vertically
        val scaledWidth = bounds.width * scale
        val offsetX = (canvasWidth - scaledWidth) / 2 - bounds.minX * scale
        val topPadding = 8f
        val offsetY = topPadding - bounds.minY * scale

        _uiState.value = _uiState.value.copy(
            scale = scale,
            offset = Offset(offsetX, offsetY)
        )
        lastFitMode = FitMode.FIT_WIDTH
    }

    /**
     * Fit the graph height to the screen, showing the left side of the graph
     */
    private fun fitToHeight() {
        val bounds = _uiState.value.graphBounds
        if (bounds.width <= 0 || bounds.height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return
        }

        // Small top padding, reserve space for bottom toolbar
        val topPadding = 8f
        val bottomToolbarPadding = 100f
        val availableHeight = canvasHeight - topPadding - bottomToolbarPadding

        // Calculate scale to fit height only
        val scale = minOf(availableHeight / bounds.height, 1.5f) // max 1.5x

        // Calculate offset - anchor to left horizontally, anchor to top vertically
        val leftPadding = 16f
        val offsetX = leftPadding - bounds.minX * scale
        val offsetY = topPadding - bounds.minY * scale

        _uiState.value = _uiState.value.copy(
            scale = scale,
            offset = Offset(offsetX, offsetY)
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

        _uiState.value = state.copy(
            isEditingNode = true,
            selectedNodeForEditing = node,
            editableInputNames = editableInputNames,
            savedScaleBeforeEditing = savedScale,
            savedOffsetBeforeEditing = savedOffset,
            scale = newScale,
            offset = Offset(newOffsetX, newOffsetY)
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
        val currentEdits = state.nodeAttributeEdits.toMutableMap()

        val nodeEdits = currentEdits[nodeId]?.toMutableMap() ?: return
        nodeEdits.remove(inputName)

        if (nodeEdits.isEmpty()) {
            currentEdits.remove(nodeId)
        } else {
            currentEdits[nodeId] = nodeEdits
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

        _uiState.value = state.copy(
            isEditMode = true,
            selectedNodeIds = emptySet(),
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
     * Clear node selection.
     */
    fun clearSelection() {
        val state = _uiState.value
        _uiState.value = state.copy(selectedNodeIds = emptySet())
    }

    /**
     * Delete selected nodes and their connected edges.
     */
    fun deleteSelectedNodes() {
        val state = _uiState.value
        val graph = mutableGraph ?: return
        if (!state.isEditMode || state.selectedNodeIds.isEmpty()) return

        val selectedIds = state.selectedNodeIds

        // Remove nodes
        graph.nodes.removeAll { it.id in selectedIds }

        // Remove edges connected to deleted nodes
        graph.edges.removeAll { edge ->
            edge.sourceNodeId in selectedIds || edge.targetNodeId in selectedIds
        }

        // Re-layout graph after deletion to fill gaps
        relayoutGraph()

        _uiState.value = _uiState.value.copy(
            selectedNodeIds = emptySet(),
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

        val selectedIds = state.selectedNodeIds
        val nodesToDuplicate = graph.nodes.filter { it.id in selectedIds }

        // Create ID mapping from old to new
        val idMapping = mutableMapOf<String, String>()

        // Duplicate nodes with offset position (temporary - will be re-laid out)
        val duplicatedNodes = nodesToDuplicate.map { node ->
            val newId = generateUniqueNodeId()
            idMapping[node.id] = newId
            node.copy(
                id = newId,
                x = node.x + 50f,
                y = node.y + 50f
            )
        }

        // Duplicate internal edges (edges between selected nodes)
        val internalEdges = graph.edges.filter { edge ->
            edge.sourceNodeId in selectedIds && edge.targetNodeId in selectedIds
        }
        val duplicatedEdges = internalEdges.map { edge ->
            edge.copy(
                sourceNodeId = idMapping[edge.sourceNodeId] ?: edge.sourceNodeId,
                targetNodeId = idMapping[edge.targetNodeId] ?: edge.targetNodeId
            )
        }

        // Add duplicated nodes and edges
        graph.nodes.addAll(duplicatedNodes)
        graph.edges.addAll(duplicatedEdges)

        // Store new node IDs before relayout (IDs are preserved)
        val newNodeIds = duplicatedNodes.map { it.id }.toSet()

        // Re-layout to prevent overlaps
        relayoutGraph()

        // Update selection to the new nodes
        _uiState.value = _uiState.value.copy(
            selectedNodeIds = newNodeIds,
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
     * to recalculate optimal positions for all nodes.
     */
    fun relayoutGraph() {
        val graph = mutableGraph ?: return

        // Re-layout the entire graph
        val layoutedGraph = layoutEngine.layoutGraph(graph.toImmutable())

        // Update mutable graph from layouted result
        mutableGraph = MutableWorkflowGraph.fromImmutable(layoutedGraph)

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
                val defaultValue = inputDef.default ?: when (inputDef.type) {
                    "INT" -> 0
                    "FLOAT" -> 0.0
                    "STRING" -> ""
                    "BOOLEAN" -> false
                    "ENUM" -> inputDef.options?.firstOrNull() ?: ""
                    else -> ""
                }
                inputs[inputDef.name] = InputValue.Literal(defaultValue)
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
            inputs = inputs,
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
                sourceOutputSlot = resolvedOutputSlot,
                validInputSlots = validInputs
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
     * Connect to an input slot when tapped in connection mode.
     */
    fun connectToInput(inputSlot: SlotPosition) {
        val graph = mutableGraph ?: return
        val state = _uiState.value
        val connectionState = state.connectionModeState ?: return

        val sourceSlot = connectionState.sourceOutputSlot

        // Remove any existing edge to this input (inputs can only have one connection)
        graph.edges.removeAll { edge ->
            edge.targetNodeId == inputSlot.nodeId && edge.targetInputName == inputSlot.slotName
        }

        // Create new edge
        val newEdge = WorkflowEdge(
            sourceNodeId = sourceSlot.nodeId,
            targetNodeId = inputSlot.nodeId,
            sourceOutputIndex = sourceSlot.outputIndex,
            targetInputName = inputSlot.slotName,
            slotType = sourceSlot.slotType
        )
        graph.edges.add(newEdge)

        // Update the target node's input to be a Connection type
        val targetNode = graph.nodes.find { it.id == inputSlot.nodeId }
        if (targetNode != null) {
            val updatedInputs = targetNode.inputs.toMutableMap()
            updatedInputs[inputSlot.slotName] = InputValue.Connection(
                sourceNodeId = sourceSlot.nodeId,
                outputIndex = sourceSlot.outputIndex
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
     * Returns slots on OTHER nodes that are type-compatible.
     * Only connection-type inputs (Connection or UnconnectedSlot) are valid targets.
     */
    private fun calculateValidInputSlots(
        sourceNodeId: String,
        outputType: String?,
        graph: MutableWorkflowGraph
    ): List<SlotPosition> {
        val validSlots = mutableListOf<SlotPosition>()

        // Check all other nodes
        for (node in graph.nodes) {
            // Skip the source node - can't connect to self
            if (node.id == sourceNodeId) continue

            // Check each input on this node
            var inputIndex = 0
            for ((inputName, inputValue) in node.inputs) {
                // Only connection-type inputs can be connected
                val isConnectionInput = inputValue is InputValue.Connection || inputValue is InputValue.UnconnectedSlot

                if (isConnectionInput) {
                    // Get input type - prefer from UnconnectedSlot, fallback to registry
                    val inputType = when (inputValue) {
                        is InputValue.UnconnectedSlot -> inputValue.slotType
                        else -> nodeTypeRegistry.getInputType(node.classType, inputName)
                    }

                    // Type compatibility check:
                    // - "*" matches anything
                    // - Exact match
                    // - null types are considered compatible (for flexibility)
                    val isCompatible = outputType == null ||
                        inputType == null ||
                        inputType == "*" ||
                        outputType == "*" ||
                        inputType.equals(outputType, ignoreCase = true)

                    if (isCompatible) {
                        // Calculate input slot position (left side of node)
                        val slotY = node.y + WorkflowLayoutEngine.NODE_HEADER_HEIGHT + 20f +
                            (inputIndex * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)

                        validSlots.add(
                            SlotPosition(
                                nodeId = node.id,
                                slotName = inputName,
                                isOutput = false,
                                outputIndex = 0,
                                center = Offset(node.x, slotY),
                                slotType = inputType
                            )
                        )
                    }
                }
                // Always increment inputIndex to maintain correct Y position
                inputIndex++
            }
        }

        return validSlots
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
