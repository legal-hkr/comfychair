package sh.hnet.comfychair.model

import okhttp3.HttpUrl
import org.json.JSONObject

/** Indexed assets are not a filesystem snapshot; totals include non-image assets. */
enum class ServerImageSort(val field: String, val order: String) {
    NEWEST("created_at", "desc"), OLDEST("created_at", "asc"),
    NAME("name", "asc"), SIZE("size", "desc")
}
data class ServerImageQuery(val name: String = "", val sort: ServerImageSort = ServerImageSort.NEWEST,
    val cursor: String? = null, val legacyOffset: Int? = null)

object ServerAssetDiscovery {
    fun contentUrl(base: HttpUrl, id: String): HttpUrl {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return base.newBuilder().addPathSegment("api").addPathSegment("assets")
            .addPathSegment(id).addPathSegment("content").build()
    }

    /** Only native, relative /view descriptors qualify a namespace; tags alone are mutable. */
    fun image(asset: JSONObject, source: ServerImageSource, base: HttpUrl): ServerImage? {
        val tags = asset.optJSONArray("tags") ?: return null
        val labels = (0 until tags.length()).map { tags.optString(it) }
        if (source.type !in labels || "missing" in labels || !asset.optString("mime_type").startsWith("image/")) return null
        val raw = asset.optString("preview_url")
        if (!raw.startsWith('/') || raw.startsWith("//") || '\\' in raw || raw.any { it.code < 32 }) return null
        val url = base.resolve(raw) ?: return null
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            url.encodedPath !in setOf("/api/view", "/view") ||
            url.queryParameterNames.any { it !in setOf("filename", "subfolder", "type") } ||
            url.queryParameterNames.any { url.queryParameterValues(it).size != 1 } ||
            url.queryParameter("type") != source.type) return null
        val filename = url.queryParameter("filename") ?: return null
        if ('/' in filename) return null
        val folder = url.queryParameter("subfolder").orEmpty()
        val descriptor = ServerImageDiscovery.descriptor((if (folder.isEmpty()) "" else "$folder/") + filename, source.type) ?: return null
        val id = asset.optString("id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return null
        return descriptor.copy(assetId = id)
    }

    fun page(json: JSONObject, source: ServerImageSource, base: HttpUrl, previous: String?): ServerImagePage {
        val assets = json.getJSONArray("assets")
        val total = json.getLong("total").also { require(it >= 0) }
        val more = json.getBoolean("has_more")
        val cursor = if (more) json.getString("next_cursor").also {
            require(it.isNotBlank() && it.length <= 8192 && it != previous)
        } else null
        return ServerImagePage((0 until assets.length()).mapNotNull { image(assets.getJSONObject(it), source, base) }.distinct(),
            nextCursor = cursor, assets = true, totalAssets = total)
    }
}
