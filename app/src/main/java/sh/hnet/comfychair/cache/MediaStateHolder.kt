package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.util.Logger
import sh.hnet.comfychair.util.VideoUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton that holds in-memory media state for generation screens.
 *
 * During runtime, all preview images, source images, and video bytes are stored
 * in memory. They are only persisted to disk when the app goes to background (onStop).
 * This eliminates frequent disk I/O during active generation.
 */
object MediaStateHolder {

    private const val TAG = "MediaStateHolder"

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

    // In-memory storage
    private val bitmaps = ConcurrentHashMap<MediaKey, Bitmap>()
    private val videoBytes = ConcurrentHashMap<MediaKey, ByteArray>()

    // Dirty flags - tracks what needs to be persisted to disk
    private val dirtyBitmaps = ConcurrentHashMap.newKeySet<MediaKey>()
    private val dirtyVideos = ConcurrentHashMap.newKeySet<MediaKey>()

    // Track current video prompt IDs for persistence
    private var currentTtvPromptId: String? = null
    private var currentItvPromptId: String? = null

    /**
     * Store a bitmap in memory and mark it as dirty for persistence.
     */
    fun putBitmap(key: MediaKey, bitmap: Bitmap) {
        bitmaps[key] = bitmap
        dirtyBitmaps.add(key)
    }

    /**
     * Get a bitmap from memory.
     * Returns null if not in memory.
     */
    fun getBitmap(key: MediaKey): Bitmap? = bitmaps[key]

    /**
     * Store video bytes in memory and mark as dirty for persistence.
     */
    fun putVideoBytes(key: MediaKey, bytes: ByteArray) {
        videoBytes[key] = bytes
        dirtyVideos.add(key)

        // Track prompt ID for persistence
        when (key) {
            is MediaKey.TtvVideo -> currentTtvPromptId = key.promptId
            is MediaKey.ItvVideo -> currentItvPromptId = key.promptId
            else -> { }
        }
    }

    /**
     * Get video bytes from memory.
     * Returns null if not in memory.
     */
    fun getVideoBytes(key: MediaKey): ByteArray? = videoBytes[key]

    /**
     * Get a video URI for ExoPlayer playback.
     * Creates a temp file in cacheDir from the in-memory bytes.
     * Returns null if video bytes are not in memory.
     */
    suspend fun getVideoUri(context: Context, key: MediaKey): Uri? {
        val bytes = videoBytes[key] ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "playback_${key.filename.hashCode()}.mp4")
                tempFile.writeBytes(bytes)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to create video URI for ${key.filename}", e)
                null
            }
        }
    }

    /**
     * Persist all dirty items to disk.
     * Called from MainContainerActivity.onStop().
     */
    suspend fun persistToDisk(context: Context) {
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
                    Logger.d(TAG, "Persisted bitmap: ${key.filename}")
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to persist bitmap: ${key.filename}", e)
                }
            }

            // Persist dirty videos
            val videosToPersist = dirtyVideos.toList()
            for (key in videosToPersist) {
                val bytes = videoBytes[key] ?: continue
                try {
                    File(context.filesDir, key.filename).writeBytes(bytes)
                    dirtyVideos.remove(key)
                    Logger.d(TAG, "Persisted video: ${key.filename}")
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to persist video: ${key.filename}", e)
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
                            Logger.d(TAG, "Loaded bitmap: ${key.filename}")
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to load bitmap: ${key.filename}", e)
                }
            }

            // Load latest video files
            loadLatestVideoFile(context, VideoUtils.FilePrefix.TEXT_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.TtvVideo(promptId)
                videoBytes[key] = bytes
                currentTtvPromptId = promptId
                Logger.d(TAG, "Loaded video: ${key.filename}")
            }

            loadLatestVideoFile(context, VideoUtils.FilePrefix.IMAGE_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.ItvVideo(promptId)
                videoBytes[key] = bytes
                currentItvPromptId = promptId
                Logger.d(TAG, "Loaded video: ${key.filename}")
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
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load video with prefix: $prefix", e)
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
                    Logger.d(TAG, "Deleted from disk: ${key.filename}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete from disk: ${key.filename}", e)
            }
        }
    }

    /**
     * Clear all media from memory.
     * Called during cache clearing.
     */
    fun clearAll() {
        bitmaps.clear()
        videoBytes.clear()
        dirtyBitmaps.clear()
        dirtyVideos.clear()
        currentTtvPromptId = null
        currentItvPromptId = null
        Logger.d(TAG, "Cleared all media state")
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
     * Check if a bitmap exists in memory.
     */
    fun hasBitmap(key: MediaKey): Boolean = bitmaps.containsKey(key)

    /**
     * Check if video bytes exist in memory.
     */
    fun hasVideoBytes(key: MediaKey): Boolean = videoBytes.containsKey(key)
}
