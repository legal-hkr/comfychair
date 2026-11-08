package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient

/**
 * The files in the server's input folder that a screen can use as its source (spec §21): images or videos, newest
 * first where the server tells the order. A server file is used by its name, without uploading it again.
 */
object ServerInputs {

    /** The folder type ComfyUI's /view and the media cache take for these files. */
    const val TYPE = "input"

    /** A file in the input folder; [name] includes its subfolder ("pasted/image.png"), as the workflow takes it. */
    data class File(val name: String) {
        val subfolder: String get() = name.substringBeforeLast('/', "")
        val filename: String get() = name.substringAfterLast('/')
    }

    sealed interface ListResult {
        data class Ok(val files: List<File>) : ListResult
        /** No list: [details] for the error's Show (an HTTP status or the client's message). */
        data class Failed(val details: String?) : ListResult
    }

    /**
     * The name prefix of the images inpainting uploads: the source with its mask in the alpha channel, of no use as
     * a source (the masked part is cut out).
     */
    const val INPAINT_PREFIX = "inpaint_source"

    // What ComfyUI's own mask editor saves (the web interface): four files per edit (the image painted and masked,
    // painted, the paint layer alone, the mask), none of them a source; in the input folder as "clipspace-…", in a
    // "clipspace" subfolder before
    private const val MASK_EDITOR_PREFIX = "clipspace"

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff")
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "mkv", "avi", "m4v")
    private const val MAX_LIST_BYTES = 4L * 1024 * 1024

    /** A file made on the way to a job, not a source: inpainting's masked uploads and the mask editor's files. */
    internal fun isWorkFile(name: String): Boolean =
        name.startsWith("${INPAINT_PREFIX}_") || name.startsWith("$MASK_EDITOR_PREFIX/") ||
            name.substringAfterLast('/').startsWith("$MASK_EDITOR_PREFIX-")

    fun isVideoName(name: String): Boolean = name.substringAfterLast('.').lowercase() in VIDEO_EXTENSIONS

    /**
     * The server's [video] or image inputs. ComfyUI's file listing gives them newest first (an internal endpoint, so
     * it may go); else the options of LoadImage's or LoadVideo's file input, by name descending.
     */
    suspend fun list(client: ComfyUIClient, video: Boolean): ListResult {
        val extensions = if (video) VIDEO_EXTENSIONS else IMAGE_EXTENSIONS
        fun accepted(names: List<String>) = names
            .filter { it.substringAfterLast('.').lowercase() in extensions }
            .filterNot { isWorkFile(it) }
            .map(::File)

        when (val listing = client.getText("/internal/files/input", MAX_LIST_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> parseListing(listing.text)?.let { return ListResult.Ok(accepted(it)) }
            else -> Unit
        }
        val (node, input) = if (video) "LoadVideo" to "file" else "LoadImage" to "image"
        return when (val info = client.getText("/object_info/$node", MAX_LIST_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> parseOptions(info.text, node, input)
                ?.let { ListResult.Ok(accepted(it.sortedDescending())) }
                ?: ListResult.Failed(null)
            is ComfyUIClient.TextResult.HttpError -> ListResult.Failed("HTTP ${info.code}")
            ComfyUIClient.TextResult.TooLarge -> ListResult.Failed(null)
            is ComfyUIClient.TextResult.Failed -> ListResult.Failed(info.message)
        }
    }

    /** The file listing's names ("name [input]"), without the folder mark; null if it isn't one. */
    internal fun parseListing(text: String): List<String>? = try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty)?.removeSuffix(" [input]") }
    } catch (_: Exception) {
        null
    }

    /** The options of [node]'s [input] in object_info: a plain list or the newer COMBO with "options". */
    internal fun parseOptions(text: String, node: String, input: String): List<String>? = try {
        val spec = JSONObject(text).getJSONObject(node).getJSONObject("input")
        val entry = (spec.optJSONObject("required")?.optJSONArray(input) ?: spec.optJSONObject("optional")?.optJSONArray(input))!!
        val first = entry.opt(0)
        val options = (first as? JSONArray) ?: entry.optJSONObject(1)?.optJSONArray("options")
        options?.let { list -> (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotEmpty) } }
    } catch (_: Exception) {
        null
    }
}
