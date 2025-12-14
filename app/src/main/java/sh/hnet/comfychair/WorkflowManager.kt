package sh.hnet.comfychair

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import java.io.File
import java.io.InputStream

/**
 * Workflow types supported by ComfyChair
 */
enum class WorkflowType {
    TTI_CHECKPOINT,    // Text-to-Image Checkpoint
    TTI_UNET,          // Text-to-Image UNET
    IIP_CHECKPOINT,    // Inpainting Checkpoint
    IIP_UNET,          // Inpainting UNET
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
        val isBuiltIn: Boolean
    )

    companion object {
        private const val USER_WORKFLOWS_PREFS = "UserWorkflowsPrefs"
        private const val USER_WORKFLOWS_KEY = "user_workflows_json"
        private const val USER_WORKFLOWS_DIR = "user_workflows"

        // Required placeholders per workflow type
        val REQUIRED_PLACEHOLDERS = mapOf(
            WorkflowType.TTI_CHECKPOINT to listOf(
                "{{prompt}}", "{{ckpt_name}}", "{{width}}", "{{height}}", "{{steps}}"
            ),
            WorkflowType.TTI_UNET to listOf(
                "{{prompt}}", "{{unet_name}}", "{{vae_name}}", "{{clip_name}}",
                "{{width}}", "{{height}}", "{{steps}}"
            ),
            WorkflowType.IIP_CHECKPOINT to listOf(
                "{{prompt}}", "{{ckpt_name}}", "{{megapixels}}", "{{steps}}"
            ),
            WorkflowType.IIP_UNET to listOf(
                "{{prompt}}", "{{unet_name}}", "{{vae_name}}", "{{clip_name}}", "{{steps}}"
            ),
            WorkflowType.TTV_UNET to listOf(
                "{{prompt}}", "{{highnoise_unet_name}}", "{{lownoise_unet_name}}",
                "{{highnoise_lora_name}}", "{{lownoise_lora_name}}",
                "{{vae_name}}", "{{clip_name}}", "{{width}}", "{{height}}", "{{length}}", "{{frame_rate}}"
            ),
            WorkflowType.ITV_UNET to listOf(
                "{{prompt}}", "{{highnoise_unet_name}}", "{{lownoise_unet_name}}",
                "{{highnoise_lora_name}}", "{{lownoise_lora_name}}",
                "{{vae_name}}", "{{clip_name}}", "{{width}}", "{{height}}", "{{length}}", "{{frame_rate}}",
                "{{image_filename}}"
            )
        )

        // Required patterns (not placeholders but literal strings that must exist)
        val REQUIRED_PATTERNS = mapOf(
            WorkflowType.IIP_CHECKPOINT to listOf("uploaded_image.png [input]"),
            WorkflowType.IIP_UNET to listOf("uploaded_image.png [input]")
        )

        // Filename prefix to type mapping
        val PREFIX_TO_TYPE = mapOf(
            "tti_checkpoint_" to WorkflowType.TTI_CHECKPOINT,
            "tti_unet_" to WorkflowType.TTI_UNET,
            "iip_checkpoint_" to WorkflowType.IIP_CHECKPOINT,
            "iip_unet_" to WorkflowType.IIP_UNET,
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
     */
    private fun loadBuiltInWorkflows() {
        val workflowResources = listOf(
            R.raw.tti_checkpoint_default,
            R.raw.tti_unet_zimage,
            R.raw.iip_checkpoint_default,
            R.raw.iip_unet_zimage,
            R.raw.ttv_unet_wan22_lightx2v,
            R.raw.itv_unet_wan22_lightx2v
        )

        for (resId in workflowResources) {
            try {
                val inputStream: InputStream = context.resources.openRawResource(resId)
                val jsonContent = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)

                val name = jsonObject.optString("name", "Unnamed Workflow")
                val description = jsonObject.optString("description", "")
                val id = context.resources.getResourceEntryName(resId)
                val type = parseWorkflowType(id) ?: continue

                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = true))
            } catch (e: Exception) {
                // Failed to load workflow
            }
        }
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
                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = false))
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

        // Check for inpainting indicators
        val hasInpaintingNodes = classTypes.any { classType ->
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

            // Inpainting with checkpoint
            hasInpaintingNodes && hasCheckpointLoader -> WorkflowType.IIP_CHECKPOINT

            // Inpainting with UNET
            hasInpaintingNodes && hasUNETLoader -> WorkflowType.IIP_UNET

            // Inpainting with LoadImage (fallback)
            hasLoadImage && hasCheckpointLoader -> WorkflowType.IIP_CHECKPOINT
            hasLoadImage && hasUNETLoader -> WorkflowType.IIP_UNET

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

        // Get required keys for this workflow type
        val requiredKeys = TemplateKeyRegistry.getKeysForType(type)
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

        // Apply field mappings - replace values with placeholders
        fieldMappings.forEach { (fieldKey, mapping) ->
            val (nodeId, inputKey) = mapping
            val nodeJson = nodesJson.optJSONObject(nodeId)
            if (nodeJson != null) {
                val inputsJson = nodeJson.optJSONObject("inputs")
                if (inputsJson != null && inputsJson.has(inputKey)) {
                    // Convert input key to proper placeholder name
                    // e.g., "text" -> "prompt", "ckpt_name" -> "checkpoint"
                    val placeholderName = TemplateKeyRegistry.getPlaceholderForKey(fieldKey)
                    inputsJson.put(inputKey, "{{$placeholderName}}")
                }
            }
        }

        // Create wrapped JSON with metadata
        val finalJson = JSONObject().apply {
            put("name", name)
            put("description", description)
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
            return Result.failure(Exception("A workflow with this name already exists"))
        }

        // Save file
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, filename)
        try {
            file.writeText(finalJson.toString(2))
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save file: ${e.message}"))
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

        val workflow = Workflow(id, name, description, finalJson.toString(2), type, isBuiltIn = false)
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
                    "Missing placeholders: ${validationResult.missing.joinToString(", ")}"
                is WorkflowValidationResult.MissingKeys ->
                    "Missing required inputs: ${validationResult.missing.joinToString(", ")}"
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
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, prefixedFilename)
        try {
            file.writeText(finalJson)
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to save file: ${e.message}"))
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
     * Get list of workflow names for dropdown
     */
    fun getWorkflowNames(): List<String> {
        return workflows.map { it.name }
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
     * Get list of inpainting checkpoint workflow names for dropdown
     */
    fun getInpaintingCheckpointWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.IIP_CHECKPOINT }.map { it.name }
    }

    /**
     * Get list of inpainting UNET workflow names for dropdown
     */
    fun getInpaintingUNETWorkflowNames(): List<String> {
        return workflows.filter { it.type == WorkflowType.IIP_UNET }.map { it.name }
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
     * Check if a workflow is a text-to-image checkpoint workflow
     */
    fun isCheckpointWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.type == WorkflowType.TTI_CHECKPOINT
    }

    /**
     * Check if a workflow is a text-to-image UNET workflow
     */
    fun isUNETWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.type == WorkflowType.TTI_UNET
    }

    /**
     * Check if a workflow is an inpainting checkpoint workflow
     */
    fun isInpaintingCheckpointWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.type == WorkflowType.IIP_CHECKPOINT
    }

    /**
     * Check if a workflow is an inpainting UNET workflow
     */
    fun isInpaintingUNETWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.type == WorkflowType.IIP_UNET
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
     * Prepare workflow JSON with actual parameter values
     */
    fun prepareWorkflow(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): String? {
        val workflow = getWorkflowByName(workflowName) ?: return null

        val randomSeed = (0..999999999999).random()
        val escapedPrompt = escapeForJson(prompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{ckpt_name}}", checkpoint)
        processedJson = processedJson.replace("{{unet_name}}", unet)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        return processedJson
    }

    /**
     * Prepare inpainting workflow JSON with actual parameter values
     */
    fun prepareInpaintingWorkflow(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        megapixels: Float = 1.0f,
        steps: Int,
        imageFilename: String
    ): String? {
        val workflow = getWorkflowByName(workflowName) ?: return null

        val randomSeed = (0..999999999999).random()
        val escapedPrompt = escapeForJson(prompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{ckpt_name}}", checkpoint)
        processedJson = processedJson.replace("{{unet_name}}", unet)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
        processedJson = processedJson.replace("{{megapixels}}", megapixels.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        processedJson = processedJson.replace("uploaded_image.png [input]", "$imageFilename [input]")
        processedJson = processedJson.replace("\"seed\": 42", "\"seed\": $randomSeed")
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        return processedJson
    }

    /**
     * Prepare video workflow JSON with actual parameter values
     */
    fun prepareVideoWorkflow(
        workflowName: String,
        prompt: String,
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
        val escapedPrompt = escapeForJson(prompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{highnoise_unet_name}}", highnoiseUnet)
        processedJson = processedJson.replace("{{lownoise_unet_name}}", lownoiseUnet)
        processedJson = processedJson.replace("{{highnoise_lora_name}}", highnoiseLora)
        processedJson = processedJson.replace("{{lownoise_lora_name}}", lownoiseLora)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
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
        prompt: String,
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
        val escapedPrompt = escapeForJson(prompt)

        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{highnoise_unet_name}}", highnoiseUnet)
        processedJson = processedJson.replace("{{lownoise_unet_name}}", lownoiseUnet)
        processedJson = processedJson.replace("{{highnoise_lora_name}}", highnoiseLora)
        processedJson = processedJson.replace("{{lownoise_lora_name}}", lownoiseLora)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{length}}", length.toString())
        processedJson = processedJson.replace("{{frame_rate}}", fps.toString())
        processedJson = processedJson.replace("{{image_filename}}", imageFilename)
        processedJson = processedJson.replace("\"noise_seed\": 42", "\"noise_seed\": $randomSeed")
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        return processedJson
    }

    /**
     * Get workflow JSON nodes (without metadata)
     */
    fun getWorkflowNodes(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): JSONObject? {
        val processedJson = prepareWorkflow(workflowName, prompt, checkpoint, unet, vae, clip, width, height, steps)
            ?: return null

        val jsonObject = JSONObject(processedJson)
        return jsonObject.optJSONObject("nodes")
    }
}
