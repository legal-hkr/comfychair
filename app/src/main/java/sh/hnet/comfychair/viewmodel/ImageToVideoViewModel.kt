package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.suspendCoroutine
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.base.BaseVideoGenerationViewModel
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.viewmodel.base.VideoResult
import sh.hnet.comfychair.viewmodel.base.VideoResultState
import sh.hnet.comfychair.viewmodel.base.videoScreenDefaults

/**
 * UI state for the view mode toggle
 */
enum class ImageToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * UI state for the Image-to-Video screen.
 *
 * Generation parameters and the video result are exposed directly through delegation
 * (`state.steps`, `state.currentVideoUri` etc.).
 */
data class ImageToVideoUiState(
    val params: GenerationParams = videoScreenDefaults,
    val video: VideoResult = VideoResult(),
    val availableWorkflows: List<WorkflowItem> = emptyList(),
    val viewMode: ImageToVideoViewMode = ImageToVideoViewMode.SOURCE,
    val sourceImage: Bitmap? = null,
    val isUploading: Boolean = false
) : CommonGenerationState by params, VideoResultState by video

/**
 * ViewModel for the Image-to-Video screen
 */
class ImageToVideoViewModel : BaseVideoGenerationViewModel<ImageToVideoUiState>() {

    companion object {
        const val OWNER_ID = "IMAGE_TO_VIDEO"
    }

    override val initialState = ImageToVideoUiState()

    override val tag = "ImageToVideo"
    override val ownerId = OWNER_ID
    override val workflowType = WorkflowType.ITV
    override val prefsName = "ImageToVideoFragmentPrefs"
    override val videoFilePrefix = VideoUtils.FilePrefix.IMAGE_TO_VIDEO
    override val previewKey = MediaStateHolder.MediaKey.ItvPreview
    override fun videoKey(promptId: String) = MediaStateHolder.MediaKey.ItvVideo(promptId)
    override fun currentVideoPromptId() = MediaStateHolder.getCurrentItvPromptId()
    override fun clearCurrentVideoPromptId() = MediaStateHolder.clearCurrentItvPromptId()
    override fun defaultPositivePrompt() = SeasonalPrompts.getImageToVideoPrompt()

    override fun ImageToVideoUiState.params() = params
    override fun ImageToVideoUiState.withParams(params: GenerationParams) = copy(params = params)
    override fun ImageToVideoUiState.video() = video
    override fun ImageToVideoUiState.withVideo(video: VideoResult) = copy(video = video)
    override fun ImageToVideoUiState.workflows() = availableWorkflows
    override fun ImageToVideoUiState.withWorkflows(workflows: List<WorkflowItem>) = copy(availableWorkflows = workflows)

    override fun onInitialize() {
        super.onInitialize()
        loadSavedSourceImage()
    }

    override fun onStoredMediaAvailable() {
        super.onStoredMediaAvailable()
        loadSavedSourceImage()
    }

    override fun onClearShownMedia() {
        super.onClearShownMedia()
        _uiState.update { it.copy(sourceImage = null) }
    }

    override fun hasValidConfiguration(): Boolean =
        _uiState.value.sourceImage != null && isReadyToGenerate(_uiState.value.params)

    private fun loadSavedSourceImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItvSource, applicationContext)
        if (sourceImage != null) {
            // A source chosen meanwhile wins
            _uiState.update { if (it.sourceImage == null) it.copy(sourceImage = sourceImage) else it }
        }
    }

    // View mode
    fun onViewModeChange(mode: ImageToVideoViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    /**
     * Use a generated image as the source image and switch to the Source tab.
     *
     * May run before the screen was first opened, i.e. before [initialize]: the context is then
     * passed in so the image is stored where the initialization will read it, otherwise it would
     * bring back the previous source.
     */
    /**
     * Use a generated image as the source image and switch to the Source tab.
     *
     * May run before the screen was first opened, i.e. before [initialize]: the context is then
     * passed in so the image is stored where the initialization will read it, otherwise it would
     * bring back the previous source.
     */
    fun useAsSource(context: Context, bitmap: Bitmap) {
        val appContext = applicationContext ?: context.applicationContext
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItvSource, bitmap, appContext)
        _uiState.update { it.copy(sourceImage = bitmap, viewMode = ImageToVideoViewMode.SOURCE) }
    }

    /** Remove the source image (also from the device in device storage mode). */
    fun clearSourceImage() {
        val context = applicationContext ?: return
        _uiState.update { it.copy(sourceImage = null) }
        viewModelScope.launch { MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.ItvSource) }
    }

    // Source image
    fun onSourceImageChange(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItvSource, bitmap, context)

                    _uiState.update { it.copy(sourceImage = bitmap) }
                }
            } catch (e: Exception) {
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    /**
     * Upload source image to ComfyUI and prepare workflow
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

        val sourceImage = state.sourceImage
        if (sourceImage == null) {
            _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_no_source_image))
            return null
        }

        if (p.hasErrors) return null

        _uiState.update { it.copy(isUploading = true) }
        return try {
            // Convert bitmap to PNG byte array
            // Ensure we have ARGB_8888 format for proper PNG encoding
            val imageBytes = withContext(Dispatchers.IO) {
                val bitmapToUpload = if (sourceImage.config != Bitmap.Config.ARGB_8888) {
                    sourceImage.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    sourceImage
                }
                val outputStream = java.io.ByteArrayOutputStream()
                bitmapToUpload.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                if (bitmapToUpload !== sourceImage) {
                    bitmapToUpload.recycle()
                }
                outputStream.toByteArray()
            }

            // Upload to ComfyUI
            data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
            val uploadResult: UploadResult = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.uploadImage(imageBytes, UuidUtils.generateUniqueUploadFilename("itv_source")) { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }

            if (uploadResult.filename == null) {
                // Check for stall or auth failure - show dialog instead of toast
                if (uploadResult.failureType == ConnectionFailure.STALLED ||
                    uploadResult.failureType == ConnectionFailure.AUTHENTICATION) {
                    applicationContext?.let { ctx ->
                        ConnectionManager.showConnectionAlert(ctx, uploadResult.failureType)
                    }
                } else {
                    _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
                }
                return null
            }
            val uploadedFilename = uploadResult.filename

            WorkflowManager.prepareWorkflow(p.selectedWorkflowId, withSubmittedSeed(p), WorkflowManager.WorkflowImages(source = uploadedFilename))
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }
}
