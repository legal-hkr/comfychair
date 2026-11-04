package sh.hnet.comfychair.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.connection.ConnectionManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Generation parameters (prompt, models, seed…) read from the workflow ComfyUI embeds in its output files: PNG text
 * chunks or MP4 metadata. Everything is read into memory; nothing is written to the device.
 */
object GenerationMetadataLoader {

    /** Metadata of a file as the server stores it (decoded bitmaps no longer carry it). */
    suspend fun fromServerFile(filename: String, subfolder: String, type: String, isVideo: Boolean): GenerationMetadata? {
        if (filename.isEmpty()) return null
        val client = ConnectionManager.clientOrNull ?: return null
        val bytes = suspendCoroutine<ByteArray?> { continuation ->
            client.fetchRawBytes(filename, subfolder, type) { rawBytes, _ -> continuation.resume(rawBytes) }
        } ?: return null
        return parse(bytes, isVideo)
    }

    /** Metadata of a video shown in the app, played from RAM or from a stored file. */
    suspend fun fromVideo(context: Context, videoUri: Uri): GenerationMetadata? = withContext(Dispatchers.IO) {
        val bytes = try {
            InMemoryVideo.openInputStream(context, videoUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            DebugLogger.w("Metadata", "Video could not be read: ${e.javaClass.simpleName}")
            null
        }
        bytes?.let { parse(it, isVideo = true) }
    }

    suspend fun parse(bytes: ByteArray, isVideo: Boolean): GenerationMetadata? = withContext(Dispatchers.Default) {
        val json = if (isVideo) Mp4MetadataExtractor.extractPromptMetadata(bytes) else PngMetadataExtractor.extractPromptMetadata(bytes)
        json?.let { MetadataParser.parseWorkflowJson(it) }
    }
}
