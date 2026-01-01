package sh.hnet.comfychair.util

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowValidationResult
import sh.hnet.comfychair.workflow.TemplateKeyRegistry

/**
 * Utilities for analyzing workflow JSON content.
 * Handles type detection, validation, and structure analysis.
 *
 * Note: Different from ValidationUtils.kt which validates UI input.
 * This analyzes workflow JSON structure and content.
 */
internal object WorkflowJsonAnalyzer {

    // Required placeholders per workflow type - only positive_prompt is required now
    // All other fields are optional and handled by WorkflowDefaults capability flags
    val REQUIRED_PLACEHOLDERS = mapOf(
        WorkflowType.TTI to listOf("{{positive_prompt}}"),
        WorkflowType.ITI_INPAINTING to listOf("{{positive_prompt}}"),
        WorkflowType.ITI_EDITING to listOf("{{positive_prompt}}"),
        WorkflowType.TTV to listOf("{{positive_prompt}}"),
        WorkflowType.ITV to listOf("{{positive_prompt}}")
    )

    // Required patterns (not placeholders but literal strings that must exist)
    val REQUIRED_PATTERNS = mapOf(
        WorkflowType.ITI_INPAINTING to listOf("uploaded_image.png [input]"),
        WorkflowType.ITI_EDITING to listOf("uploaded_image.png [input]")
    )

    // Filename prefix to type mapping
    val PREFIX_TO_TYPE = mapOf(
        "tti_" to WorkflowType.TTI,
        "iti_inpainting_" to WorkflowType.ITI_INPAINTING,
        "iti_editing_" to WorkflowType.ITI_EDITING,
        "ttv_" to WorkflowType.TTV,
        "itv_" to WorkflowType.ITV
    )

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
    fun extractNodesObject(json: JSONObject): JSONObject {
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
            hasLoadImage && hasVideoNodes -> WorkflowType.ITV

            // Text-to-video: has video nodes but no LoadImage
            hasVideoNodes -> WorkflowType.TTV

            // Image-to-image editing (no mask, uses QwenImageEdit-style nodes)
            hasImageEditingNodes -> WorkflowType.ITI_EDITING

            // Image-to-image inpainting (with mask nodes or LoadImage)
            hasInpaintingNodes -> WorkflowType.ITI_INPAINTING
            hasLoadImage -> WorkflowType.ITI_INPAINTING

            // Text-to-image (no LoadImage, no video nodes)
            hasCheckpointLoader || hasUNETLoader -> WorkflowType.TTI

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
}
