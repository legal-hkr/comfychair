package sh.hnet.comfychair.viewmodel

import android.content.Context
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

/**
 * UI state for the Text-to-Video screen
 */
data class TextToVideoUiState(
    // Preview state
    val previewBitmap: android.graphics.Bitmap? = null,

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
 * One-time events for Text-to-Video screen
 */
sealed class TextToVideoEvent {
    data class ShowToastMessage(val message: String) : TextToVideoEvent()
}

/**
 * ViewModel for the Text-to-Video screen
 */
class TextToVideoViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TextToVideoUiState())
    val uiState: StateFlow<TextToVideoUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TextToVideoEvent>()
    val events: SharedFlow<TextToVideoEvent> = _events.asSharedFlow()

    private var workflowManager: WorkflowManager? = null
    private var comfyUIClient: ComfyUIClient? = null
    private var applicationContext: Context? = null

    // Reference to GenerationViewModel and callbacks for event handling
    private var generationViewModelRef: GenerationViewModel? = null
    private var previewBitmapCallback: ((android.graphics.Bitmap) -> Unit)? = null
    private var videoFetchedCallback: ((promptId: String) -> Unit)? = null

    companion object {
        const val OWNER_ID = "TEXT_TO_VIDEO"
        private const val PREFS_NAME = "TextToVideoFragmentPrefs"
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
        loadModels()
    }

    private fun loadWorkflows() {
        val workflows = workflowManager?.getVideoUNETWorkflowNames() ?: emptyList()
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
        val savedWidth = prefs.getString(KEY_WIDTH, "480") ?: "480"
        val savedHeight = prefs.getString(KEY_HEIGHT, "800") ?: "800"
        val savedLength = prefs.getString(KEY_LENGTH, "49") ?: "49"
        val savedFps = prefs.getString(KEY_FPS, "24") ?: "24"
        val defaultPositivePrompt = context.getString(R.string.default_prompt_text_to_video)
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

    fun onPreviewBitmapChange(bitmap: android.graphics.Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewBitmap = null)
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

    fun prepareWorkflow(): String? {
        val state = _uiState.value

        // Validate all fields
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        val width = state.width.toIntOrNull() ?: return null
        val height = state.height.toIntOrNull() ?: return null
        val length = state.length.toIntOrNull() ?: return null
        val fps = state.fps.toIntOrNull() ?: return null

        return workflowManager?.prepareVideoWorkflow(
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
            fps = fps
        )
    }

    fun hasValidConfiguration(): Boolean {
        val state = _uiState.value
        return state.selectedWorkflow.isNotEmpty() &&
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
        onPreviewBitmap: (android.graphics.Bitmap) -> Unit,
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
                viewModelScope.launch {
                    _events.emit(TextToVideoEvent.ShowToastMessage(event.message))
                }
                generationViewModelRef?.completeGeneration()
            }
            else -> {}
        }
    }
}
