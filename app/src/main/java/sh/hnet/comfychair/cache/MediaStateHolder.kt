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
 */
object MediaStateHolder {

    private const val TAG = "MediaState"

    /**
     * Keys for different media types stored in the holder.
     */
    sealed class MediaKey(val filename: String) {
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
    }

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
     * Clear all disk cache files created during disk-first mode.
     */
    private fun clearDiskCache(context: Context) {
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
                val file = context.getFileStreamPath(key.filename)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }

        // Also delete any video files
        try {
            context.filesDir.listFiles()?.filter { file ->
                file.name.startsWith(VideoUtils.FilePrefix.TEXT_TO_VIDEO) ||
                file.name.startsWith(VideoUtils.FilePrefix.IMAGE_TO_VIDEO)
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
        if (isMemoryFirstMode) {
            // Memory-first: store in memory, mark as dirty for later persistence
            bitmaps[key] = bitmap
            dirtyBitmaps.add(key)
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            context?.let { ctx ->
                try {
                    ctx.openFileOutput(key.filename, Context.MODE_PRIVATE).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (_: Exception) {
                    // Ignore write failures
                }
            }
        }
    }

    /**
     * Get a bitmap. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getBitmap(key: MediaKey, context: Context? = null): Bitmap? {
        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            bitmaps[key]
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = ctx.getFileStreamPath(key.filename)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    } else null
                } catch (_: Exception) {
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
                    File(ctx.filesDir, key.filename).writeBytes(bytes)
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
        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            videoBytes[key]
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = File(ctx.filesDir, key.filename)
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
        return withContext(Dispatchers.IO) {
            try {
                if (isMemoryFirstMode) {
                    // Memory-first: create temp file from in-memory bytes
                    val bytes = videoBytes[key] ?: return@withContext null
                    val tempFile = File(context.cacheDir, "playback_${key.filename.hashCode()}.mp4")
                    tempFile.writeBytes(bytes)
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        tempFile
                    )
                } else {
                    // Disk-first: return URI to the stored file directly
                    val file = File(context.filesDir, key.filename)
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
        val bitmapCount = dirtyBitmaps.size
        val videoCount = dirtyVideos.size
        if (bitmapCount > 0 || videoCount > 0) {
            DebugLogger.d(TAG, "Persisting to disk: $bitmapCount bitmaps, $videoCount videos")
        }
        withContext(Dispatchers.IO) {
            // Persist dirty bitmaps
            val bitmapsToPersist = dirtyBitmaps.toList()
            for (key in bitmapsToPersist) {
                val bitmap = bitmaps[key] ?: continue
                try {
                    context.openFileOutput(key.filename, Context.MODE_PRIVATE).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    dirtyBitmaps.remove(key)
                } catch (_: Exception) {
                    // Ignore persistence failures
                }
            }

            // Persist dirty videos
            val videosToPersist = dirtyVideos.toList()
            for (key in videosToPersist) {
                val bytes = videoBytes[key] ?: continue
                try {
                    File(context.filesDir, key.filename).writeBytes(bytes)
                    dirtyVideos.remove(key)
                } catch (_: Exception) {
                    // Ignore persistence failures
                }
            }
        }
    }

    /**
     * Load persisted media from disk into memory.
     * Called from MainContainerActivity.onCreate().
     */
    suspend fun loadFromDisk(context: Context) {
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

            for (key in bitmapKeys) {
                try {
                    val file = context.getFileStreamPath(key.filename)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            bitmaps[key] = bitmap
                            // NOT dirty - already on disk
                        }
                    }
                } catch (_: Exception) {
                    // Ignore load failures
                }
            }

            // Load latest video files
            loadLatestVideoFile(context, VideoUtils.FilePrefix.TEXT_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.TtvVideo(promptId)
                videoBytes[key] = bytes
                currentTtvPromptId = promptId
            }

            loadLatestVideoFile(context, VideoUtils.FilePrefix.IMAGE_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.ItvVideo(promptId)
                videoBytes[key] = bytes
                currentItvPromptId = promptId
            }
        }
    }

    private fun loadLatestVideoFile(
        context: Context,
        prefix: String,
        onLoaded: (promptId: String, bytes: ByteArray) -> Unit
    ) {
        try {
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    val promptId = file.name
                        .removePrefix(prefix)
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
        evict(key)
        withContext(Dispatchers.IO) {
            try {
                val file = context.getFileStreamPath(key.filename)
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
        // Discover TTV video
        try {
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(VideoUtils.FilePrefix.TEXT_TO_VIDEO) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentTtvPromptId = file.name
                        .removePrefix(VideoUtils.FilePrefix.TEXT_TO_VIDEO)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }

        // Discover ITV video
        try {
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(VideoUtils.FilePrefix.IMAGE_TO_VIDEO) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentItvPromptId = file.name
                        .removePrefix(VideoUtils.FilePrefix.IMAGE_TO_VIDEO)
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
        return if (isMemoryFirstMode) {
            bitmaps.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    ctx.getFileStreamPath(key.filename).exists()
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
        return if (isMemoryFirstMode) {
            videoBytes.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }
}
