package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import java.io.File
import java.io.FileOutputStream

/**
 * UI state for the view mode toggle
 */
enum class ImageToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * UI state for the Image-to-Video screen
 */
data class ImageToVideoUiState(
    // View state
    val viewMode: ImageToVideoViewMode = ImageToVideoViewMode.SOURCE,
    val sourceImage: Bitmap? = null,
    val previewBitmap: Bitmap? = null,

    // Workflow selection
    val selectedWorkflow: String = "",
    val availableWorkflows: List<String> = emptyList(),

    // Model selections
    val selectedHighnoiseUnet: String = "",
    val selectedLownoiseUnet: String = "",
    val selectedHighnoiseLora: String = "",
    val selectedLownoiseLora: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",

    // Available models
    val availableUnets: List<String> = emptyList(),
    val availableLoras: List<String> = emptyList(),
    val availableVaes: List<String> = emptyList(),
    val availableClips: List<String> = emptyList(),

    // Generation parameters
    val width: String = "848",
    val height: String = "480",
    val length: String = "33",
    val fps: String = "16",

    // Validation errors
    val widthError: String? = null,
    val heightError: String? = null,
    val lengthError: String? = null,
    val fpsError: String? = null,

    // Positive prompt
    val positivePrompt: String = "",

    // Deferred model selections (for restoring after models load)
    val deferredHighnoiseUnet: String? = null,
    val deferredLownoiseUnet: String? = null,
    val deferredHighnoiseLora: String? = null,
    val deferredLownoiseLora: String? = null,
    val deferredVae: String? = null,
    val deferredClip: String? = null
)

/**
 * Events emitted by the image-to-video screen
 */
sealed class ImageToVideoEvent {
    data class ShowToast(val messageResId: Int) : ImageToVideoEvent()
    data class ShowToastMessage(val message: String) : ImageToVideoEvent()
}

/**
 * ViewModel for the Image-to-Video screen
 */
class ImageToVideoViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ImageToVideoUiState())
    val uiState: StateFlow<ImageToVideoUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImageToVideoEvent>()
    val events: SharedFlow<ImageToVideoEvent> = _events.asSharedFlow()

    private var workflowManager: WorkflowManager? = null
    private var comfyUIClient: ComfyUIClient? = null
    private var applicationContext: Context? = null

    // Reference to GenerationViewModel and callbacks for event handling
    private var generationViewModelRef: GenerationViewModel? = null
    private var previewBitmapCallback: ((Bitmap) -> Unit)? = null
    private var videoFetchedCallback: ((promptId: String) -> Unit)? = null

    companion object {
        const val OWNER_ID = "IMAGE_TO_VIDEO"
        private const val PREFS_NAME = "ImageToVideoFragmentPrefs"
        private const val KEY_WORKFLOW = "workflow"
        private const val KEY_HIGHNOISE_UNET = "highnoise_unet"
        private const val KEY_LOWNOISE_UNET = "lownoise_unet"
        private const val KEY_HIGHNOISE_LORA = "highnoise_lora"
        private const val KEY_LOWNOISE_LORA = "lownoise_lora"
        private const val KEY_VAE = "vae"
        private const val KEY_CLIP = "clip"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_LENGTH = "length"
        private const val KEY_FPS = "fps"
        private const val KEY_POSITIVE_PROMPT = "positive_prompt"
    }

    fun initialize(context: Context, client: ComfyUIClient) {
        applicationContext = context.applicationContext
        workflowManager = WorkflowManager(context)
        comfyUIClient = client

        loadWorkflows()
        restorePreferences()
        loadSavedSourceImage()
        loadModels()
    }

    private fun loadWorkflows() {
        val workflows = workflowManager?.getImageToVideoUNETWorkflowNames() ?: emptyList()
        _uiState.value = _uiState.value.copy(availableWorkflows = workflows)

        // Select first workflow if none selected
        if (_uiState.value.selectedWorkflow.isEmpty() && workflows.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(selectedWorkflow = workflows.first())
        }
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedWorkflow = prefs.getString(KEY_WORKFLOW, "") ?: ""
        val savedWidth = prefs.getString(KEY_WIDTH, "848") ?: "848"
        val savedHeight = prefs.getString(KEY_HEIGHT, "480") ?: "480"
        val savedLength = prefs.getString(KEY_LENGTH, "33") ?: "33"
        val savedFps = prefs.getString(KEY_FPS, "16") ?: "16"
        val defaultPositivePrompt = context.getString(R.string.default_prompt_image)
        val savedPositivePrompt = prefs.getString(KEY_POSITIVE_PROMPT, null) ?: defaultPositivePrompt

        // Deferred model selections
        val deferredHighnoiseUnet = prefs.getString(KEY_HIGHNOISE_UNET, null)
        val deferredLownoiseUnet = prefs.getString(KEY_LOWNOISE_UNET, null)
        val deferredHighnoiseLora = prefs.getString(KEY_HIGHNOISE_LORA, null)
        val deferredLownoiseLora = prefs.getString(KEY_LOWNOISE_LORA, null)
        val deferredVae = prefs.getString(KEY_VAE, null)
        val deferredClip = prefs.getString(KEY_CLIP, null)

        _uiState.value = _uiState.value.copy(
            selectedWorkflow = if (savedWorkflow.isNotEmpty() && _uiState.value.availableWorkflows.contains(savedWorkflow)) {
                savedWorkflow
            } else {
                _uiState.value.selectedWorkflow
            },
            width = savedWidth,
            height = savedHeight,
            length = savedLength,
            fps = savedFps,
            positivePrompt = savedPositivePrompt,
            deferredHighnoiseUnet = deferredHighnoiseUnet,
            deferredLownoiseUnet = deferredLownoiseUnet,
            deferredHighnoiseLora = deferredHighnoiseLora,
            deferredLownoiseLora = deferredLownoiseLora,
            deferredVae = deferredVae,
            deferredClip = deferredClip
        )
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val state = _uiState.value

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKFLOW, state.selectedWorkflow)
            .putString(KEY_HIGHNOISE_UNET, state.selectedHighnoiseUnet)
            .putString(KEY_LOWNOISE_UNET, state.selectedLownoiseUnet)
            .putString(KEY_HIGHNOISE_LORA, state.selectedHighnoiseLora)
            .putString(KEY_LOWNOISE_LORA, state.selectedLownoiseLora)
            .putString(KEY_VAE, state.selectedVae)
            .putString(KEY_CLIP, state.selectedClip)
            .putString(KEY_WIDTH, state.width)
            .putString(KEY_HEIGHT, state.height)
            .putString(KEY_LENGTH, state.length)
            .putString(KEY_FPS, state.fps)
            .putString(KEY_POSITIVE_PROMPT, state.positivePrompt)
            .apply()
    }

    private fun loadSavedSourceImage() {
        val context = applicationContext ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val sourceFile = File(context.filesDir, "image_to_video_last_source.png")
            if (sourceFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                _uiState.value = _uiState.value.copy(sourceImage = bitmap)
            }
        }
    }

    private fun loadModels() {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            // Load UNETs
            withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchUNETs { unets ->
                        continuation.resumeWith(Result.success(unets))
                    }
                }
            }.let { unets ->
                val state = _uiState.value
                val highnoiseUnet = state.deferredHighnoiseUnet?.takeIf { it in unets }
                    ?: unets.firstOrNull() ?: ""
                val lownoiseUnet = state.deferredLownoiseUnet?.takeIf { it in unets }
                    ?: unets.firstOrNull() ?: ""

                _uiState.value = state.copy(
                    availableUnets = unets,
                    selectedHighnoiseUnet = highnoiseUnet,
                    selectedLownoiseUnet = lownoiseUnet,
                    deferredHighnoiseUnet = null,
                    deferredLownoiseUnet = null
                )
            }

            // Load LoRAs
            withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchLoRAs { loras ->
                        continuation.resumeWith(Result.success(loras))
                    }
                }
            }.let { loras ->
                val state = _uiState.value
                val highnoiseLora = state.deferredHighnoiseLora?.takeIf { it in loras }
                    ?: loras.firstOrNull() ?: ""
                val lownoiseLora = state.deferredLownoiseLora?.takeIf { it in loras }
                    ?: loras.firstOrNull() ?: ""

                _uiState.value = state.copy(
                    availableLoras = loras,
                    selectedHighnoiseLora = highnoiseLora,
                    selectedLownoiseLora = lownoiseLora,
                    deferredHighnoiseLora = null,
                    deferredLownoiseLora = null
                )
            }

            // Load VAEs
            withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchVAEs { vaes ->
                        continuation.resumeWith(Result.success(vaes))
                    }
                }
            }.let { vaes ->
                val state = _uiState.value
                val vae = state.deferredVae?.takeIf { it in vaes }
                    ?: vaes.firstOrNull() ?: ""

                _uiState.value = state.copy(
                    availableVaes = vaes,
                    selectedVae = vae,
                    deferredVae = null
                )
            }

            // Load CLIPs
            withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchCLIPs { clips ->
                        continuation.resumeWith(Result.success(clips))
                    }
                }
            }.let { clips ->
                val state = _uiState.value
                val clip = state.deferredClip?.takeIf { it in clips }
                    ?: clips.firstOrNull() ?: ""

                _uiState.value = state.copy(
                    availableClips = clips,
                    selectedClip = clip,
                    deferredClip = null
                )
            }

            savePreferences()
        }
    }

    // View mode
    fun onViewModeChange(mode: ImageToVideoViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    // Source image
    fun onSourceImageChange(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    // Save to file
                    val file = File(context.filesDir, "image_to_video_last_source.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    _uiState.value = _uiState.value.copy(sourceImage = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(ImageToVideoEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    fun onWorkflowChange(workflow: String) {
        _uiState.value = _uiState.value.copy(selectedWorkflow = workflow)
        savePreferences()
    }

    fun onHighnoiseUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedHighnoiseUnet = unet)
        savePreferences()
    }

    fun onLownoiseUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedLownoiseUnet = unet)
        savePreferences()
    }

    fun onHighnoiseLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedHighnoiseLora = lora)
        savePreferences()
    }

    fun onLownoiseLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedLownoiseLora = lora)
        savePreferences()
    }

    fun onVaeChange(vae: String) {
        _uiState.value = _uiState.value.copy(selectedVae = vae)
        savePreferences()
    }

    fun onClipChange(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip = clip)
        savePreferences()
    }

    fun onWidthChange(width: String) {
        val error = validateDimension(width, "Width")
        _uiState.value = _uiState.value.copy(width = width, widthError = error)
        if (error == null) savePreferences()
    }

    fun onHeightChange(height: String) {
        val error = validateDimension(height, "Height")
        _uiState.value = _uiState.value.copy(height = height, heightError = error)
        if (error == null) savePreferences()
    }

    fun onLengthChange(length: String) {
        val error = validateLength(length)
        _uiState.value = _uiState.value.copy(length = length, lengthError = error)
        if (error == null) savePreferences()
    }

    fun onFpsChange(fps: String) {
        val error = validateFps(fps)
        _uiState.value = _uiState.value.copy(fps = fps, fpsError = error)
        if (error == null) savePreferences()
    }

    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        savePreferences()
    }

    private fun validateDimension(value: String, name: String): String? {
        val num = value.toIntOrNull()
        return when {
            value.isEmpty() -> "Required"
            num == null -> "Invalid number"
            num !in 1..4096 -> "$name must be 1-4096"
            else -> null
        }
    }

    private fun validateLength(value: String): String? {
        val num = value.toIntOrNull()
        return when {
            value.isEmpty() -> "Required"
            num == null -> "Invalid number"
            num !in 1..129 -> "Length must be 1-129"
            (num - 1) % 4 != 0 -> "Length must be 1, 5, 9, 13... up to 129"
            else -> null
        }
    }

    private fun validateFps(value: String): String? {
        val num = value.toIntOrNull()
        return when {
            value.isEmpty() -> "Required"
            num == null -> "Invalid number"
            num !in 1..120 -> "FPS must be 1-120"
            else -> null
        }
    }

    /**
     * Upload source image to ComfyUI and prepare workflow
     */
    suspend fun prepareWorkflow(): String? {
        val client = comfyUIClient ?: return null
        val wm = workflowManager ?: return null
        val context = applicationContext ?: return null
        val state = _uiState.value

        val sourceImage = state.sourceImage
        if (sourceImage == null) {
            _events.emit(ImageToVideoEvent.ShowToast(R.string.no_source_image))
            return null
        }

        // Validate all fields
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        val width = state.width.toIntOrNull() ?: return null
        val height = state.height.toIntOrNull() ?: return null
        val length = state.length.toIntOrNull() ?: return null
        val fps = state.fps.toIntOrNull() ?: return null

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
        val uploadedFilename: String? = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.uploadImage(imageBytes, "itv_source.png") { success, filename, _ ->
                    continuation.resumeWith(Result.success(if (success) filename else null))
                }
            }
        }

        if (uploadedFilename == null) {
            _events.emit(ImageToVideoEvent.ShowToast(R.string.failed_save_image))
            return null
        }

        val workflow = wm.prepareImageToVideoWorkflow(
            workflowName = state.selectedWorkflow,
            positivePrompt = state.positivePrompt,
            highnoiseUnet = state.selectedHighnoiseUnet,
            lownoiseUnet = state.selectedLownoiseUnet,
            highnoiseLora = state.selectedHighnoiseLora,
            lownoiseLora = state.selectedLownoiseLora,
            vae = state.selectedVae,
            clip = state.selectedClip,
            width = width,
            height = height,
            length = length,
            fps = fps,
            imageFilename = uploadedFilename
        )

        return workflow
    }

    fun hasValidConfiguration(): Boolean {
        val state = _uiState.value
        return state.sourceImage != null &&
                state.selectedWorkflow.isNotEmpty() &&
                state.selectedHighnoiseUnet.isNotEmpty() &&
                state.selectedLownoiseUnet.isNotEmpty() &&
                state.selectedHighnoiseLora.isNotEmpty() &&
                state.selectedLownoiseLora.isNotEmpty() &&
                state.selectedVae.isNotEmpty() &&
                state.selectedClip.isNotEmpty() &&
                state.widthError == null &&
                state.heightError == null &&
                state.lengthError == null &&
                state.fpsError == null
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewBitmap = null)
    }

    // Event listener management

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     * @param generationViewModel The shared GenerationViewModel
     * @param onPreviewBitmap Callback for preview image updates
     * @param onVideoFetched Callback when video generation is complete (provides promptId)
     */
    fun startListening(
        generationViewModel: GenerationViewModel,
        onPreviewBitmap: (Bitmap) -> Unit,
        onVideoFetched: (promptId: String) -> Unit
    ) {
        generationViewModelRef = generationViewModel
        previewBitmapCallback = onPreviewBitmap
        videoFetchedCallback = onVideoFetched

        generationViewModel.registerEventHandler(OWNER_ID) { event ->
            handleGenerationEvent(event)
        }
    }

    /**
     * Stop listening for generation events.
     * Note: We keep the refs if generation is still running,
     * as the handler may still be called for completion events.
     */
    fun stopListening(generationViewModel: GenerationViewModel) {
        generationViewModel.unregisterEventHandler(OWNER_ID)
        // Only clear refs if no generation is active (handler was actually unregistered)
        // If generation is running, the handler is kept and needs the refs
        if (!generationViewModel.generationState.value.isGenerating) {
            if (generationViewModelRef == generationViewModel) {
                generationViewModelRef = null
                previewBitmapCallback = null
                videoFetchedCallback = null
            }
        }
    }

    /**
     * Handle generation events from the GenerationViewModel.
     */
    private fun handleGenerationEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.PreviewImage -> {
                previewBitmapCallback?.invoke(event.bitmap)
            }
            is GenerationEvent.ImageGenerated -> {
                // For video, ImageGenerated event is used since we parse history the same way
                videoFetchedCallback?.invoke(event.promptId)
            }
            is GenerationEvent.Error -> {
                generationViewModelRef?.completeGeneration()
            }
            else -> {}
        }
    }
}
