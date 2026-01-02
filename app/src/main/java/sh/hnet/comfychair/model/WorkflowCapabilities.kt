package sh.hnet.comfychair.model

/**
 * Unified workflow capability flags derived from workflow placeholders.
 *
 * This data class provides a single source of truth for all capability flags,
 * eliminating duplicate placeholder-to-flag mapping code across ViewModels.
 *
 * Naming convention:
 * - `has*Name` → dropdown visibility (checks placeholder existence)
 * - `has*` (no Name suffix) → LoRA injection chain (checks target model loader)
 */
data class WorkflowCapabilities(
    // Parameter flags
    val hasNegativePrompt: Boolean = false,
    val hasCfg: Boolean = false,
    val hasWidth: Boolean = false,
    val hasHeight: Boolean = false,
    val hasMegapixels: Boolean = false,
    val hasLength: Boolean = false,
    val hasFrameRate: Boolean = false,
    val hasSteps: Boolean = false,
    val hasSamplerName: Boolean = false,
    val hasScheduler: Boolean = false,
    val hasSeed: Boolean = false,
    val hasDenoise: Boolean = false,
    val hasBatchSize: Boolean = false,
    val hasUpscaleMethod: Boolean = false,
    val hasScaleBy: Boolean = false,
    val hasStopAtClipLayer: Boolean = false,

    // Model dropdown flags (*Name pattern)
    val hasCheckpointName: Boolean = false,
    val hasUnetName: Boolean = false,
    val hasHighnoiseUnetName: Boolean = false,
    val hasLownoiseUnetName: Boolean = false,
    val hasVaeName: Boolean = false,
    val hasClipName: Boolean = false,
    val hasClipName1: Boolean = false,
    val hasClipName2: Boolean = false,
    val hasClipName3: Boolean = false,
    val hasClipName4: Boolean = false,

    // Mandatory LoRA dropdown flags (*Name pattern)
    val hasLoraName: Boolean = false,
    val hasHighnoiseLoraName: Boolean = false,
    val hasLownoiseLoraName: Boolean = false,

    // LoRA injection chain flags (no *Name suffix)
    // These are triggered by model loader placeholders, not LoRA placeholders
    val hasLora: Boolean = false,           // Triggered by ckpt_name OR unet_name
    val hasHighnoiseLora: Boolean = false,  // Triggered by highnoise_unet_name
    val hasLownoiseLora: Boolean = false,   // Triggered by lownoise_unet_name

    // Reference images (ITI/ITV specific)
    val hasReferenceImage1: Boolean = false,
    val hasReferenceImage2: Boolean = false,

    // Derived flags
    val isCheckpointMode: Boolean = false
) {
    companion object {
        /**
         * Creates WorkflowCapabilities from a set of workflow placeholders.
         *
         * @param placeholders Set of placeholder names found in the workflow
         * @return WorkflowCapabilities with all flags set based on placeholder presence
         */
        fun fromPlaceholders(placeholders: Set<String>): WorkflowCapabilities {
            return WorkflowCapabilities(
                // Parameters
                hasNegativePrompt = "negative_prompt" in placeholders,
                hasCfg = "cfg" in placeholders,
                hasWidth = "width" in placeholders,
                hasHeight = "height" in placeholders,
                hasMegapixels = "megapixels" in placeholders,
                hasLength = "length" in placeholders,
                hasFrameRate = "frame_rate" in placeholders,
                hasSteps = "steps" in placeholders,
                hasSamplerName = "sampler_name" in placeholders,
                hasScheduler = "scheduler" in placeholders,
                hasSeed = "seed" in placeholders,
                hasDenoise = "denoise" in placeholders,
                hasBatchSize = "batch_size" in placeholders,
                hasUpscaleMethod = "upscale_method" in placeholders,
                hasScaleBy = "scale_by" in placeholders,
                hasStopAtClipLayer = "stop_at_clip_layer" in placeholders,

                // Model dropdowns
                hasCheckpointName = "ckpt_name" in placeholders,
                hasUnetName = "unet_name" in placeholders,
                hasHighnoiseUnetName = "highnoise_unet_name" in placeholders,
                hasLownoiseUnetName = "lownoise_unet_name" in placeholders,
                hasVaeName = "vae_name" in placeholders,
                hasClipName = "clip_name" in placeholders,
                hasClipName1 = "clip_name1" in placeholders,
                hasClipName2 = "clip_name2" in placeholders,
                hasClipName3 = "clip_name3" in placeholders,
                hasClipName4 = "clip_name4" in placeholders,

                // Mandatory LoRA dropdowns
                hasLoraName = "lora_name" in placeholders,
                hasHighnoiseLoraName = "highnoise_lora_name" in placeholders,
                hasLownoiseLoraName = "lownoise_lora_name" in placeholders,

                // LoRA injection chains (triggered by model loader placeholders)
                hasLora = "ckpt_name" in placeholders || "unet_name" in placeholders,
                hasHighnoiseLora = "highnoise_unet_name" in placeholders,
                hasLownoiseLora = "lownoise_unet_name" in placeholders,

                // Reference images (check both naming conventions)
                hasReferenceImage1 = "reference_image_1" in placeholders || "reference_1" in placeholders,
                hasReferenceImage2 = "reference_image_2" in placeholders || "reference_2" in placeholders,

                // Derived
                isCheckpointMode = "ckpt_name" in placeholders
            )
        }
    }
}
