package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.VideoUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton that holds media state for generation screens.
 *
 * Supports two caching modes:
 * - Memory-first (default): Media stored in RAM, persisted to disk on app background.
 *   This eliminates frequent disk I/O during active generation.
 * - Disk-first: Media written directly to disk, read from disk when needed.
 *   This minimizes RAM usage for low-end devices with slow networks.
 *
 * Media files are scoped per-server using the format: {serverId}_{filename}
 */
object MediaStateHolder {

    private const val TAG = "MediaState"

    // Current server ID for per-server file scoping
    private var currentServerId: String? = null

    /**
     * Keys for different media types stored in the holder.
     * Base filenames are used - the server ID prefix is added dynamically.
     */
    sealed class MediaKey(val baseFilename: String) {
        // Image previews (final generated images displayed on screens)
        data object TtiPreview : MediaKey("tti_last_preview.png")
        data object ItiPreview : MediaKey("iti_last_preview.png")
        data object TtvPreview : MediaKey("ttv_last_preview.png")
        data object ItvPreview : MediaKey("itv_last_preview.png")

        // Source images (user-selected images for Image-to-X workflows)
        data object ItiSource : MediaKey("iti_last_source.png")
        data object ItvSource : MediaKey("itv_last_source.png")

        // Reference images for ITE (Image Editing) workflow
        data object IteReferenceImage1 : MediaKey("ite_reference_1.png")
        data object IteReferenceImage2 : MediaKey("ite_reference_2.png")

        // Generated videos (with prompt ID for uniqueness)
        data class TtvVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.TEXT_TO_VIDEO}$promptId.mp4")
        data class ItvVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}$promptId.mp4")

        /**
         * Get the full filename including server ID prefix.
         */
        fun filename(serverId: String): String = "${serverId}_$baseFilename"
    }

    /**
     * Set the current server ID for per-server file scoping.
     * Should be called when connecting to a server.
     */
    fun setCurrentServerId(serverId: String?) {
        if (currentServerId != serverId) {
            DebugLogger.i(TAG, "Server ID changed: ${currentServerId?.take(8)} -> ${serverId?.take(8)}")
            currentServerId = serverId
        }
    }

    /**
     * Get the current server ID.
     */
    fun getCurrentServerId(): String? = currentServerId

    // Caching mode - true for memory-first (default), false for disk-first
    private var isMemoryFirstMode = true

    // In-memory storage (used in memory-first mode)
    private val bitmaps = ConcurrentHashMap<MediaKey, Bitmap>()
    private val videoBytes = ConcurrentHashMap<MediaKey, ByteArray>()

    // Dirty flags - tracks what needs to be persisted to disk (memory-first mode only)
    private val dirtyBitmaps = ConcurrentHashMap.newKeySet<MediaKey>()
    private val dirtyVideos = ConcurrentHashMap.newKeySet<MediaKey>()

    // Track current video prompt IDs for persistence
    private var currentTtvPromptId: String? = null
    private var currentItvPromptId: String? = null

    /**
     * Set the caching mode.
     * @param enabled true for memory-first (default), false for disk-first
     * @param context Required for content migration when switching modes
     */
    fun setMemoryFirstMode(enabled: Boolean, context: Context? = null) {
        if (isMemoryFirstMode == enabled) return  // No change

        DebugLogger.i(TAG, "Switching to ${if (enabled) "memory-first" else "disk-first"} mode")
        if (enabled) {
            // Switching TO memory-first: load disk content into memory
            context?.let { ctx ->
                runBlocking {
                    loadFromDisk(ctx)
                }
                // Disk files remain as backup - don't clear them
            }
        } else {
            // Switching TO disk-first: persist memory content to disk first
            context?.let { ctx ->
                runBlocking {
                    persistToDisk(ctx)
                }
            }
            // Now safe to clear in-memory caches
            bitmaps.clear()
            videoBytes.clear()
            dirtyBitmaps.clear()
            dirtyVideos.clear()
        }

        isMemoryFirstMode = enabled
    }

    /**
     * Clear all disk cache files for the current server.
     */
    private fun clearDiskCache(context: Context) {
        val serverId = currentServerId ?: return

        val allKeys = listOf(
            MediaKey.TtiPreview,
            MediaKey.ItiPreview,
            MediaKey.ItiSource,
            MediaKey.TtvPreview,
            MediaKey.ItvPreview,
            MediaKey.ItvSource,
            MediaKey.IteReferenceImage1,
            MediaKey.IteReferenceImage2
        )

        for (key in allKeys) {
            try {
                val file = context.getFileStreamPath(key.filename(serverId))
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }

        // Also delete any video files for this server
        val prefix = "${serverId}_"
        try {
            context.filesDir.listFiles()?.filter { file ->
                file.name.startsWith(prefix) && (
                    file.name.contains(VideoUtils.FilePrefix.TEXT_TO_VIDEO) ||
                    file.name.contains(VideoUtils.FilePrefix.IMAGE_TO_VIDEO)
                )
            }?.forEach { file ->
                file.delete()
            }
        } catch (_: Exception) {
            // Ignore deletion failures
        }
    }

    /**
     * Check if memory-first mode is enabled.
     */
    fun isMemoryFirstMode(): Boolean = isMemoryFirstMode

    /**
     * Store a bitmap. In memory-first mode, stores in RAM. In disk-first mode, writes to disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun putBitmap(key: MediaKey, bitmap: Bitmap, context: Context? = null) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "putBitmap SKIPPED - currentServerId is null (key: ${key.baseFilename})")
            return
        }

        DebugLogger.d(TAG, "putBitmap: ${key.baseFilename} (server: ${serverId.take(8)}..., mode: ${if (isMemoryFirstMode) "memory" else "disk"})")

        if (isMemoryFirstMode) {
            // Memory-first: store in memory, mark as dirty for later persistence
            bitmaps[key] = bitmap
            dirtyBitmaps.add(key)
            DebugLogger.d(TAG, "putBitmap: stored in memory, marked dirty (dirty count: ${dirtyBitmaps.size})")
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            context?.let { ctx ->
                try {
                    ctx.openFileOutput(key.filename(serverId), Context.MODE_PRIVATE).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    DebugLogger.d(TAG, "putBitmap: written to disk")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "putBitmap: disk write FAILED - ${e.message}")
                }
            } ?: DebugLogger.w(TAG, "putBitmap: context is null, cannot write to disk")
        }
    }

    /**
     * Get a bitmap. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getBitmap(key: MediaKey, context: Context? = null): Bitmap? {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "getBitmap SKIPPED - currentServerId is null (key: ${key.baseFilename})")
            return null
        }

        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            val bitmap = bitmaps[key]
            DebugLogger.d(TAG, "getBitmap: ${key.baseFilename} from memory -> ${if (bitmap != null) "FOUND" else "NOT FOUND"}")
            bitmap
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = ctx.getFileStreamPath(key.filename(serverId))
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        DebugLogger.d(TAG, "getBitmap: ${key.baseFilename} from disk -> ${if (bitmap != null) "FOUND" else "DECODE FAILED"}")
                        bitmap
                    } else {
                        DebugLogger.d(TAG, "getBitmap: ${key.baseFilename} from disk -> FILE NOT FOUND")
                        null
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "getBitmap: ${key.baseFilename} disk read FAILED - ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Store video bytes. In memory-first mode, stores in RAM. In disk-first mode, writes to disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun putVideoBytes(key: MediaKey, bytes: ByteArray, context: Context? = null) {
        val serverId = currentServerId ?: return

        // Track prompt ID for persistence
        when (key) {
            is MediaKey.TtvVideo -> currentTtvPromptId = key.promptId
            is MediaKey.ItvVideo -> currentItvPromptId = key.promptId
            else -> { }
        }

        if (isMemoryFirstMode) {
            // Memory-first: store in memory, mark as dirty for later persistence
            videoBytes[key] = bytes
            dirtyVideos.add(key)
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).writeBytes(bytes)
                } catch (_: Exception) {
                    // Ignore write failures
                }
            }
        }
    }

    /**
     * Get video bytes. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getVideoBytes(key: MediaKey, context: Context? = null): ByteArray? {
        val serverId = currentServerId ?: return null

        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            videoBytes[key]
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = File(ctx.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        file.readBytes()
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Get a video URI for ExoPlayer playback.
     * In memory-first mode, creates a temp file from in-memory bytes.
     * In disk-first mode, returns URI directly to the stored file.
     */
    suspend fun getVideoUri(context: Context, key: MediaKey): Uri? {
        val serverId = currentServerId ?: return null

        return withContext(Dispatchers.IO) {
            try {
                if (isMemoryFirstMode) {
                    // Memory-first: create temp file from in-memory bytes
                    val bytes = videoBytes[key] ?: return@withContext null
                    val tempFile = File(context.cacheDir, "playback_${key.baseFilename.hashCode()}.mp4")
                    tempFile.writeBytes(bytes)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    )
                } else {
                    // Disk-first: return URI to the stored file directly
                    val file = File(context.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Persist all dirty items to disk.
     * Called from MainContainerActivity.onStop().
     */
    suspend fun persistToDisk(context: Context) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "persistToDisk SKIPPED - currentServerId is null! (dirty: ${dirtyBitmaps.size} bitmaps, ${dirtyVideos.size} videos)")
            return
        }

        val bitmapCount = dirtyBitmaps.size
        val videoCount = dirtyVideos.size
        DebugLogger.i(TAG, "persistToDisk START (server: ${serverId.take(8)}..., dirty: $bitmapCount bitmaps, $videoCount videos)")

        if (bitmapCount == 0 && videoCount == 0) {
            DebugLogger.d(TAG, "persistToDisk: nothing to persist")
            return
        }

        withContext(Dispatchers.IO) {
            // Persist dirty bitmaps
            val bitmapsToPersist = dirtyBitmaps.toList()
            var bitmapSuccess = 0
            var bitmapFailed = 0
            for (key in bitmapsToPersist) {
                val bitmap = bitmaps[key]
                if (bitmap == null) {
                    DebugLogger.w(TAG, "persistToDisk: bitmap ${key.baseFilename} is dirty but not in memory!")
                    dirtyBitmaps.remove(key)
                    continue
                }
                try {
                    context.openFileOutput(key.filename(serverId), Context.MODE_PRIVATE).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    dirtyBitmaps.remove(key)
                    bitmapSuccess++
                    DebugLogger.d(TAG, "persistToDisk: ${key.baseFilename} -> OK")
                } catch (e: Exception) {
                    bitmapFailed++
                    DebugLogger.e(TAG, "persistToDisk: ${key.baseFilename} -> FAILED: ${e.message}")
                }
            }

            // Persist dirty videos
            val videosToPersist = dirtyVideos.toList()
            var videoSuccess = 0
            var videoFailed = 0
            for (key in videosToPersist) {
                val bytes = videoBytes[key]
                if (bytes == null) {
                    DebugLogger.w(TAG, "persistToDisk: video ${key.baseFilename} is dirty but not in memory!")
                    dirtyVideos.remove(key)
                    continue
                }
                try {
                    File(context.filesDir, key.filename(serverId)).writeBytes(bytes)
                    dirtyVideos.remove(key)
                    videoSuccess++
                    DebugLogger.d(TAG, "persistToDisk: ${key.baseFilename} -> OK")
                } catch (e: Exception) {
                    videoFailed++
                    DebugLogger.e(TAG, "persistToDisk: ${key.baseFilename} -> FAILED: ${e.message}")
                }
            }

            DebugLogger.i(TAG, "persistToDisk DONE (bitmaps: $bitmapSuccess OK, $bitmapFailed failed; videos: $videoSuccess OK, $videoFailed failed)")
        }
    }

    /**
     * Load persisted media from disk into memory for the current server.
     * Called from MainContainerActivity.onCreate().
     */
    suspend fun loadFromDisk(context: Context) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "loadFromDisk SKIPPED - currentServerId is null!")
            return
        }

        DebugLogger.i(TAG, "loadFromDisk START (server: ${serverId.take(8)}...)")

        withContext(Dispatchers.IO) {
            // Load bitmap files
            val bitmapKeys = listOf(
                MediaKey.TtiPreview,
                MediaKey.ItiPreview,
                MediaKey.ItiSource,
                MediaKey.TtvPreview,
                MediaKey.ItvPreview,
                MediaKey.ItvSource,
                MediaKey.IteReferenceImage1,
                MediaKey.IteReferenceImage2
            )

            var bitmapsLoaded = 0
            var bitmapsNotFound = 0
            for (key in bitmapKeys) {
                try {
                    val file = context.getFileStreamPath(key.filename(serverId))
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            bitmaps[key] = bitmap
                            bitmapsLoaded++
                            DebugLogger.d(TAG, "loadFromDisk: ${key.baseFilename} -> LOADED")
                            // NOT dirty - already on disk
                        } else {
                            DebugLogger.w(TAG, "loadFromDisk: ${key.baseFilename} -> DECODE FAILED")
                        }
                    } else {
                        bitmapsNotFound++
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "loadFromDisk: ${key.baseFilename} -> ERROR: ${e.message}")
                }
            }

            // Load latest video files for this server
            val serverPrefix = "${serverId}_"
            loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.TEXT_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.TtvVideo(promptId)
                videoBytes[key] = bytes
                currentTtvPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: TTV video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.IMAGE_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.ItvVideo(promptId)
                videoBytes[key] = bytes
                currentItvPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: ITV video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            DebugLogger.i(TAG, "loadFromDisk DONE (loaded: $bitmapsLoaded bitmaps, not found: $bitmapsNotFound)")
        }
    }

    private fun loadLatestVideoFile(
        context: Context,
        serverPrefix: String,
        videoPrefix: String,
        onLoaded: (promptId: String, bytes: ByteArray) -> Unit
    ) {
        try {
            val fullPrefix = "$serverPrefix$videoPrefix"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    val promptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                    val bytes = file.readBytes()
                    onLoaded(promptId, bytes)
                }
        } catch (_: Exception) {
            // Ignore load failures
        }
    }

    /**
     * Remove a specific media item from memory.
     */
    fun evict(key: MediaKey) {
        bitmaps.remove(key)
        videoBytes.remove(key)
        dirtyBitmaps.remove(key)
        dirtyVideos.remove(key)

        // Clear prompt ID tracking
        when (key) {
            is MediaKey.TtvVideo -> if (key.promptId == currentTtvPromptId) currentTtvPromptId = null
            is MediaKey.ItvVideo -> if (key.promptId == currentItvPromptId) currentItvPromptId = null
            else -> { }
        }
    }

    /**
     * Remove a specific media item from memory AND delete from disk.
     * Use this when the item should not be restored on app restart.
     */
    suspend fun evictAndDeleteFromDisk(context: Context, key: MediaKey) {
        val serverId = currentServerId ?: return
        evict(key)
        withContext(Dispatchers.IO) {
            try {
                val file = context.getFileStreamPath(key.filename(serverId))
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }
    }

    /**
     * Clear all media from memory.
     * Called during cache clearing.
     */
    fun clearAll() {
        DebugLogger.i(TAG, "Clearing all media from memory")
        bitmaps.clear()
        videoBytes.clear()
        dirtyBitmaps.clear()
        dirtyVideos.clear()
        currentTtvPromptId = null
        currentItvPromptId = null
    }

    /**
     * Get the current Text-to-Video prompt ID (if any video is loaded).
     */
    fun getCurrentTtvPromptId(): String? = currentTtvPromptId

    /**
     * Get the current Image-to-Video prompt ID (if any video is loaded).
     */
    fun getCurrentItvPromptId(): String? = currentItvPromptId

    /**
     * Clear the current Text-to-Video prompt ID tracking.
     * This prevents video restoration on subsequent screen initializations.
     */
    fun clearCurrentTtvPromptId() {
        currentTtvPromptId = null
    }

    /**
     * Clear the current Image-to-Video prompt ID tracking.
     * This prevents video restoration on subsequent screen initializations.
     */
    fun clearCurrentItvPromptId() {
        currentItvPromptId = null
    }

    /**
     * Discover video prompt IDs from disk without loading bytes into memory.
     * Used in disk-first mode to enable video restoration on app restart.
     * In disk-first mode, videos are read on-demand, but we still need to know
     * which promptIds have saved videos.
     */
    fun discoverVideoPromptIds(context: Context) {
        val serverId = currentServerId ?: return
        val serverPrefix = "${serverId}_"

        // Discover TTV video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.TEXT_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentTtvPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }

        // Discover ITV video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentItvPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }
    }

    /**
     * Check if a bitmap exists. In memory-first mode, checks RAM. In disk-first mode, checks disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun hasBitmap(key: MediaKey, context: Context? = null): Boolean {
        val serverId = currentServerId ?: return false

        return if (isMemoryFirstMode) {
            bitmaps.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    ctx.getFileStreamPath(key.filename(serverId)).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }

    /**
     * Check if video bytes exist. In memory-first mode, checks RAM. In disk-first mode, checks disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun hasVideoBytes(key: MediaKey, context: Context? = null): Boolean {
        val serverId = currentServerId ?: return false

        return if (isMemoryFirstMode) {
            videoBytes.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }

    /**
     * Cleanup orphaned media files that don't belong to any registered server.
     * Call this after deleting a server or during app cleanup.
     *
     * @param context Application context
     * @param validServerIds List of server IDs that should be kept
     */
    fun cleanupOrphanedMediaFiles(context: Context, validServerIds: Set<String>) {
        DebugLogger.i(TAG, "Cleaning up orphaned media files")
        try {
            context.filesDir.listFiles()?.forEach { file ->
                // Check if file starts with a UUID pattern (server-scoped file)
                val name = file.name
                val underscoreIndex = name.indexOf('_')
                if (underscoreIndex > 0) {
                    val potentialServerId = name.substring(0, underscoreIndex)
                    // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (36 chars)
                    if (potentialServerId.length == 36 && potentialServerId.contains('-')) {
                        if (potentialServerId !in validServerIds) {
                            DebugLogger.d(TAG, "Deleting orphaned file: $name")
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to cleanup orphaned files: ${e.message}")
        }
    }
}
