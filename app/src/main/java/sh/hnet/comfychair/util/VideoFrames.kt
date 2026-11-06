package sh.hnet.comfychair.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import sh.hnet.comfychair.cache.InMemoryVideo

/**
 * Reading videos and their frames without touching the disk: bytes from RAM (in-memory URIs) or from where the video
 * is stored, frames through MediaMetadataRetriever over those bytes.
 */
object VideoFrames {
    private const val TAG = "VideoFrames"

    /** Outcome of reading a video with a size limit. */
    sealed interface ReadResult {
        class Ok(val bytes: ByteArray) : ReadResult
        data object TooLarge : ReadResult
        data object Unreadable : ReadResult
    }

    /** The stream's bytes, or null when there are more than [limit]. */
    fun readAtMost(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** The bytes of a video shown in the app (in RAM or stored), at most [limit]. */
    fun readBytes(context: Context, uri: Uri, limit: Long): ReadResult {
        InMemoryVideo.bytes(uri)?.let { return if (it.size > limit) ReadResult.TooLarge else ReadResult.Ok(it) }
        return try {
            val bytes = InMemoryVideo.openInputStream(context, uri)?.use { readAtMost(it, limit) ?: return ReadResult.TooLarge }
            if (bytes == null) ReadResult.Unreadable else ReadResult.Ok(bytes)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Video could not be read: ${e.javaClass.simpleName}")
            ReadResult.Unreadable
        }
    }

    /** The video's first frame, scaled down so its longer side is at most [maxPx]. */
    fun firstFrame(context: Context, uri: Uri, maxPx: Int): Bitmap? = withRetriever {
        InMemoryVideo.setDataSource(it, context, uri)
        val width = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val scale = if (width > 0 && height > 0) minOf(1f, maxPx.toFloat() / maxOf(width, height)) else 1f
        if (scale < 1f) {
            it.getScaledFrameAtTime(
                0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (width * scale).toInt(), (height * scale).toInt()
            )
        } else {
            it.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    /**
     * The video's last frame, exactly and at full size: a video animated on from it must continue where this one ends.
     * Taken by index where the container reports the frame count; otherwise the frame closest to 1 ms before the end,
     * which is the last one (frames are further apart than that; the end itself lies past the last frame's start, where
     * decoders may return nothing).
     */
    fun lastFrame(bytes: ByteArray): Bitmap? = withRetriever {
        it.setDataSource(InMemoryVideo.ByteArrayMediaDataSource(bytes))
        val frameCount = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        val byIndex = if (frameCount > 0) runCatching { it.getFrameAtIndex(frameCount - 1) }.getOrNull() else null
        byIndex ?: run {
            val durationMs = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            it.getFrameAtTime((durationMs - 1).coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    }

    /**
     * The frame shown at [positionMs] (e.g. where playback stopped or a frame step landed), at full size: by its
     * number where the frame rate is known, counted as the player does ([frameIndex]), otherwise the frame closest in
     * time. Read from RAM where the video is kept there.
     *
     * @param frameIndex Number of the frame shown at a position for a frame rate (the player's own count)
     */
    fun frameAt(
        context: Context,
        uri: Uri,
        positionMs: Long,
        frameRate: Float?,
        frameIndex: (positionMs: Long, fps: Double) -> Int
    ): Bitmap? = withRetriever {
        InMemoryVideo.setDataSource(it, context, uri)
        val frameCount = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        val byIndex = if (frameRate != null && frameCount > 0) {
            val index = frameIndex(positionMs, frameRate.toDouble()).coerceIn(0, frameCount - 1)
            runCatching { it.getFrameAtIndex(index) }.getOrNull()
        } else null
        byIndex ?: it.getFrameAtTime(positionMs.coerceAtLeast(0) * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    private fun withRetriever(block: (MediaMetadataRetriever) -> Bitmap?): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        try {
            block(retriever)
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        DebugLogger.w(TAG, "Frame could not be read: ${e.javaClass.simpleName}")
        null
    }
}
