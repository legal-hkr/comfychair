package sh.hnet.comfychair.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.cache.InMemoryVideo

/**
 * Saving generated media to the device gallery and sharing it with other apps – the only places
 * where media leaves the app at the user's request.
 *
 * Share files go to the app's private cache with a "share_" prefix and are deleted on the next start
 * ([InMemoryVideo.deleteShareFiles]); each share gets its own file, so a share doesn't overwrite a file
 * another app may still be reading.
 */
object MediaExport {

    enum class Kind(
        val mimeType: String,
        val extension: String,
        internal val collection: Uri,
        internal val relativePath: String
    ) {
        IMAGE("image/png", "png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/ComfyChair"),
        VIDEO("video/mp4", "mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + "/ComfyChair")
    }

    private const val SHARE_FILE_PREFIX = "share_"

    // Gallery

    /**
     * Save to Pictures/ComfyChair or Movies/ComfyChair. The entry stays hidden until written and is
     * removed if writing fails, so a failed save leaves no empty file in the gallery.
     *
     * @return Whether the media was saved
     */
    suspend fun saveToGallery(
        context: Context,
        kind: Kind,
        namePrefix: String = "ComfyChair",
        write: (OutputStream) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${namePrefix}_${System.currentTimeMillis()}.${kind.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(kind.collection, values)
        } catch (e: Exception) {
            null
        } ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("No output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            DebugLogger.e("MediaExport", "Saving to gallery failed: ${Obfuscator.error(e)}")
            resolver.delete(uri, null, null)
            false
        }
    }

    suspend fun saveImage(context: Context, bitmap: Bitmap, namePrefix: String = "ComfyChair"): Boolean =
        saveToGallery(context, Kind.IMAGE, namePrefix) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    suspend fun saveVideo(context: Context, bytes: ByteArray, namePrefix: String = "ComfyChair"): Boolean =
        saveToGallery(context, Kind.VIDEO, namePrefix) { it.write(bytes) }

    /** Save a video shown in the app: played from RAM or from a stored file. */
    suspend fun saveVideo(context: Context, videoUri: Uri, namePrefix: String = "ComfyChair"): Boolean =
        saveToGallery(context, Kind.VIDEO, namePrefix) { out ->
            InMemoryVideo.openInputStream(context, videoUri)?.use { it.copyTo(out) }
                ?: throw IllegalStateException("Video is no longer available")
        }

    // Sharing

    /** Write a share file and return a URI other apps can read, or null on failure. */
    suspend fun shareFile(context: Context, kind: Kind, write: (OutputStream) -> Unit): Uri? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "$SHARE_FILE_PREFIX${UUID.randomUUID()}.${kind.extension}")
            file.outputStream().use(write)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            DebugLogger.e("MediaExport", "Writing share file failed: ${Obfuscator.error(e)}")
            null
        }
    }

    suspend fun shareFile(context: Context, bitmap: Bitmap): Uri? =
        shareFile(context, Kind.IMAGE) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    suspend fun shareFile(context: Context, videoBytes: ByteArray): Uri? =
        shareFile(context, Kind.VIDEO) { it.write(videoBytes) }

    /**
     * A shareable URI for a video shown in the app: an in-memory video is written to a share file only
     * now, at the user's request; a stored file is shared as it is.
     */
    suspend fun shareFile(context: Context, videoUri: Uri): Uri? {
        if (!InMemoryVideo.isInMemory(videoUri)) return videoUri
        val bytes = InMemoryVideo.bytes(videoUri) ?: return null
        return shareFile(context, bytes)
    }

    /**
     * Open the system share sheet for one or more files.
     *
     * @param mimeType Type of all files; a wildcard type for mixed media
     */
    fun startShare(context: Context, uris: List<Uri>, mimeType: String, title: String) {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, title)
        // Started from an application context (ViewModels), the chooser needs its own task
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
