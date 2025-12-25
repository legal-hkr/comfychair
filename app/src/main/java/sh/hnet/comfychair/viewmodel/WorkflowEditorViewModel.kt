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
import kotlinx.coroutines.launch
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.workflow.GraphBounds
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowParser
import sh.hnet.comfychair.workflow.WorkflowPreviewerUiState
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.connection.ConnectionManager

/**
 * Events emitted by the Workflow Previewer
 */
sealed class WorkflowPreviewerEvent {
    data class MappingConfirmed(val mappingsJson: String) : WorkflowPreviewerEvent()
    object MappingCancelled : WorkflowPreviewerEvent()
}

/**
 * ViewModel for the Workflow Previewer screen
 */
class WorkflowPreviewerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WorkflowPreviewerUiState())
    val uiState: StateFlow<WorkflowPreviewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WorkflowPreviewerEvent>()
    val events: SharedFlow<WorkflowPreviewerEvent> = _events.asSharedFlow()

    private val parser = WorkflowParser()
    private val layoutEngine = WorkflowLayoutEngine()
    private val nodeTypeRegistry = NodeTypeRegistry()

    private var canvasWidth: Float = 0f
    private var canvasHeight: Float = 0f

    /**
     * Initialize the previewer with a workflow ID (view mode)
     */
    fun initialize(context: Context, workflowId: String?, workflowJson: String?) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // Fetch object_info first for edge type resolution, then parse workflow
        fetchObjectInfo { _ ->
            // Continue regardless of success - edges will use default color if fetch failed
            try {
                val jsonContent: String
                val name: String
                val description: String

                if (workflowJson != null) {
                    // Direct JSON content provided
                    jsonContent = workflowJson
                    name = "Workflow Preview"
                    description = ""
                } else if (workflowId != null) {
                    // Load from WorkflowManager
                    val workflowManager = WorkflowManager(context)
                    val workflow = workflowManager.getWorkflowById(workflowId)

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
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.error_no_workflow_specified)
                    )
                    return@fetchObjectInfo
                }

                // Parse the workflow
                var graph = parser.parse(jsonContent, name, description)

                // Layout the graph
                graph = layoutEngine.layoutGraph(graph)

                // Resolve edge types for colored connections
                graph = resolveEdgeTypes(graph)

                // Calculate bounds
                val bounds = layoutEngine.calculateBounds(graph)

                _uiState.value = _uiState.value.copy(
                    graph = graph,
                    workflowName = name,
                    isLoading = false,
                    errorMessage = null,
                    graphBounds = bounds,
                    isFieldMappingMode = false
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
     * Initialize the previewer for field mapping mode (new workflow upload)
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
                    highlightedNodeIds = highlightedNodes,
                    canConfirmMapping = mappingState.allFieldsMapped,
                    showTemplateHighlight = false  // Disable default highlight in mapping mode
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
            canConfirmMapping = newMappingState.allFieldsMapped
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
     */
    fun getFinalMappingsJson(): String? {
        val mappingState = _uiState.value.mappingState ?: return null
        if (!mappingState.allFieldsMapped) return null

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
     * Confirm the mapping and emit event
     */
    fun confirmMapping() {
        val mappingsJson = getFinalMappingsJson()
        if (mappingsJson != null) {
            viewModelScope.launch {
                _events.emit(WorkflowPreviewerEvent.MappingConfirmed(mappingsJson))
            }
        }
    }

    /**
     * Cancel mapping and emit event
     */
    fun cancelMapping() {
        viewModelScope.launch {
            _events.emit(WorkflowPreviewerEvent.MappingCancelled)
        }
    }

    /**
     * Fetch object_info from the ComfyUI server to populate node type registry.
     * Calls the callback with true if successful, false otherwise.
     */
    private fun fetchObjectInfo(callback: (success: Boolean) -> Unit) {
        val client = ConnectionManager.clientOrNull
        if (client == null) {
            callback(false)
            return
        }

        client.fetchFullObjectInfo { objectInfo ->
            if (objectInfo != null) {
                nodeTypeRegistry.parseObjectInfo(objectInfo)
                callback(true)
            } else {
                callback(false)
            }
        }
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
     * Fit the graph to the screen
     */
    fun fitToScreen() {
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
    }

    /**
     * Reset view to fit screen
     */
    fun resetView() {
        fitToScreen()
    }

    /**
     * Toggle template variable highlighting
     */
    fun toggleTemplateHighlight() {
        _uiState.value = _uiState.value.copy(
            showTemplateHighlight = !_uiState.value.showTemplateHighlight
        )
    }
}
