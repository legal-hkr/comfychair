package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Represents default values for a workflow.
 * These values are stored in the workflow JSON and used when no user-saved values exist.
 */
data class WorkflowDefaults(
    // Image generation parameters
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val samplerName: String? = null,
    val scheduler: String? = null,

    // Prompt parameters
    val negativePrompt: String? = null,

    // Image-to-image specific
    val megapixels: Float? = null,

    // Video specific
    val length: Int? = null,
    val frameRate: Int? = null,

    // Workflow capability flags (backwards compatible)
    val hasNegativePrompt: Boolean = true,
    val hasCfg: Boolean = true,

    // Field presence flags - indicate which optional fields are mapped in the workflow
    // If a field is not mapped (false), it won't appear in the Generation screen's Bottom Sheet
    val hasWidth: Boolean = true,
    val hasHeight: Boolean = true,
    val hasSteps: Boolean = true,
    val hasSamplerName: Boolean = true,
    val hasScheduler: Boolean = true,
    val hasMegapixels: Boolean = true,
    val hasLength: Boolean = true,
    val hasFrameRate: Boolean = true,
    val hasVaeName: Boolean = true,
    val hasClipName: Boolean = true,      // For single CLIP ({{clip_name}})
    val hasClipName1: Boolean = false,    // For multi-CLIP slot 1 ({{clip_name1}})
    val hasClipName2: Boolean = false,    // For multi-CLIP slot 2 ({{clip_name2}})
    val hasClipName3: Boolean = false,    // For multi-CLIP slot 3 ({{clip_name3}})
    val hasClipName4: Boolean = false,    // For multi-CLIP slot 4 ({{clip_name4}})
    val hasLoraName: Boolean = true,

    // Dual-UNET/LoRA field presence flags (for video workflows)
    val hasLownoiseUnet: Boolean = false,     // Low noise UNET (optional secondary UNET)
    val hasHighnoiseLora: Boolean = false,    // High noise LoRA (primary LoRA)
    val hasLownoiseLora: Boolean = false,     // Low noise LoRA (secondary LoRA)

    // Model presence flags - indicate which primary model is mapped
    val hasCheckpointName: Boolean = false,   // Checkpoint model mapped (TTI/ITI)
    val hasUnetName: Boolean = false,         // UNET model mapped (TTI/ITI/ITE)
    val hasHighnoiseUnet: Boolean = false,    // High noise UNET mapped (TTV/ITV)

    // ITE reference image flags - indicate which reference images are supported by the workflow
    val hasReferenceImage1: Boolean = false,  // Reference image 1 slot available
    val hasReferenceImage2: Boolean = false   // Reference image 2 slot available
) {
    companion object {
        fun fromJson(jsonObject: JSONObject?): WorkflowDefaults {
            if (jsonObject == null) return WorkflowDefaults()

            return WorkflowDefaults(
                width = jsonObject.optInt("width").takeIf { it > 0 },
                height = jsonObject.optInt("height").takeIf { it > 0 },
                steps = jsonObject.optInt("steps").takeIf { it > 0 },
                cfg = jsonObject.optDouble("cfg").takeIf { !it.isNaN() }?.toFloat(),
                samplerName = jsonObject.optString("sampler_name").takeIf { it.isNotEmpty() },
                scheduler = jsonObject.optString("scheduler").takeIf { it.isNotEmpty() },
                negativePrompt = if (jsonObject.has("negative_prompt")) jsonObject.optString("negative_prompt") else null,
                megapixels = jsonObject.optDouble("megapixels").takeIf { !it.isNaN() }?.toFloat(),
                length = jsonObject.optInt("length").takeIf { it > 0 },
                frameRate = jsonObject.optInt("frame_rate").takeIf { it > 0 },
                // Capability flags (default to true/false if not present)
                hasNegativePrompt = jsonObject.optBoolean("has_negative_prompt", true),
                hasCfg = jsonObject.optBoolean("has_cfg", true),
                // Field presence flags (default to true for backwards compatibility)
                hasWidth = jsonObject.optBoolean("has_width", true),
                hasHeight = jsonObject.optBoolean("has_height", true),
                hasSteps = jsonObject.optBoolean("has_steps", true),
                hasSamplerName = jsonObject.optBoolean("has_sampler_name", true),
                hasScheduler = jsonObject.optBoolean("has_scheduler", true),
                hasMegapixels = jsonObject.optBoolean("has_megapixels", true),
                hasLength = jsonObject.optBoolean("has_length", true),
                hasFrameRate = jsonObject.optBoolean("has_frame_rate", true),
                hasVaeName = jsonObject.optBoolean("has_vae_name", true),
                hasClipName = jsonObject.optBoolean("has_clip_name", true),
                hasClipName1 = jsonObject.optBoolean("has_clip_name1", false),
                hasClipName2 = jsonObject.optBoolean("has_clip_name2", false),
                hasClipName3 = jsonObject.optBoolean("has_clip_name3", false),
                hasClipName4 = jsonObject.optBoolean("has_clip_name4", false),
                hasLoraName = jsonObject.optBoolean("has_lora_name", true),
                // Dual-UNET/LoRA flags (default to false)
                hasLownoiseUnet = jsonObject.optBoolean("has_lownoise_unet", false),
                hasHighnoiseLora = jsonObject.optBoolean("has_highnoise_lora", false),
                hasLownoiseLora = jsonObject.optBoolean("has_lownoise_lora", false),
                // Model presence flags (default to false)
                hasCheckpointName = jsonObject.optBoolean("has_checkpoint_name", false),
                hasUnetName = jsonObject.optBoolean("has_unet_name", false),
                hasHighnoiseUnet = jsonObject.optBoolean("has_highnoise_unet", false),
                // ITE reference image flags (default to false)
                hasReferenceImage1 = jsonObject.optBoolean("has_reference_image_1", false),
                hasReferenceImage2 = jsonObject.optBoolean("has_reference_image_2", false)
            )
        }

        fun toJson(defaults: WorkflowDefaults): JSONObject {
            return JSONObject().apply {
                defaults.width?.let { put("width", it) }
                defaults.height?.let { put("height", it) }
                defaults.steps?.let { put("steps", it) }
                defaults.cfg?.let { put("cfg", it.toDouble()) }
                defaults.samplerName?.let { put("sampler_name", it) }
                defaults.scheduler?.let { put("scheduler", it) }
                defaults.negativePrompt?.let { put("negative_prompt", it) }
                defaults.megapixels?.let { put("megapixels", it.toDouble()) }
                defaults.length?.let { put("length", it) }
                defaults.frameRate?.let { put("frame_rate", it) }
                // Only include capability flags if they differ from defaults
                if (!defaults.hasNegativePrompt) put("has_negative_prompt", false)
                if (!defaults.hasCfg) put("has_cfg", false)
                // Only include field presence flags if they differ from defaults (false)
                if (!defaults.hasWidth) put("has_width", false)
                if (!defaults.hasHeight) put("has_height", false)
                if (!defaults.hasSteps) put("has_steps", false)
                if (!defaults.hasSamplerName) put("has_sampler_name", false)
                if (!defaults.hasScheduler) put("has_scheduler", false)
                if (!defaults.hasMegapixels) put("has_megapixels", false)
                if (!defaults.hasLength) put("has_length", false)
                if (!defaults.hasFrameRate) put("has_frame_rate", false)
                if (!defaults.hasVaeName) put("has_vae_name", false)
                if (!defaults.hasClipName) put("has_clip_name", false)
                // Multi-CLIP flags (only write if true since default is false)
                if (defaults.hasClipName1) put("has_clip_name1", true)
                if (defaults.hasClipName2) put("has_clip_name2", true)
                if (defaults.hasClipName3) put("has_clip_name3", true)
                if (defaults.hasClipName4) put("has_clip_name4", true)
                if (!defaults.hasLoraName) put("has_lora_name", false)
                // Dual-UNET/LoRA flags (only write if true since default is false)
                if (defaults.hasLownoiseUnet) put("has_lownoise_unet", true)
                if (defaults.hasHighnoiseLora) put("has_highnoise_lora", true)
                if (defaults.hasLownoiseLora) put("has_lownoise_lora", true)
                // Model presence flags (only write if true since default is false)
                if (defaults.hasCheckpointName) put("has_checkpoint_name", true)
                if (defaults.hasUnetName) put("has_unet_name", true)
                if (defaults.hasHighnoiseUnet) put("has_highnoise_unet", true)
                // ITE reference image flags (only write if true since default is false)
                if (defaults.hasReferenceImage1) put("has_reference_image_1", true)
                if (defaults.hasReferenceImage2) put("has_reference_image_2", true)
            }
        }
    }
}
