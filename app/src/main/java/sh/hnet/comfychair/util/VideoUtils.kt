package sh.hnet.comfychair.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager

/**
 * Utility functions for video operations shared across video generation screens.
 */
object VideoUtils {

    // Delivers fetch results on the main thread; one shared scope instead of a new one per video
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Fetches a generated video from ComfyUI history and saves it to internal storage.
     *
     * @param context Application context
     * @param client ComfyUI API client
     * @param promptId The prompt ID to fetch history for
     * @param filePrefix Prefix for the local video file (e.g., "last_generated_video_" or "image_to_video_last_generated_")
     * @param onComplete Callback with the video URI (null if failed) and all result files of the job
     */
    fun fetchVideoFromHistory(
        context: Context,
        client: ComfyUIClient,
        promptId: String,
        filePrefix: String,
        onComplete: (Uri?, List<GenerationOutput>) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        client.fetchHistory(promptId) { historyJson ->
            // The first video file of the job; still frames saved next to it are skipped
            val outputs = GenerationOutputs.fromHistory(historyJson, promptId)
            val video = outputs.firstOrNull { it.isVideo } ?: run {
                mainHandler.post { onComplete(null, outputs) }
                return@fetchHistory
            }

            // Fetch video bytes
            client.fetchVideo(video.filename, video.subfolder, video.type) { videoBytes, failureType ->
                if (videoBytes == null) {
                    mainHandler.post {
                        if (failureType == ConnectionFailure.STALLED) {
                            ConnectionManager.showConnectionAlert(context, failureType)
                        }
                        onComplete(null, outputs)
                    }
                    return@fetchVideo
                }

                // Store in memory/disk based on caching mode
                val mediaKey = when (filePrefix) {
                    FilePrefix.TEXT_TO_VIDEO -> MediaStateHolder.MediaKey.TtvVideo(promptId)
                    FilePrefix.IMAGE_TO_VIDEO -> MediaStateHolder.MediaKey.ItvVideo(promptId)
                    FilePrefix.VIDEO_TO_VIDEO -> MediaStateHolder.MediaKey.V2vVideo(promptId)
                    else -> null
                }

                if (mediaKey == null) {
                    mainHandler.post { onComplete(null, outputs) }
                    return@fetchVideo
                }

                // Pass context for disk-first mode support
                MediaStateHolder.putVideoBytes(mediaKey, videoBytes, context)

                // Get URI for playback (in RAM in memory-first mode, the stored file otherwise)
                mainScope.launch {
                    val uri = MediaStateHolder.getVideoUri(context, mediaKey)
                    onComplete(uri, outputs)
                }
            }
        }
    }

    /**
     * Constants for video file prefixes used by different screens.
     */
    object FilePrefix {
        const val TEXT_TO_VIDEO = "last_generated_video_"
        const val IMAGE_TO_VIDEO = "image_to_video_last_generated_"
        const val VIDEO_TO_VIDEO = "video_to_video_last_generated_"
    }

    /**
     * Constants for gallery filename prefixes.
     */
    object GalleryPrefix {
        const val TEXT_TO_VIDEO = "ComfyChair"
        const val IMAGE_TO_VIDEO = "ComfyChair_ITV"
        const val VIDEO_TO_VIDEO = "ComfyChair_V2V"
    }
}
