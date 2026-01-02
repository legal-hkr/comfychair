package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Represents default values for a workflow.
 * These values are stored in the workflow JSON and used when no user-saved values exist.
 *
 * Note: Field visibility is determined automatically by checking for {{placeholder}} patterns
 * in the workflow JSON using WorkflowManager.getWorkflowPlaceholders(), not by has* flags.
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

    // Advanced generation parameter defaults
    val seed: Long? = null,
    val denoise: Float? = null,
    val batchSize: Int? = null,
    val upscaleMethod: String? = null,
    val scaleBy: Float? = null,
    val stopAtClipLayer: Int? = null
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
                // Advanced generation parameter defaults
                seed = jsonObject.optLong("seed", -1).takeIf { it >= 0 },
                denoise = jsonObject.optDouble("denoise").takeIf { !it.isNaN() }?.toFloat(),
                batchSize = jsonObject.optInt("batch_size").takeIf { it > 0 },
                upscaleMethod = jsonObject.optString("upscale_method").takeIf { it.isNotEmpty() },
                scaleBy = jsonObject.optDouble("scale_by").takeIf { !it.isNaN() }?.toFloat(),
                stopAtClipLayer = jsonObject.optInt("stop_at_clip_layer", 0).takeIf { it != 0 }
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
                // Advanced generation parameter defaults
                defaults.seed?.let { put("seed", it) }
                defaults.denoise?.let { put("denoise", it.toDouble()) }
                defaults.batchSize?.let { put("batch_size", it) }
                defaults.upscaleMethod?.let { put("upscale_method", it) }
                defaults.scaleBy?.let { put("scale_by", it.toDouble()) }
                defaults.stopAtClipLayer?.let { put("stop_at_clip_layer", it) }
            }
        }
    }
}
