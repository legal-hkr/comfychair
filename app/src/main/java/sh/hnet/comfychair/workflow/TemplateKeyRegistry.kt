package sh.hnet.comfychair.workflow

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType

/**
 * Centralized registry of template keys used in workflow JSON files.
 * Maps placeholder names (e.g., "positive_prompt") to actual JSON input keys (e.g., "text").
 */
object TemplateKeyRegistry {

    /**
     * Map from placeholder name to the JSON input key it targets.
     * Example: "{{positive_prompt}}" placeholder maps to "positive_text" key in CLIPTextEncode inputs
     */
    private val PLACEHOLDER_TO_KEY = mapOf(
        "positive_prompt" to "positive_text",
        "negative_prompt" to "negative_text",
        "ckpt_name" to "ckpt_name",
        "unet_name" to "unet_name",
        "vae_name" to "vae_name",
        "clip_name" to "clip_name",
        "clip_name1" to "clip_name1",
        "clip_name2" to "clip_name2",
        "width" to "width",
        "height" to "height",
        "steps" to "steps",
        "cfg" to "cfg",
        "sampler_name" to "sampler_name",
        "scheduler" to "scheduler",
        "megapixels" to "megapixels",
        "highnoise_unet_name" to "unet_name",
        "lownoise_unet_name" to "unet_name",
        "highnoise_lora_name" to "lora_name",
        "lownoise_lora_name" to "lora_name",
        "length" to "length",
        "frame_rate" to "fps",
        "image_filename" to "image"
    )

    /**
     * Map from JSON input key to the primary placeholder name.
     * Note: Some keys have multiple placeholders (e.g., "unet_name" can be "unet_name",
     * "highnoise_unet_name", or "lownoise_unet_name"). This map provides the primary/canonical
     * placeholder name for each input key.
     */
    private val KEY_TO_PLACEHOLDER = mapOf(
        "positive_text" to "positive_prompt",
        "negative_text" to "negative_prompt",
        "ckpt_name" to "ckpt_name",
        "unet_name" to "unet_name",
        "vae_name" to "vae_name",
        "clip_name" to "clip_name",
        "clip_name1" to "clip_name1",
        "clip_name2" to "clip_name2",
        "width" to "width",
        "height" to "height",
        "steps" to "steps",
        "cfg" to "cfg",
        "sampler_name" to "sampler_name",
        "scheduler" to "scheduler",
        "megapixels" to "megapixels",
        "lora_name" to "lora_name",
        "length" to "length",
        "fps" to "frame_rate",
        "image" to "image_filename"
    )

    /**
     * All unique input keys that could be template targets
     */
    val ALL_TEMPLATE_KEYS: Set<String> = PLACEHOLDER_TO_KEY.values.toSet()

    /**
     * Keys that require graph tracing to find (not direct JSON key lookups).
     * These are excluded from simple key validation but included in field mapping.
     */
    val GRAPH_TRACED_KEYS: Set<String> = setOf("positive_text", "negative_text")

    /**
     * STRICTLY required keys per workflow type - workflow MUST have mappings for these.
     * These are the minimum fields needed for a workflow to function.
     */
    private val REQUIRED_KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = mapOf(
        WorkflowType.TTI_CHECKPOINT to setOf("positive_text"),
        WorkflowType.TTI_UNET to setOf("positive_text"),
        WorkflowType.ITI_CHECKPOINT to setOf("positive_text", "image"),
        WorkflowType.ITI_UNET to setOf("positive_text", "image"),
        WorkflowType.ITE_UNET to setOf("positive_text", "image"),
        WorkflowType.TTV_UNET to setOf("positive_text"),
        WorkflowType.ITV_UNET to setOf("positive_text", "image")
    )

    /**
     * Optional keys per workflow type - workflow can have mappings for these, but they're not required.
     * If not mapped, these fields won't appear in the Generation screen's Bottom Sheet.
     */
    private val OPTIONAL_KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = mapOf(
        WorkflowType.TTI_CHECKPOINT to setOf("negative_text", "ckpt_name", "width", "height", "steps", "cfg", "sampler_name", "scheduler", "lora_name"),
        WorkflowType.TTI_UNET to setOf("negative_text", "unet_name", "vae_name", "clip_name", "width", "height", "steps", "cfg", "sampler_name", "scheduler", "lora_name"),
        WorkflowType.ITI_CHECKPOINT to setOf("negative_text", "ckpt_name", "megapixels", "steps", "cfg", "sampler_name", "scheduler", "lora_name"),
        WorkflowType.ITI_UNET to setOf("negative_text", "unet_name", "vae_name", "clip_name", "megapixels", "steps", "cfg", "sampler_name", "scheduler", "lora_name"),
        WorkflowType.ITE_UNET to setOf("negative_text", "unet_name", "vae_name", "clip_name", "megapixels", "steps", "cfg", "sampler_name", "scheduler", "lora_name"),
        WorkflowType.TTV_UNET to setOf("negative_text", "highnoise_unet_name", "lownoise_unet_name", "highnoise_lora_name", "lownoise_lora_name", "vae_name", "clip_name", "width", "height", "length", "fps"),
        WorkflowType.ITV_UNET to setOf("negative_text", "highnoise_unet_name", "lownoise_unet_name", "highnoise_lora_name", "lownoise_lora_name", "vae_name", "clip_name", "width", "height", "length", "fps")
    )

    /**
     * All keys per workflow type (required + optional) - for backwards compatibility.
     */
    private val ALL_KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = WorkflowType.entries.associateWith { type ->
        (REQUIRED_KEYS_BY_TYPE[type] ?: emptySet()) + (OPTIONAL_KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Get the set of STRICTLY required keys for simple key validation (excludes graph-traced keys).
     */
    fun getDirectKeysForType(type: WorkflowType): Set<String> {
        return (REQUIRED_KEYS_BY_TYPE[type] ?: emptySet()) - GRAPH_TRACED_KEYS
    }

    /**
     * Get the set of STRICTLY required input keys for a workflow type.
     */
    fun getRequiredKeysForType(type: WorkflowType): Set<String> {
        return REQUIRED_KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Get the set of optional input keys for a workflow type.
     */
    fun getOptionalKeysForType(type: WorkflowType): Set<String> {
        return OPTIONAL_KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Get all keys (required + optional) for a workflow type.
     */
    fun getAllKeysForType(type: WorkflowType): Set<String> {
        return ALL_KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Get the set of all input keys for a workflow type (backwards compatibility alias).
     */
    fun getKeysForType(type: WorkflowType): Set<String> {
        return getAllKeysForType(type)
    }

    /**
     * Get the actual JSON input key for a placeholder name.
     * Example: getJsonKeyForPlaceholder("highnoise_unet_name") returns "unet_name"
     * Returns the placeholder name itself if no mapping exists.
     */
    fun getJsonKeyForPlaceholder(placeholder: String): String {
        return PLACEHOLDER_TO_KEY[placeholder] ?: placeholder
    }

    /**
     * Check if an input key is a known template key
     */
    fun isTemplateKey(key: String): Boolean {
        return key in ALL_TEMPLATE_KEYS
    }

    /**
     * Check if an input key is known for a specific workflow type (required or optional)
     */
    fun isTemplateKeyForType(key: String, type: WorkflowType): Boolean {
        return key in (ALL_KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Check if an input key is STRICTLY required for a specific workflow type.
     */
    fun isRequiredKeyForType(key: String, type: WorkflowType): Boolean {
        return key in (REQUIRED_KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Get the placeholder name for a given input key.
     * Returns the input key itself if no mapping exists.
     * Example: getPlaceholderForKey("text") returns "positive_prompt"
     */
    fun getPlaceholderForKey(key: String): String {
        return KEY_TO_PLACEHOLDER[key] ?: key
    }

    /**
     * Analyze workflow JSON and determine actual STRICTLY required keys based on workflow structure.
     * This returns only the minimum required fields for a workflow to function.
     */
    fun getStrictlyRequiredKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        // For strictly required keys, we don't need to adjust for DualCLIPLoader or BasicGuider
        // since CLIP, CFG, and negative_text are optional now
        return REQUIRED_KEYS_BY_TYPE[workflowType] ?: emptySet()
    }

    /**
     * Analyze workflow JSON and determine actual optional keys based on workflow structure.
     * This handles Flux-style workflows with:
     * - DualCLIPLoader (replaces clip_name with clip_name1 + clip_name2)
     * - BasicGuider (no CFG, no negative prompt)
     */
    fun getOptionalKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        val baseKeys = OPTIONAL_KEYS_BY_TYPE[workflowType]?.toMutableSet() ?: return emptySet()

        if (workflowType == WorkflowType.TTI_UNET) {
            val nodesJson = workflowJson.optJSONObject("nodes") ?: return baseKeys

            // Check for DualCLIPLoader (replaces clip_name with clip_name1 + clip_name2)
            val hasDualClip = nodesJson.keys().asSequence().any { nodeId ->
                nodesJson.optJSONObject(nodeId)?.optString("class_type") == "DualCLIPLoader"
            }
            if (hasDualClip) {
                baseKeys.remove("clip_name")
                baseKeys.add("clip_name1")
                baseKeys.add("clip_name2")
            }

            // Check for BasicGuider (no CFG, no negative prompt)
            val hasBasicGuider = nodesJson.keys().asSequence().any { nodeId ->
                nodesJson.optJSONObject(nodeId)?.optString("class_type") == "BasicGuider"
            }
            if (hasBasicGuider) {
                baseKeys.remove("cfg")
                baseKeys.remove("negative_text")
            }
        }

        return baseKeys
    }

    /**
     * Analyze workflow JSON and determine all keys (required + optional) based on workflow structure.
     * This handles Flux-style workflows with DualCLIPLoader and BasicGuider.
     */
    fun getRequiredKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        return getStrictlyRequiredKeysForWorkflow(workflowType, workflowJson) +
               getOptionalKeysForWorkflow(workflowType, workflowJson)
    }

    /**
     * Get direct keys for validation (excludes graph-traced keys), adjusted for workflow structure.
     * Only checks strictly required keys.
     */
    fun getDirectKeysForWorkflow(workflowType: WorkflowType, workflowJson: JSONObject): Set<String> {
        return getStrictlyRequiredKeysForWorkflow(workflowType, workflowJson) - GRAPH_TRACED_KEYS
    }

    // ===========================================
    // Field Mapping Matching Functions
    // ===========================================

    /**
     * Check if an input key matches a field key.
     * Handles key translation (e.g., "highnoise_unet_name" → "unet_name").
     *
     * @param fieldKey The template field key (e.g., "highnoise_unet_name")
     * @param inputKey The actual input key from the node (e.g., "unet_name")
     * @return true if the input key corresponds to this field
     */
    fun doesInputKeyMatchField(fieldKey: String, inputKey: String): Boolean {
        val expectedJsonKey = getJsonKeyForPlaceholder(fieldKey)
        return inputKey == expectedJsonKey
    }

    /**
     * Check if an input value matches a field's placeholder pattern.
     * For highnoise/lownoise variants, verifies the specific placeholder is present.
     *
     * @param fieldKey The template field key (e.g., "highnoise_unet_name")
     * @param inputValue The current value of the input (may be "{{highnoise_unet_name}}")
     * @return true if the value matches the expected placeholder pattern
     */
    fun doesValueMatchPlaceholder(fieldKey: String, inputValue: Any?): Boolean {
        // For highnoise/lownoise variants, we need to check the specific placeholder
        // to distinguish between them (both map to the same JSON key like "unet_name")
        return when {
            fieldKey.startsWith("highnoise_") || fieldKey.startsWith("lownoise_") -> {
                val placeholderPattern = "{{$fieldKey}}"
                inputValue?.toString()?.contains(placeholderPattern) ?: false
            }
            // For all other fields, accept any node with the correct JSON input key
            else -> true
        }
    }

    /**
     * Combined check: does this (inputKey, inputValue) pair match the fieldKey?
     */
    fun isFieldMatch(fieldKey: String, inputKey: String, inputValue: Any?): Boolean {
        return doesInputKeyMatchField(fieldKey, inputKey) &&
               doesValueMatchPlaceholder(fieldKey, inputValue)
    }
}
