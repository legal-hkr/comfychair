package sh.hnet.comfychair

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.NodeAttributeEdits
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.BypassNodeResolver
import sh.hnet.comfychair.util.LoraInjectionUtils
import sh.hnet.comfychair.util.WorkflowJsonAnalyzer
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.UuidUtils
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

    // Constants - delegate to WorkflowJsonAnalyzer
    val REQUIRED_PLACEHOLDERS get() = WorkflowJsonAnalyzer.REQUIRED_PLACEHOLDERS
    val REQUIRED_PATTERNS get() = WorkflowJsonAnalyzer.REQUIRED_PATTERNS
    val PREFIX_TO_TYPE get() = WorkflowJsonAnalyzer.PREFIX_TO_TYPE

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

    // Workflow JSON analysis - delegates to WorkflowJsonAnalyzer

    /** Parse workflow type from filename prefix */
    fun parseWorkflowType(filename: String): WorkflowType? =
        WorkflowJsonAnalyzer.parseWorkflowType(filename)

    /** Detect workflow type by analyzing JSON content */
    fun detectWorkflowType(jsonContent: String): WorkflowType? =
        WorkflowJsonAnalyzer.detectWorkflowType(jsonContent)

    /** Validate workflow JSON against type requirements */
    fun validateWorkflow(jsonContent: String, type: WorkflowType): WorkflowValidationResult =
        WorkflowJsonAnalyzer.validateWorkflow(jsonContent, type)

    /** Validate workflow JSON by checking for required input keys */
    fun validateWorkflowKeys(jsonContent: String, type: WorkflowType): WorkflowValidationResult =
        WorkflowJsonAnalyzer.validateWorkflowKeys(jsonContent, type)

    /** Extract all class_type values from workflow JSON */
    fun extractClassTypes(jsonContent: String): Result<Set<String>> =
        WorkflowJsonAnalyzer.extractClassTypes(jsonContent)

    /** Validate workflow nodes against available server nodes */
    fun validateNodesAgainstServer(
        workflowClassTypes: Set<String>,
        availableNodes: Set<String>
    ): List<String> =
        WorkflowJsonAnalyzer.validateNodesAgainstServer(workflowClassTypes, availableNodes)

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
                    val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                    // Check both placeholder name and JSON key (OPTIONAL_KEYS uses mixed formats:
                    // JSON keys for prompts like "negative_text", placeholder names for
                    // highnoise/lownoise variants that share the same JSON key)
                    if (placeholderName in unmappedOptionalKeys || fieldKey in unmappedOptionalKeys) {
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
                    val fieldKey = TemplateKeyRegistry.getJsonKeyForPlaceholder(placeholderName)
                    // Check both placeholder name and JSON key (OPTIONAL_KEYS uses mixed formats:
                    // JSON keys for prompts like "negative_text", placeholder names for
                    // highnoise/lownoise variants that share the same JSON key)
                    if (placeholderName in unmappedOptionalKeys || fieldKey in unmappedOptionalKeys) {
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

    // Bypass handling - delegates to BypassNodeResolver

    /**
     * Apply bypass logic to a workflow JSON before submission to ComfyUI API.
     * Delegates to BypassNodeResolver.
     */
    fun applyBypassedNodes(workflowJson: String): String =
        BypassNodeResolver.applyBypassedNodes(workflowJson)

    // LoRA chain injection - delegates to LoraInjectionUtils

    /**
     * Inject a chain of LoRA loaders into a workflow JSON.
     * Delegates to LoraInjectionUtils.
     */
    fun injectLoraChain(
        workflowJson: String,
        loraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        workflowType: WorkflowType
    ): String = LoraInjectionUtils.injectLoraChain(workflowJson, loraChain, workflowType)

    /**
     * Inject additional LoRAs into a video workflow.
     * Delegates to LoraInjectionUtils.
     */
    fun injectAdditionalVideoLoras(
        workflowJson: String,
        additionalLoraChain: List<sh.hnet.comfychair.model.LoraSelection>,
        isHighNoise: Boolean
    ): String = LoraInjectionUtils.injectAdditionalVideoLoras(workflowJson, additionalLoraChain, isHighNoise)

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
