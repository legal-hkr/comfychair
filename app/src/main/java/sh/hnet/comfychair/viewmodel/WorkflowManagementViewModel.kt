package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowValidationResult
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LiteGraphConverter
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.WorkflowJsonAnalyzer
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.PendingWorkflowUpload
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowMappingState

/**
 * Export format options
 */
enum class ExportFormat {
    INTERNAL,  // Full internal format with placeholders, groups, notes
    API        // Raw ComfyUI API format with placeholders replaced
}

/** Which types the workflows page shows; Image to Image covers both of its modes. */
enum class WorkflowFilter(val types: Set<WorkflowType>) {
    ALL(WorkflowType.entries.toSet()),
    TTI(setOf(WorkflowType.TTI)),
    ITI(setOf(WorkflowType.ITI_EDITING, WorkflowType.ITI_INPAINTING)),
    TTV(setOf(WorkflowType.TTV)),
    ITV(setOf(WorkflowType.ITV))
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
    // Workflows switched off by the user (not offered on the generation screens)
    val disabledWorkflowIds: Set<String> = emptySet(),
    // Types shown; kept while the app runs (the view model belongs to the activity), not saved
    val filter: WorkflowFilter = WorkflowFilter.ALL,

    // Import dialog state
    val showImportDialog: Boolean = false,
    val pendingImportJsonContent: String = "",
    val importSelectedType: WorkflowType? = null,
    // The picked file's name, under the import dialog's headline
    val importFileName: String? = null,
    val importName: String = "",
    val importDescription: String = "",
    val importNameError: String? = null,

    // Validation state
    val isValidatingNodes: Boolean = false,
    val showMissingNodesDialog: Boolean = false,
    val missingNodes: List<String> = emptyList(),
    val showMissingFieldsDialog: Boolean = false,
    val missingFields: List<String> = emptyList(),

    // Pending workflow for editor
    val pendingWorkflowForMapping: PendingWorkflowUpload? = null,

    // LiteGraph conversion warnings
    val pendingImportWarnings: List<String> = emptyList(),

    // Edit dialog state
    val showEditDialog: Boolean = false,
    val editingWorkflow: WorkflowManager.Workflow? = null,
    val editName: String = "",
    val editDescription: String = "",
    val editNameError: String? = null,

    // Duplicate dialog state
    val showDuplicateDialog: Boolean = false,
    val duplicatingWorkflow: WorkflowManager.Workflow? = null,
    val duplicateName: String = "",
    val duplicateDescription: String = "",
    val duplicateNameError: String? = null,

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
    /** A workflow was imported; the list shows it and offers its details. */
    data class WorkflowImported(val workflowId: String) : WorkflowManagementEvent()
    /** A copy was created; its details open. */
    data class DuplicateCreated(val workflowId: String) : WorkflowManagementEvent()
    /** The workflow shown was deleted; the page returns to the list. */
    object WorkflowDeleted : WorkflowManagementEvent()
}

/**
 * ViewModel for the Workflow Management screen
 */
class WorkflowManagementViewModel : ViewModel() {

    companion object {
        private const val TAG = "WorkflowImport"
    }

    // State
    private val _uiState = MutableStateFlow(WorkflowManagementUiState())
    val uiState: StateFlow<WorkflowManagementUiState> = _uiState.asStateFlow()

    // Everything for the pages (messages, export, import, copy, deletion): queued until the page on top takes it, so
    // nothing is lost on the way back from the editor or between pages, and each event is handled once
    private val _pageEvents = Channel<WorkflowManagementEvent>(Channel.BUFFERED)
    val pageEvents: Flow<WorkflowManagementEvent> = _pageEvents.receiveAsFlow()

    private suspend fun emitEvent(event: WorkflowManagementEvent) {
        _pageEvents.send(event)
    }

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
        if (applicationContext == null) return

        _uiState.value = _uiState.value.copy(
            disabledWorkflowIds = WorkflowManager.getDisabledWorkflowIds(),
            ttiWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.TTI),
            itiInpaintingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_INPAINTING),
            itiEditingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_EDITING),
            ttvWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.TTV),
            itvWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV)
        )
    }

    /**
     * Switch a workflow on or off; switched-off workflows stay in this list but are not offered
     * on the generation screens.
     */
    fun onWorkflowEnabledChange(workflow: WorkflowManager.Workflow, enabled: Boolean) {
        WorkflowManager.setWorkflowEnabled(workflow.id, enabled)
        _uiState.value = _uiState.value.copy(disabledWorkflowIds = WorkflowManager.getDisabledWorkflowIds())
    }

    /** Switch all workflows of a type on or off (built-in and own). */
    fun onSectionEnabledChange(type: WorkflowType, enabled: Boolean) {
        WorkflowManager.setWorkflowsEnabled(WorkflowManager.getWorkflowsByType(type).map { it.id }, enabled)
        _uiState.value = _uiState.value.copy(disabledWorkflowIds = WorkflowManager.getDisabledWorkflowIds())
    }

    fun onFilterChange(filter: WorkflowFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
    }

    /**
     * Reload workflows from disk and refresh UI.
     * Use this when workflows were modified by another component (e.g., WorkflowEditorViewModel); the generation screens
     * follow through WorkflowManager.workflowsVersion.
     */
    fun reloadAndRefreshWorkflows() {
        WorkflowManager.reloadWorkflows()
        loadWorkflows()
    }

    // New import flow

    /**
     * Handle file selection from file picker - new flow without filename prefix requirement
     */
    fun onFileSelected(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // The file's display name, to show and to suggest a workflow name
                val fileName = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull()
                }

                // Read file content
                val jsonContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                }

                // Validate JSON format
                try {
                    JSONObject(jsonContent)
                } catch (e: Exception) {
                    emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_invalid_json))
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@launch
                }

                // The type picked with the filter, if it names one; otherwise detected from the workflow (user can
                // override either)
                val filterTypes = _uiState.value.filter.types
                val detectedType = filterTypes.singleOrNull() ?: WorkflowManager.detectWorkflowType(jsonContent)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    showImportDialog = true,
                    pendingImportJsonContent = jsonContent,
                    importSelectedType = detectedType,
                    importFileName = fileName,
                    // The file's name as a start; the user can change it
                    importName = fileName?.substringBeforeLast('.')?.let { ValidationUtils.truncateWorkflowName(it) } ?: "",
                    importDescription = "",
                    importNameError = null,
                )
            } catch (e: Exception) {
                emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_import))
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun onImportTypeSelected(type: WorkflowType) {
        _uiState.value = _uiState.value.copy(
            importSelectedType = type
        )
    }

    fun onImportNameChange(name: String) {
        val error = if (name.length > ValidationUtils.MAX_WORKFLOW_NAME_LENGTH) {
            applicationContext?.getString(R.string.error_workflow_name_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            importName = ValidationUtils.truncateWorkflowName(name),
            importNameError = error
        )
    }

    fun onImportDescriptionChange(description: String) {
        _uiState.value = _uiState.value.copy(importDescription = description)
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


        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
            _uiState.value = _uiState.value.copy(importNameError = applicationContext?.getString(R.string.error_name_taken))
            return
        }

        // Start node validation
        _uiState.value = _uiState.value.copy(isValidatingNodes = true)

        // Fetch available nodes from server
        comfyUIClient.fetchAllNodeTypes { availableNodes ->
            viewModelScope.launch {
                if (availableNodes == null) {
                    emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_fetch_nodes_failed))
                    _uiState.value = _uiState.value.copy(isValidatingNodes = false)
                    return@launch
                }

                // Check if JSON is LiteGraph format and convert if needed
                var jsonContent = state.pendingImportJsonContent
                var conversionWarnings = emptyList<String>()

                try {
                    val jsonObject = JSONObject(jsonContent)
                    val isLiteGraph = WorkflowJsonAnalyzer.isLiteGraphFormat(jsonObject)
                    DebugLogger.i(TAG, "Import: format=${if (isLiteGraph) "LiteGraph" else "API/ComfyChair"}")

                    if (isLiteGraph) {
                        val converter = LiteGraphConverter { classType ->
                            ConnectionManager.nodeTypeRegistry.getNodeDefinition(classType)
                        }
                        val result = converter.convert(jsonObject)
                        jsonContent = result.jsonContent
                        conversionWarnings = result.warnings
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Import failed: ${e.message}")
                    emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_invalid_json))
                    _uiState.value = _uiState.value.copy(isValidatingNodes = false)
                    return@launch
                }

                // Store conversion warnings
                if (conversionWarnings.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(pendingImportWarnings = conversionWarnings)
                }

                // Extract class types from workflow
                val classTypesResult = WorkflowManager.extractClassTypes(jsonContent)
                if (classTypesResult.isFailure) {
                    emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_invalid_json))
                    _uiState.value = _uiState.value.copy(isValidatingNodes = false)
                    return@launch
                }
                val workflowClassTypes = classTypesResult.getOrThrow()

                // Validate nodes
                val missingNodes = WorkflowManager.validateNodesAgainstServer(workflowClassTypes, availableNodes)
                if (missingNodes.isNotEmpty()) {
                    DebugLogger.w(TAG, "Import blocked: ${missingNodes.size} missing nodes: ${missingNodes.joinToString()}")
                    _uiState.value = _uiState.value.copy(
                        isValidatingNodes = false,
                        showMissingNodesDialog = true,
                        missingNodes = missingNodes
                    )
                    return@launch
                }

                // Validate required fields for type
                val keyValidation = WorkflowManager.validateWorkflowKeys(jsonContent, selectedType)
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
                    jsonContent,
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
                    jsonContent = jsonContent,
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

                emitEvent(WorkflowManagementEvent.LaunchEditor(pendingImport))
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

        // Primitive String nodes (node ID -> text input key), e.g. one "String (Multiline)" node
        // feeding the same prompt to several text encoders
        val primitiveTextNodes = mutableMapOf<String, String>()
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val inputMap = inputs.keys().asSequence().associateWith { inputs.opt(it) }
            TemplateKeyRegistry.primitiveTextKey(node.optString("class_type", ""), inputMap) { it is JSONArray }
                ?.let { primitiveTextNodes[nodeId] = it }
        }

        fun candidateFor(nodeId: String, inputKey: String): FieldCandidate? {
            val node = nodesJson.optJSONObject(nodeId) ?: return null
            val classType = node.optString("class_type", "Unknown")
            return FieldCandidate(
                nodeId = nodeId,
                nodeName = node.optJSONObject("_meta")?.optString("title") ?: classType,
                classType = classType,
                inputKey = inputKey,
                currentValue = node.optJSONObject("inputs")?.opt(inputKey)
            )
        }

        fun MutableList<FieldCandidate>.addUnique(candidate: FieldCandidate, first: Boolean = false) {
            if (any { it.nodeId == candidate.nodeId && it.inputKey == candidate.inputKey }) return
            if (first) add(0, candidate) else add(candidate)
        }

        // For each text encoder, trace its output to find if it connects to positive or negative
        for ((nodeId, node, inputKey) in textEncoderNodes) {
            val inputs = node.optJSONObject("inputs") ?: continue

            // Text linked from a primitive String node: offer that node, so the prompt reaches
            // every encoder it feeds instead of replacing this encoder's link
            val sourceId = (inputs.opt(inputKey) as? JSONArray)?.optString(0, "")
            val sourceKey = sourceId?.let { primitiveTextNodes[it] }
            val candidate = (if (sourceId != null && sourceKey != null) {
                candidateFor(sourceId, sourceKey)
            } else {
                candidateFor(nodeId, inputKey)
            }) ?: continue

            // Determine if this node connects to positive or negative input
            val connectionType = traceClipTextEncodeConnection(nodesJson, nodeId)

            when (connectionType) {
                "positive" -> positiveTextCandidates.addUnique(candidate, first = true) // Traced match first
                "negative" -> negativeTextCandidates.addUnique(candidate, first = true) // Traced match first
                else -> {
                    // Unknown connection - use title-based classification
                    val titleLower = candidate.nodeName.lowercase()
                    when {
                        titleLower.contains("positive") -> positiveTextCandidates.addUnique(candidate)
                        titleLower.contains("negative") -> negativeTextCandidates.addUnique(candidate)
                        else -> {
                            // Add to both as fallback candidates
                            positiveTextCandidates.addUnique(candidate)
                            negativeTextCandidates.addUnique(candidate)
                        }
                    }
                }
            }
        }

        // Primitive String nodes not feeding a text encoder (e.g. prompts of API nodes without
        // CLIP encoding) as fallback candidates
        for ((nodeId, inputKey) in primitiveTextNodes) {
            val candidate = candidateFor(nodeId, inputKey) ?: continue
            val alreadyOffered = (positiveTextCandidates + negativeTextCandidates).any { it.nodeId == nodeId }
            if (alreadyOffered) continue
            val titleLower = candidate.nodeName.lowercase()
            if (!titleLower.contains("negative")) positiveTextCandidates.addUnique(candidate)
            if (!titleLower.contains("positive")) negativeTextCandidates.addUnique(candidate)
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
            pendingWorkflowForMapping = null,
            pendingImportWarnings = emptyList()
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

    /**
     * Complete the import after mapping is confirmed in editor
     */
    fun completeImport(fieldMappings: Map<String, Pair<String, String>>) {
        val pending = _uiState.value.pendingWorkflowForMapping ?: return
        val warnings = _uiState.value.pendingImportWarnings

        viewModelScope.launch {
            val result = WorkflowManager.addUserWorkflowWithMapping(
                name = pending.name,
                description = pending.description,
                jsonContent = pending.jsonContent,
                type = pending.type,
                fieldMappings = fieldMappings
            )

            val imported = result.getOrNull()
            if (imported != null) {
                // Show conversion warnings if any
                if (warnings.isNotEmpty()) {
                    val warningMessage = applicationContext?.resources?.getQuantityString(
                        R.plurals.msg_litegraph_conversion_notes,
                        warnings.size,
                        warnings.size
                    ) ?: "Conversion completed with ${warnings.size} note(s)"
                    emitEvent(WorkflowManagementEvent.ShowToastMessage(warningMessage))
                }
                // Shown where it now sits; its type may not be the one filtered
                if (imported.type !in _uiState.value.filter.types) onFilterChange(WorkflowFilter.ALL)
                loadWorkflows()
                emitEvent(WorkflowManagementEvent.WorkflowImported(imported.id))
            } else {
                emitEvent(WorkflowManagementEvent.ShowToastMessage(
                    result.exceptionOrNull()?.message ?: "Import failed"
                ))
            }

            _uiState.value = _uiState.value.copy(
                pendingWorkflowForMapping = null,
                pendingImportWarnings = emptyList()
            )
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
        )
    }

    fun onEditNameChange(name: String) {
        val error = if (name.length > ValidationUtils.MAX_WORKFLOW_NAME_LENGTH) {
            applicationContext?.getString(R.string.error_workflow_name_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            editName = ValidationUtils.truncateWorkflowName(name),
            editNameError = error
        )
    }

    fun onEditDescriptionChange(description: String) {
        _uiState.value = _uiState.value.copy(editDescription = description)
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


        // Check for duplicate name (excluding current workflow)
        if (WorkflowManager.isWorkflowNameTaken(name, excludeWorkflowId = workflow.id)) {
            _uiState.value = _uiState.value.copy(
                editNameError = applicationContext?.getString(R.string.error_name_taken)
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
                emitEvent(WorkflowManagementEvent.ShowToast(R.string.msg_workflow_update_success))
                loadWorkflows()
            } else {
                emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_update))
            }

            _uiState.value = _uiState.value.copy(
                showEditDialog = false,
                editingWorkflow = null,
                editName = "",
                editDescription = "",
                editNameError = null,
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
        )
    }

    // Delete flow

    /**
     * Delete an own workflow (the detail page confirms first). On success the page returns to the list
     * ([WorkflowManagementEvent.WorkflowDeleted]).
     */
    fun deleteWorkflow(workflow: WorkflowManager.Workflow) {
        if (workflow.isBuiltIn) return
        viewModelScope.launch {
            if (WorkflowManager.deleteUserWorkflow(workflow.id)) {
                loadWorkflows()
                emitEvent(WorkflowManagementEvent.WorkflowDeleted)
            } else {
                emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_delete))
            }
        }
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
            applicationContext?.getString(R.string.error_workflow_name_too_long)
        } else null
        _uiState.value = _uiState.value.copy(
            duplicateName = ValidationUtils.truncateWorkflowName(name),
            duplicateNameError = error
        )
    }

    fun onDuplicateDescriptionChange(description: String) {
        _uiState.value = _uiState.value.copy(duplicateDescription = description)
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


        // Check for duplicate name
        if (WorkflowManager.isWorkflowNameTaken(name)) {
            _uiState.value = _uiState.value.copy(duplicateNameError = applicationContext?.getString(R.string.error_name_taken))
            return
        }

        viewModelScope.launch {
            val result = WorkflowManager.duplicateWorkflow(
                sourceWorkflowId = workflow.id,
                newName = name,
                newDescription = description
            )

            val copy = result.getOrNull()
            if (copy != null) {
                loadWorkflows()
                // The detail page opens the copy's details
                emitEvent(WorkflowManagementEvent.DuplicateCreated(copy.id))
            } else {
                emitEvent(WorkflowManagementEvent.ShowToastMessage(
                    result.exceptionOrNull()?.message ?: "Duplicate failed"
                ))
            }

            _uiState.value = _uiState.value.copy(
                showDuplicateDialog = false,
                duplicatingWorkflow = null,
                duplicateName = "",
                duplicateDescription = "",
                duplicateNameError = null,
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
            emitEvent(WorkflowManagementEvent.LaunchExportFilePicker(suggestedFilename))
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
                    emitEvent(WorkflowManagementEvent.ShowToast(R.string.msg_workflow_export_success))
                } else {
                    emitEvent(WorkflowManagementEvent.ShowToast(R.string.error_workflow_export))
                }
            } else {
                emitEvent(WorkflowManagementEvent.ShowToastMessage(
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
