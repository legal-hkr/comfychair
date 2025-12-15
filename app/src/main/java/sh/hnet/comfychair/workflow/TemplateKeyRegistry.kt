package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.WorkflowType

/**
 * Centralized registry of template keys used in workflow JSON files.
 * Maps placeholder names (e.g., "positive_prompt") to actual JSON input keys (e.g., "text").
 */
object TemplateKeyRegistry {

    /**
     * Map from placeholder name to the JSON input key it targets.
     * Example: "{{positive_prompt}}" placeholder maps to "text" key in CLIPTextEncode inputs
     */
    private val PLACEHOLDER_TO_KEY = mapOf(
        "positive_prompt" to "text",
        "ckpt_name" to "ckpt_name",
        "unet_name" to "unet_name",
        "vae_name" to "vae_name",
        "clip_name" to "clip_name",
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
        "text" to "positive_prompt",
        "ckpt_name" to "ckpt_name",
        "unet_name" to "unet_name",
        "vae_name" to "vae_name",
        "clip_name" to "clip_name",
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
     * Required keys per workflow type (derived from WorkflowManager.REQUIRED_PLACEHOLDERS)
     */
    private val KEYS_BY_TYPE: Map<WorkflowType, Set<String>> = mapOf(
        WorkflowType.TTI_CHECKPOINT to setOf("text", "ckpt_name", "width", "height", "steps", "cfg", "sampler_name", "scheduler"),
        WorkflowType.TTI_UNET to setOf("text", "unet_name", "vae_name", "clip_name", "width", "height", "steps", "cfg", "sampler_name", "scheduler"),
        WorkflowType.IIP_CHECKPOINT to setOf("text", "ckpt_name", "megapixels", "steps", "cfg", "sampler_name", "scheduler"),
        WorkflowType.IIP_UNET to setOf("text", "unet_name", "vae_name", "clip_name", "steps", "cfg", "sampler_name", "scheduler"),
        WorkflowType.TTV_UNET to setOf("text", "unet_name", "lora_name", "vae_name", "clip_name", "width", "height", "length", "fps"),
        WorkflowType.ITV_UNET to setOf("text", "unet_name", "lora_name", "vae_name", "clip_name", "width", "height", "length", "fps", "image")
    )

    /**
     * Get the set of required input keys for a workflow type
     */
    fun getKeysForType(type: WorkflowType): Set<String> {
        return KEYS_BY_TYPE[type] ?: emptySet()
    }

    /**
     * Check if an input key is a known template key
     */
    fun isTemplateKey(key: String): Boolean {
        return key in ALL_TEMPLATE_KEYS
    }

    /**
     * Check if an input key is required for a specific workflow type
     */
    fun isTemplateKeyForType(key: String, type: WorkflowType): Boolean {
        return key in (KEYS_BY_TYPE[type] ?: emptySet())
    }

    /**
     * Get the placeholder name for a given input key.
     * Returns the input key itself if no mapping exists.
     * Example: getPlaceholderForKey("text") returns "positive_prompt"
     */
    fun getPlaceholderForKey(key: String): String {
        return KEY_TO_PLACEHOLDER[key] ?: key
    }
}
