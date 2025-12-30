package sh.hnet.comfychair

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.NodeAttributeEdits
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import java.io.File
import java.io.InputStream

/**
 * Workflow types supported by ComfyChair
 */
enum class WorkflowType {
    TTI_CHECKPOINT,    // Text-to-Image Checkpoint
    TTI_UNET,          // Text-to-Image UNET
    ITI_CHECKPOINT,    // Image-to-Image Inpainting Checkpoint
    ITI_UNET,          // Image-to-Image Inpainting UNET
    ITE_UNET,          // Image-to-Image Editing UNET
    TTV_UNET,          // Text-to-Video UNET
    ITV_UNET           // Image-to-Video UNET
}

/**
 * Result of workflow validation
 */
sealed class WorkflowValidationResult {
    object Success : WorkflowValidationResult()
    data class InvalidFilename(val message: String) : WorkflowValidationResult()
    data class InvalidJson(val message: String) : WorkflowValidationResult()
    data class MissingPlaceholders(val missing: List<String>) : WorkflowValidationResult()
    data class MissingKeys(val missing: List<String>) : WorkflowValidationResult()
}

/**
 * WorkflowManager - Manages ComfyUI workflow JSON files
 *
 * This singleton handles:
 * - Loading workflow JSON files from res/raw (built-in) and internal storage (user-uploaded)
 * - Extracting workflow names and descriptions
 * - Validating workflows against required placeholders
 * - Replacing template variables with actual values
 * - Providing workflow data for API calls
 */
object WorkflowManager {

    private const val TAG = "Workflow"
    private const val USER_WORKFLOWS_PREFS = "UserWorkflowsPrefs"
    private const val USER_WORKFLOWS_KEY = "user_workflows_json"
    private const val USER_WORKFLOWS_DIR = "user_workflows"

    // Workflow data class
    data class Workflow(
        val id: String,
        val name: String,
        val description: String,
        val jsonContent: String,
        val type: WorkflowType,
        val isBuiltIn: Boolean,
        val defaults: WorkflowDefaults = WorkflowDefaults()
    )

    // Context initialization
    private lateinit var applicationContext: Context
    private var isInitialized = false

    /**
     * Initialize the WorkflowManager with application context.
     * Must be called before first use.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (!isInitialized) {
            applicationContext = context.applicationContext
            loadAllWorkflows()
            isInitialized = true
            DebugLogger.i(TAG, "WorkflowManager initialized")
        }
    }

    /**
     * Ensure initialized, or initialize lazily with provided context.
     */
    fun ensureInitialized(context: Context) {
        if (!isInitialized) {
            initialize(context)
        }
    }

    // Required placeholders per workflow type
    val REQUIRED_PLACEHOLDERS = mapOf(
        WorkflowType.TTI_CHECKPOINT to listOf(
            "{{positive_prompt}}", "{{negative_prompt}}", "{{ckpt_name}}", "{{width}}", "{{height}}", "{{steps}}"
        ),
        WorkflowType.TTI_UNET to listOf(
            "{{positive_prompt}}", "{{negative_prompt}}", "{{unet_name}}", "{{vae_name}}", "{{clip_name}}",
            "{{width}}", "{{height}}", "{{steps}}"
        ),
        WorkflowType.ITI_CHECKPOINT to listOf(
            "{{positive_prompt}}", "{{negative_prompt}}", "{{ckpt_name}}", "{{megapixels}}", "{{steps}}"
        ),
        WorkflowType.ITI_UNET to listOf(
            "{{positive_prompt}}", "{{negative_prompt}}", "{{unet_name}}", "{{vae_name}}", "{{clip_name}}", "{{steps}}"
        ),
        WorkflowType.ITE_UNET to listOf(
            "{{positive_prompt}}", "{{unet_name}}",
            "{{vae_name}}", "{{clip_name}}", "{{megapixels}}", "{{steps}}"
        ),
        WorkflowType.TTV_UNET to listOf(
            "{{positive_prompt}}", "{{negative_prompt}}", "{{highnoise_unet_name}}", "{{lownoise_unet_name}}",
            "{{highnoise_lora_name}}", "{{lownoise_lora_name}}",
            "{{vae_name}}", "{{clip_name}}", "{{width}}", "{{height}}", "{{length}}", "{{frame_rate}}"
        ),
        WorkflowType.ITV_UNET to listOf(
            "{{positive_prompt}}", "{{negative_prompt}}", "{{highnoise_unet_name}}", "{{lownoise_unet_name}}",
            "{{highnoise_lora_name}}", "{{lownoise_lora_name}}",
            "{{vae_name}}", "{{clip_name}}", "{{width}}", "{{height}}", "{{length}}", "{{frame_rate}}",
            "{{image_filename}}"
        )
    )

    // Required patterns (not placeholders but literal strings that must exist)
    val REQUIRED_PATTERNS = mapOf(
        WorkflowType.ITI_CHECKPOINT to listOf("uploaded_image.png [input]"),
        WorkflowType.ITI_UNET to listOf("uploaded_image.png [input]"),
        WorkflowType.ITE_UNET to listOf("uploaded_image.png [input]")
    )

    // Filename prefix to type mapping
    val PREFIX_TO_TYPE = mapOf(
        "tti_checkpoint_" to WorkflowType.TTI_CHECKPOINT,
        "tti_unet_" to WorkflowType.TTI_UNET,
        "iti_checkpoint_" to WorkflowType.ITI_CHECKPOINT,
        "iti_unet_" to WorkflowType.ITI_UNET,
        "ite_unet_" to WorkflowType.ITE_UNET,
        "ttv_unet_" to WorkflowType.TTV_UNET,
        "itv_unet_" to WorkflowType.ITV_UNET
    )

    // Available workflows loaded from res/raw and user storage
    private val workflows = mutableListOf<Workflow>()
    private val workflowValuesStorage by lazy { WorkflowValuesStorage(applicationContext) }

    /**
     * Load all workflows (built-in and user-uploaded)
     */
    private fun loadAllWorkflows() {
        workflows.clear()
        loadBuiltInWorkflows()
        loadUserWorkflows()
    }

    /**
     * Reload all workflows (call after adding/deleting user workflows)
     */
    fun reloadWorkflows() {
        loadAllWorkflows()
    }

    /**
     * Load built-in workflow JSON files from res/raw
     * Auto-discovers all workflow resources by scanning R.raw fields that match workflow prefixes
     */
    private fun loadBuiltInWorkflows() {
        // Get all workflow resource IDs by scanning R.raw fields
        val workflowResources = discoverWorkflowResources()

        for (resId in workflowResources) {
            try {
                val inputStream: InputStream = applicationContext.resources.openRawResource(resId)
                val jsonContent = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)

                val name = jsonObject.optString("name", "Unnamed Workflow")
                val description = jsonObject.optString("description", "")
                val resourceName = applicationContext.resources.getResourceEntryName(resId)
                val type = parseWorkflowType(resourceName) ?: continue
                val defaults = WorkflowDefaults.fromJson(jsonObject.optJSONObject("defaults"))

                // Generate deterministic UUID from resource name
                val id = UuidUtils.generateDeterministicId(resourceName)

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = true, defaults))
            } catch (e: Exception) {
                // Failed to load workflow
            }
        }
    }

    /**
     * Discover all workflow resources in R.raw using reflection
     * Looks for fields matching workflow prefixes (tti_checkpoint_, tti_unet_, etc.)
     */
    private fun discoverWorkflowResources(): List<Int> {
        val resources = mutableListOf<Int>()
        val prefixes = PREFIX_TO_TYPE.keys

        try {
            // Use reflection to scan R.raw class fields
            val rawClass = R.raw::class.java
            for (field in rawClass.fields) {
                val fieldName = field.name
                // Check if field name matches any workflow prefix
                if (prefixes.any { fieldName.startsWith(it) }) {
                    try {
                        val resId = field.getInt(null)
                        resources.add(resId)
                    } catch (e: Exception) {
                        // Skip fields that can't be read
                    }
                }
            }
        } catch (e: Exception) {
            // Reflection failed, return empty list
        }

        return resources
    }

    /**
     * Load user-uploaded workflows from internal storage
     */
    private fun loadUserWorkflows() {
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return

        try {
            val metadataArray = JSONArray(metadataJson)
            val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                val id = metadata.getString("id")
                val name = metadata.getString("name")
                val description = metadata.optString("description", "")
                val typeStr = metadata.getString("type")
                val filename = metadata.getString("filename")

                val type = try {
                    WorkflowType.valueOf(typeStr)
                } catch (e: Exception) {
                    continue
                }

                val file = File(dir, filename)
                if (!file.exists()) continue

                val jsonContent = file.readText()
                val jsonObject = try {
                    JSONObject(jsonContent)
                } catch (e: Exception) {
                    null
                }
                val defaults = WorkflowDefaults.fromJson(jsonObject?.optJSONObject("defaults"))

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = false, defaults))
            }
        } catch (e: Exception) {
            // Failed to load user workflows
        }
    }

    /**
     * Parse workflow type from filename prefix
     */
    fun parseWorkflowType(filename: String): WorkflowType? {
        val lowercaseFilename = filename.lowercase()
        PREFIX_TO_TYPE.forEach { (prefix, type) ->
            if (lowercaseFilename.startsWith(prefix)) {
                return type
            }
        }
        return null
    }

    /**
     * Extract the nodes object from workflow JSON, handling both wrapped and raw formats
     */
    private fun extractNodesObject(json: JSONObject): JSONObject {
        return if (json.has("nodes") && json.optJSONObject("nodes") != null) {
            json.getJSONObject("nodes")
        } else {
            json
        }
    }

    /**
     * Detect workflow type by analyzing JSON content
     * Returns null if type cannot be determined
     */
    fun detectWorkflowType(jsonContent: String): WorkflowType? {
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return null
        }

        val nodesJson = extractNodesObject(json)

        // Collect all class_types in workflow
        val classTypes = mutableSetOf<String>()
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            if (classType.isNotEmpty()) {
                classTypes.add(classType)
            }
        }

        // Check for video creation nodes
        val hasVideoNodes = classTypes.any { classType ->
            classType.contains("CreateVideo", ignoreCase = true) ||
            classType.contains("VHS_VideoCombine", ignoreCase = true) ||
            classType.contains("VideoLinearCFGGuidance", ignoreCase = true)
        }

        // Check for LoadImage node
        val hasLoadImage = classTypes.any { classType ->
            classType.equals("LoadImage", ignoreCase = true)
        }

        // Check for Image-to-image inpainting indicators (mask nodes in ComfyUI)
        val hasInpaintingNodes = classTypes.any { classType ->
            classType.contains("SetLatentNoiseMask", ignoreCase = true) ||
            classType.contains("InpaintModel", ignoreCase = true) ||
            classType.contains("Inpaint", ignoreCase = true)
        }

        // Check for Image-to-image editing indicators (QwenImage Edit style)
        val hasImageEditingNodes = classTypes.any { classType ->
            classType.contains("TextEncodeQwenImageEditPlus", ignoreCase = true) ||
            classType.contains("QwenImageEdit", ignoreCase = true)
        }

        // Check for checkpoint loader
        val hasCheckpointLoader = classTypes.any { classType ->
            classType.equals("CheckpointLoaderSimple", ignoreCase = true)
        }

        // Check for UNET loader
        val hasUNETLoader = classTypes.any { classType ->
            classType.equals("UNETLoader", ignoreCase = true)
        }

        // Detection rules (order matters - most specific first):
        return when {
            // Image-to-video: has both LoadImage and video nodes
            hasLoadImage && hasVideoNodes -> WorkflowType.ITV_UNET

            // Text-to-video: has video nodes but no LoadImage
            hasVideoNodes -> WorkflowType.TTV_UNET

            // Image-to-image editing (no mask, uses QwenImageEdit-style nodes)
            hasImageEditingNodes && hasUNETLoader -> WorkflowType.ITE_UNET

            // Image-to-image inpainting with checkpoint
            hasInpaintingNodes && hasCheckpointLoader -> WorkflowType.ITI_CHECKPOINT

            // Image-to-image inpainting with UNET
            hasInpaintingNodes && hasUNETLoader -> WorkflowType.ITI_UNET

            // Image-to-image with LoadImage (fallback to inpainting)
            hasLoadImage && hasCheckpointLoader -> WorkflowType.ITI_CHECKPOINT
            hasLoadImage && hasUNETLoader -> WorkflowType.ITI_UNET

            // Text-to-image checkpoint
            hasCheckpointLoader -> WorkflowType.TTI_CHECKPOINT

            // Text-to-image UNET
            hasUNETLoader -> WorkflowType.TTI_UNET

            // Default fallback
            else -> null
        }
    }

    /**
     * Validate workflow JSON against type requirements
     */
    fun validateWorkflow(jsonContent: String, type: WorkflowType): WorkflowValidationResult {
        // Validate JSON format
        try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return WorkflowValidationResult.InvalidJson(e.message ?: "Invalid JSON")
        }

        // Check for required placeholders
        val requiredPlaceholders = REQUIRED_PLACEHOLDERS[type] ?: emptyList()
        val missingPlaceholders = requiredPlaceholders.filter { placeholder ->
            !jsonContent.contains(placeholder)
        }

        // Check for required patterns
        val requiredPatterns = REQUIRED_PATTERNS[type] ?: emptyList()
        val missingPatterns = requiredPatterns.filter { pattern ->
            !jsonContent.contains(pattern)
        }

        val allMissing = missingPlaceholders + missingPatterns

        return if (allMissing.isEmpty()) {
            WorkflowValidationResult.Success
        } else {
            WorkflowValidationResult.MissingPlaceholders(allMissing)
        }
    }

    /**
     * Validate workflow JSON by checking for required input keys
     * This is used for raw ComfyUI workflows that don't use placeholders
     */
    fun validateWorkflowKeys(jsonContent: String, type: WorkflowType): WorkflowValidationResult {
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return WorkflowValidationResult.InvalidJson(e.message ?: "Invalid JSON")
        }

        val nodesJson = extractNodesObject(json)

        // Collect all input keys from all nodes, along with their placeholder values
        val presentKeys = mutableSetOf<String>()
        val presentPlaceholders = mutableSetOf<String>()  // e.g., "highnoise_unet_name" from "{{highnoise_unet_name}}"
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            for (key in inputs.keys()) {
                presentKeys.add(key)
                // Also extract placeholder names from values like "{{highnoise_unet_name}}"
                val value = inputs.optString(key, "")
                if (value.startsWith("{{") && value.endsWith("}}")) {
                    val placeholderName = value.substring(2, value.length - 2)
                    presentPlaceholders.add(placeholderName)
                }
            }
        }

        // Get required keys for this workflow type, adjusted for workflow structure
        // (excludes graph-traced keys like positive_text/negative_text)
        val requiredKeys = TemplateKeyRegistry.getDirectKeysForWorkflow(type, json)

        // For each required key, check if either:
        // 1. The actual JSON key is present (mapped via PLACEHOLDER_TO_KEY), or
        // 2. The placeholder is present in values (for specific placeholders like highnoise_unet_name)
        val missingKeys = requiredKeys.filter { requiredKey ->
            val jsonKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(requiredKey)
            val hasJsonKey = jsonKey in presentKeys
            val hasPlaceholder = requiredKey in presentPlaceholders
            !hasJsonKey && !hasPlaceholder
        }

        // Also check for required patterns (like uploaded_image.png for inpainting)
        val requiredPatterns = REQUIRED_PATTERNS[type] ?: emptyList()
        val missingPatterns = requiredPatterns.filter { pattern ->
            !jsonContent.contains(pattern)
        }

        return when {
            missingKeys.isNotEmpty() -> WorkflowValidationResult.MissingKeys(missingKeys)
            missingPatterns.isNotEmpty() -> WorkflowValidationResult.MissingPlaceholders(missingPatterns)
            else -> WorkflowValidationResult.Success
        }
    }

    /**
     * Extract all class_type values from workflow JSON
     */
    fun extractClassTypes(jsonContent: String): Result<Set<String>> {
        return try {
            val json = JSONObject(jsonContent)
            val nodesJson = extractNodesObject(json)
            val classTypes = mutableSetOf<String>()

            for (nodeId in nodesJson.keys()) {
                val node = nodesJson.optJSONObject(nodeId) ?: continue
                val classType = node.optString("class_type", "")
                if (classType.isNotEmpty()) {
                    classTypes.add(classType)
                }
            }
            Result.success(classTypes)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse workflow JSON: ${e.message}"))
        }
    }

    /**
     * Validate workflow nodes against available server nodes
     */
    fun validateNodesAgainstServer(
        workflowClassTypes: Set<String>,
        availableNodes: Set<String>
    ): List<String> {
        return workflowClassTypes.filter { it !in availableNodes }
    }

    /**
     * Check if workflow name is already taken
     * @param name The name to check
     * @param excludeWorkflowId Optional workflow ID to exclude from the check (used when editing)
     */
    fun isWorkflowNameTaken(name: String, excludeWorkflowId: String? = null): Boolean {
        return workflows.any { workflow ->
            workflow.name.equals(name, ignoreCase = true) &&
            (excludeWorkflowId == null || workflow.id != excludeWorkflowId)
        }
    }

    /**
     * Generate a unique name for duplicating a workflow.
     * Returns "Name 1", "Name 2", etc. until a unique name is found.
     */
    fun generateUniqueDuplicateName(baseName: String): String {
        var counter = 1
        var candidateName = "$baseName $counter"
        while (isWorkflowNameTaken(candidateName)) {
            counter++
            candidateName = "$baseName $counter"
        }
        return candidateName
    }

    /**
     * Duplicate a workflow (built-in or user) as a new user workflow.
     * The duplicate is always a user workflow that can be edited.
     */
    fun duplicateWorkflow(
        sourceWorkflowId: String,
        newName: String,
        newDescription: String
    ): Result<Workflow> {
        val sourceWorkflow = workflows.find { it.id == sourceWorkflowId }
            ?: return Result.failure(Exception("Source workflow not found"))

        // Validate new name
        val nameError = validateWorkflowName(newName)
        if (nameError != null) {
            return Result.failure(Exception(nameError))
        }

        // Check for duplicate name
        if (isWorkflowNameTaken(newName)) {
            return Result.failure(Exception("A workflow with this name already exists"))
        }

        // Generate filename for the new workflow
        val filename = generateFilename(sourceWorkflow.type, newName)

        // Parse and update the JSON with new name and description
        val json = try {
            JSONObject(sourceWorkflow.jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse source workflow"))
        }

        // Update metadata in the JSON
        if (json.has("name")) {
            json.put("name", newName)
        }
        if (json.has("description")) {
            json.put("description", newDescription)
        }

        val updatedJsonContent = json.toString(2)

        // Save the new workflow file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(updatedJsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Create the new workflow entry with a random UUID
        val newId = UuidUtils.generateRandomId()
        val newWorkflow = Workflow(
            id = newId,
            name = newName,
            description = newDescription,
            jsonContent = updatedJsonContent,
            type = sourceWorkflow.type,
            isBuiltIn = false,
            defaults = sourceWorkflow.defaults
        )

        // Save metadata
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", newId)
            put("filename", filename)
            put("name", newName)
            put("description", newDescription)
            put("type", sourceWorkflow.type.name)
            put("defaults", WorkflowDefaults.toJson(newWorkflow.defaults))
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        // Add to in-memory list
        workflows.add(newWorkflow)

        DebugLogger.i(TAG, "Duplicated workflow: ${sourceWorkflow.name} -> $newName (id: $newId)")

        return Result.success(newWorkflow)
    }

    /**
     * Export a workflow to ComfyUI format (just the nodes, without our wrapper metadata).
     * This is the inverse of importing - strips name, description, defaults, fieldMappings
     * and returns only the raw ComfyUI workflow JSON.
     *
     * @return Result containing the JSON string in ComfyUI format, or error
     */
    fun exportWorkflowToComfyUIFormat(workflowId: String): Result<String> {
        val workflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        val json = try {
            JSONObject(workflow.jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse workflow JSON"))
        }

        // Extract the nodes object (ComfyUI format is just the nodes)
        val nodesJson = if (json.has("nodes") && json.optJSONObject("nodes") != null) {
            json.getJSONObject("nodes")
        } else {
            // Already in raw format
            json
        }

        // Replace any placeholders ({{placeholder}}) with default values if available
        val defaults = workflow.defaults
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys()) {
                val value = inputs.opt(inputKey)
                if (value is String && value.startsWith("{{") && value.endsWith("}}")) {
                    // This is a placeholder, try to replace with default
                    val placeholderName = value.substring(2, value.length - 2)
                    val defaultValue = getDefaultValueForPlaceholder(placeholderName, defaults)
                    if (defaultValue != null) {
                        inputs.put(inputKey, defaultValue)
                    }
                }
            }
        }

        DebugLogger.i(TAG, "Exported workflow to ComfyUI format: ${workflow.name}")

        return Result.success(nodesJson.toString(2))
    }

    /**
     * Get default value for a placeholder name.
     */
    private fun getDefaultValueForPlaceholder(placeholder: String, defaults: WorkflowDefaults): Any? {
        return when (placeholder) {
            "width" -> defaults.width
            "height" -> defaults.height
            "steps" -> defaults.steps
            "cfg" -> defaults.cfg
            "sampler_name", "sampler" -> defaults.samplerName
            "scheduler" -> defaults.scheduler
            "denoise" -> 1.0  // Common default
            "positive_prompt", "positive_text" -> ""
            "negative_prompt", "negative_text" -> defaults.negativePrompt ?: ""
            "seed" -> -1  // Random seed
            "batch_size" -> 1  // Default batch size
            "megapixels" -> defaults.megapixels
            "length" -> defaults.length
            "frame_rate", "fps" -> defaults.frameRate
            else -> null
        }
    }

    /**
     * Generate a sanitized filename for export (without type prefix).
     */
    fun generateExportFilename(workflowName: String): String {
        val sanitized = workflowName
            .replace(Regex("[^a-zA-Z0-9 _\\-]"), "")
            .replace(Regex("\\s+"), "_")
            .trim('_')
        return "$sanitized.json"
    }

    /**
     * Generate filename from workflow type and name
     */
    fun generateFilename(type: WorkflowType, name: String): String {
        val prefix = PREFIX_TO_TYPE.entries.find { it.value == type }?.key ?: "tti_unet_"
        val sanitizedName = name.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return "${prefix}${sanitizedName}.json"
    }

    /**
     * Check if a filename already exists in user workflows
     */
    fun isFilenameExists(filename: String): Boolean {
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        return File(dir, filename).exists()
    }

    /**
     * Validate workflow name format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowName(name: String): String? {
        return ValidationUtils.validateWorkflowName(name, applicationContext)
    }

    /**
     * Validate workflow description format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowDescription(description: String): String? {
        return ValidationUtils.validateWorkflowDescription(description, applicationContext)
    }

    /**
     * Add a user workflow with explicit type and field mappings
     * This is used for the new upload flow where the user selects the type
     */
    fun addUserWorkflowWithMapping(
        name: String,
        description: String,
        jsonContent: String,
        type: WorkflowType,
        fieldMappings: Map<String, Pair<String, String>>  // fieldKey -> (nodeId, inputKey)
    ): Result<Workflow> {
        // Parse JSON
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message}"))
        }

        // Check if already wrapped
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null
        val nodesJson = if (isWrapped) json.getJSONObject("nodes") else json

        // Extract original values before replacing with placeholders to create defaults
        val extractedDefaults = mutableMapOf<String, Any>()
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val originalValue = inputsJson.get(inputKey)
                    // Only capture numeric and string values as defaults (not model names, but include prompts and params)
                    if (originalValue is Number || (originalValue is String && fieldKey in listOf(
                            "width", "height", "steps", "cfg", "sampler_name", "scheduler",
                            "megapixels", "length", "frame_rate", "negative_text"
                        ))) {
                        extractedDefaults[fieldKey] = originalValue
                    }
                }
            }
        }

        // Apply field mappings - replace values with placeholders
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    // Convert input key to proper placeholder name
                    // e.g., "text" -> "positive_prompt", "ckpt_name" -> "checkpoint"
                    val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
                    inputsJson.put(inputKey, "{{$placeholderName}}")
                }
            }
        }

        // Remove placeholders for unmapped optional fields
        // This prevents fields like {{highnoise_lora_name}} from appearing as "UI: ..." when not mapped
        val mappedFieldKeys = fieldMappings.keys
        val allOptionalKeys = TemplateKeyRegistry.getOptionalKeysForType(type)
        val unmappedOptionalKeys = allOptionalKeys - mappedFieldKeys
        val placeholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys().asSequence().toList()) {
                val value = inputs.optString(inputKey, "")
                val placeholderMatch = placeholderRegex.find(value)
                if (placeholderMatch != null) {
                    val placeholderName = placeholderMatch.groupValues[1]
                    // Convert placeholder name to field key before checking
                    val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                    // Check if this field key corresponds to an unmapped optional field
                    if (fieldKey in unmappedOptionalKeys) {
                        // Replace placeholder with empty string so it shows as unmapped in editor
                        inputs.put(inputKey, "")
                    }
                }
            }
        }

        // Auto-detect capability flags from workflow structure
        val hasDualClip = nodesJson.keys().asSequence().any { nodeId ->
            nodesJson.optJSONObject(nodeId)?.optString("class_type") == "DualCLIPLoader"
        }
        val hasBasicGuider = nodesJson.keys().asSequence().any { nodeId ->
            nodesJson.optJSONObject(nodeId)?.optString("class_type") == "BasicGuider"
        }

        // Create defaults object from extracted values with auto-detected capability flags
        // and field presence flags based on which fields are mapped
        val defaults = WorkflowDefaults(
            width = (extractedDefaults["width"] as? Number)?.toInt(),
            height = (extractedDefaults["height"] as? Number)?.toInt(),
            steps = (extractedDefaults["steps"] as? Number)?.toInt(),
            cfg = (extractedDefaults["cfg"] as? Number)?.toFloat(),
            samplerName = extractedDefaults["sampler_name"] as? String,
            scheduler = extractedDefaults["scheduler"] as? String,
            negativePrompt = extractedDefaults["negative_text"] as? String,
            megapixels = (extractedDefaults["megapixels"] as? Number)?.toFloat(),
            length = (extractedDefaults["length"] as? Number)?.toInt(),
            frameRate = (extractedDefaults["frame_rate"] as? Number)?.toInt(),
            // Capability flags for Flux-style workflows
            hasNegativePrompt = !hasBasicGuider && "negative_text" in mappedFieldKeys,
            hasCfg = !hasBasicGuider && "cfg" in mappedFieldKeys,
            hasDualClip = hasDualClip,
            // Field presence flags - true only if the field is mapped
            hasWidth = "width" in mappedFieldKeys,
            hasHeight = "height" in mappedFieldKeys,
            hasSteps = "steps" in mappedFieldKeys,
            hasSamplerName = "sampler_name" in mappedFieldKeys,
            hasScheduler = "scheduler" in mappedFieldKeys,
            hasMegapixels = "megapixels" in mappedFieldKeys,
            hasLength = "length" in mappedFieldKeys,
            hasFrameRate = "fps" in mappedFieldKeys || "frame_rate" in mappedFieldKeys,
            hasVaeName = "vae_name" in mappedFieldKeys,
            hasClipName = "clip_name" in mappedFieldKeys || ("clip_name1" in mappedFieldKeys && "clip_name2" in mappedFieldKeys),
            hasLoraName = "lora_name" in mappedFieldKeys,
            // Dual-UNET/LoRA flags for video workflows
            hasLownoiseUnet = "lownoise_unet_name" in mappedFieldKeys,
            hasHighnoiseLora = "highnoise_lora_name" in mappedFieldKeys,
            hasLownoiseLora = "lownoise_lora_name" in mappedFieldKeys,
            // Model presence flags
            hasCheckpointName = "ckpt_name" in mappedFieldKeys,
            hasUnetName = "unet_name" in mappedFieldKeys,
            hasHighnoiseUnet = "highnoise_unet_name" in mappedFieldKeys,
            // ITE reference image flags - detect from workflow content
            hasReferenceImage1 = jsonContent.contains("reference_image_1") || jsonContent.contains("reference_1"),
            hasReferenceImage2 = jsonContent.contains("reference_image_2") || jsonContent.contains("reference_2")
        )

        // Create wrapped JSON with metadata and defaults
        val finalJson = JSONObject().apply {
            put("name", name)
            put("description", description)
            // Add defaults if any were extracted
            val defaultsJson = WorkflowDefaults.toJson(defaults)
            if (defaultsJson.length() > 0) {
                put("defaults", defaultsJson)
            }
            put("nodes", nodesJson)
            // Store field mappings for reference
            if (fieldMappings.isNotEmpty()) {
                put("fieldMappings", JSONObject().apply {
                    fieldMappings.forEach { (fieldKey, mapping) ->
                        put(fieldKey, JSONObject().apply {
                            put("nodeId", mapping.first)
                            put("inputKey", mapping.second)
                        })
                    }
                })
            }
        }

        // Generate filename
        val filename = generateFilename(type, name)

        // Check for duplicate filename
        if (isFilenameExists(filename)) {
            return Result.failure(Exception("Workflow with this name already exists"))
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Save metadata with UUID
        val id = UuidUtils.generateRandomId()
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("filename", filename)
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        val workflow = Workflow(id, name, description, finalJson.toString(2), type, isBuiltIn = false, defaults)
        workflows.add(workflow)

        return Result.success(workflow)
    }

    /**
     * Update an existing user workflow's JSON structure with new field mappings.
     * Preserves the workflow's name, description, and type.
     * @return Result with the updated Workflow or error message
     */
    fun updateUserWorkflowWithMapping(
        workflowId: String,
        jsonContent: String,
        fieldMappings: Map<String, Pair<String, String>>  // fieldKey -> (nodeId, inputKey)
    ): Result<Workflow> {
        // Find existing workflow
        val existingWorkflow = workflows.find { it.id == workflowId }
            ?: return Result.failure(Exception("Workflow not found"))

        if (existingWorkflow.isBuiltIn) {
            return Result.failure(Exception("Cannot edit built-in workflows"))
        }

        // Parse JSON
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message}"))
        }

        // Check if already wrapped
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null
        val nodesJson = if (isWrapped) json.getJSONObject("nodes") else json

        // Extract original values before replacing with placeholders to create defaults
        val extractedDefaults = mutableMapOf<String, Any>()
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val originalValue = inputsJson.get(inputKey)
                    if (originalValue is Number || (originalValue is String && fieldKey in listOf(
                            "width", "height", "steps", "cfg", "sampler_name", "scheduler",
                            "megapixels", "length", "frame_rate", "negative_text"
                        ))) {
                        extractedDefaults[fieldKey] = originalValue
                    }
                }
            }
        }

        // Apply field mappings - replace values with placeholders
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
                    inputsJson.put(inputKey, "{{$placeholderName}}")
                }
            }
        }

        // Remove placeholders for unmapped optional fields
        // This prevents fields like {{highnoise_lora_name}} from appearing as "UI: ..." when not mapped
        val mappedFieldKeys = fieldMappings.keys
        val allOptionalKeys = TemplateKeyRegistry.getOptionalKeysForType(existingWorkflow.type)
        val unmappedOptionalKeys = allOptionalKeys - mappedFieldKeys
        val placeholderRegex = Regex("\\{\\{(\\w+)\\}\\}")

        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            for (inputKey in inputs.keys().asSequence().toList()) {
                val value = inputs.optString(inputKey, "")
                val placeholderMatch = placeholderRegex.find(value)
                if (placeholderMatch != null) {
                    val placeholderName = placeholderMatch.groupValues[1]
                    // Convert placeholder name to field key before checking
                    val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                    // Check if this field key corresponds to an unmapped optional field
                    if (fieldKey in unmappedOptionalKeys) {
                        // Replace placeholder with empty string so it shows as unmapped in editor
                        inputs.put(inputKey, "")
                    }
                }
            }
        }

        // Auto-detect capability flags from workflow structure
        val hasDualClip = nodesJson.keys().asSequence().any { nodeId ->
            nodesJson.optJSONObject(nodeId)?.optString("class_type") == "DualCLIPLoader"
        }
        val hasBasicGuider = nodesJson.keys().asSequence().any { nodeId ->
            nodesJson.optJSONObject(nodeId)?.optString("class_type") == "BasicGuider"
        }

        // Create defaults object from extracted values with auto-detected capability flags
        // and field presence flags based on which fields are mapped
        val defaults = WorkflowDefaults(
            width = (extractedDefaults["width"] as? Number)?.toInt(),
            height = (extractedDefaults["height"] as? Number)?.toInt(),
            steps = (extractedDefaults["steps"] as? Number)?.toInt(),
            cfg = (extractedDefaults["cfg"] as? Number)?.toFloat(),
            samplerName = extractedDefaults["sampler_name"] as? String,
            scheduler = extractedDefaults["scheduler"] as? String,
            negativePrompt = extractedDefaults["negative_text"] as? String,
            megapixels = (extractedDefaults["megapixels"] as? Number)?.toFloat(),
            length = (extractedDefaults["length"] as? Number)?.toInt(),
            frameRate = (extractedDefaults["frame_rate"] as? Number)?.toInt(),
            // Capability flags for Flux-style workflows
            hasNegativePrompt = !hasBasicGuider && "negative_text" in mappedFieldKeys,
            hasCfg = !hasBasicGuider && "cfg" in mappedFieldKeys,
            hasDualClip = hasDualClip,
            // Field presence flags - true only if the field is mapped
            hasWidth = "width" in mappedFieldKeys,
            hasHeight = "height" in mappedFieldKeys,
            hasSteps = "steps" in mappedFieldKeys,
            hasSamplerName = "sampler_name" in mappedFieldKeys,
            hasScheduler = "scheduler" in mappedFieldKeys,
            hasMegapixels = "megapixels" in mappedFieldKeys,
            hasLength = "length" in mappedFieldKeys,
            hasFrameRate = "fps" in mappedFieldKeys || "frame_rate" in mappedFieldKeys,
            hasVaeName = "vae_name" in mappedFieldKeys,
            hasClipName = "clip_name" in mappedFieldKeys || ("clip_name1" in mappedFieldKeys && "clip_name2" in mappedFieldKeys),
            hasLoraName = "lora_name" in mappedFieldKeys,
            // Dual-UNET/LoRA flags for video workflows
            hasLownoiseUnet = "lownoise_unet_name" in mappedFieldKeys,
            hasHighnoiseLora = "highnoise_lora_name" in mappedFieldKeys,
            hasLownoiseLora = "lownoise_lora_name" in mappedFieldKeys,
            // Model presence flags
            hasCheckpointName = "ckpt_name" in mappedFieldKeys,
            hasUnetName = "unet_name" in mappedFieldKeys,
            hasHighnoiseUnet = "highnoise_unet_name" in mappedFieldKeys,
            // ITE reference image flags - detect from workflow content
            hasReferenceImage1 = jsonContent.contains("reference_image_1") || jsonContent.contains("reference_1"),
            hasReferenceImage2 = jsonContent.contains("reference_image_2") || jsonContent.contains("reference_2")
        )

        // Create wrapped JSON with original metadata preserved
        val finalJson = JSONObject().apply {
            put("name", existingWorkflow.name)
            put("description", existingWorkflow.description)
            val defaultsJson = WorkflowDefaults.toJson(defaults)
            if (defaultsJson.length() > 0) {
                put("defaults", defaultsJson)
            }
            put("nodes", nodesJson)
            if (fieldMappings.isNotEmpty()) {
                put("fieldMappings", JSONObject().apply {
                    fieldMappings.forEach { (fieldKey, mapping) ->
                        put(fieldKey, JSONObject().apply {
                            put("nodeId", mapping.first)
                            put("inputKey", mapping.second)
                        })
                    }
                })
            }
        }

        // Get filename from preferences
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null)
            ?: return Result.failure(Exception("Workflow metadata not found"))

        var filename: String? = null
        val metadataArray = JSONArray(metadataJson)
        for (i in 0 until metadataArray.length()) {
            val metadata = metadataArray.getJSONObject(i)
            if (metadata.getString("id") == workflowId) {
                filename = metadata.getString("filename")
                break
            }
        }

        if (filename == null) {
            return Result.failure(Exception("Workflow file not found"))
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save: ${e.message}"))
        }

        // Update in-memory list
        val workflowIndex = workflows.indexOfFirst { it.id == workflowId }
        if (workflowIndex >= 0) {
            val updatedWorkflow = existingWorkflow.copy(
                jsonContent = finalJson.toString(2),
                defaults = defaults
            )
            workflows[workflowIndex] = updatedWorkflow
            return Result.success(updatedWorkflow)
        }

        return Result.failure(Exception("Failed to update workflow list"))
    }

    /**
     * Add a user workflow
     * Auto-detects workflow type from content and wraps raw ComfyUI workflows if needed
     * @return Result with the created Workflow or error message
     */
    fun addUserWorkflow(
        filename: String,
        name: String,
        description: String,
        jsonContent: String
    ): Result<Workflow> {
        // Parse JSON to check format
        val json = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid JSON: ${e.message ?: ""}"))
        }

        // Check if already wrapped (has "nodes" object at top level)
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null

        // Auto-detect type from content
        val type = detectWorkflowType(jsonContent)
            ?: return Result.failure(Exception("Cannot determine workflow type"))

        // Wrap if needed
        val finalJson = if (isWrapped) {
            jsonContent
        } else {
            // Wrap raw workflow: {"name": "...", "description": "...", "nodes": {...}}
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("nodes", json)
            }.toString(2)
        }

        // Validate workflow - first try placeholder-based validation (for our wrapped format),
        // then try key-based validation (for raw ComfyUI workflows)
        val placeholderValidation = validateWorkflow(finalJson, type)
        val keyValidation = if (placeholderValidation !is WorkflowValidationResult.Success) {
            validateWorkflowKeys(finalJson, type)
        } else {
            placeholderValidation
        }

        // Use key validation result if placeholder validation failed
        val validationResult = if (placeholderValidation is WorkflowValidationResult.Success) {
            placeholderValidation
        } else {
            keyValidation
        }

        if (validationResult !is WorkflowValidationResult.Success) {
            val errorMessage = when (validationResult) {
                is WorkflowValidationResult.InvalidJson -> validationResult.message
                is WorkflowValidationResult.MissingPlaceholders ->
                    "Missing placeholders: ${validationResult.missing.joinToString(", ")}"
                is WorkflowValidationResult.MissingKeys ->
                    "Missing inputs: ${validationResult.missing.joinToString(", ")}"
                else -> "Unknown error"
            }
            return Result.failure(Exception(errorMessage))
        }

        // Generate filename with correct prefix if not already prefixed
        val prefixedFilename = if (parseWorkflowType(filename) != null) {
            filename
        } else {
            // Add prefix based on detected type
            val prefix = PREFIX_TO_TYPE.entries.find { it.value == type }?.key ?: "tti_unet_"
            prefix + filename.substringBeforeLast(".") + ".json"
        }

        // Save file
        val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, prefixedFilename)
        try {
            file.writeText(finalJson)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save workflow: ${e.message ?: ""}"))
        }

        // Save metadata
        val id = UuidUtils.generateRandomId()
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null)
        val metadataArray = if (existingJson != null) JSONArray(existingJson) else JSONArray()

        val newMetadata = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("filename", prefixedFilename)
        }
        metadataArray.put(newMetadata)

        prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()

        val workflow = Workflow(id, name, description, finalJson, type, isBuiltIn = false)
        workflows.add(workflow)

        return Result.success(workflow)
    }

    /**
     * Delete a user workflow
     */
    fun deleteUserWorkflow(workflowId: String): Boolean {
        val workflow = workflows.find { it.id == workflowId }
        if (workflow == null || workflow.isBuiltIn) return false

        // Remove from list
        workflows.removeAll { it.id == workflowId }

        // Remove file
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return false

        try {
            val metadataArray = JSONArray(existingJson)
            var filename: String? = null
            val newArray = JSONArray()

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                if (metadata.getString("id") == workflowId) {
                    filename = metadata.getString("filename")
                } else {
                    newArray.put(metadata)
                }
            }

            // Delete file
            filename?.let {
                val file = File(applicationContext.filesDir, "$USER_WORKFLOWS_DIR/$it")
                file.delete()
            }

            // Update preferences
            prefs.edit().putString(USER_WORKFLOWS_KEY, newArray.toString()).apply()

            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Clear all user-uploaded workflows
     */
    fun clearAllUserWorkflows(): Boolean {
        try {
            // Remove all user workflows from the list
            workflows.removeAll { !it.isBuiltIn }

            // Delete all files in user workflows directory
            val dir = File(applicationContext.filesDir, USER_WORKFLOWS_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            // Clear preferences
            val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove(USER_WORKFLOWS_KEY).apply()

            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Update user workflow metadata (name and description)
     */
    fun updateUserWorkflowMetadata(workflowId: String, name: String, description: String): Boolean {
        val workflowIndex = workflows.indexOfFirst { it.id == workflowId }
        if (workflowIndex == -1) return false

        val workflow = workflows[workflowIndex]
        if (workflow.isBuiltIn) return false

        // Update in list
        workflows[workflowIndex] = workflow.copy(name = name, description = description)

        // Update in preferences
        val prefs = applicationContext.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return false

        try {
            val metadataArray = JSONArray(existingJson)

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                if (metadata.getString("id") == workflowId) {
                    metadata.put("name", name)
                    metadata.put("description", description)
                    break
                }
            }

            prefs.edit().putString(USER_WORKFLOWS_KEY, metadataArray.toString()).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get all workflows
     */
    fun getAllWorkflows(): List<Workflow> = workflows.toList()

    /**
     * Get workflows by type
     */
    fun getWorkflowsByType(type: WorkflowType): List<Workflow> {
        return workflows.filter { it.type == type }.sortedBy { it.name.lowercase() }
    }

    /**
     * Check if a workflow can be deleted (not built-in)
     */
    fun canDeleteWorkflow(workflowId: String): Boolean {
        return workflows.find { it.id == workflowId }?.isBuiltIn == false
    }

    /**
     * Get list of text-to-image checkpoint workflow names for dropdown
     */
    fun getCheckpointWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTI_CHECKPOINT }.map { it.name }
    }

    /**
     * Get list of text-to-image UNET workflow names for dropdown
     */
    fun getUNETWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTI_UNET }.map { it.name }
    }

    /**
     * Get list of Image-to-image checkpoint workflow names for dropdown
     */
    fun getImageToImageCheckpointWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITI_CHECKPOINT }.map { it.name }
    }

    /**
     * Get list of Image-to-image UNET workflow names for dropdown
     */
    fun getImageToImageUNETWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITI_UNET }.map { it.name }
    }

    /**
     * Get list of text-to-video UNET workflow names for dropdown
     */
    fun getVideoUNETWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.TTV_UNET }.map { it.name }
    }

    /**
     * Get list of image-to-video UNET workflow names for dropdown
     */
    fun getImageToVideoUNETWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITV_UNET }.map { it.name }
    }

    /**
     * Get list of Image-to-Image Editing UNET workflow names for dropdown
     */
    fun getImageEditingUNETWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.ITE_UNET }.map { it.name }
    }

    /**
     * Get workflow by name
     */
    fun getWorkflowByName(name: String): Workflow? {
        return workflows.find { it.name == name }
    }

    /**
     * Get workflow by ID
     */
    fun getWorkflowById(id: String): Workflow? {
        return workflows.find { it.id == id }
    }

    /**
     * Get workflow defaults by workflow name
     */
    fun getWorkflowDefaults(workflowName: String): WorkflowDefaults? {
        return getWorkflowByName(workflowName)?.defaults
    }

    /**
     * Get workflow defaults by workflow ID
     */
    fun getWorkflowDefaultsById(workflowId: String): WorkflowDefaults? {
        return getWorkflowById(workflowId)?.defaults
    }

    /**
     * Escape special characters in a string for safe JSON insertion
     */
    private fun escapeForJson(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
    }

    /**
     * Apply node attribute edits to a workflow JSON.
     * This modifies the input values in specific nodes based on user edits.
     *
     * @param workflowJson The workflow JSON string (with or without "nodes" wrapper)
     * @param edits Map of nodeId -> (inputName -> value)
     * @return Modified workflow JSON with edits applied
     */
    fun applyNodeAttributeEdits(
        workflowJson: String,
        edits: Map<String, Map<String, Any>>
    ): String {
        if (edits.isEmpty()) return workflowJson

        return try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: json

            for ((nodeId, nodeEdits) in edits) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                for ((inputName, value) in nodeEdits) {
                    // Only update if the input exists and is not a connection
                    if (inputs.has(inputName)) {
                        val currentValue = inputs.opt(inputName)
                        // Don't overwrite connections (JSONArray format)
                        if (currentValue !is JSONArray) {
                            when (value) {
                                is String -> inputs.put(inputName, value)
                                is Int -> inputs.put(inputName, value)
                                is Long -> inputs.put(inputName, value.toInt())
                                is Float -> inputs.put(inputName, value.toDouble())
                                is Double -> inputs.put(inputName, value)
                                is Boolean -> inputs.put(inputName, value)
                                else -> inputs.put(inputName, value.toString())
                            }
                        }
                    }
                }
            }

            json.toString()
        } catch (e: Exception) {
            workflowJson
        }
    }

    // Bypass handling

    /**
     * Infer the expected data type from an input name using ComfyUI naming conventions.
     * This allows type-based matching when rewiring connections around bypassed nodes.
     */
    private fun inferTypeFromInputName(inputName: String): String? {
        val lowerName = inputName.lowercase()
        return when {
            lowerName in listOf("samples", "latent_image", "latent", "latent_images") -> "LATENT"
            lowerName in listOf("model", "unet") || lowerName.startsWith("model") -> "MODEL"
            lowerName in listOf("clip", "clip_l", "clip_g") || lowerName.startsWith("clip") -> "CLIP"
            lowerName == "vae" -> "VAE"
            lowerName in listOf("positive", "negative", "conditioning", "cond") -> "CONDITIONING"
            lowerName in listOf("image", "images", "pixels") -> "IMAGE"
            lowerName == "mask" -> "MASK"
            lowerName == "noise" -> "NOISE"
            else -> null
        }
    }

    /**
     * Find a connection input on a node that matches the expected type.
     * Returns the connection JSONArray [sourceNodeId, outputIndex] or null.
     */
    private fun findConnectionByType(nodeInputs: JSONObject, expectedType: String): JSONArray? {
        val inputKeys = nodeInputs.keys()
        while (inputKeys.hasNext()) {
            val inputKey = inputKeys.next()
            val inputValue = nodeInputs.opt(inputKey)

            // Only consider connections (array format)
            if (inputValue is JSONArray && inputValue.length() >= 2) {
                val inputType = inferTypeFromInputName(inputKey)
                if (inputType == expectedType) {
                    DebugLogger.d(TAG, "findConnectionByType: Found $expectedType input '$inputKey' -> [${inputValue.optString(0)}, ${inputValue.optInt(1)}]")
                    return inputValue
                }
            }
        }
        return null
    }

    /**
     * Follow a connection through any bypassed nodes to find the final non-bypassed source.
     * Uses type inference to match the correct input when traversing bypassed nodes.
     */
    private fun resolveBypassChain(
        nodes: JSONObject,
        sourceNodeId: String,
        sourceOutputIndex: Int,
        expectedType: String,
        bypassedNodeIds: Set<String>,
        depth: Int = 0
    ): JSONArray? {
        if (depth > 10) {
            DebugLogger.w(TAG, "resolveBypassChain: Max depth reached, possible cycle")
            return null
        }

        if (sourceNodeId !in bypassedNodeIds) {
            // Found non-bypassed source
            return JSONArray().apply {
                put(sourceNodeId)
                put(sourceOutputIndex)
            }
        }

        // Source is bypassed - find matching input and follow it
        val bypassedNode = nodes.optJSONObject(sourceNodeId) ?: return null
        val bypassedInputs = bypassedNode.optJSONObject("inputs") ?: return null

        val matchingConnection = findConnectionByType(bypassedInputs, expectedType)
        if (matchingConnection != null) {
            val nextSourceId = matchingConnection.optString(0, "")
            val nextOutputIndex = matchingConnection.optInt(1, 0)
            DebugLogger.d(TAG, "resolveBypassChain: Following $expectedType through bypassed $sourceNodeId to $nextSourceId[$nextOutputIndex]")
            return resolveBypassChain(nodes, nextSourceId, nextOutputIndex, expectedType, bypassedNodeIds, depth + 1)
        }

        DebugLogger.w(TAG, "resolveBypassChain: No $expectedType input found on bypassed node $sourceNodeId")
        return null
    }

    /**
     * Apply bypass logic to a workflow JSON before submission to ComfyUI API.
     * ComfyUI's API doesn't automatically handle the mode property - we need to
     * manually remove bypassed nodes and rewire connections around them.
     *
     * Uses type-inference to correctly match inputs and outputs when rewiring:
     * 1. Infer the expected type from the target node's input name
     * 2. Find the matching type input on the bypassed node
     * 3. Follow the connection chain to the original non-bypassed source
     *
     * @param workflowJson The workflow JSON string (with or without "nodes" wrapper)
     * @return Modified workflow JSON with bypassed nodes removed and connections rewired
     */
    fun applyBypassedNodes(workflowJson: String): String {
        return try {
            val json = JSONObject(workflowJson)
            val hasWrapper = json.has("nodes") && json.optJSONObject("nodes") != null
            val nodes = if (hasWrapper) json.getJSONObject("nodes") else json

            // Find all bypassed nodes (mode=4)
            val bypassedNodeIds = mutableSetOf<String>()
            val nodeIds = nodes.keys().asSequence().toList()
            for (nodeId in nodeIds) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val mode = node.optInt("mode", 0)
                if (mode == 4) {
                    bypassedNodeIds.add(nodeId)
                    DebugLogger.d(TAG, "applyBypassedNodes: Found bypassed node $nodeId (${node.optString("class_type")})")
                }
            }

            if (bypassedNodeIds.isEmpty()) {
                DebugLogger.d(TAG, "applyBypassedNodes: No bypassed nodes found")
                return workflowJson
            }

            // Rewire connections using type-based matching
            for (nodeId in nodeIds) {
                if (nodeId in bypassedNodeIds) continue
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                val inputKeys = inputs.keys().asSequence().toList()
                for (inputKey in inputKeys) {
                    val inputValue = inputs.opt(inputKey)
                    if (inputValue is JSONArray && inputValue.length() >= 2) {
                        val sourceNodeId = inputValue.optString(0, "")

                        if (sourceNodeId in bypassedNodeIds) {
                            // This input references a bypassed node - need to rewire using type matching
                            val expectedType = inferTypeFromInputName(inputKey)

                            if (expectedType != null) {
                                DebugLogger.d(TAG, "applyBypassedNodes: Input '$inputKey' on node $nodeId expects type $expectedType")

                                val resolvedSource = resolveBypassChain(
                                    nodes,
                                    sourceNodeId,
                                    inputValue.optInt(1, 0),
                                    expectedType,
                                    bypassedNodeIds
                                )

                                if (resolvedSource != null) {
                                    DebugLogger.d(TAG, "applyBypassedNodes: Rewiring node $nodeId input '$inputKey' ($expectedType) from bypassed $sourceNodeId to ${resolvedSource.optString(0)}[${resolvedSource.optInt(1)}]")
                                    inputs.put(inputKey, resolvedSource)
                                } else {
                                    DebugLogger.w(TAG, "applyBypassedNodes: Could not find $expectedType source for node $nodeId input '$inputKey' - removing connection")
                                    inputs.remove(inputKey)
                                }
                            } else {
                                DebugLogger.w(TAG, "applyBypassedNodes: Unknown type for input '$inputKey' on node $nodeId - removing connection")
                                inputs.remove(inputKey)
                            }
                        }
                    }
                }
            }

            // Remove bypassed nodes from the JSON
            for (bypassedId in bypassedNodeIds) {
                nodes.remove(bypassedId)
                DebugLogger.d(TAG, "applyBypassedNodes: Removed bypassed node $bypassedId")
            }

            DebugLogger.i(TAG, "applyBypassedNodes: Processed ${bypassedNodeIds.size} bypassed nodes")
            json.toString()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "applyBypassedNodes: Error processing bypass: ${e.message}")
            workflowJson
        }
    }

    // LoRA chain injection

    /**
     * Inject a chain of LoRA loaders into a workflow JSON.
     * LoRAs are chained in order, with each LoRA's output feeding into the next.
     *
     * @param workflowJson The prepared workflow JSON string
     * @param loraChain List of LoRA selections to inject (can be empty)
     * @param workflowType The type of workflow (determines model source node type)
     * @return Modified workflow JSON with LoRA nodes injected, or original if chain is empty
     */
    fun injectLoraChain(
        workflowJson: String,
        loraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        workflowType: WorkflowType
    ): String {
        if (loraChain.isEmpty()) return workflowJson

        try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: return workflowJson

            // Find the model source node
            val modelSourceId = findModelSourceNode(nodes, workflowType) ?: return workflowJson

            // Find all nodes that consume the model output
            val modelConsumers = findModelConsumers(nodes, modelSourceId)
            if (modelConsumers.isEmpty()) return workflowJson

            // Generate unique node IDs for LoRA nodes
            var nextNodeId = generateUniqueNodeId(nodes)

            // Track the current model source (starts with original, updates as we chain)
            var currentModelSource = modelSourceId
            val loraNodeIds = mutableListOf<String>()

            // Create LoRA nodes in chain
            for (lora in loraChain) {
                val loraNodeId = nextNodeId.toString()
                val loraNode = createLoraNode(lora.name, lora.strength, currentModelSource, 0)
                nodes.put(loraNodeId, loraNode)
                loraNodeIds.add(loraNodeId)
                currentModelSource = loraNodeId
                nextNodeId++
            }

            // Update all original model consumers to reference the last LoRA's output
            val lastLoraId = loraNodeIds.last()
            for ((consumerId, inputKey) in modelConsumers) {
                val consumerNode = nodes.optJSONObject(consumerId) ?: continue
                val inputs = consumerNode.optJSONObject("inputs") ?: continue
                // Update the model reference to point to last LoRA
                inputs.put(inputKey, JSONArray().apply {
                    put(lastLoraId)
                    put(0)
                })
            }

            return json.toString()
        } catch (e: Exception) {
            return workflowJson
        }
    }

    /**
     * Inject additional LoRAs into a video workflow AFTER the mandatory LightX2V LoRAs.
     * This handles the two-chain structure of video workflows (high noise and low noise).
     *
     * @param workflowJson The prepared workflow JSON string
     * @param additionalLoraChain List of additional LoRA selections
     * @param isHighNoise Whether to inject into the high noise chain (true) or low noise chain (false)
     * @return Modified workflow JSON with additional LoRA nodes injected
     */
    fun injectAdditionalVideoLoras(
        workflowJson: String,
        additionalLoraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        isHighNoise: Boolean
    ): String {
        if (additionalLoraChain.isEmpty()) return workflowJson

        try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: return workflowJson

            // For video workflows, we need to find the existing LoraLoaderModelOnly node
            // that feeds into ModelSamplingSD3 and chain after it
            val (existingLoraId, modelSamplingId) = findVideoLoraChainEnd(nodes, isHighNoise)
                ?: return workflowJson

            // Generate unique node IDs
            var nextNodeId = generateUniqueNodeId(nodes)

            // Track the current model source (starts with existing LoRA output)
            var currentModelSource = existingLoraId
            val loraNodeIds = mutableListOf<String>()

            // Create additional LoRA nodes in chain
            for (lora in additionalLoraChain) {
                val loraNodeId = nextNodeId.toString()
                val loraNode = createLoraNode(lora.name, lora.strength, currentModelSource, 0)
                nodes.put(loraNodeId, loraNode)
                loraNodeIds.add(loraNodeId)
                currentModelSource = loraNodeId
                nextNodeId++
            }

            // Update ModelSamplingSD3 to reference the last additional LoRA
            val modelSamplingNode = nodes.optJSONObject(modelSamplingId)
            val modelSamplingInputs = modelSamplingNode?.optJSONObject("inputs")
            modelSamplingInputs?.put("model", JSONArray().apply {
                put(loraNodeIds.last())
                put(0)
            })

            return json.toString()
        } catch (e: Exception) {
            return workflowJson
        }
    }

    /**
     * Find the model source node in a workflow.
     */
    private fun findModelSourceNode(nodes: JSONObject, workflowType: WorkflowType): String? {
        val targetClassType = when (workflowType) {
            WorkflowType.TTI_CHECKPOINT, WorkflowType.ITI_CHECKPOINT -> "CheckpointLoaderSimple"
            else -> "UNETLoader"
        }

        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodes.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            if (classType == targetClassType) {
                return nodeId
            }
        }
        return null
    }

    /**
     * Find all nodes that consume the model output from a source node.
     * Returns list of (consumerId, inputKey) pairs.
     */
    private fun findModelConsumers(nodes: JSONObject, modelSourceId: String): List<Pair<String, String>> {
        val consumers = mutableListOf<Pair<String, String>>()

        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodes.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            val inputKeys = inputs.keys()
            while (inputKeys.hasNext()) {
                val inputKey = inputKeys.next()
                val inputValue = inputs.opt(inputKey)

                // Check if this input references the model source (array format: ["nodeId", outputIndex])
                if (inputValue is JSONArray && inputValue.length() >= 2) {
                    val refNodeId = inputValue.optString(0, "")
                    val refOutputIndex = inputValue.optInt(1, -1)
                    // Model output is always index 0
                    if (refNodeId == modelSourceId && refOutputIndex == 0) {
                        consumers.add(Pair(nodeId, inputKey))
                    }
                }
            }
        }
        return consumers
    }

    /**
     * Find the end of the existing LoRA chain in a video workflow.
     * Returns (lastLoraNodeId, modelSamplingNodeId) or null if not found.
     *
     * In video workflows:
     * - High noise path: UNETLoader → LoRA → ModelSamplingSD3 → KSamplerAdvanced (start_at_step=0)
     * - Low noise path: UNETLoader → LoRA → ModelSamplingSD3 → KSamplerAdvanced (start_at_step>0)
     */
    private fun findVideoLoraChainEnd(nodes: JSONObject, isHighNoise: Boolean): Pair<String, String>? {
        // Collect all relevant nodes by type
        val modelSamplingNodes = mutableListOf<String>()
        val loraNodes = mutableMapOf<String, JSONObject>()
        val kSamplerNodes = mutableMapOf<String, JSONObject>()

        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodes.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            when (classType) {
                "ModelSamplingSD3" -> modelSamplingNodes.add(nodeId)
                "LoraLoaderModelOnly" -> loraNodes[nodeId] = node
                "KSamplerAdvanced" -> kSamplerNodes[nodeId] = node
            }
        }

        // For each ModelSamplingSD3, find which LoRA feeds into it and which KSampler consumes it
        for (modelSamplingId in modelSamplingNodes) {
            val modelSamplingNode = nodes.optJSONObject(modelSamplingId) ?: continue
            val inputs = modelSamplingNode.optJSONObject("inputs") ?: continue
            val modelInput = inputs.optJSONArray("model") ?: continue

            val sourceNodeId = modelInput.optString(0, "")
            if (sourceNodeId !in loraNodes) continue

            // Found a ModelSamplingSD3 that takes input from a LoRA
            // Now find which KSamplerAdvanced uses this ModelSamplingSD3
            for ((kSamplerId, kSamplerNode) in kSamplerNodes) {
                val kSamplerInputs = kSamplerNode.optJSONObject("inputs") ?: continue
                val kSamplerModelInput = kSamplerInputs.optJSONArray("model") ?: continue
                val kSamplerModelSource = kSamplerModelInput.optString(0, "")

                if (kSamplerModelSource == modelSamplingId) {
                    // This KSamplerAdvanced uses our ModelSamplingSD3
                    // Check start_at_step to determine if high noise or low noise
                    val startAtStep = kSamplerInputs.optInt("start_at_step", -1)
                    val isHighNoisePath = (startAtStep == 0)

                    if (isHighNoisePath == isHighNoise) {
                        // Found the correct path
                        return Pair(sourceNodeId, modelSamplingId)
                    }
                }
            }
        }
        return null
    }

    /**
     * Generate a unique node ID that doesn't conflict with existing nodes.
     */
    private fun generateUniqueNodeId(nodes: JSONObject): Int {
        var maxId = 99 // Start from 100 for injected nodes
        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val numericId = nodeId.toIntOrNull()
            if (numericId != null && numericId > maxId) {
                maxId = numericId
            }
        }
        return maxId + 1
    }

    /**
     * Create a LoraLoaderModelOnly node JSON object.
     */
    private fun createLoraNode(loraName: String, strength: Float, modelSourceId: String, modelOutputIndex: Int): JSONObject {
        return JSONObject().apply {
            put("class_type", "LoraLoaderModelOnly")
            put("inputs", JSONObject().apply {
                put("lora_name", loraName)
                put("strength_model", strength.toDouble())
                put("model", JSONArray().apply {
                    put(modelSourceId)
                    put(modelOutputIndex)
                })
            })
        }
    }

    /**
     * Apply node attribute edits from storage to the processed workflow JSON.
     * This allows users to customize node attributes via the Workflow Editor
     * that will be applied when generating.
     *
     * @param processedJson The workflow JSON after placeholder replacement
     * @param workflowId The workflow ID to load edits for
     * @return The JSON with node attribute edits applied
     */
    private fun applyNodeAttributeEdits(processedJson: String, workflowId: String): String {
        // Load edits from storage
        val serverId = sh.hnet.comfychair.connection.ConnectionManager.currentServerId ?: run {
            DebugLogger.w(TAG, "applyNodeAttributeEdits: No serverId (ConnectionManager.currentServerId is null)")
            return processedJson
        }
        val values = workflowValuesStorage.loadValues(serverId, workflowId) ?: run {
            DebugLogger.d(TAG, "applyNodeAttributeEdits: No saved values for server=$serverId, workflow=$workflowId")
            return processedJson
        }
        val editsJson = values.nodeAttributeEdits ?: run {
            DebugLogger.d(TAG, "applyNodeAttributeEdits: No node edits in saved values for server=$serverId, workflow=$workflowId")
            return processedJson
        }
        val edits = NodeAttributeEdits.fromJson(editsJson)

        if (edits.isEmpty()) return processedJson

        // Parse the workflow JSON
        val json = try {
            JSONObject(processedJson)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to parse workflow JSON for edits: ${e.message}")
            return processedJson
        }

        // Get the nodes object (could be at root level or under "nodes" key)
        val nodesJson = if (json.has("nodes")) {
            json.optJSONObject("nodes") ?: json
        } else {
            json
        }

        // Apply edits to each node
        var modified = false
        edits.edits.forEach { (nodeId, nodeEdits) ->
            val node = nodesJson.optJSONObject(nodeId)
            if (node != null) {
                val inputs = node.optJSONObject("inputs")
                if (inputs != null) {
                    nodeEdits.forEach { (inputName, value) ->
                        // Only apply if input exists (don't add new inputs)
                        if (inputs.has(inputName)) {
                            when (value) {
                                is String -> inputs.put(inputName, value)
                                is Int -> inputs.put(inputName, value)
                                is Long -> inputs.put(inputName, value.toInt())
                                is Float -> inputs.put(inputName, value.toDouble())
                                is Double -> inputs.put(inputName, value)
                                is Boolean -> inputs.put(inputName, value)
                                else -> inputs.put(inputName, value.toString())
                            }
                            modified = true
                            DebugLogger.d(TAG, "Applied edit: node $nodeId, $inputName = $value")
                        }
                    }
                }
            }
        }

        return if (modified) json.toString() else processedJson
    }

    /**
     * Prepare workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String? = null,
        clip1: String? = null,
        clip2: String? = null,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float = 8.0f,
        samplerName: String = "euler",
        scheduler: String = "normal"
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Dimensions: ${width}x${height}, Steps: $steps, CFG: $cfg")
        DebugLogger.d(TAG, "Sampler: $samplerName, Scheduler: $scheduler")
        if (checkpoint.isNotEmpty()) DebugLogger.d(TAG, "Checkpoint: ${Obfuscator.modelName(checkpoint)}")
        if (unet.isNotEmpty()) DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        if (vae.isNotEmpty()) DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        clip?.let { DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(it)}") }
        clip1?.let { DebugLogger.d(TAG, "CLIP1: ${Obfuscator.modelName(it)}") }
        clip2?.let { DebugLogger.d(TAG, "CLIP2: ${Obfuscator.modelName(it)}") }

        val randomSeed = (0..999999999999).random()
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        // Handle single CLIP or dual CLIP
        clip?.let { processedJson = processedJson.replace("{{clip_name}}", escapeForJson(it)) }
        clip1?.let { processedJson = processedJson.replace("{{clip_name1}}", escapeForJson(it)) }
        clip2?.let { processedJson = processedJson.replace("{{clip_name2}}", escapeForJson(it)) }
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        processedJson = processedJson.replace("{{cfg}}", cfg.toString())
        processedJson = processedJson.replace("{{sampler_name}}", samplerName)
        processedJson = processedJson.replace("{{scheduler}}", scheduler)
        // Handle both seed formats: regular KSampler and RandomNoise node
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        // Apply node attribute edits from the Workflow Editor
        return applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
    }

    /**
     * Prepare Image-to-image workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareImageToImageWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        megapixels: Float = 1.0f,
        steps: Int,
        cfg: Float = 8.0f,
        samplerName: String = "euler",
        scheduler: String = "normal",
        imageFilename: String
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing ITI workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Megapixels: $megapixels, Steps: $steps, CFG: $cfg")
        DebugLogger.d(TAG, "Sampler: $samplerName, Scheduler: $scheduler")
        if (checkpoint.isNotEmpty()) DebugLogger.d(TAG, "Checkpoint: ${Obfuscator.modelName(checkpoint)}")
        if (unet.isNotEmpty()) DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        if (vae.isNotEmpty()) DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        if (clip.isNotEmpty()) DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(clip)}")
        DebugLogger.d(TAG, "Source image: ${Obfuscator.filename(imageFilename)}")

        val randomSeed = (0..999999999999).random()
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        processedJson = processedJson.replace("{{ckpt_name}}", escapeForJson(checkpoint))
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        processedJson = processedJson.replace("{{megapixels}}", megapixels.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        processedJson = processedJson.replace("{{cfg}}", cfg.toString())
        processedJson = processedJson.replace("{{sampler_name}}", samplerName)
        processedJson = processedJson.replace("{{scheduler}}", scheduler)
        // Replace source image placeholder (both template and literal formats)
        val escapedImageFilename = escapeForJson(imageFilename)
        processedJson = processedJson.replace("{{image_filename}}", "$escapedImageFilename [input]")
        processedJson = processedJson.replace("uploaded_image.png [input]", "$escapedImageFilename [input]")
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        // Apply node attribute edits from the Workflow Editor
        return applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
    }

    /**
     * Prepare Image-to-Image Editing workflow JSON with actual parameter values (by workflow ID).
     * This is for the QwenImage Edit-style workflows that use reference images instead of masks.
     */
    fun prepareImageEditingWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        unet: String,
        lora: String,
        vae: String,
        clip: String,
        megapixels: Float = 2.0f,
        steps: Int,
        cfg: Float = 1.0f,
        samplerName: String = "euler",
        scheduler: String = "simple",
        sourceImageFilename: String,
        referenceImage1Filename: String? = null,
        referenceImage2Filename: String? = null
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing ITE workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Megapixels: $megapixels, Steps: $steps, CFG: $cfg")
        DebugLogger.d(TAG, "Sampler: $samplerName, Scheduler: $scheduler")
        DebugLogger.d(TAG, "UNET: ${Obfuscator.modelName(unet)}")
        DebugLogger.d(TAG, "LoRA: ${Obfuscator.modelName(lora)}")
        DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}")
        DebugLogger.d(TAG, "CLIP: ${Obfuscator.modelName(clip)}")
        DebugLogger.d(TAG, "Source: ${Obfuscator.filename(sourceImageFilename)}")
        referenceImage1Filename?.let { DebugLogger.d(TAG, "Reference1: ${Obfuscator.filename(it)}") }
        referenceImage2Filename?.let { DebugLogger.d(TAG, "Reference2: ${Obfuscator.filename(it)}") }

        val randomSeed = (0..999999999999).random()
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        // First pass: simple placeholder replacements
        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        processedJson = processedJson.replace("{{unet_name}}", escapeForJson(unet))
        processedJson = processedJson.replace("{{lora_name}}", escapeForJson(lora))
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        processedJson = processedJson.replace("{{megapixels}}", megapixels.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        processedJson = processedJson.replace("{{cfg}}", cfg.toString())
        processedJson = processedJson.replace("{{sampler_name}}", samplerName)
        processedJson = processedJson.replace("{{scheduler}}", scheduler)

        // Replace source image placeholder (both template and literal formats)
        val escapedSourceFilename = escapeForJson(sourceImageFilename)
        processedJson = processedJson.replace("{{image_filename}}", "$escapedSourceFilename [input]")
        processedJson = processedJson.replace("uploaded_image.png [input]", "$escapedSourceFilename [input]")

        // Replace reference images if provided
        if (referenceImage1Filename != null) {
            processedJson = processedJson.replace("reference_image_1.png [input]", "${escapeForJson(referenceImage1Filename)} [input]")
        }
        if (referenceImage2Filename != null) {
            processedJson = processedJson.replace("reference_image_2.png [input]", "${escapeForJson(referenceImage2Filename)} [input]")
        }

        // Handle seed
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        // Second pass: remove unused reference image nodes and their connections
        try {
            val json = JSONObject(processedJson)
            val nodes = json.optJSONObject("nodes") ?: return processedJson

            // Find and remove reference image nodes that weren't provided
            // Also remove connections to those nodes from other nodes
            val nodesToRemove = mutableListOf<String>()
            val nodeIdToImageInput = mutableMapOf<String, String>() // nodeId -> "image2" or "image3"

            // Scan for LoadImage nodes with reference image placeholders
            val nodeIds = nodes.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue
                val classType = node.optString("class_type")

                if (classType == "LoadImage") {
                    val imageName = inputs.optString("image", "")
                    if (imageName == "reference_image_1.png [input]" && referenceImage1Filename == null) {
                        nodesToRemove.add(nodeId)
                        nodeIdToImageInput[nodeId] = "image2"
                    } else if (imageName == "reference_image_2.png [input]" && referenceImage2Filename == null) {
                        nodesToRemove.add(nodeId)
                        nodeIdToImageInput[nodeId] = "image3"
                    }
                }
            }

            // Remove the nodes
            for (nodeId in nodesToRemove) {
                nodes.remove(nodeId)
            }

            // Remove connections to removed nodes from all other nodes
            val allNodeIds = nodes.keys()
            while (allNodeIds.hasNext()) {
                val nodeId = allNodeIds.next()
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                // Check each input for connections to removed nodes
                val inputsToRemove = mutableListOf<String>()
                val inputKeys = inputs.keys()
                while (inputKeys.hasNext()) {
                    val inputKey = inputKeys.next()
                    val inputValue = inputs.opt(inputKey)

                    // Connection format is [nodeId, outputIndex] as JSONArray
                    if (inputValue is JSONArray && inputValue.length() == 2) {
                        val connectedNodeId = inputValue.optString(0)
                        if (connectedNodeId in nodesToRemove) {
                            inputsToRemove.add(inputKey)
                        }
                    }
                }

                // Remove the connections
                for (inputKey in inputsToRemove) {
                    inputs.remove(inputKey)
                }
            }

            // Apply node attribute edits from the Workflow Editor
            return applyNodeAttributeEdits(json.toString(), workflow.id)
        } catch (e: Exception) {
            // If JSON parsing fails, return the string-processed version with edits applied
            return applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
        }
    }

    /**
     * Prepare video workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareVideoWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        highnoiseUnet: String,
        lownoiseUnet: String,
        highnoiseLora: String,
        lownoiseLora: String,
        vae: String,
        clip: String,
        width: Int,
        height: Int,
        length: Int,
        fps: Int = 16
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing TTV workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Dimensions: ${width}x${height}, Length: $length frames, FPS: $fps")
        DebugLogger.d(TAG, "High-noise UNET: ${Obfuscator.modelName(highnoiseUnet)}")
        DebugLogger.d(TAG, "Low-noise UNET: ${Obfuscator.modelName(lownoiseUnet)}")
        DebugLogger.d(TAG, "High-noise LoRA: ${Obfuscator.modelName(highnoiseLora)}")
        DebugLogger.d(TAG, "Low-noise LoRA: ${Obfuscator.modelName(lownoiseLora)}")
        DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}, CLIP: ${Obfuscator.modelName(clip)}")

        val randomSeed = (0..999999999999).random()
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{length}}", length.toString())
        processedJson = processedJson.replace("{{frame_rate}}", fps.toString())
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        // Apply node attribute edits from the Workflow Editor
        return applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
    }

    /**
     * Prepare image-to-video workflow JSON with actual parameter values (by workflow ID)
     */
    fun prepareImageToVideoWorkflowById(
        workflowId: String,
        positivePrompt: String,
        negativePrompt: String = "",
        highnoiseUnet: String,
        lownoiseUnet: String,
        highnoiseLora: String,
        lownoiseLora: String,
        vae: String,
        clip: String,
        width: Int,
        height: Int,
        length: Int,
        fps: Int = 16,
        imageFilename: String
    ): String? {
        val workflow = getWorkflowById(workflowId) ?: return null
        DebugLogger.i(TAG, "Preparing ITV workflow: ${workflow.name} (id: $workflowId)")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(positivePrompt)}")
        DebugLogger.d(TAG, "Dimensions: ${width}x${height}, Length: $length frames, FPS: $fps")
        DebugLogger.d(TAG, "High-noise UNET: ${Obfuscator.modelName(highnoiseUnet)}")
        DebugLogger.d(TAG, "Low-noise UNET: ${Obfuscator.modelName(lownoiseUnet)}")
        DebugLogger.d(TAG, "High-noise LoRA: ${Obfuscator.modelName(highnoiseLora)}")
        DebugLogger.d(TAG, "Low-noise LoRA: ${Obfuscator.modelName(lownoiseLora)}")
        DebugLogger.d(TAG, "VAE: ${Obfuscator.modelName(vae)}, CLIP: ${Obfuscator.modelName(clip)}")
        DebugLogger.d(TAG, "Source image: ${Obfuscator.filename(imageFilename)}")

        val randomSeed = (0..999999999999).random()
        val escapedPositivePrompt = escapeForJson(positivePrompt)
        val escapedNegativePrompt = escapeForJson(negativePrompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{positive_prompt}}", escapedPositivePrompt)
        processedJson = processedJson.replace("{{negative_prompt}}", escapedNegativePrompt)
        processedJson = processedJson.replace("{{highnoise_unet_name}}", escapeForJson(highnoiseUnet))
        processedJson = processedJson.replace("{{lownoise_unet_name}}", escapeForJson(lownoiseUnet))
        processedJson = processedJson.replace("{{highnoise_lora_name}}", escapeForJson(highnoiseLora))
        processedJson = processedJson.replace("{{lownoise_lora_name}}", escapeForJson(lownoiseLora))
        processedJson = processedJson.replace("{{vae_name}}", escapeForJson(vae))
        processedJson = processedJson.replace("{{clip_name}}", escapeForJson(clip))
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{length}}", length.toString())
        processedJson = processedJson.replace("{{frame_rate}}", fps.toString())
        processedJson = processedJson.replace("{{image_filename}}", escapeForJson(imageFilename))
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        // Apply node attribute edits from the Workflow Editor
        return applyBypassedNodes(applyNodeAttributeEdits(processedJson, workflow.id))
    }

}
