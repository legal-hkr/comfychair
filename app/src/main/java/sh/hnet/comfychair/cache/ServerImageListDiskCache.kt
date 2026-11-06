package sh.hnet.comfychair.cache

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.model.*

class ServerImageListDiskCache(private val disk: PrivateImageDiskCache, private val owner: String) {
    private fun key(source: ServerImageSource, query: ServerImageQuery) =
        "list:${disk.listVersion(owner, source.type)}:${source.name}:${query.sort.name}:${query.name.length}:${query.name}"
    fun get(source: ServerImageSource, query: ServerImageQuery): ServerImagePage? {
        val id = key(source, query)
        val bytes = disk.get(owner, id, 24 * 60 * 60 * 1000L) ?: return null
        return try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val array = json.getJSONArray("images")
            require(array.length() <= 5000)
            val images = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val descriptor = ServerImageDiscovery.descriptor(item.getString("path"), source.type)
                    ?: throw IllegalArgumentException("Invalid cached image")
                descriptor.copy(assetId = item.optString("id").takeIf { it.isNotEmpty() }?.also {
                    require(it.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                })
            }
            ServerImagePage(images, if (json.has("offset")) json.getInt("offset") else null,
                json.optString("cursor").takeIf { it.isNotEmpty() }, json.getBoolean("assets"),
                if (json.has("total")) json.getLong("total") else null)
        } catch (_: Exception) { disk.remove(owner, id); null }
    }
    fun put(source: ServerImageSource, query: ServerImageQuery, page: ServerImagePage, generation: Long = disk.generation()) {
        if (page.images.size > 5000) return
        val json = JSONObject().put("assets", page.assets).put("images", JSONArray().apply {
            page.images.forEach { put(JSONObject().put("path", it.path).put("id", it.assetId.orEmpty())) }
        })
        page.nextOffset?.let { json.put("offset", it) }
        page.nextCursor?.let { json.put("cursor", it) }
        page.totalAssets?.let { json.put("total", it) }
        disk.put(owner, key(source, query), json.toString().toByteArray(Charsets.UTF_8), generation)
    }
}
