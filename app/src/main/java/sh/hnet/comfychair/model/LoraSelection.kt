package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single LoRA selection with name and strength.
 * Used in LoRA chain configurations across all workflow types.
 */
@Immutable
data class LoraSelection(
    val name: String,
    val strength: Float = DEFAULT_STRENGTH
) {
    companion object {
        const val MIN_STRENGTH = 0.0f
        const val MAX_STRENGTH = 2.0f
        const val DEFAULT_STRENGTH = 1.0f
        const val MAX_CHAIN_LENGTH = 5

        /**
         * Deserialize a LoRA chain from JSON array string.
         * Format: [{"name": "lora1.safetensors", "strength": 1.0}, ...]
         */
        fun fromJsonString(jsonString: String?): List<LoraSelection> {
            if (jsonString.isNullOrBlank()) return emptyList()
            return try {
                val jsonArray = JSONArray(jsonString)
                (0 until jsonArray.length()).mapNotNull { i ->
                    val obj = jsonArray.optJSONObject(i)
                    val name = obj?.optString("name", "") ?: ""
                    val strength = obj?.optDouble("strength", DEFAULT_STRENGTH.toDouble())?.toFloat()
                        ?: DEFAULT_STRENGTH
                    if (name.isNotEmpty()) LoraSelection(name, strength) else null
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Serialize a LoRA chain to JSON array string.
         */
        fun toJsonString(loraChain: List<LoraSelection>): String {
            val jsonArray = JSONArray()
            loraChain.forEach { lora ->
                jsonArray.put(JSONObject().apply {
                    put("name", lora.name)
                    put("strength", lora.strength.toDouble())
                })
            }
            return jsonArray.toString()
        }
    }
}
