package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.VideoFrames
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
    // The server's input file the source is, while it is (spec §21: used by name)
    val sourceServer: ServerSource? = null,
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

    // The source is kept under one name, so each version gets its own address: the player loads a replaced video
    // instead of replaying the one it has (readers use the path only)
    private var sourceVersion = 0

    private fun versioned(uri: Uri): Uri =
        uri.buildUpon().appendQueryParameter("version", (++sourceVersion).toString()).build()

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
            val uri = MediaStateHolder.getVideoUri(context, MediaStateHolder.MediaKey.V2vSource)?.let(::versioned) ?: return@launch
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
            val uri = MediaStateHolder.getVideoUri(context, MediaStateHolder.MediaKey.V2vSource)?.let(::versioned) ?: return@launch
            val frame = withContext(Dispatchers.IO) { VideoFrames.firstFrame(context, uri, FRAME_MAX_PX) }
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
            val read = if (declaredSize != null && declaredSize > MAX_SOURCE_BYTES) {
                VideoFrames.ReadResult.TooLarge
            } else {
                VideoFrames.readBytes(context, uri, MAX_SOURCE_BYTES)
            }
            val bytes = when (read) {
                is VideoFrames.ReadResult.Ok -> read.bytes
                VideoFrames.ReadResult.TooLarge -> {
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_too_large))
                    return@launch
                }
                VideoFrames.ReadResult.Unreadable -> {
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_unreadable))
                    return@launch
                }
            }
            val extension = resolver.getType(uri)
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: "mp4"
            if (!setSource(context, bytes, extension)) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_unreadable))
            }
        }
    }

    /**
     * The source from the server's input folder (spec §21): read into RAM to show it (at most [MAX_SOURCE_BYTES]),
     * used by name when generating, so nothing is uploaded.
     */
    fun onServerSourceVideo(context: Context, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = SourceUploads.serverVideo(name) ?: run {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_unreadable))
                return@launch
            }
            if (bytes.size > MAX_SOURCE_BYTES) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_too_large))
                return@launch
            }
            if (!setSource(context, bytes, name.substringAfterLast('.', "mp4").lowercase())) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_source_video_unreadable))
                return@launch
            }
            _uiState.update { it.copy(sourceServer = it.sourceVideoUri?.let { uri -> ServerSource(name, uri) }) }
        }
    }

    /**
     * Use a generated video as the source video and show it. The caller has checked [MAX_SOURCE_BYTES].
     *
     * May run before the screen was first opened, i.e. before [initialize]: the context is then passed in so the
     * video is stored where the initialization will read it.
     *
     * @return False if the video can't be played from where it's kept
     */
    suspend fun useAsSource(context: Context, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        setSource(applicationContext ?: context.applicationContext, bytes, "mp4")
    }

    /** Keeps [bytes] as the source (RAM or the device, by the storage mode) and shows it with its first frame. */
    private suspend fun setSource(context: Context, bytes: ByteArray, extension: String): Boolean {
        MediaStateHolder.putVideoBytes(MediaStateHolder.MediaKey.V2vSource, bytes, context)
        val playUri = MediaStateHolder.getVideoUri(context, MediaStateHolder.MediaKey.V2vSource)?.let(::versioned) ?: return false
        val frame = VideoFrames.firstFrame(context, playUri, FRAME_MAX_PX)
        sourceExtension = extension
        _uiState.update { it.copy(sourceVideoUri = playUri, sourceFrame = frame, viewMode = VideoToVideoViewMode.SOURCE) }
        return true
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
            // A server file by name (nothing read or uploaded), else the video uploaded, named by its content
            val uploadedFilename = state.sourceServer.nameWhile(state.sourceVideoUri) ?: run {
                val videoBytes = withContext(Dispatchers.IO) {
                    MediaStateHolder.getVideoBytes(MediaStateHolder.MediaKey.V2vSource, context)
                }
                if (videoBytes == null) {
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_no_source_video))
                    return null
                }
                val extension = sourceExtension
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/mp4"
                val uploaded = SourceUploads.bytes(client, "v2v_source", videoBytes, extension, mimeType)
                uploaded.filename ?: run {
                    // A stall or an authentication failure gets the connection dialog instead of a toast
                    if (!SourceUploads.alertIfConnection(context, uploaded.failure)) {
                        _events.emit(GenerationScreenEvent.ShowToast(R.string.error_upload_source_video))
                    }
                    return null
                }
            }

            WorkflowManager.prepareWorkflow(
                p.selectedWorkflowId, withSubmittedSeed(p), WorkflowManager.WorkflowImages(video = uploadedFilename)
            )
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }
}
