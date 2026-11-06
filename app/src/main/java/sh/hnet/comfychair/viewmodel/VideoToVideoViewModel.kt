package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.base.BaseVideoGenerationViewModel
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.viewmodel.base.VideoResult
import sh.hnet.comfychair.viewmodel.base.VideoResultState
import sh.hnet.comfychair.viewmodel.base.videoScreenDefaults

/** What the Video to Video preview shows full size: the source video or the result. */
enum class VideoToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * UI state for the Video-to-Video screen.
 *
 * Generation parameters and the video result are exposed directly through delegation (`state.steps`,
 * `state.currentVideoUri` etc.).
 *
 * @param sourceVideoUri The source video, played from where it's kept (RAM, or the device in device storage mode)
 * @param sourceFrame Its first frame: the thumbnail, and what stands in while the player starts
 */
data class VideoToVideoUiState(
    val params: GenerationParams = videoScreenDefaults,
    val video: VideoResult = VideoResult(),
    val availableWorkflows: List<WorkflowItem> = emptyList(),
    val viewMode: VideoToVideoViewMode = VideoToVideoViewMode.SOURCE,
    val sourceVideoUri: Uri? = null,
    val sourceFrame: Bitmap? = null,
    val isUploading: Boolean = false
) : CommonGenerationState by params, VideoResultState by video

/**
 * ViewModel for the Video-to-Video screen: a video screen whose source is a video, kept like the generated videos
 * ([MediaStateHolder.MediaKey.V2vSource]: in RAM only by default) and sent to the server with the job.
 */
class VideoToVideoViewModel : BaseVideoGenerationViewModel<VideoToVideoUiState>() {

    companion object {
        const val OWNER_ID = "VIDEO_TO_VIDEO"

        /** Largest source video, as it's held in RAM */
        const val MAX_SOURCE_BYTES = 100L * 1024 * 1024

        /** Longest side of the source's first frame (thumbnails and the still under the player) */
        private const val FRAME_MAX_PX = 1280
    }

    override val initialState = VideoToVideoUiState()

    override val tag = "VideoToVideo"
    override val ownerId = OWNER_ID
    override val workflowType = WorkflowType.V2V
    override val prefsName = "VideoToVideoFragmentPrefs"
    override val videoFilePrefix = VideoUtils.FilePrefix.VIDEO_TO_VIDEO
    override val previewKey = MediaStateHolder.MediaKey.V2vPreview
    override fun videoKey(promptId: String) = MediaStateHolder.MediaKey.V2vVideo(promptId)
    override fun currentVideoPromptId() = MediaStateHolder.getCurrentV2vPromptId()
    override fun clearCurrentVideoPromptId() = MediaStateHolder.clearCurrentV2vPromptId()
    override fun defaultPositivePrompt() = SeasonalPrompts.getVideoToVideoPrompt()

    override fun VideoToVideoUiState.params() = params
    override fun VideoToVideoUiState.withParams(params: GenerationParams) = copy(params = params)
    override fun VideoToVideoUiState.video() = video
    override fun VideoToVideoUiState.withVideo(video: VideoResult) = copy(video = video)
    override fun VideoToVideoUiState.workflows() = availableWorkflows
    override fun VideoToVideoUiState.withWorkflows(workflows: List<WorkflowItem>) = copy(availableWorkflows = workflows)

    // File extension of the source, for its name on the server (a restored source is taken as MP4)
    private var sourceExtension = "mp4"

    override fun onInitialize() {
        super.onInitialize()
        loadSavedSourceVideo()
    }

    override fun onStoredMediaAvailable() {
        super.onStoredMediaAvailable()
        loadSavedSourceVideo()
    }

    override fun onClearShownMedia() {
        super.onClearShownMedia()
        _uiState.update { it.copy(sourceVideoUri = null, sourceFrame = null) }
    }

    /** The source is played from where it's kept now (RAM or the device), not from where it was. */
    override fun onMediaStorageChanged() {
        super.onMediaStorageChanged()
        val context = applicationContext ?: return
        if (_uiState.value.sourceVideoUri == null) return
        viewModelScope.launch {
            val uri = MediaStateHolder.getVideoUri(context, MediaStateHolder.MediaKey.V2vSource) ?: return@launch
            _uiState.update { if (it.sourceVideoUri != null) it.copy(sourceVideoUri = uri) else it }
        }
    }

    override fun hasValidConfiguration(): Boolean =
        _uiState.value.sourceVideoUri != null && isReadyToGenerate(_uiState.value.params)

    private fun loadSavedSourceVideo() {
        val context = applicationContext ?: return
        if (_uiState.value.sourceVideoUri != null) return
        if (!MediaStateHolder.hasVideoBytes(MediaStateHolder.MediaKey.V2vSource, context)) return
        viewModelScope.launch {
            val uri = MediaStateHolder.getVideoUri(context, MediaStateHolder.MediaKey.V2vSource) ?: return@launch
            val frame = withContext(Dispatchers.IO) { firstFrame(context, uri) }
            // A source chosen meanwhile wins
            _uiState.update { if (it.sourceVideoUri == null) it.copy(sourceVideoUri = uri, sourceFrame = frame) else it }
        }
    }

    // View mode
    fun onViewModeChange(mode: VideoToVideoViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    /** Remove the source video (also from the device in device storage mode). */
    fun clearSourceVideo() {
        val context = applicationContext ?: return
        _uiState.update { it.copy(sourceVideoUri = null, sourceFrame = null) }
        viewModelScope.launch { MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.V2vSource) }
    }

    /**
     * A video picked as the source: read into RAM (at most [MAX_SOURCE_BYTES]), kept where the storage mode keeps
     * media, and shown with its first frame.
     */
    fun onSourceVideoChange(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            // Refuse too large a video before reading it, where the size is known
            val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
            if (declaredSize != null && declaredSize > MAX_SOURCE_BYTES) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_too_large))
                return@launch
            }
            val bytes = try {
                resolver.openInputStream(uri)?.use { readAtMost(it, MAX_SOURCE_BYTES) }
            } catch (e: Exception) {
                DebugLogger.w(tag, "Source video could not be read: ${e.javaClass.simpleName}")
                null
            }
            when {
                bytes == null -> {
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_unreadable))
                    return@launch
                }
                bytes.isEmpty() -> {
                    // Larger than the limit (the size wasn't declared)
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_too_large))
                    return@launch
                }
            }
            val extension = resolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: "mp4"

            MediaStateHolder.putVideoBytes(MediaStateHolder.MediaKey.V2vSource, bytes, context)
            val playUri = MediaStateHolder.getVideoUri(context, MediaStateHolder.MediaKey.V2vSource)
            if (playUri == null) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_unreadable))
                return@launch
            }
            val frame = firstFrame(context, playUri)
            sourceExtension = extension
            _uiState.update { it.copy(sourceVideoUri = playUri, sourceFrame = frame, viewMode = VideoToVideoViewMode.SOURCE) }
        }
    }

    /** The stream's bytes, or an empty array when there are more than [limit]. */
    private fun readAtMost(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return ByteArray(0)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** The video's first frame, scaled down to [FRAME_MAX_PX] (read from RAM where the video is kept there). */
    private fun firstFrame(context: Context, uri: Uri): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        try {
            InMemoryVideo.setDataSource(retriever, context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val scale = if (width > 0 && height > 0) minOf(1f, FRAME_MAX_PX.toFloat() / maxOf(width, height)) else 1f
            if (scale < 1f) {
                retriever.getScaledFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (width * scale).toInt(), (height * scale).toInt()
                )
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        DebugLogger.w(tag, "Source frame could not be read: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Upload the source video to ComfyUI and prepare the workflow.
     */
    suspend fun prepareWorkflow(): String? {
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        // Check connection before uploading
        val connected = suspendCoroutine { cont ->
            ConnectionManager.ensureConnection(context) { success ->
                cont.resumeWith(Result.success(success))
            }
        }
        if (!connected) return null  // Dialog already shown by ensureConnection

        val state = _uiState.value
        val p = state.params
        if (state.sourceVideoUri == null) {
            _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_no_source_video))
            return null
        }
        if (p.hasErrors) return null

        _uiState.update { it.copy(isUploading = true) }
        return try {
            val videoBytes = withContext(Dispatchers.IO) {
                MediaStateHolder.getVideoBytes(MediaStateHolder.MediaKey.V2vSource, context)
            }
            if (videoBytes == null) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_no_source_video))
                return null
            }
            val extension = sourceExtension
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"

            data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
            val uploadResult: UploadResult = withContext(Dispatchers.IO) {
                suspendCoroutine { continuation ->
                    client.uploadImage(
                        videoBytes,
                        UuidUtils.generateUniqueUploadFilename("v2v_source", extension),
                        mimeType = mimeType
                    ) { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }

            val uploadedFilename = uploadResult.filename
            if (uploadedFilename == null) {
                // A stall or an authentication failure gets the connection dialog instead of a toast
                if (uploadResult.failureType == ConnectionFailure.STALLED ||
                    uploadResult.failureType == ConnectionFailure.AUTHENTICATION) {
                    ConnectionManager.showConnectionAlert(context, uploadResult.failureType)
                } else {
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.error_upload_source_video))
                }
                return null
            }

            WorkflowManager.prepareWorkflow(
                p.selectedWorkflowId, withSubmittedSeed(p), WorkflowManager.WorkflowImages(video = uploadedFilename)
            )
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }
}
