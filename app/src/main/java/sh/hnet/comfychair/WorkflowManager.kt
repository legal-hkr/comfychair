package sh.hnet.comfychair

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import java.io.File
import java.io.InputStream

/**
 * Workflow types supported by ComfyChair
 */
enum class WorkflowType {
    TTI_CHECKPOINT,    // Text-to-Image Checkpoint
    TTI_UNET,          // Text-to-Image UNET
    ITI_CHECKPOINT,    // Image-to-Image Checkpoint
    ITI_UNET,          // Image-to-Image UNET
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
 * This class handles:
 * - Loading workflow JSON files from res/raw (built-in) and internal storage (user-uploaded)
 * - Extracting workflow names and descriptions
 * - Validating workflows against required placeholders
 * - Replacing template variables with actual values
 * - Providing workflow data for API calls
 */
class WorkflowManager(private val context: Context) {

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

    companion object {
        private const val USER_WORKFLOWS_PREFS = "UserWorkflowsPrefs"
        private const val USER_WORKFLOWS_KEY = "user_workflows_json"
        private const val USER_WORKFLOWS_DIR = "user_workflows"

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
            WorkflowType.ITI_UNET to listOf("uploaded_image.png [input]")
        )

        // Filename prefix to type mapping
        val PREFIX_TO_TYPE = mapOf(
            "tti_checkpoint_" to WorkflowType.TTI_CHECKPOINT,
            "tti_unet_" to WorkflowType.TTI_UNET,
            "iti_checkpoint_" to WorkflowType.ITI_CHECKPOINT,
            "iti_unet_" to WorkflowType.ITI_UNET,
            "ttv_unet_" to WorkflowType.TTV_UNET,
            "itv_unet_" to WorkflowType.ITV_UNET
        )
    }

    // Available workflows loaded from res/raw and user storage
    private val workflows = mutableListOf<Workflow>()

    init {
        loadAllWorkflows()
    }

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
                val inputStream: InputStream = context.resources.openRawResource(resId)
                val jsonContent = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)

                val name = jsonObject.optString("name", "Unnamed Workflow")
                val description = jsonObject.optString("description", "")
                val id = context.resources.getResourceEntryName(resId)
                val type = parseWorkflowType(id) ?: continue
                val defaults = WorkflowDefaults.fromJson(jsonObject.optJSONObject("defaults"))

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
        val prefs = context.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString(USER_WORKFLOWS_KEY, null) ?: return

        try {
            val metadataArray = JSONArray(metadataJson)
            val dir = File(context.filesDir, USER_WORKFLOWS_DIR)

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

        // Check for Image-to-image indicators (inpainting nodes in ComfyUI)
        val hasImageToImageNodes = classTypes.any { classType ->
            classType.contains("SetLatentNoiseMask", ignoreCase = true) ||
            classType.contains("InpaintModel", ignoreCase = true) ||
            classType.contains("Inpaint", ignoreCase = true)
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

            // Image-to-image with checkpoint
            hasImageToImageNodes && hasCheckpointLoader -> WorkflowType.ITI_CHECKPOINT

            // Image-to-image with UNET
            hasImageToImageNodes && hasUNETLoader -> WorkflowType.ITI_UNET

            // Image-to-image with LoadImage (fallback)
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

        // Collect all input keys from all nodes
        val presentKeys = mutableSetOf<String>()
        for (nodeId in nodesJson.keys()) {
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            for (key in inputs.keys()) {
                presentKeys.add(key)
            }
        }

        // Get required keys for this workflow type, adjusted for workflow structure
        // (excludes graph-traced keys like positive_text/negative_text)
        val requiredKeys = TemplateKeyRegistry.getDirectKeysForWorkflow(type, json)
        val missingKeys = requiredKeys.filter { it !in presentKeys }

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
     */
    fun isWorkflowNameTaken(name: String): Boolean {
        return workflows.any { it.name.equals(name, ignoreCase = true) }
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
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
        return File(dir, filename).exists()
    }

    /**
     * Validate workflow name format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowName(name: String): String? {
        if (name.isBlank()) return context.getString(R.string.error_required)
        if (name.length > 40) return context.getString(R.string.workflow_name_error_too_long)
        val validPattern = Regex("^[a-zA-Z0-9 _\\-\\[\\]()]+$")
        if (!validPattern.matches(name)) {
            return context.getString(R.string.workflow_name_error_invalid_chars)
        }
        return null
    }

    /**
     * Validate workflow description format
     * @return error message if invalid, null if valid
     */
    fun validateWorkflowDescription(description: String): String? {
        if (description.length > 120) return context.getString(R.string.workflow_description_error_too_long)
        return null
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

        // Auto-detect capability flags from workflow structure
        val hasDualClip = nodesJson.keys().asSequence().any { nodeId ->
            nodesJson.optJSONObject(nodeId)?.optString("class_type") == "DualCLIPLoader"
        }
        val hasBasicGuider = nodesJson.keys().asSequence().any { nodeId ->
            nodesJson.optJSONObject(nodeId)?.optString("class_type") == "BasicGuider"
        }

        // Create defaults object from extracted values with auto-detected capability flags
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
            hasNegativePrompt = !hasBasicGuider,  // BasicGuider means no negative prompt
            hasCfg = !hasBasicGuider,              // BasicGuider means no CFG
            hasDualClip = hasDualClip              // DualCLIPLoader detected
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
            return Result.failure(Exception(context.getString(R.string.workflow_error_duplicate_filename)))
        }

        // Save file
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception(context.getString(R.string.workflow_error_save_failed, e.message ?: "")))
        }

        // Save metadata
        val id = "user_${filename.substringBeforeLast(".")}"
        val prefs = context.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
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
            return Result.failure(Exception(context.getString(R.string.error_invalid_json, e.message ?: "")))
        }

        // Check if already wrapped (has "nodes" object at top level)
        val isWrapped = json.has("nodes") && json.optJSONObject("nodes") != null

        // Auto-detect type from content
        val type = detectWorkflowType(jsonContent)
            ?: return Result.failure(Exception(context.getString(R.string.error_cannot_determine_workflow_type)))

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
                    context.getString(R.string.workflow_error_missing_placeholders, validationResult.missing.joinToString(", "))
                is WorkflowValidationResult.MissingKeys ->
                    context.getString(R.string.workflow_error_missing_inputs, validationResult.missing.joinToString(", "))
                else -> context.getString(R.string.error_unknown)
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
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, prefixedFilename)
        try {
            file.writeText(finalJson)
        } catch (e: Exception) {
            return Result.failure(Exception(context.getString(R.string.workflow_error_save_failed, e.message ?: "")))
        }

        // Save metadata
        val id = "user_${prefixedFilename.substringBeforeLast(".")}"
        val prefs = context.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
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
                val file = File(context.filesDir, "$USER_WORKFLOWS_DIR/$it")
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
            val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }

            // Clear preferences
            val prefs = context.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(USER_WORKFLOWS_PREFS, Context.MODE_PRIVATE)
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
        return workflows.filter { it.type == type }
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

    // ==================== LoRA CHAIN INJECTION ====================

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

    // ==================== END LoRA CHAIN INJECTION ====================

    /**
     * Prepare workflow JSON with actual parameter values
     */
    fun prepareWorkflow(
        workflowName: String,
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
        val workflow = getWorkflowByName(workflowName) ?: return null

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

        return processedJson
    }

    /**
     * Prepare Image-to-image workflow JSON with actual parameter values
     */
    fun prepareImageToImageWorkflow(
        workflowName: String,
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
        val workflow = getWorkflowByName(workflowName) ?: return null

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
        processedJson = processedJson.replace("uploaded_image.png [input]", "${escapeForJson(imageFilename)} [input]")
        processedJson = processedJson.replace("\"seed\": 42", "\"seed\": $randomSeed")
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        return processedJson
    }

    /**
     * Prepare video workflow JSON with actual parameter values
     */
    fun prepareVideoWorkflow(
        workflowName: String,
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
        val workflow = getWorkflowByName(workflowName) ?: return null

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
        processedJson = processedJson.replace("\"noise_seed\": 42", "\"noise_seed\": $randomSeed")
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        return processedJson
    }

    /**
     * Prepare image-to-video workflow JSON with actual parameter values
     */
    fun prepareImageToVideoWorkflow(
        workflowName: String,
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
        val workflow = getWorkflowByName(workflowName) ?: return null

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
        processedJson = processedJson.replace("\"noise_seed\": 42", "\"noise_seed\": $randomSeed")
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        return processedJson
    }

    /**
     * Get workflow JSON nodes (without metadata)
     */
    fun getWorkflowNodes(
        workflowName: String,
        positivePrompt: String,
        negativePrompt: String = "",
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): JSONObject? {
        val processedJson = prepareWorkflow(
            workflowName = workflowName,
            positivePrompt = positivePrompt,
            negativePrompt = negativePrompt,
            checkpoint = checkpoint,
            unet = unet,
            vae = vae,
            clip = clip,
            width = width,
            height = height,
            steps = steps
        ) ?: return null

        val jsonObject = JSONObject(processedJson)
        return jsonObject.optJSONObject("nodes")
    }
}
