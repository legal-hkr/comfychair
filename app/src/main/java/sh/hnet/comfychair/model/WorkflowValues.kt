package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Represents user-saved values for a specific workflow.
 * These values are persisted per-workflow and restored when switching workflows.
 */
data class WorkflowValues(
    val workflowId: String,

    // Generation parameters (nullable = use workflow default)
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val samplerName: String? = null,
    val scheduler: String? = null,

    // Prompt parameters
    val negativePrompt: String? = null,

    // Inpainting specific
    val megapixels: Float? = null,

    // Video specific
    val length: Int? = null,
    val frameRate: Int? = null,

    // Model selections (always saved, never in defaults)
    val checkpointModel: String? = null,
    val unetModel: String? = null,
    val vaeModel: String? = null,
    val clipModel: String? = null,
    val highnoiseUnetModel: String? = null,
    val lownoiseUnetModel: String? = null,
    val highnoiseLoraModel: String? = null,
    val lownoiseLoraModel: String? = null,

    // LoRA chains (JSON serialized using LoraSelection.toJsonString/fromJsonString)
    val loraChain: String? = null,
    val highnoiseLoraChain: String? = null,
    val lownoiseLoraChain: String? = null
) {
    companion object {
        fun fromJson(jsonString: String): WorkflowValues {
            val json = JSONObject(jsonString)
            return WorkflowValues(
                workflowId = json.optString("workflowId", ""),
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
                checkpointModel = json.optString("checkpointModel").takeIf { it.isNotEmpty() },
                unetModel = json.optString("unetModel").takeIf { it.isNotEmpty() },
                vaeModel = json.optString("vaeModel").takeIf { it.isNotEmpty() },
                clipModel = json.optString("clipModel").takeIf { it.isNotEmpty() },
                highnoiseUnetModel = json.optString("highnoiseUnetModel").takeIf { it.isNotEmpty() },
                lownoiseUnetModel = json.optString("lownoiseUnetModel").takeIf { it.isNotEmpty() },
                highnoiseLoraModel = json.optString("highnoiseLoraModel").takeIf { it.isNotEmpty() },
                lownoiseLoraModel = json.optString("lownoiseLoraModel").takeIf { it.isNotEmpty() },
                loraChain = json.optString("loraChain").takeIf { it.isNotEmpty() },
                highnoiseLoraChain = json.optString("highnoiseLoraChain").takeIf { it.isNotEmpty() },
                lownoiseLoraChain = json.optString("lownoiseLoraChain").takeIf { it.isNotEmpty() }
            )
        }

        fun toJson(values: WorkflowValues): String {
            return JSONObject().apply {
                put("workflowId", values.workflowId)
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
                values.checkpointModel?.let { put("checkpointModel", it) }
                values.unetModel?.let { put("unetModel", it) }
                values.vaeModel?.let { put("vaeModel", it) }
                values.clipModel?.let { put("clipModel", it) }
                values.highnoiseUnetModel?.let { put("highnoiseUnetModel", it) }
                values.lownoiseUnetModel?.let { put("lownoiseUnetModel", it) }
                values.highnoiseLoraModel?.let { put("highnoiseLoraModel", it) }
                values.lownoiseLoraModel?.let { put("lownoiseLoraModel", it) }
                values.loraChain?.let { put("loraChain", it) }
                values.highnoiseLoraChain?.let { put("highnoiseLoraChain", it) }
                values.lownoiseLoraChain?.let { put("lownoiseLoraChain", it) }
            }.toString()
        }
    }
}
