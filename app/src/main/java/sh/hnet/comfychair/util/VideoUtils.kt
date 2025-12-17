package sh.hnet.comfychair.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import java.io.File

/**
 * Utility functions for video operations shared across video generation screens.
 */
object VideoUtils {

    /**
     * Fetches a generated video from ComfyUI history and saves it to internal storage.
     *
     * @param context Application context
     * @param client ComfyUI API client
     * @param promptId The prompt ID to fetch history for
     * @param filePrefix Prefix for the local video file (e.g., "last_generated_video_" or "image_to_video_last_generated_")
     * @param onComplete Callback with the video URI, or null if failed
     */
    fun fetchVideoFromHistory(
        context: Context,
        client: ComfyUIClient,
        promptId: String,
        filePrefix: String,
        onComplete: (Uri?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) {
                mainHandler.post { onComplete(null) }
                return@fetchHistory
            }

            // Parse video info from history
            val promptHistory = historyJson.optJSONObject(promptId)
            val outputs = promptHistory?.optJSONObject("outputs")

            if (outputs == null) {
                mainHandler.post { onComplete(null) }
                return@fetchHistory
            }

            // Find video in outputs
            // Note: ComfyUI may return videos in "videos", "gifs", or even "images" arrays
            // We check file extension to identify video files
            val outputKeys = outputs.keys()
            while (outputKeys.hasNext()) {
                val nodeId = outputKeys.next()
                val nodeOutput = outputs.optJSONObject(nodeId)

                // Check videos, gifs, and images arrays for video files
                val videos = nodeOutput?.optJSONArray("videos")
                    ?: nodeOutput?.optJSONArray("gifs")
                    ?: nodeOutput?.optJSONArray("images")

                if (videos != null && videos.length() > 0) {
                    val videoInfo = videos.optJSONObject(0)
                    val filename = videoInfo?.optString("filename") ?: continue
                    val subfolder = videoInfo.optString("subfolder", "")
                    val type = videoInfo.optString("type", "output")

                    // Fetch video bytes
                    client.fetchVideo(filename, subfolder, type) { videoBytes ->
                        if (videoBytes == null) {
                            mainHandler.post { onComplete(null) }
                            return@fetchVideo
                        }

                        // Clean up old generated videos with this prefix
                        context.filesDir.listFiles()?.forEach { file ->
                            if (file.name.startsWith(filePrefix) && file.name.endsWith(".mp4")) {
                                file.delete()
                            }
                        }

                        // Save to internal storage with unique name to force player reload
                        val videoFile = File(context.filesDir, "${filePrefix}${promptId}.mp4")
                        videoFile.writeBytes(videoBytes)

                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            videoFile
                        )

                        mainHandler.post { onComplete(uri) }
                    }
                    return@fetchHistory
                }
            }

            mainHandler.post { onComplete(null) }
        }
    }

    /**
     * Saves a video from a content URI to the device's Movies gallery.
     *
     * @param context Application context
     * @param videoUri The source video URI to save
     * @param filenamePrefix Prefix for the saved file (e.g., "ComfyChair" or "ComfyChair_ITV")
     */
    suspend fun saveVideoToGallery(
        context: Context,
        videoUri: Uri?,
        filenamePrefix: String = "ComfyChair"
    ) {
        if (videoUri == null) return

        withContext(Dispatchers.IO) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "${filenamePrefix}_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        resolver.openInputStream(videoUri)?.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.video_saved_to_gallery),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.failed_save_video),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Shares a video via Android's share sheet.
     *
     * @param context Application context
     * @param videoUri The video URI to share
     */
    fun shareVideo(context: Context, videoUri: Uri?) {
        if (videoUri == null) return

        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, videoUri)
                type = "video/mp4"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    context.getString(R.string.share_video)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.failed_share_video),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Constants for video file prefixes used by different screens.
     */
    object FilePrefix {
        const val TEXT_TO_VIDEO = "last_generated_video_"
        const val IMAGE_TO_VIDEO = "image_to_video_last_generated_"
    }

    /**
     * Constants for gallery filename prefixes.
     */
    object GalleryPrefix {
        const val TEXT_TO_VIDEO = "ComfyChair"
        const val IMAGE_TO_VIDEO = "ComfyChair_ITV"
    }
}
