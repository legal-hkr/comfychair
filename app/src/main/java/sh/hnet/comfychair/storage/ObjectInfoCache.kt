package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Singleton for caching /object_info responses to disk per server.
 * Uses GZIP compression since object_info can be 500KB-1MB+.
 * Files are stored in context.filesDir for persistence across app restarts.
 */
object ObjectInfoCache {
    private const val TAG = "ObjectInfoCache"
    private const val CACHE_PREFIX = "object_info_"
    private const val CACHE_SUFFIX = ".json.gz"
    private const val TIMESTAMP_SUFFIX = ".timestamp"

    /**
     * Save object_info JSON to disk cache for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     * @param json The object_info JSON response
     */
    fun saveObjectInfo(context: Context, serverId: String, json: JSONObject) {
        try {
            val cacheFile = getCacheFile(context, serverId)
            val timestampFile = getTimestampFile(context, serverId)

            // Write compressed JSON
            FileOutputStream(cacheFile).use { fos ->
                GZIPOutputStream(fos).use { gzip ->
                    gzip.write(json.toString().toByteArray(Charsets.UTF_8))
                }
            }

            // Write timestamp
            timestampFile.writeText(System.currentTimeMillis().toString())

            DebugLogger.i(TAG, "Object info cached for server $serverId, size: ${cacheFile.length()} bytes")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save object info cache: ${e.message}")
        }
    }

    /**
     * Load object_info JSON from disk cache for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     * @return The cached JSON object, or null if not found or corrupted
     */
    fun loadObjectInfo(context: Context, serverId: String): JSONObject? {
        val cacheFile = getCacheFile(context, serverId)
        if (!cacheFile.exists()) {
            DebugLogger.d(TAG, "No cache file found for server $serverId")
            return null
        }

        return try {
            val jsonString = FileInputStream(cacheFile).use { fis ->
                GZIPInputStream(fis).use { gzip ->
                    gzip.bufferedReader(Charsets.UTF_8).readText()
                }
            }
            val json = JSONObject(jsonString)
            DebugLogger.i(TAG, "Object info loaded from cache for server $serverId")
            json
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load object info cache: ${e.message}")
            null
        }
    }

    /**
     * Check if a cache exists for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     * @return true if a valid cache file exists
     */
    fun hasCache(context: Context, serverId: String): Boolean {
        val cacheFile = getCacheFile(context, serverId)
        return cacheFile.exists() && cacheFile.length() > 0
    }

    /**
     * Get the timestamp when the cache was last updated.
     * @param context Application context
     * @param serverId Unique server identifier
     * @return Timestamp in milliseconds, or 0 if not available
     */
    fun getCacheTimestamp(context: Context, serverId: String): Long {
        val timestampFile = getTimestampFile(context, serverId)
        return if (timestampFile.exists()) {
            try {
                timestampFile.readText().toLong()
            } catch (e: Exception) {
                0L
            }
        } else {
            0L
        }
    }

    /**
     * Clear the cache for the specified server.
     * @param context Application context
     * @param serverId Unique server identifier
     */
    fun clearCache(context: Context, serverId: String) {
        try {
            val cacheFile = getCacheFile(context, serverId)
            val timestampFile = getTimestampFile(context, serverId)

            if (cacheFile.exists()) cacheFile.delete()
            if (timestampFile.exists()) timestampFile.delete()

            DebugLogger.i(TAG, "Cache cleared for server $serverId")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear cache: ${e.message}")
        }
    }

    /**
     * Clear all cached object_info files.
     * @param context Application context
     */
    fun clearAllCaches(context: Context) {
        try {
            val filesDir = context.filesDir
            filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(CACHE_PREFIX)) {
                    file.delete()
                }
            }
            DebugLogger.i(TAG, "All object info caches cleared")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear all caches: ${e.message}")
        }
    }

    /**
     * Get the list of server IDs that have cached object_info.
     * @param context Application context
     * @return Set of server IDs with caches
     */
    fun getCachedServerIds(context: Context): Set<String> {
        return try {
            val filesDir = context.filesDir
            filesDir.listFiles()
                ?.filter { it.name.startsWith(CACHE_PREFIX) && it.name.endsWith(CACHE_SUFFIX) }
                ?.map { it.name.removePrefix(CACHE_PREFIX).removeSuffix(CACHE_SUFFIX) }
                ?.toSet()
                ?: emptySet()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to get cached server IDs: ${e.message}")
            emptySet()
        }
    }

    private fun getCacheFile(context: Context, serverId: String): File {
        return File(context.filesDir, "$CACHE_PREFIX$serverId$CACHE_SUFFIX")
    }

    private fun getTimestampFile(context: Context, serverId: String): File {
        return File(context.filesDir, "$CACHE_PREFIX$serverId$TIMESTAMP_SUFFIX")
    }
}
