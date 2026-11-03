package sh.hnet.comfychair.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationOutput
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Small thumbnails of the results of a generation job, for the results strip.
 *
 * Thumbnails are decoded from bytes held in RAM and kept only in an in-memory LRU cache; nothing
 * is ever written to disk. Images are downloaded and decoded downsampled without keeping the full
 * image (a job can have up to 64 results); videos go through MediaCache, as their bytes are needed
 * for playback anyway.
 */
object ResultThumbnails {
    private const val TAG = "ResultThumbnails"

    // Thumbnails are ~56 dp; a few MB hold all 64 results of a big batch
    private val cache = object : LruCache<String, ImageBitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    // Files whose thumbnail could not be produced, so the UI can show the failed state
    private val failed = ConcurrentHashMap.newKeySet<String>()

    // Video durations read from the file when the server didn't report one
    private val durations = ConcurrentHashMap<String, Long>()

    // Limits parallel downloads when a strip with many results appears
    private val downloads = Semaphore(3)

    private fun cacheSizeBytes(): Int =
        minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16).toInt()

    /** Returns a cached thumbnail without loading. */
    fun get(output: GenerationOutput): ImageBitmap? = cache.get(output.id)

    fun isFailed(output: GenerationOutput): Boolean = output.id in failed

    /** Video duration from the server metadata or, once the thumbnail is loaded, from the file. */
    fun durationMs(output: GenerationOutput): Long? = output.durationMs ?: durations[output.id]

    /**
     * Returns the thumbnail of [output], loading it if needed. [sizePx] is the edge length the
     * thumbnail is shown at; it's decoded at least that large. Returns null if loading failed.
     */
    suspend fun load(output: GenerationOutput, sizePx: Int): ImageBitmap? {
        cache.get(output.id)?.let { return it }
        return downloads.withPermit {
            // Another caller may have loaded it while this one waited
            cache.get(output.id)?.let { return@withPermit it }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    if (output.isVideo) loadVideoFrame(output, sizePx) else loadImage(output, sizePx)
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "Thumbnail failed: ${e.message}")
                    null
                }
            }
            if (bitmap != null) {
                val thumbnail = bitmap.asImageBitmap()
                cache.put(output.id, thumbnail)
                failed.remove(output.id)
                thumbnail
            } else {
                failed.add(output.id)
                null
            }
        }
    }

    /** Drops all thumbnails, e.g. when disconnecting or switching servers. */
    fun clear() {
        cache.evictAll()
        failed.clear()
        durations.clear()
    }

    private suspend fun loadImage(output: GenerationOutput, sizePx: Int): Bitmap? {
        // The image shown on the screen is usually in MediaCache already
        MediaCache.getBitmap(MediaCacheKey(output.promptId, output.filename))?.let { return scaleDown(it, sizePx) }

        val client = ConnectionManager.clientOrNull ?: return null
        val bytes = suspendCoroutine<ByteArray?> { continuation ->
            client.fetchRawBytes(output.filename, output.subfolder, output.type) { bytes, _ ->
                continuation.resume(bytes)
            }
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, sizePx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { scaleDown(it, sizePx) }
    }

    private suspend fun loadVideoFrame(output: GenerationOutput, sizePx: Int): Bitmap? {
        val key = MediaCacheKey(output.promptId, output.filename)
        val bytes = MediaCache.getVideoBytes(key)
            ?: MediaCache.fetchVideoBytes(key, output.subfolder, output.type)
            ?: return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(InMemoryVideo.ByteArrayMediaDataSource(bytes))
            if (output.durationMs == null) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.let { durations[output.id] = it }
            }
            // Scale so the shorter edge is sizePx (the frame is fitted into the given box)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val scaled = if (width > 0 && height > 0) {
                val scale = sizePx.toFloat() / minOf(width, height)
                retriever.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
            } else null
            scaled ?: retriever.getFrameAtTime(0)?.let { scaleDown(it, sizePx) }
        } finally {
            retriever.release()
        }
    }

    /** Largest power of two that keeps the shorter edge at least [sizePx]. */
    private fun sampleSize(width: Int, height: Int, sizePx: Int): Int {
        var sample = 1
        val shorter = minOf(width, height)
        while (shorter / (sample * 2) >= sizePx) sample *= 2
        return sample
    }

    /** Scales so the shorter edge is [sizePx] (thumbnails are center-cropped when shown). */
    private fun scaleDown(bitmap: Bitmap, sizePx: Int): Bitmap {
        val shorter = minOf(bitmap.width, bitmap.height)
        if (shorter <= sizePx) return bitmap
        val scale = sizePx.toFloat() / shorter
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
