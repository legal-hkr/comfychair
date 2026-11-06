package sh.hnet.comfychair.model

import okhttp3.HttpUrl
import org.json.JSONObject

/** Discovery sources, not filesystem directory listings. */
enum class ServerImageSource(val type: String) { INPUT("input"), OUTPUT("output") }

data class ServerImage(val filename: String, val subfolder: String, val type: String, val assetId: String? = null) {
    // LazyGrid keys must be Bundle-saveable; data classes are not automatically Parcelable.
    val stableKey: String get() = listOf(type, subfolder, filename, assetId.orEmpty()).joinToString("") { "${it.length}:$it" }
    val path: String get() = if (subfolder.isEmpty()) filename else "$subfolder/$filename"

    fun viewUrl(base: HttpUrl): HttpUrl = base.newBuilder().addPathSegment("view")
        .addQueryParameter("filename", filename)
        .addQueryParameter("subfolder", subfolder)
        .addQueryParameter("type", type).build()
}

/** Cursor is an oldest-based history RECORD offset, never an image count. */
data class ServerImagePage(val images: List<ServerImage>, val nextOffset: Int? = null,
    val nextCursor: String? = null, val assets: Boolean = false, val totalAssets: Long? = null)

object ServerImageDiscovery {
    private val extensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff", "avif")

    fun descriptor(path: String, type: String): ServerImage? {
        if (type !in setOf("input", "output") || path.isBlank() || path.startsWith('/') ||
            '\\' in path || ':' in path || path.any { it.code < 32 } ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            path.substringAfterLast('.', "").lowercase() !in extensions) return null
        return ServerImage(path.substringAfterLast('/'), path.substringBeforeLast('/', ""), type)
    }

    fun input(json: JSONObject): List<ServerImage> {
        val options = json.getJSONObject("LoadImage").getJSONObject("input")
            .getJSONObject("required").getJSONArray("image").getJSONArray(0)
        return (0 until options.length()).mapNotNull { index ->
            (options.opt(index) as? String)?.let { descriptor(it, "input") }
        }.distinct().sortedBy { it.path.lowercase() }
    }

    fun output(json: JSONObject): List<ServerImage> {
        // Order by execution number, not JSON object iteration order. Newest first.
        return json.keys().asSequence().mapNotNull { json.optJSONObject(it) }
            .sortedByDescending { it.optJSONArray("prompt")?.optDouble(0) ?: 0.0 }
            .flatMap { entry ->
                val outputs = entry.optJSONObject("outputs") ?: return@flatMap emptySequence()
                outputs.keys().asSequence().flatMap { node ->
                    val images = outputs.optJSONObject(node)?.optJSONArray("images")
                        ?: return@flatMap emptySequence<ServerImage>()
                    (0 until images.length()).asSequence().mapNotNull { index ->
                        val image = images.optJSONObject(index) ?: return@mapNotNull null
                        if (image.optString("type", "output") != "output") return@mapNotNull null
                        val folder = image.optString("subfolder", "")
                        descriptor((if (folder.isEmpty()) "" else "$folder/") + image.optString("filename"), "output")
                    }
                }
            }.distinct().toList()
    }
}
