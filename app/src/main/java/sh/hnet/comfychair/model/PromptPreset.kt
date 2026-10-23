package sh.hnet.comfychair.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.UuidUtils

/**
 * Screen types for prompt preset categorization.
 * Each generation screen has its own separate preset library.
 */
enum class ScreenType {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE,  // Shared for both Inpainting and Editing modes
    TEXT_TO_VIDEO,
    IMAGE_TO_VIDEO,
    VIDEO_TO_VIDEO
}

/**
 * Get the localized display name for this screen type.
 */
@Composable
fun ScreenType.displayName(): String = when (this) {
    ScreenType.TEXT_TO_IMAGE -> stringResource(R.string.screen_type_text_to_image)
    ScreenType.IMAGE_TO_IMAGE -> stringResource(R.string.screen_type_image_to_image)
    ScreenType.TEXT_TO_VIDEO -> stringResource(R.string.screen_type_text_to_video)
    ScreenType.IMAGE_TO_VIDEO -> stringResource(R.string.screen_type_image_to_video)
    ScreenType.VIDEO_TO_VIDEO -> stringResource(R.string.screen_type_video_to_video)
}

/**
 * Represents a saved prompt preset.
 */
@Immutable
data class PromptPreset(
    val id: String,
    val screenType: ScreenType,
    val name: String,
    val prompt: String,
    val tags: List<String>,
    val isFavorite: Boolean,
    val createdAt: Long
) {
    companion object {
        /**
         * Create a new preset with auto-generated UUID.
         */
        fun create(
            screenType: ScreenType,
            name: String,
            prompt: String,
            tags: List<String> = emptyList()
        ): PromptPreset {
            return PromptPreset(
                id = UuidUtils.generateRandomId(),
                screenType = screenType,
                name = name,
                prompt = prompt,
                tags = tags,
                isFavorite = false,
                createdAt = System.currentTimeMillis()
            )
        }

        /**
         * Parse preset from JSON object.
         * Returns null if required fields are missing or invalid.
         */
        fun fromJson(json: JSONObject): PromptPreset? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val screenTypeStr = json.optString("screenType").takeIf { it.isNotEmpty() } ?: return null
            val screenType = try {
                ScreenType.valueOf(screenTypeStr)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val prompt = json.optString("prompt", "")
            val tagsArray = json.optJSONArray("tags")
            val tags = if (tagsArray != null) {
                (0 until tagsArray.length()).mapNotNull {
                    tagsArray.optString(it).takeIf { s -> s.isNotEmpty() }
                }
            } else {
                emptyList()
            }
            val isFavorite = json.optBoolean("isFavorite", false)
            val createdAt = json.optLong("createdAt", System.currentTimeMillis())

            return PromptPreset(id, screenType, name, prompt, tags, isFavorite, createdAt)
        }
    }

    /**
     * Convert preset to JSON object.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("screenType", screenType.name)
            put("name", name)
            put("prompt", prompt)
            put("tags", JSONArray(tags))
            put("isFavorite", isFavorite)
            put("createdAt", createdAt)
        }
    }
}
