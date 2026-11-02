package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Singleton for caching /object_info responses to disk per server.
 * Uses GZIP compression since object_info ranges from about 1MB to tens of MB on servers
 * with many custom nodes or model files; it is always streamed, never held in memory as a whole.
 * Files are stored in context.filesDir for persistence across app restarts.
 */
object ObjectInfoCache {
    private const val TAG = "ObjectInfoCache"
    private const val CACHE_PREFIX = "object_info_"
    private const val CACHE_SUFFIX = ".json.gz"
    private const val TIMESTAMP_SUFFIX = ".timestamp"
    private const val TEMP_SUFFIX = ".json.gz.tmp"

    /**
     * Stream an /object_info response into the disk cache for the specified server and parse it.
     *
     * The response is compressed into a temporary file without being held in memory, then read
     * back through [parse]. The existing cache is replaced only if [parse] succeeds, so an
     * interrupted download or a malformed response never leaves a broken cache behind.
     *
     * @param context Application context
     * @param serverId Unique server identifier
     * @param source The object_info response body
     * @param parse Consumer of the cached JSON, e.g. NodeTypeRegistry.parseObjectInfo
     * @return true if the response was cached and parsed successfully
     */
    fun saveObjectInfo(
        context: Context,
        serverId: String,
        source: InputStream,
        parse: (Reader) -> Unit
    ): Boolean {
        val cacheFile = getCacheFile(context, serverId)
        val tempFile = File(context.filesDir, "$CACHE_PREFIX$serverId$TEMP_SUFFIX")

        return try {
            GZIPOutputStream(FileOutputStream(tempFile).buffered()).use { gzip ->
                source.copyTo(gzip)
            }
            readGzipped(tempFile, parse)

            if (!tempFile.renameTo(cacheFile)) {
                throw IOException("Failed to move ${tempFile.name} to ${cacheFile.name}")
            }
            getTimestampFile(context, serverId).writeText(System.currentTimeMillis().toString())

            DebugLogger.i(TAG, "Object info cached for server $serverId, size: ${cacheFile.length()} bytes")
            true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save object info cache: ${e.message}")
            false
        } catch (e: OutOfMemoryError) {
            // Fail this fetch instead of crashing on every connection attempt to the same server
            DebugLogger.e(TAG, "Out of memory while processing object info for server $serverId")
            false
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Read the object_info disk cache for the specified server through [parse].
     * @param context Application context
     * @param serverId Unique server identifier
     * @param parse Consumer of the cached JSON, e.g. NodeTypeRegistry.parseObjectInfo
     * @return true if the cache exists and was parsed successfully
     */
    fun loadObjectInfo(context: Context, serverId: String, parse: (Reader) -> Unit): Boolean {
        val cacheFile = getCacheFile(context, serverId)
        if (!cacheFile.exists()) {
            DebugLogger.d(TAG, "No cache file found for server $serverId")
            return false
        }

        return try {
            readGzipped(cacheFile, parse)
            DebugLogger.i(TAG, "Object info loaded from cache for server $serverId")
            true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load object info cache: ${e.message}")
            false
        } catch (e: OutOfMemoryError) {
            DebugLogger.e(TAG, "Out of memory while loading object info cache for server $serverId")
            false
        }
    }

    private fun readGzipped(file: File, parse: (Reader) -> Unit) {
        GZIPInputStream(FileInputStream(file).buffered()).bufferedReader(Charsets.UTF_8).use(parse)
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
