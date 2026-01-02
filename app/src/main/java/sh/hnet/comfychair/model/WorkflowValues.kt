package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Represents user-saved values for a specific workflow.
 * These values are persisted per-workflow and restored when switching workflows.
 * Note: workflowId is stored externally in the storage key, not in this class.
 */
data class WorkflowValues(
    // Generation parameters (nullable = use workflow default)
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

    // Model selections (always saved, never in defaults)
    val model: String? = null,      // Unified model (checkpoint or UNET)
    val loraModel: String? = null,  // Mandatory LoRA for editing mode
    val vaeModel: String? = null,
    val clipModel: String? = null,
    val clip1Model: String? = null,  // For multi-CLIP slot 1
    val clip2Model: String? = null,  // For multi-CLIP slot 2
    val clip3Model: String? = null,  // For multi-CLIP slot 3
    val clip4Model: String? = null,  // For multi-CLIP slot 4
    val highnoiseUnetModel: String? = null,
    val lownoiseUnetModel: String? = null,
    val highnoiseLoraModel: String? = null,
    val lownoiseLoraModel: String? = null,

    // LoRA chains (JSON serialized using LoraSelection.toJsonString/fromJsonString)
    val loraChain: String? = null,
    val highnoiseLoraChain: String? = null,
    val lownoiseLoraChain: String? = null,

    // Node attribute edits (JSON serialized using NodeAttributeEdits.toJson/fromJson)
    val nodeAttributeEdits: String? = null,

    // Advanced generation parameters
    val seed: Long? = null,
    val randomSeed: Boolean? = null,  // null = use default (true)
    val denoise: Float? = null,
    val batchSize: Int? = null,
    val upscaleMethod: String? = null,
    val scaleBy: Float? = null,
    val stopAtClipLayer: Int? = null
) {
    companion object {
        fun fromJson(jsonString: String): WorkflowValues {
            val json = JSONObject(jsonString)
            return WorkflowValues(
                width = json.optInt("width").takeIf { it > 0 },
                height = json.optInt("height").takeIf { it > 0 },
                steps = json.optInt("steps").takeIf { it > 0 },
                cfg = json.optDouble("cfg").takeIf { !it.isNaN() }?.toFloat(),
                samplerName = json.optString("samplerName").takeIf { it.isNotEmpty() },
                scheduler = json.optString("scheduler").takeIf { it.isNotEmpty() },
                negativePrompt = if (json.has("negativePrompt")) json.optString("negativePrompt") else null,
                megapixels = json.optDouble("megapixels").takeIf { !it.isNaN() }?.toFloat(),
                length = json.optInt("length").takeIf { it > 0 },
                frameRate = json.optInt("frameRate").takeIf { it > 0 },
                model = json.optString("model").takeIf { it.isNotEmpty() },
                loraModel = json.optString("loraModel").takeIf { it.isNotEmpty() },
                vaeModel = json.optString("vaeModel").takeIf { it.isNotEmpty() },
                clipModel = json.optString("clipModel").takeIf { it.isNotEmpty() },
                clip1Model = json.optString("clip1Model").takeIf { it.isNotEmpty() },
                clip2Model = json.optString("clip2Model").takeIf { it.isNotEmpty() },
                clip3Model = json.optString("clip3Model").takeIf { it.isNotEmpty() },
                clip4Model = json.optString("clip4Model").takeIf { it.isNotEmpty() },
                highnoiseUnetModel = json.optString("highnoiseUnetModel").takeIf { it.isNotEmpty() },
                lownoiseUnetModel = json.optString("lownoiseUnetModel").takeIf { it.isNotEmpty() },
                highnoiseLoraModel = json.optString("highnoiseLoraModel").takeIf { it.isNotEmpty() },
                lownoiseLoraModel = json.optString("lownoiseLoraModel").takeIf { it.isNotEmpty() },
                loraChain = json.optString("loraChain").takeIf { it.isNotEmpty() },
                highnoiseLoraChain = json.optString("highnoiseLoraChain").takeIf { it.isNotEmpty() },
                lownoiseLoraChain = json.optString("lownoiseLoraChain").takeIf { it.isNotEmpty() },
                nodeAttributeEdits = json.optString("nodeAttributeEdits").takeIf { it.isNotEmpty() },
                seed = json.optLong("seed", -1).takeIf { it >= 0 },
                randomSeed = if (json.has("randomSeed")) json.optBoolean("randomSeed") else null,
                denoise = json.optDouble("denoise").takeIf { !it.isNaN() }?.toFloat(),
                batchSize = json.optInt("batchSize").takeIf { it > 0 },
                upscaleMethod = json.optString("upscaleMethod").takeIf { it.isNotEmpty() },
                scaleBy = json.optDouble("scaleBy").takeIf { !it.isNaN() }?.toFloat(),
                stopAtClipLayer = json.optInt("stopAtClipLayer", 0).takeIf { it != 0 }
            )
        }

        fun toJson(values: WorkflowValues): String {
            return JSONObject().apply {
                values.width?.let { put("width", it) }
                values.height?.let { put("height", it) }
                values.steps?.let { put("steps", it) }
                values.cfg?.let { put("cfg", it.toDouble()) }
                values.samplerName?.let { put("samplerName", it) }
                values.scheduler?.let { put("scheduler", it) }
                values.negativePrompt?.let { put("negativePrompt", it) }
                values.megapixels?.let { put("megapixels", it.toDouble()) }
                values.length?.let { put("length", it) }
                values.frameRate?.let { put("frameRate", it) }
                values.model?.let { put("model", it) }
                values.loraModel?.let { put("loraModel", it) }
                values.vaeModel?.let { put("vaeModel", it) }
                values.clipModel?.let { put("clipModel", it) }
                values.clip1Model?.let { put("clip1Model", it) }
                values.clip2Model?.let { put("clip2Model", it) }
                values.clip3Model?.let { put("clip3Model", it) }
                values.clip4Model?.let { put("clip4Model", it) }
                values.highnoiseUnetModel?.let { put("highnoiseUnetModel", it) }
                values.lownoiseUnetModel?.let { put("lownoiseUnetModel", it) }
                values.highnoiseLoraModel?.let { put("highnoiseLoraModel", it) }
                values.lownoiseLoraModel?.let { put("lownoiseLoraModel", it) }
                values.loraChain?.let { put("loraChain", it) }
                values.highnoiseLoraChain?.let { put("highnoiseLoraChain", it) }
                values.lownoiseLoraChain?.let { put("lownoiseLoraChain", it) }
                values.nodeAttributeEdits?.let { put("nodeAttributeEdits", it) }
                values.seed?.let { put("seed", it) }
                values.randomSeed?.let { put("randomSeed", it) }
                values.denoise?.let { put("denoise", it.toDouble()) }
                values.batchSize?.let { put("batchSize", it) }
                values.upscaleMethod?.let { put("upscaleMethod", it) }
                values.scaleBy?.let { put("scaleBy", it.toDouble()) }
                values.stopAtClipLayer?.let { put("stopAtClipLayer", it) }
            }.toString()
        }
    }
}
