package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.generation.serverInputKey
import sh.hnet.comfychair.util.ServerInputs
import sh.hnet.comfychair.util.UuidUtils
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A source taken from the server's input folder (spec §21): its [name] there, and the [media] the screen shows for it
 * (the bitmap, or the video's URI). While the screen's source is still that same object, the workflow takes the
 * name and nothing is uploaded; a source set any other way (device, a result, restored) isn't it, so it's uploaded.
 */
class ServerSource(val name: String, val media: Any)

/** The server file's name while the screen's source is still [media]; null otherwise (the sheet marks it). */
fun ServerSource?.nameWhile(media: Any?): String? = this?.takeIf { media != null && it.media === media }?.name

/** Getting a screen's sources into the server's input folder, shared by the generation screens. */
internal object SourceUploads {

    data class Uploaded(val filename: String?, val failure: ConnectionFailure)

    /**
     * [image] in the workflow: by [server]'s name when it's that source, else uploaded as PNG, named by its content
     * (once per content, issue #62).
     */
    suspend fun image(client: ComfyUIClient, prefix: String, image: Bitmap, server: ServerSource?): Uploaded {
        server.nameWhile(image)?.let { return Uploaded(it, ConnectionFailure.NONE) }
        val bytes = withContext(Dispatchers.IO) {
            // ARGB_8888 for a proper PNG (a hardware or 565 bitmap is copied first)
            val argb = if (image.config != Bitmap.Config.ARGB_8888) image.copy(Bitmap.Config.ARGB_8888, false) else image
            ByteArrayOutputStream().also { argb.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                .also { if (argb !== image) argb.recycle() }
        }
        return bytes(client, prefix, bytes)
    }

    /** [bytes] uploaded, named by their content; the server's file of that name used if it has it. */
    suspend fun bytes(
        client: ComfyUIClient,
        prefix: String,
        bytes: ByteArray,
        extension: String = "png",
        mimeType: String = "image/png"
    ): Uploaded = withContext(Dispatchers.IO) {
        suspendCoroutine { continuation ->
            client.uploadImage(
                bytes,
                UuidUtils.contentUploadFilename(prefix, bytes, extension),
                mimeType = mimeType,
                reuseExisting = true
            ) { success, filename, _, failure ->
                continuation.resume(Uploaded(if (success) filename else null, failure))
            }
        }
    }

    /** A stall or refused credentials get the connection dialog; true when shown (the caller shows nothing more). */
    fun alertIfConnection(context: Context?, failure: ConnectionFailure): Boolean {
        if (failure != ConnectionFailure.STALLED && failure != ConnectionFailure.AUTHENTICATION) return false
        context?.let { ConnectionManager.showConnectionAlert(it, failure) }
        return true
    }

    /** An image picked on the device, decoded; null when it can't be read. */
    suspend fun decodeImage(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** An image of the server's input folder, full size, through the media cache (as the Gallery loads them). */
    suspend fun serverImage(name: String): Bitmap? {
        val file = ServerInputs.File(name)
        return MediaCache.fetchImage(serverInputKey(name), file.subfolder, ServerInputs.TYPE)
    }

    /** A video of the server's input folder, through the media cache. */
    suspend fun serverVideo(name: String): ByteArray? {
        val file = ServerInputs.File(name)
        return MediaCache.fetchVideoBytes(serverInputKey(name), file.subfolder, ServerInputs.TYPE)
    }
}
