package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowValidationResult
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.PendingWorkflowUpload
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowMappingState

/**
 * UI state for the Workflow Management screen
 */
data class WorkflowManagementUiState(
    // Workflows organized by type
    val ttiCheckpointWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val ttiUnetWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val itiCheckpointWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val itiUnetWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val iteUnetWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val ttvUnetWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val itvUnetWorkflows: List<WorkflowManager.Workflow> = emptyList(),

    // Upload dialog state
    val showUploadDialog: Boolean = false,
    val pendingUploadJsonContent: String = "",
    val uploadSelectedType: WorkflowType? = null,
    val uploadTypeDropdownExpanded: Boolean = false,
    val uploadName: String = "",
    val uploadDescription: String = "",
    val uploadNameError: String? = null,
    val uploadDescriptionError: String? = null,

    // Validation state
    val isValidatingNodes: Boolean = false,
    val showMissingNodesDialog: Boolean = false,
    val missingNodes: List<String> = emptyList(),
    val showMissingFieldsDialog: Boolean = false,
    val missingFields: List<String> = emptyList(),
    val showDuplicateNameDialog: Boolean = false,

    // Pending workflow for editor
    val pendingWorkflowForMapping: PendingWorkflowUpload? = null,

    // Edit dialog state
    val showEditDialog: Boolean = false,
    val editingWorkflow: WorkflowManager.Workflow? = null,
    val editName: String = "",
    val editDescription: String = "",

    // Delete confirmation dialog
    val showDeleteDialog: Boolean = false,
    val workflowToDelete: WorkflowManager.Workflow? = null,

    // Loading state
    val isLoading: Boolean = false
)

/**
 * Events emitted by the Workflow Management screen
 */
sealed class WorkflowManagementEvent {
    data class ShowToast(val messageResId: Int) : WorkflowManagementEvent()
    data class ShowToastMessage(val message: String) : WorkflowManagementEvent()
    data class LaunchEditor(val pendingUpload: PendingWorkflowUpload) : WorkflowManagementEvent()
    object WorkflowsChanged : WorkflowManagementEvent()
}

/**
 * ViewModel for the Workflow Management screen
 */
class WorkflowManagementViewModel : ViewModel() {

    // State
    private val _uiState = MutableStateFlow(WorkflowManagementUiState())
    val uiState: StateFlow<WorkflowManagementUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WorkflowManagementEvent>()
    val events: SharedFlow<WorkflowManagementEvent> = _events.asSharedFlow()

    private var workflowManager: WorkflowManager? = null
    private var applicationContext: Context? = null

    // Initialization
    /**
     * Initialize the ViewModel with context
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        workflowManager = WorkflowManager(context)
        loadWorkflows()
    }

    /**
     * Load all workflows and organize by type
     */
    fun loadWorkflows() {
        val wm = workflowManager ?: return

        _uiState.value = _uiState.value.copy(
            ttiCheckpointWorkflows = wm.getWorkflowsByType(WorkflowType.TTI_CHECKPOINT),
            ttiUnetWorkflows = wm.getWorkflowsByType(WorkflowType.TTI_UNET),
            itiCheckpointWorkflows = wm.getWorkflowsByType(WorkflowType.ITI_CHECKPOINT),
            itiUnetWorkflows = wm.getWorkflowsByType(WorkflowType.ITI_UNET),
            iteUnetWorkflows = wm.getWorkflowsByType(WorkflowType.ITE_UNET),
            ttvUnetWorkflows = wm.getWorkflowsByType(WorkflowType.TTV_UNET),
            itvUnetWorkflows = wm.getWorkflowsByType(WorkflowType.ITV_UNET)
        )
    }

    /**
     * Reload workflows from disk and refresh UI.
     * Use this when workflows were modified by another component (e.g., WorkflowEditorViewModel).
     * Also emits WorkflowsChanged to notify parent activities (triggers generation screen refresh).
     */
    fun reloadAndRefreshWorkflows() {
        workflowManager?.reloadWorkflows()
        loadWorkflows()
        viewModelScope.launch {
            _events.emit(WorkflowManagementEvent.WorkflowsChanged)
        }
    }

    // New upload flow

    /**
     * Handle file selection from file picker - new flow without filename prefix requirement
     */
    fun onFileSelected(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Read file content
                val jsonContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                }

                // Validate JSON format
                try {
                    JSONObject(jsonContent)
                } catch (e: Exception) {
                    _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_error_invalid_json))
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@launch
                }

                // Auto-detect type as suggestion (user can override)
                val wm = workflowManager
                val detectedType = wm?.detectWorkflowType(jsonContent)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showUploadDialog = true,
                    pendingUploadJsonContent = jsonContent,
                    uploadSelectedType = detectedType,
                    uploadTypeDropdownExpanded = false,
                    uploadName = "",
                    uploadDescription = "",
                    uploadNameError = null,
                    uploadDescriptionError = null
                )
            } catch (e: Exception) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_upload_failed))
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun onUploadTypeSelected(type: WorkflowType) {
        _uiState.value = _uiState.value.copy(
            uploadSelectedType = type,
            uploadTypeDropdownExpanded = false
        )
    }

    fun onToggleTypeDropdown() {
        _uiState.value = _uiState.value.copy(
            uploadTypeDropdownExpanded = !_uiState.value.uploadTypeDropdownExpanded
        )
    }

    fun onUploadNameChange(name: String) {
        val error = if (name.length > 40) applicationContext?.getString(R.string.workflow_name_error_too_long) else null
        _uiState.value = _uiState.value.copy(
            uploadName = name.take(40),
            uploadNameError = error
        )
    }

    fun onUploadDescriptionChange(description: String) {
        val error = if (description.length > 120) applicationContext?.getString(R.string.workflow_description_error_too_long) else null
        _uiState.value = _uiState.value.copy(
            uploadDescription = description.take(120),
            uploadDescriptionError = error
        )
    }

    /**
     * Proceed with upload - validate and launch editor
     */
    fun proceedWithUpload(comfyUIClient: ComfyUIClient) {
        val state = _uiState.value
        val wm = workflowManager ?: return

        val selectedType = state.uploadSelectedType ?: return
        val name = state.uploadName.trim()
        val description = state.uploadDescription.trim()

        // Validate name format
        val nameError = wm.validateWorkflowName(name)
        if (nameError != null) {
            _uiState.value = _uiState.value.copy(uploadNameError = nameError)
            return
        }

        // Validate description format
        val descError = wm.validateWorkflowDescription(description)
        if (descError != null) {
            _uiState.value = _uiState.value.copy(uploadDescriptionError = descError)
            return
        }

        // Check for duplicate name
        if (wm.isWorkflowNameTaken(name)) {
            _uiState.value = _uiState.value.copy(showDuplicateNameDialog = true)
            return
        }

        // Start node validation
        _uiState.value = _uiState.value.copy(isValidatingNodes = true)

        // Fetch available nodes from server
        comfyUIClient.fetchAllNodeTypes { availableNodes ->
            viewModelScope.launch {
                if (availableNodes == null) {
                    _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_error_fetch_nodes_failed))
                    _uiState.value = _uiState.value.copy(isValidatingNodes = false)
                    return@launch
                }

                // Extract class types from workflow
                val classTypesResult = wm.extractClassTypes(state.pendingUploadJsonContent)
                if (classTypesResult.isFailure) {
                    _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_error_invalid_json))
                    _uiState.value = _uiState.value.copy(isValidatingNodes = false)
                    return@launch
                }
                val workflowClassTypes = classTypesResult.getOrThrow()

                // Validate nodes
                val missingNodes = wm.validateNodesAgainstServer(workflowClassTypes, availableNodes)
                if (missingNodes.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isValidatingNodes = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                // Validate required fields for type
                val keyValidation = wm.validateWorkflowKeys(state.pendingUploadJsonContent, selectedType)
                when (keyValidation) {
                    is WorkflowValidationResult.MissingKeys -> {
                        _uiState.value = _uiState.value.copy(
                            isValidatingNodes = false,
                            showMissingFieldsDialog = true,
                            missingFields = keyValidation.missing
                        )
                        return@launch
                    }
                    is WorkflowValidationResult.Success -> { /* Continue */ }
                    else -> { /* Continue - may have placeholders instead of keys */ }
                }

                // Create field mapping state
                val mappingState = createFieldMappingState(
                    state.pendingUploadJsonContent,
                    selectedType
                )

                // Check if all fields are mapped
                if (!mappingState.allFieldsMapped) {
                    val unmappedFieldNames = mappingState.unmappedFields.map { it.displayName }
                    _uiState.value = _uiState.value.copy(
                        isValidatingNodes = false,
                        showMissingFieldsDialog = true,
                        missingFields = unmappedFieldNames
                    )
                    return@launch
                }

                // Prepare pending upload and launch editor
                val pendingUpload = PendingWorkflowUpload(
                    jsonContent = state.pendingUploadJsonContent,
                    name = name,
                    description = description,
                    type = selectedType,
                    mappingState = mappingState
                )

                _uiState.value = _uiState.value.copy(
                    isValidatingNodes = false,
                    showUploadDialog = false,
                    pendingWorkflowForMapping = pendingUpload
                )

                _events.emit(WorkflowManagementEvent.LaunchEditor(pendingUpload))
            }
        }
    }

    /**
     * Create field mapping state by analyzing workflow JSON
     */
    private fun createFieldMappingState(jsonContent: String, type: WorkflowType): WorkflowMappingState {
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return WorkflowMappingState(type, emptyList())
        }

        // Use dynamic key detection based on workflow structure
        val requiredKeys = TemplateKeyRegistry.getRequiredKeysForWorkflow(type, json)

        val nodesJson = if (json.has("nodes")) json.optJSONObject("nodes") ?: json else json

        // For positive_text and negative_text, we need graph tracing to determine which CLIPTextEncode
        // connects to which sampler input (positive vs negative)
        val promptMappings = if (requiredKeys.contains("positive_text") || requiredKeys.contains("negative_text")) {
            createPromptFieldMappings(nodesJson)
        } else {
            emptyMap()
        }

        val fieldMappings = requiredKeys.map { fieldKey ->
            // Handle positive_text and negative_text specially with graph tracing results
            if (fieldKey == "positive_text" || fieldKey == "negative_text") {
                val candidates = promptMappings[fieldKey] ?: emptyList()
                FieldMappingState(
                    field = FieldDisplayRegistry.createRequiredField(fieldKey),
                    candidates = candidates,
                    selectedCandidateIndex = 0  // Auto-select first (which is the traced one)
                )
            } else {
                val candidates = mutableListOf<FieldCandidate>()

                // Find all nodes that have this input key
                for (nodeId in nodesJson.keys()) {
                    val node = nodesJson.optJSONObject(nodeId) ?: continue
                    val inputs = node.optJSONObject("inputs") ?: continue

                    if (inputs.has(fieldKey)) {
                        val classType = node.optString("class_type", "Unknown")
                        val meta = node.optJSONObject("_meta")
                        val title = meta?.optString("title") ?: classType
                        val currentValue = inputs.opt(fieldKey)

                        candidates.add(
                            FieldCandidate(
                                nodeId = nodeId,
                                nodeName = title,
                                classType = classType,
                                inputKey = fieldKey,
                                currentValue = currentValue
                            )
                        )
                    }
                }

                FieldMappingState(
                    field = FieldDisplayRegistry.createRequiredField(fieldKey),
                    candidates = candidates,
                    selectedCandidateIndex = 0  // Auto-select first
                )
            }
        }

        return WorkflowMappingState(type, fieldMappings)
    }

    /**
     * Create field mappings for positive_text and negative_text using graph tracing.
     * Traces CLIPTextEncode outputs to find which connects to "positive" vs "negative" sampler inputs.
     */
    private fun createPromptFieldMappings(nodesJson: JSONObject): Map<String, List<FieldCandidate>> {
        val positiveTextCandidates = mutableListOf<FieldCandidate>()
        val negativeTextCandidates = mutableListOf<FieldCandidate>()

        // Find all CLIPTextEncode nodes with "text" input
        val clipTextEncodeNodes = mutableMapOf<String, JSONObject>()
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            if (classType == "CLIPTextEncode") {
                val inputs = node.optJSONObject("inputs")
                if (inputs != null && inputs.has("text")) {
                    clipTextEncodeNodes[nodeId] = node
                }
            }
        }

        // For each CLIPTextEncode, trace its output to find if it connects to positive or negative
        for ((clipNodeId, clipNode) in clipTextEncodeNodes) {
            val inputs = clipNode.optJSONObject("inputs") ?: continue
            val meta = clipNode.optJSONObject("_meta")
            val title = meta?.optString("title") ?: "CLIPTextEncode"
            val currentValue = inputs.opt("text")

            // Determine if this node connects to positive or negative input
            val connectionType = traceClipTextEncodeConnection(nodesJson, clipNodeId)

            val candidate = FieldCandidate(
                nodeId = clipNodeId,
                nodeName = title,
                classType = "CLIPTextEncode",
                inputKey = "text",
                currentValue = currentValue
            )

            when (connectionType) {
                "positive" -> positiveTextCandidates.add(0, candidate) // Add traced match first
                "negative" -> negativeTextCandidates.add(0, candidate) // Add traced match first
                else -> {
                    // Unknown connection - add to both as fallback candidates (not first)
                    positiveTextCandidates.add(candidate)
                    negativeTextCandidates.add(candidate)
                }
            }
        }

        // If graph tracing didn't find matches, try title-based fallback
        if (positiveTextCandidates.isEmpty() || negativeTextCandidates.isEmpty()) {
            for ((clipNodeId, clipNode) in clipTextEncodeNodes) {
                val inputs = clipNode.optJSONObject("inputs") ?: continue
                val meta = clipNode.optJSONObject("_meta")
                val title = meta?.optString("title")?.lowercase() ?: ""
                val currentValue = inputs.opt("text")

                val candidate = FieldCandidate(
                    nodeId = clipNodeId,
                    nodeName = meta?.optString("title") ?: "CLIPTextEncode",
                    classType = "CLIPTextEncode",
                    inputKey = "text",
                    currentValue = currentValue
                )

                // Title-based fallback
                if (positiveTextCandidates.isEmpty() && title.contains("positive")) {
                    positiveTextCandidates.add(0, candidate)
                }
                if (negativeTextCandidates.isEmpty() && title.contains("negative")) {
                    negativeTextCandidates.add(0, candidate)
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
     * Returns "positive", "negative", or null if no connection found.
     */
    private fun traceClipTextEncodeConnection(nodesJson: JSONObject, clipNodeId: String): String? {
        // Search all nodes for ones that reference this CLIPTextEncode output
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            val inputs = node.optJSONObject("inputs") ?: continue

            // Check KSampler and KSamplerAdvanced nodes
            if (classType == "KSampler" || classType == "KSamplerAdvanced") {
                // Check "positive" input
                val positiveInput = inputs.optJSONArray("positive")
                if (positiveInput != null && positiveInput.length() >= 1) {
                    val refNodeId = positiveInput.optString(0, "")
                    if (refNodeId == clipNodeId) {
                        return "positive"
                    }
                }

                // Check "negative" input
                val negativeInput = inputs.optJSONArray("negative")
                if (negativeInput != null && negativeInput.length() >= 1) {
                    val refNodeId = negativeInput.optString(0, "")
                    if (refNodeId == clipNodeId) {
                        return "negative"
                    }
                }
            }

            // For video workflows, check conditioning nodes like WanImageToVideo
            if (classType.contains("ImageToVideo", ignoreCase = true) ||
                classType.contains("TextToVideo", ignoreCase = true)) {
                val positiveInput = inputs.optJSONArray("positive")
                if (positiveInput != null && positiveInput.length() >= 1) {
                    val refNodeId = positiveInput.optString(0, "")
                    if (refNodeId == clipNodeId) {
                        return "positive"
                    }
                }

                val negativeInput = inputs.optJSONArray("negative")
                if (negativeInput != null && negativeInput.length() >= 1) {
                    val refNodeId = negativeInput.optString(0, "")
                    if (refNodeId == clipNodeId) {
                        return "negative"
                    }
                }
            }

            // Check BasicGuider nodes (Flux-style single conditioning)
            if (classType == "BasicGuider") {
                val conditioningInput = inputs.optJSONArray("conditioning")
                if (conditioningInput != null && conditioningInput.length() >= 1) {
                    val refNodeId = conditioningInput.optString(0, "")
                    if (refNodeId == clipNodeId) {
                        return "positive"  // BasicGuider only has positive conditioning
                    }
                }
            }
        }

        // If direct connection not found, check for intermediate conditioning nodes
        // that might reference this CLIPTextEncode and connect to samplers
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            // Check if any input references this CLIPTextEncode
            for (inputKey in inputs.keys()) {
                val inputValue = inputs.optJSONArray(inputKey)
                if (inputValue != null && inputValue.length() >= 1) {
                    val refNodeId = inputValue.optString(0, "")
                    if (refNodeId == clipNodeId) {
                        // This node references our CLIPTextEncode, now trace this node's output
                        val intermediateResult = traceIntermediateConnection(nodesJson, nodeId)
                        if (intermediateResult != null) {
                            return intermediateResult
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Trace an intermediate conditioning node to find if it connects to positive or negative.
     */
    private fun traceIntermediateConnection(nodesJson: JSONObject, intermediateNodeId: String): String? {
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            val inputs = node.optJSONObject("inputs") ?: continue

            if (classType == "KSampler" || classType == "KSamplerAdvanced" ||
                classType.contains("ImageToVideo", ignoreCase = true)) {

                val positiveInput = inputs.optJSONArray("positive")
                if (positiveInput != null && positiveInput.length() >= 1) {
                    val refNodeId = positiveInput.optString(0, "")
                    if (refNodeId == intermediateNodeId) {
                        return "positive"
                    }
                }

                val negativeInput = inputs.optJSONArray("negative")
                if (negativeInput != null && negativeInput.length() >= 1) {
                    val refNodeId = negativeInput.optString(0, "")
                    if (refNodeId == intermediateNodeId) {
                        return "negative"
                    }
                }
            }

            // Check BasicGuider nodes (Flux-style single conditioning)
            if (classType == "BasicGuider") {
                val conditioningInput = inputs.optJSONArray("conditioning")
                if (conditioningInput != null && conditioningInput.length() >= 1) {
                    val refNodeId = conditioningInput.optString(0, "")
                    if (refNodeId == intermediateNodeId) {
                        return "positive"  // BasicGuider only has positive conditioning
                    }
                }
            }
        }
        return null
    }

    fun cancelUpload() {
        _uiState.value = _uiState.value.copy(
            showUploadDialog = false,
            pendingUploadJsonContent = "",
            uploadSelectedType = null,
            uploadName = "",
            uploadDescription = "",
            uploadNameError = null,
            uploadDescriptionError = null,
            pendingWorkflowForMapping = null
        )
    }

    fun dismissMissingNodesDialog() {
        _uiState.value = _uiState.value.copy(
            showMissingNodesDialog = false,
            missingNodes = emptyList(),
            showUploadDialog = false
        )
        cancelUpload()
    }

    fun dismissMissingFieldsDialog() {
        _uiState.value = _uiState.value.copy(
            showMissingFieldsDialog = false,
            missingFields = emptyList(),
            showUploadDialog = false
        )
        cancelUpload()
    }

    fun dismissDuplicateNameDialog() {
        _uiState.value = _uiState.value.copy(showDuplicateNameDialog = false)
    }

    /**
     * Complete the upload after mapping is confirmed in editor
     */
    fun completeUpload(fieldMappings: Map<String, Pair<String, String>>) {
        val pending = _uiState.value.pendingWorkflowForMapping ?: return
        val wm = workflowManager ?: return

        viewModelScope.launch {
            val result = wm.addUserWorkflowWithMapping(
                name = pending.name,
                description = pending.description,
                jsonContent = pending.jsonContent,
                type = pending.type,
                fieldMappings = fieldMappings
            )

            if (result.isSuccess) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_upload_success))
                _events.emit(WorkflowManagementEvent.WorkflowsChanged)
                loadWorkflows()
            } else {
                _events.emit(WorkflowManagementEvent.ShowToastMessage(
                    result.exceptionOrNull()?.message ?: "Upload failed"
                ))
            }

            _uiState.value = _uiState.value.copy(pendingWorkflowForMapping = null)
        }
    }

    /**
     * Cancel mapping and discard workflow
     */
    fun cancelMapping() {
        _uiState.value = _uiState.value.copy(pendingWorkflowForMapping = null)
    }

    // Edit flow

    fun onEditWorkflow(workflow: WorkflowManager.Workflow) {
        if (workflow.isBuiltIn) return

        _uiState.value = _uiState.value.copy(
            showEditDialog = true,
            editingWorkflow = workflow,
            editName = workflow.name,
            editDescription = workflow.description
        )
    }

    fun onEditNameChange(name: String) {
        _uiState.value = _uiState.value.copy(editName = name)
    }

    fun onEditDescriptionChange(description: String) {
        _uiState.value = _uiState.value.copy(editDescription = description)
    }

    fun confirmEdit() {
        val state = _uiState.value
        val wm = workflowManager ?: return
        val workflow = state.editingWorkflow ?: return

        if (state.editName.isBlank()) return

        viewModelScope.launch {
            val success = wm.updateUserWorkflowMetadata(
                workflowId = workflow.id,
                name = state.editName,
                description = state.editDescription
            )

            if (success) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_update_success))
                _events.emit(WorkflowManagementEvent.WorkflowsChanged)
                loadWorkflows()
            } else {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_update_failed))
            }

            _uiState.value = _uiState.value.copy(
                showEditDialog = false,
                editingWorkflow = null,
                editName = "",
                editDescription = ""
            )
        }
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(
            showEditDialog = false,
            editingWorkflow = null,
            editName = "",
            editDescription = ""
        )
    }

    // Delete flow

    fun onDeleteWorkflow(workflow: WorkflowManager.Workflow) {
        if (workflow.isBuiltIn) return

        _uiState.value = _uiState.value.copy(
            showDeleteDialog = true,
            workflowToDelete = workflow
        )
    }

    fun confirmDelete() {
        val state = _uiState.value
        val wm = workflowManager ?: return
        val workflow = state.workflowToDelete ?: return

        viewModelScope.launch {
            val success = wm.deleteUserWorkflow(workflow.id)

            if (success) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_delete_success))
                _events.emit(WorkflowManagementEvent.WorkflowsChanged)
                loadWorkflows()
            } else {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_delete_failed))
            }

            _uiState.value = _uiState.value.copy(
                showDeleteDialog = false,
                workflowToDelete = null
            )
        }
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(
            showDeleteDialog = false,
            workflowToDelete = null
        )
    }
}
