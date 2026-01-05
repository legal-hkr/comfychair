package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * Disk cache for gallery metadata (list of gallery items).
 * Used to persist gallery items for offline mode browsing.
 *
 * Each server has its own cache file: "gallery_metadata_{serverId}.json"
 */
object GalleryMetadataCache {
    private const val TAG = "GalleryMetadataCache"
    private const val CACHE_PREFIX = "gallery_metadata_"
    private const val CACHE_SUFFIX = ".json"

    /**
     * Save gallery items to disk cache for a specific server.
     *
     * @param context Application context
     * @param serverId Server ID to scope the cache
     * @param items List of gallery items to cache
     */
    fun saveMetadata(context: Context, serverId: String, items: List<GalleryItem>) {
        try {
            val cacheFile = getCacheFile(context, serverId)

            // Convert items to JSON array
            val jsonArray = JSONArray()
            for (item in items) {
                val jsonItem = JSONObject().apply {
                    put("promptId", item.promptId)
                    put("filename", item.filename)
                    put("subfolder", item.subfolder)
                    put("type", item.type)
                    put("isVideo", item.isVideo)
                    put("index", item.index)
                }
                jsonArray.put(jsonItem)
            }

            // Write to file
            cacheFile.writeText(jsonArray.toString())
            DebugLogger.i(TAG, "Gallery metadata cached for server $serverId: ${items.size} items, ${cacheFile.length()} bytes")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save gallery metadata for server $serverId: ${e.message}")
        }
    }

    /**
     * Load gallery items from disk cache for a specific server.
     *
     * @param context Application context
     * @param serverId Server ID to load cache for
     * @return List of cached gallery items, or null if cache doesn't exist or is invalid
     */
    fun loadMetadata(context: Context, serverId: String): List<GalleryItem>? {
        try {
            val cacheFile = getCacheFile(context, serverId)
            if (!cacheFile.exists()) {
                DebugLogger.d(TAG, "No gallery metadata cache found for server $serverId")
                return null
            }

            val jsonText = cacheFile.readText()
            val jsonArray = JSONArray(jsonText)

            val items = mutableListOf<GalleryItem>()
            for (i in 0 until jsonArray.length()) {
                val jsonItem = jsonArray.getJSONObject(i)
                items.add(GalleryItem(
                    promptId = jsonItem.getString("promptId"),
                    filename = jsonItem.getString("filename"),
                    subfolder = jsonItem.optString("subfolder", ""),
                    type = jsonItem.optString("type", "output"),
                    isVideo = jsonItem.optBoolean("isVideo", false),
                    index = jsonItem.optInt("index", i)
                ))
            }

            DebugLogger.i(TAG, "Gallery metadata loaded from cache for server $serverId: ${items.size} items")
            return items
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load gallery metadata for server $serverId: ${e.message}")
            return null
        }
    }

    /**
     * Check if cache exists for a specific server.
     *
     * @param context Application context
     * @param serverId Server ID to check
     * @return true if cache file exists
     */
    fun hasCache(context: Context, serverId: String): Boolean {
        return getCacheFile(context, serverId).exists()
    }

    /**
     * Get the cache timestamp (last modified time) for a server.
     *
     * @param context Application context
     * @param serverId Server ID
     * @return Last modified time in milliseconds, or 0 if cache doesn't exist
     */
    fun getCacheTimestamp(context: Context, serverId: String): Long {
        val cacheFile = getCacheFile(context, serverId)
        return if (cacheFile.exists()) cacheFile.lastModified() else 0L
    }

    /**
     * Clear cache for a specific server.
     *
     * @param context Application context
     * @param serverId Server ID to clear cache for
     */
    fun clearCache(context: Context, serverId: String) {
        try {
            val cacheFile = getCacheFile(context, serverId)
            if (cacheFile.exists()) {
                cacheFile.delete()
                DebugLogger.d(TAG, "Gallery metadata cache cleared for server $serverId")
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear gallery metadata cache for server $serverId: ${e.message}")
        }
    }

    /**
     * Clear all gallery metadata caches.
     *
     * @param context Application context
     */
    fun clearAllCaches(context: Context) {
        try {
            val cacheDir = context.filesDir
            val cacheFiles = cacheDir.listFiles { file ->
                file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(CACHE_SUFFIX)
            }
            cacheFiles?.forEach { it.delete() }
            DebugLogger.d(TAG, "All gallery metadata caches cleared")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear all gallery metadata caches: ${e.message}")
        }
    }

    /**
     * Get the cache file for a specific server.
     */
    private fun getCacheFile(context: Context, serverId: String): File {
        return File(context.filesDir, "$CACHE_PREFIX$serverId$CACHE_SUFFIX")
    }
}
