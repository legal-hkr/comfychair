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
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.ValidationUtils

/**
 * Export format options
 */
enum class ExportFormat {
    INTERNAL,  // Full internal format with placeholders, groups, notes
    API        // Raw ComfyUI API format with placeholders replaced
}

/**
 * UI state for the Workflow Management screen
 */
data class WorkflowManagementUiState(
    // Workflows organized by type
    val ttiWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val itiInpaintingWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val itiEditingWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val ttvWorkflows: List<WorkflowManager.Workflow> = emptyList(),
    val itvWorkflows: List<WorkflowManager.Workflow> = emptyList(),

    // Import dialog state
    val showImportDialog: Boolean = false,
    val pendingImportJsonContent: String = "",
    val importSelectedType: WorkflowType? = null,
    val importTypeDropdownExpanded: Boolean = false,
    val importName: String = "",
    val importDescription: String = "",
    val importNameError: String? = null,
    val importDescriptionError: String? = null,

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
    val editNameError: String? = null,
    val editDescriptionError: String? = null,

    // Delete confirmation dialog
    val showDeleteDialog: Boolean = false,
    val workflowToDelete: WorkflowManager.Workflow? = null,

    // Duplicate dialog state
    val showDuplicateDialog: Boolean = false,
    val duplicatingWorkflow: WorkflowManager.Workflow? = null,
    val duplicateName: String = "",
    val duplicateDescription: String = "",
    val duplicateNameError: String? = null,
    val duplicateDescriptionError: String? = null,

    // Export state
    val exportingWorkflow: WorkflowManager.Workflow? = null,
    val exportFormat: ExportFormat = ExportFormat.INTERNAL,

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
    data class LaunchExportFilePicker(val suggestedFilename: String) : WorkflowManagementEvent()
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

    private var applicationContext: Context? = null

    // Initialization
    /**
     * Initialize the ViewModel with context
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        WorkflowManager.ensureInitialized(context)
        loadWorkflows()
    }

    /**
     * Load all workflows and organize by type
     */
    fun loadWorkflows() {
        val ctx = applicationContext ?: return
        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)

        _uiState.value = _uiState.value.copy(
            ttiWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.TTI)
                .filter { showBuiltIn || !it.isBuiltIn },
            itiInpaintingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_INPAINTING)
                .filter { showBuiltIn || !it.isBuiltIn },
            itiEditingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_EDITING)
                .filter { showBuiltIn || !it.isBuiltIn },
            ttvWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.TTV)
                .filter { showBuiltIn || !it.isBuiltIn },
            itvWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV)
                .filter { showBuiltIn || !it.isBuiltIn }
        )
    }

    /**
     * Reload workflows from disk and refresh UI.
     * Use this when workflows were modified by another component (e.g., WorkflowEditorViewModel).
     * Also emits WorkflowsChanged to notify parent activities (triggers generation screen refresh).
     */
    fun reloadAndRefreshWorkflows() {
        WorkflowManager.reloadWorkflows()
        loadWorkflows()
        viewModelScope.launch {
            _events.emit(WorkflowManagementEvent.WorkflowsChanged)
        }
    }

    // New import flow

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
                val detectedType = WorkflowManager.detectWorkflowType(jsonContent)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showImportDialog = true,
                    pendingImportJsonContent = jsonContent,
                    importSelectedType = detectedType,
                    importTypeDropdownExpanded = false,
                    importName = "",
                    importDescription = "",
                    importNameError = null,
                    importDescriptionError = null
                )
            } catch (e: Exception) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_import_failed))
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun onImportTypeSelected(type: WorkflowType) {
        _uiState.value = _uiState.value.copy(
            importSelectedType = type,
            importTypeDropdownExpanded = false
        )
    }

    fun onToggleTypeDropdown() {
        _uiState.value = _uiState.value.copy(
            importTypeDropdownExpanded = !_uiState.value.importTypeDropdownExpanded
        )
    }

    fun onImportNameChange(name: String) {
        val error = if (name.length > ValidationUtils.MAX_WORKFLOW_NAME_LENGTH) {
            applicationContext?.getString(R.string.workflow_name_error_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            importName = ValidationUtils.truncateWorkflowName(name),
            importNameError = error
        )
    }

    fun onImportDescriptionChange(description: String) {
        val error = if (description.length > ValidationUtils.MAX_WORKFLOW_DESCRIPTION_LENGTH) {
            applicationContext?.getString(R.string.workflow_description_error_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            importDescription = ValidationUtils.truncateWorkflowDescription(description),
            importDescriptionError = error
        )
    }

    /**
     * Proceed with import - validate and launch editor
     */
    fun proceedWithImport(context: Context, comfyUIClient: ComfyUIClient) {
        val state = _uiState.value

        val selectedType = state.importSelectedType ?: return
        val name = state.importName.trim()
        val description = state.importDescription.trim()

        // Validate name format
        val nameError = WorkflowManager.validateWorkflowName(name)
        if (nameError != null) {
            _uiState.value = _uiState.value.copy(importNameError = nameError)
            return
        }

        // Validate description format
        val descError = WorkflowManager.validateWorkflowDescription(description)
        if (descError != null) {
            _uiState.value = _uiState.value.copy(importDescriptionError = descError)
            return
        }

        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
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
                val classTypesResult = WorkflowManager.extractClassTypes(state.pendingImportJsonContent)
                if (classTypesResult.isFailure) {
                    _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_error_invalid_json))
                    _uiState.value = _uiState.value.copy(isValidatingNodes = false)
                    return@launch
                }
                val workflowClassTypes = classTypesResult.getOrThrow()

                // Validate nodes
                val missingNodes = WorkflowManager.validateNodesAgainstServer(workflowClassTypes, availableNodes)
                if (missingNodes.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isValidatingNodes = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                // Validate required fields for type
                val keyValidation = WorkflowManager.validateWorkflowKeys(state.pendingImportJsonContent, selectedType)
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
                    context,
                    state.pendingImportJsonContent,
                    selectedType
                )

                // Check if all REQUIRED fields are mapped (optional fields can be unmapped)
                if (!mappingState.allRequiredFieldsMapped) {
                    val unmappedFieldNames = mappingState.unmappedRequiredFields.map { it.displayName }
                    _uiState.value = _uiState.value.copy(
                        isValidatingNodes = false,
                        showMissingFieldsDialog = true,
                        missingFields = unmappedFieldNames
                    )
                    return@launch
                }

                // Prepare pending import and launch editor
                val pendingImport = PendingWorkflowUpload(
                    jsonContent = state.pendingImportJsonContent,
                    name = name,
                    description = description,
                    type = selectedType,
                    mappingState = mappingState
                )

                _uiState.value = _uiState.value.copy(
                    isValidatingNodes = false,
                    showImportDialog = false,
                    pendingWorkflowForMapping = pendingImport
                )

                _events.emit(WorkflowManagementEvent.LaunchEditor(pendingImport))
            }
        }
    }

    /**
     * Create field mapping state by analyzing workflow JSON
     */
    private fun createFieldMappingState(context: Context, jsonContent: String, type: WorkflowType): WorkflowMappingState {
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return WorkflowMappingState(type, emptyList())
        }

        // Get strictly required keys and optional keys based on workflow structure
        val strictlyRequiredKeys = TemplateKeyRegistry.getStrictlyRequiredKeysForWorkflow(type, json)
        val optionalKeys = TemplateKeyRegistry.getOptionalKeysForWorkflow(type, json)
        val allKeys = strictlyRequiredKeys + optionalKeys

        val nodesJson = if (json.has("nodes")) json.optJSONObject("nodes") ?: json else json

        // For positive_text and negative_text, we need graph tracing to determine which CLIPTextEncode
        // connects to which sampler input (positive vs negative)
        val promptMappings = if (allKeys.contains("positive_text") || allKeys.contains("negative_text")) {
            createPromptFieldMappings(nodesJson)
        } else {
            emptyMap()
        }

        val fieldMappings = allKeys.map { fieldKey ->
            val isRequired = fieldKey in strictlyRequiredKeys

            // Handle positive_text and negative_text specially with graph tracing results
            if (fieldKey == "positive_text" || fieldKey == "negative_text") {
                val candidates = promptMappings[fieldKey] ?: emptyList()
                FieldMappingState(
                    field = FieldDisplayRegistry.createRequiredField(context, fieldKey, isRequired),
                    candidates = candidates,
                    selectedCandidateIndex = if (candidates.isNotEmpty()) 0 else -1
                )
            } else {
                val candidates = mutableListOf<FieldCandidate>()

                // Get the actual JSON input key for this placeholder
                // e.g., "highnoise_unet_name" -> "unet_name", "lownoise_lora_name" -> "lora_name"
                val jsonInputKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(fieldKey)

                // Find all nodes that have this input key
                for (nodeId in nodesJson.keys()) {
                    val node = nodesJson.optJSONObject(nodeId) ?: continue
                    val inputs = node.optJSONObject("inputs") ?: continue

                    if (inputs.has(jsonInputKey)) {
                        val currentValue = inputs.opt(jsonInputKey)

                        if (TemplateKeyRegistry.doesValueMatchPlaceholder(fieldKey, currentValue)) {
                            val classType = node.optString("class_type", "Unknown")
                            val meta = node.optJSONObject("_meta")
                            val title = meta?.optString("title") ?: classType

                            candidates.add(
                                FieldCandidate(
                                    nodeId = nodeId,
                                    nodeName = title,
                                    classType = classType,
                                    inputKey = jsonInputKey,
                                    currentValue = currentValue
                                )
                            )
                        }
                    }
                }

                FieldMappingState(
                    field = FieldDisplayRegistry.createRequiredField(context, fieldKey, isRequired),
                    candidates = candidates,
                    selectedCandidateIndex = if (candidates.isNotEmpty()) 0 else -1
                )
            }
        }

        return WorkflowMappingState(type, fieldMappings)
    }

    /**
     * Create field mappings for positive_text and negative_text using graph tracing.
     * Traces text encoding nodes to find which connects to "positive" vs "negative" sampler inputs.
     * Supports CLIPTextEncode (with "text" input) and other encoders like TextEncodeQwenImageEditPlus (with "prompt" input).
     */
    private fun createPromptFieldMappings(nodesJson: JSONObject): Map<String, List<FieldCandidate>> {
        val positiveTextCandidates = mutableListOf<FieldCandidate>()
        val negativeTextCandidates = mutableListOf<FieldCandidate>()

        // Text input keys to look for in text encoding nodes
        val textInputKeys = listOf("text", "prompt")

        // Find all text encoding nodes with text/prompt input
        data class TextEncoderNode(val nodeId: String, val node: JSONObject, val inputKey: String)
        val textEncoderNodes = mutableListOf<TextEncoderNode>()

        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            val inputs = node.optJSONObject("inputs") ?: continue

            // Look for nodes that have text-related inputs
            val matchingInputKey = textInputKeys.firstOrNull { inputs.has(it) }
            if (matchingInputKey != null) {
                // Only include known text encoding node types
                val isTextEncoder = classType == "CLIPTextEncode" ||
                        classType.contains("TextEncode", ignoreCase = true) ||
                        classType.contains("Prompt", ignoreCase = true)
                if (isTextEncoder) {
                    textEncoderNodes.add(TextEncoderNode(nodeId, node, matchingInputKey))
                }
            }
        }

        // For each text encoder, trace its output to find if it connects to positive or negative
        for ((nodeId, node, inputKey) in textEncoderNodes) {
            val inputs = node.optJSONObject("inputs") ?: continue
            val meta = node.optJSONObject("_meta")
            val classType = node.optString("class_type", "Unknown")
            val title = meta?.optString("title") ?: classType
            val currentValue = inputs.opt(inputKey)

            // Determine if this node connects to positive or negative input
            val connectionType = traceClipTextEncodeConnection(nodesJson, nodeId)

            val candidate = FieldCandidate(
                nodeId = nodeId,
                nodeName = title,
                classType = classType,
                inputKey = inputKey,
                currentValue = currentValue
            )

            when (connectionType) {
                "positive" -> positiveTextCandidates.add(0, candidate) // Add traced match first
                "negative" -> negativeTextCandidates.add(0, candidate) // Add traced match first
                else -> {
                    // Unknown connection - use title-based classification
                    val titleLower = title.lowercase()
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

    fun cancelImport() {
        _uiState.value = _uiState.value.copy(
            showImportDialog = false,
            pendingImportJsonContent = "",
            importSelectedType = null,
            importName = "",
            importDescription = "",
            importNameError = null,
            importDescriptionError = null,
            pendingWorkflowForMapping = null
        )
    }

    fun dismissMissingNodesDialog() {
        _uiState.value = _uiState.value.copy(
            showMissingNodesDialog = false,
            missingNodes = emptyList(),
            showImportDialog = false
        )
        cancelImport()
    }

    fun dismissMissingFieldsDialog() {
        _uiState.value = _uiState.value.copy(
            showMissingFieldsDialog = false,
            missingFields = emptyList(),
            showImportDialog = false
        )
        cancelImport()
    }

    fun dismissDuplicateNameDialog() {
        _uiState.value = _uiState.value.copy(showDuplicateNameDialog = false)
    }

    /**
     * Complete the import after mapping is confirmed in editor
     */
    fun completeImport(fieldMappings: Map<String, Pair<String, String>>) {
        val pending = _uiState.value.pendingWorkflowForMapping ?: return

        viewModelScope.launch {
            val result = WorkflowManager.addUserWorkflowWithMapping(
                name = pending.name,
                description = pending.description,
                jsonContent = pending.jsonContent,
                type = pending.type,
                fieldMappings = fieldMappings
            )

            if (result.isSuccess) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_import_success))
                _events.emit(WorkflowManagementEvent.WorkflowsChanged)
                loadWorkflows()
            } else {
                _events.emit(WorkflowManagementEvent.ShowToastMessage(
                    result.exceptionOrNull()?.message ?: "Import failed"
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
            editDescription = workflow.description,
            editNameError = null,
            editDescriptionError = null
        )
    }

    fun onEditNameChange(name: String) {
        val error = if (name.length > ValidationUtils.MAX_WORKFLOW_NAME_LENGTH) {
            applicationContext?.getString(R.string.workflow_name_error_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            editName = ValidationUtils.truncateWorkflowName(name),
            editNameError = error
        )
    }

    fun onEditDescriptionChange(description: String) {
        val error = if (description.length > ValidationUtils.MAX_WORKFLOW_DESCRIPTION_LENGTH) {
            applicationContext?.getString(R.string.workflow_description_error_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            editDescription = ValidationUtils.truncateWorkflowDescription(description),
            editDescriptionError = error
        )
    }

    fun confirmEdit() {
        val state = _uiState.value
        val workflow = state.editingWorkflow ?: return

        val name = state.editName.trim()
        val description = state.editDescription.trim()

        // Validate name format
        val nameError = WorkflowManager.validateWorkflowName(name)
        if (nameError != null) {
            _uiState.value = _uiState.value.copy(editNameError = nameError)
            return
        }

        // Validate description format
        val descError = WorkflowManager.validateWorkflowDescription(description)
        if (descError != null) {
            _uiState.value = _uiState.value.copy(editDescriptionError = descError)
            return
        }

        // Check for duplicate name (excluding current workflow)
        if (WorkflowManager.isWorkflowNameTaken(name, excludeWorkflowId = workflow.id)) {
            _uiState.value = _uiState.value.copy(
                editNameError = applicationContext?.getString(R.string.duplicate_name_message)
            )
            return
        }

        viewModelScope.launch {
            val success = WorkflowManager.updateUserWorkflowMetadata(
                workflowId = workflow.id,
                name = name,
                description = description
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
                editDescription = "",
                editNameError = null,
                editDescriptionError = null
            )
        }
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(
            showEditDialog = false,
            editingWorkflow = null,
            editName = "",
            editDescription = "",
            editNameError = null,
            editDescriptionError = null
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
        val workflow = state.workflowToDelete ?: return

        viewModelScope.launch {
            val success = WorkflowManager.deleteUserWorkflow(workflow.id)

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

    // Duplicate flow

    fun onDuplicateWorkflow(workflow: WorkflowManager.Workflow) {
        // Generate a unique name for the duplicate
        val suggestedName = WorkflowManager.generateUniqueDuplicateName(workflow.name)

        _uiState.value = _uiState.value.copy(
            showDuplicateDialog = true,
            duplicatingWorkflow = workflow,
            duplicateName = suggestedName,
            duplicateDescription = workflow.description,
            duplicateNameError = null
        )
    }

    fun onDuplicateNameChange(name: String) {
        val error = if (name.length > ValidationUtils.MAX_WORKFLOW_NAME_LENGTH) {
            applicationContext?.getString(R.string.workflow_name_error_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            duplicateName = ValidationUtils.truncateWorkflowName(name),
            duplicateNameError = error
        )
    }

    fun onDuplicateDescriptionChange(description: String) {
        val error = if (description.length > ValidationUtils.MAX_WORKFLOW_DESCRIPTION_LENGTH) {
            applicationContext?.getString(R.string.workflow_description_error_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            duplicateDescription = ValidationUtils.truncateWorkflowDescription(description),
            duplicateDescriptionError = error
        )
    }

    fun confirmDuplicate() {
        val state = _uiState.value
        val workflow = state.duplicatingWorkflow ?: return

        val name = state.duplicateName.trim()
        val description = state.duplicateDescription.trim()

        // Validate name
        val nameError = WorkflowManager.validateWorkflowName(name)
        if (nameError != null) {
            _uiState.value = _uiState.value.copy(duplicateNameError = nameError)
            return
        }

        // Validate description
        val descError = WorkflowManager.validateWorkflowDescription(description)
        if (descError != null) {
            _uiState.value = _uiState.value.copy(duplicateDescriptionError = descError)
            return
        }

        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
            _uiState.value = _uiState.value.copy(duplicateNameError = applicationContext?.getString(R.string.duplicate_name_message))
            return
        }

        viewModelScope.launch {
            val result = WorkflowManager.duplicateWorkflow(
                sourceWorkflowId = workflow.id,
                newName = name,
                newDescription = description
            )

            if (result.isSuccess) {
                _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_duplicate_success))
                _events.emit(WorkflowManagementEvent.WorkflowsChanged)
                loadWorkflows()
            } else {
                _events.emit(WorkflowManagementEvent.ShowToastMessage(
                    result.exceptionOrNull()?.message ?: "Duplicate failed"
                ))
            }

            _uiState.value = _uiState.value.copy(
                showDuplicateDialog = false,
                duplicatingWorkflow = null,
                duplicateName = "",
                duplicateDescription = "",
                duplicateNameError = null,
                duplicateDescriptionError = null
            )
        }
    }

    fun cancelDuplicate() {
        _uiState.value = _uiState.value.copy(
            showDuplicateDialog = false,
            duplicatingWorkflow = null,
            duplicateName = "",
            duplicateDescription = "",
            duplicateNameError = null,
            duplicateDescriptionError = null
        )
    }

    // Export flow

    fun onExportWorkflow(workflow: WorkflowManager.Workflow, format: ExportFormat) {
        val suggestedFilename = WorkflowManager.generateExportFilename(workflow.name)

        _uiState.value = _uiState.value.copy(
            exportingWorkflow = workflow,
            exportFormat = format
        )

        viewModelScope.launch {
            _events.emit(WorkflowManagementEvent.LaunchExportFilePicker(suggestedFilename))
        }
    }

    fun performExport(context: Context, uri: Uri) {
        val workflow = _uiState.value.exportingWorkflow ?: return
        val format = _uiState.value.exportFormat

        viewModelScope.launch {
            val result = when (format) {
                ExportFormat.INTERNAL -> WorkflowManager.exportWorkflowInternal(workflow.id)
                ExportFormat.API -> WorkflowManager.exportWorkflowToComfyUIFormat(workflow.id)
            }

            if (result.isSuccess) {
                val jsonContent = result.getOrThrow()
                val writeSuccess = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(jsonContent.toByteArray(Charsets.UTF_8))
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                if (writeSuccess) {
                    _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_export_success))
                } else {
                    _events.emit(WorkflowManagementEvent.ShowToast(R.string.workflow_export_failed))
                }
            } else {
                _events.emit(WorkflowManagementEvent.ShowToastMessage(
                    result.exceptionOrNull()?.message ?: "Export failed"
                ))
            }

            _uiState.value = _uiState.value.copy(exportingWorkflow = null)
        }
    }

    fun cancelExport() {
        _uiState.value = _uiState.value.copy(exportingWorkflow = null)
    }
}
