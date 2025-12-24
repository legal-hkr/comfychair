package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils

/**
 * Represents a workflow item with display name and type prefix
 */
data class TtvWorkflowItem(
    val name: String,           // Internal workflow name
    val displayName: String,    // "[UNET] LightX2V"
    val type: WorkflowType      // TTV_UNET
)

/**
 * UI state for the Text-to-Video screen
 */
data class TextToVideoUiState(
    // Preview state
    val previewBitmap: android.graphics.Bitmap? = null,
    val currentVideoUri: android.net.Uri? = null,

    // Workflow selection
    val selectedWorkflow: String = "",
    val availableWorkflows: List<TtvWorkflowItem> = emptyList(),

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

    // Positive prompt (global)
    val positivePrompt: String = "",

    // Negative prompt (per-workflow)
    val negativePrompt: String = "",

    // Deferred model selections (for restoring after models load)
    val deferredHighnoiseUnet: String? = null,
    val deferredLownoiseUnet: String? = null,
    val deferredHighnoiseLora: String? = null,
    val deferredLownoiseLora: String? = null,
    val deferredVae: String? = null,
    val deferredClip: String? = null,

    // Additional LoRA chains (optional, 0-5 LoRAs on top of mandatory LightX2V LoRAs)
    val highnoiseLoraChain: List<LoraSelection> = emptyList(),
    val lownoiseLoraChain: List<LoraSelection> = emptyList()
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

    // State
    private val _uiState = MutableStateFlow(TextToVideoUiState())
    val uiState: StateFlow<TextToVideoUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TextToVideoEvent>()
    val events: SharedFlow<TextToVideoEvent> = _events.asSharedFlow()

    private var workflowManager: WorkflowManager? = null
    private var comfyUIClient: ComfyUIClient? = null
    private var applicationContext: Context? = null
    private var workflowValuesStorage: WorkflowValuesStorage? = null

    // Reference to GenerationViewModel for event handling
    private var generationViewModelRef: GenerationViewModel? = null

    // Constants
    companion object {
        private const val TAG = "TextToVideo"
        const val OWNER_ID = "TEXT_TO_VIDEO"
        private const val PREFS_NAME = "TextToVideoFragmentPrefs"

        // Global preferences
        private const val KEY_WORKFLOW = "workflow"
        private const val KEY_POSITIVE_PROMPT = "positive_prompt"
    }

    fun initialize(context: Context, client: ComfyUIClient) {
        DebugLogger.i(TAG, "Initializing")
        applicationContext = context.applicationContext
        workflowManager = WorkflowManager(context)
        workflowValuesStorage = WorkflowValuesStorage(context)
        comfyUIClient = client

        loadWorkflows()
        restorePreferences()
        restoreLastPreviewImage()
        loadLastGeneratedVideo()
        loadModels()
    }

    /**
     * Load the last generated video from cache.
     * This restores the video preview when the screen is recreated.
     * Uses runBlocking to ensure synchronous restoration like TTI/ITI.
     */
    private fun loadLastGeneratedVideo() {
        val context = applicationContext ?: return
        val promptId = MediaStateHolder.getCurrentTtvPromptId() ?: return

        val key = MediaStateHolder.MediaKey.TtvVideo(promptId)
        if (MediaStateHolder.hasVideoBytes(key, context)) {
            val uri = runBlocking {
                MediaStateHolder.getVideoUri(context, key)
            }
            if (uri != null) {
                _uiState.value = _uiState.value.copy(currentVideoUri = uri)
            }
        }
    }

    private fun loadWorkflows() {
        val wm = workflowManager ?: return
        val ctx = applicationContext ?: return

        val unetWorkflows = wm.getVideoUNETWorkflowNames()
        val unetPrefix = ctx.getString(R.string.mode_unet)

        val unifiedWorkflows = unetWorkflows.map { name ->
            TtvWorkflowItem(
                name = name,
                displayName = "[$unetPrefix] $name",
                type = WorkflowType.TTV_UNET
            )
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val defaultWorkflow = sortedWorkflows.firstOrNull()?.name ?: ""

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = if (_uiState.value.selectedWorkflow.isEmpty()) defaultWorkflow else _uiState.value.selectedWorkflow
        )
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val manager = workflowManager ?: return
        val storage = workflowValuesStorage ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load global preferences
        val savedWorkflow = prefs.getString(KEY_WORKFLOW, "") ?: ""
        val defaultPositivePrompt = SeasonalPrompts.getTextToVideoPrompt()
        val savedPositivePrompt = prefs.getString(KEY_POSITIVE_PROMPT, null) ?: defaultPositivePrompt

        val workflow = if (savedWorkflow.isNotEmpty() && _uiState.value.availableWorkflows.any { it.name == savedWorkflow }) {
            savedWorkflow
        } else {
            _uiState.value.selectedWorkflow
        }

        // Load per-workflow values
        // Use workflow name directly as storage key for consistency
        val savedValues = storage.loadValues(workflow)
        val defaults = manager.getWorkflowDefaults(workflow)

        _uiState.value = _uiState.value.copy(
            selectedWorkflow = workflow,
            positivePrompt = savedPositivePrompt,
            negativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",
            width = savedValues?.width?.toString()
                ?: defaults?.width?.toString() ?: "848",
            height = savedValues?.height?.toString()
                ?: defaults?.height?.toString() ?: "480",
            length = savedValues?.length?.toString()
                ?: defaults?.length?.toString() ?: "33",
            fps = savedValues?.frameRate?.toString()
                ?: defaults?.frameRate?.toString() ?: "16",
            // Deferred model selections from saved values
            deferredHighnoiseUnet = savedValues?.highnoiseUnetModel,
            deferredLownoiseUnet = savedValues?.lownoiseUnetModel,
            deferredHighnoiseLora = savedValues?.highnoiseLoraModel,
            deferredLownoiseLora = savedValues?.lownoiseLoraModel,
            deferredVae = savedValues?.vaeModel,
            deferredClip = savedValues?.clipModel,
            highnoiseLoraChain = savedValues?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
        )
    }

    /**
     * Save current workflow values to per-workflow storage
     */
    private fun saveWorkflowValues(workflowName: String) {
        val storage = workflowValuesStorage ?: return
        val state = _uiState.value

        // Use workflow name directly as storage key for consistency
        // Workflow names are unique and always available
        val values = WorkflowValues(
            workflowId = workflowName,
            width = state.width.toIntOrNull(),
            height = state.height.toIntOrNull(),
            length = state.length.toIntOrNull(),
            frameRate = state.fps.toIntOrNull(),
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            highnoiseUnetModel = state.selectedHighnoiseUnet.takeIf { it.isNotEmpty() },
            lownoiseUnetModel = state.selectedLownoiseUnet.takeIf { it.isNotEmpty() },
            highnoiseLoraModel = state.selectedHighnoiseLora.takeIf { it.isNotEmpty() },
            lownoiseLoraModel = state.selectedLownoiseLora.takeIf { it.isNotEmpty() },
            vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
            highnoiseLoraChain = LoraSelection.toJsonString(state.highnoiseLoraChain).takeIf { state.highnoiseLoraChain.isNotEmpty() },
            lownoiseLoraChain = LoraSelection.toJsonString(state.lownoiseLoraChain).takeIf { state.lownoiseLoraChain.isNotEmpty() }
        )

        storage.saveValues(workflowName, values)
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val state = _uiState.value

        // Save global preferences
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKFLOW, state.selectedWorkflow)
            .putString(KEY_POSITIVE_PROMPT, state.positivePrompt)
            .apply()

        // Save per-workflow values
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow)
        }
    }

    private fun loadModels() {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            // Load UNETs
            val unets = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine<List<String>> { continuation ->
                    client.fetchUNETs { fetchedUnets ->
                        continuation.resumeWith(Result.success(fetchedUnets))
                    }
                }
            }
            // Use atomic update to avoid race conditions
            _uiState.update { state ->
                val highnoiseUnet = state.deferredHighnoiseUnet?.takeIf { it in unets }
                    ?: unets.firstOrNull() ?: ""
                val lownoiseUnet = state.deferredLownoiseUnet?.takeIf { it in unets }
                    ?: unets.firstOrNull() ?: ""
                state.copy(
                    availableUnets = unets,
                    selectedHighnoiseUnet = highnoiseUnet,
                    selectedLownoiseUnet = lownoiseUnet,
                    deferredHighnoiseUnet = null,
                    deferredLownoiseUnet = null
                )
            }

            // Load LoRAs
            val loras = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine<List<String>> { continuation ->
                    client.fetchLoRAs { fetchedLoras ->
                        continuation.resumeWith(Result.success(fetchedLoras))
                    }
                }
            }
            _uiState.update { state ->
                val highnoiseLora = state.deferredHighnoiseLora?.takeIf { it in loras }
                    ?: loras.firstOrNull() ?: ""
                val lownoiseLora = state.deferredLownoiseLora?.takeIf { it in loras }
                    ?: loras.firstOrNull() ?: ""
                val filteredHighnoiseChain = LoraChainManager.filterUnavailable(state.highnoiseLoraChain, loras)
                val filteredLownoiseChain = LoraChainManager.filterUnavailable(state.lownoiseLoraChain, loras)
                state.copy(
                    availableLoras = loras,
                    selectedHighnoiseLora = highnoiseLora,
                    selectedLownoiseLora = lownoiseLora,
                    deferredHighnoiseLora = null,
                    deferredLownoiseLora = null,
                    highnoiseLoraChain = filteredHighnoiseChain,
                    lownoiseLoraChain = filteredLownoiseChain
                )
            }

            // Load VAEs
            val vaes = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine<List<String>> { continuation ->
                    client.fetchVAEs { fetchedVaes ->
                        continuation.resumeWith(Result.success(fetchedVaes))
                    }
                }
            }
            _uiState.update { state ->
                val vae = state.deferredVae?.takeIf { it in vaes }
                    ?: vaes.firstOrNull() ?: ""
                state.copy(
                    availableVaes = vaes,
                    selectedVae = vae,
                    deferredVae = null
                )
            }

            // Load CLIPs
            val clips = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine<List<String>> { continuation ->
                    client.fetchCLIPs { fetchedClips ->
                        continuation.resumeWith(Result.success(fetchedClips))
                    }
                }
            }
            _uiState.update { state ->
                val clip = state.deferredClip?.takeIf { it in clips }
                    ?: clips.firstOrNull() ?: ""
                state.copy(
                    availableClips = clips,
                    selectedClip = clip,
                    deferredClip = null
                )
            }

            savePreferences()
        }
    }

    fun onWorkflowChange(workflow: String) {
        val state = _uiState.value
        val manager = workflowManager ?: return
        val storage = workflowValuesStorage ?: return

        // Save current workflow values before switching
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow)
        }

        // Load new workflow's saved values or defaults
        // Use workflow name directly as storage key for consistency
        val savedValues = storage.loadValues(workflow)
        val defaults = manager.getWorkflowDefaults(workflow)

        _uiState.value = state.copy(
            selectedWorkflow = workflow,
            negativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",
            width = savedValues?.width?.toString()
                ?: defaults?.width?.toString() ?: "848",
            height = savedValues?.height?.toString()
                ?: defaults?.height?.toString() ?: "480",
            length = savedValues?.length?.toString()
                ?: defaults?.length?.toString() ?: "33",
            fps = savedValues?.frameRate?.toString()
                ?: defaults?.frameRate?.toString() ?: "16",
            selectedHighnoiseUnet = savedValues?.highnoiseUnetModel ?: "",
            selectedLownoiseUnet = savedValues?.lownoiseUnetModel ?: "",
            selectedHighnoiseLora = savedValues?.highnoiseLoraModel ?: "",
            selectedLownoiseLora = savedValues?.lownoiseLoraModel ?: "",
            selectedVae = savedValues?.vaeModel ?: "",
            selectedClip = savedValues?.clipModel ?: "",
            highnoiseLoraChain = savedValues?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
        )
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
        val error = ValidationUtils.validateDimension(width, applicationContext)
        _uiState.value = _uiState.value.copy(width = width, widthError = error)
        if (error == null) savePreferences()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        _uiState.value = _uiState.value.copy(height = height, heightError = error)
        if (error == null) savePreferences()
    }

    fun onLengthChange(length: String) {
        val error = validateVideoLength(length)
        _uiState.value = _uiState.value.copy(length = length, lengthError = error)
        if (error == null) savePreferences()
    }

    fun onFpsChange(fps: String) {
        val error = ValidationUtils.validateFrameRate(fps, applicationContext)
        _uiState.value = _uiState.value.copy(fps = fps, fpsError = error)
        if (error == null) savePreferences()
    }

    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        savePreferences()
    }

    fun onNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
        savePreferences()
    }

    // High noise LoRA chain operations
    fun onAddHighnoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.highnoiseLoraChain, state.availableLoras)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveHighnoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.highnoiseLoraChain, index)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onHighnoiseLoraChainNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.highnoiseLoraChain, index, name)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    fun onHighnoiseLoraChainStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.highnoiseLoraChain, index, strength)
        if (newChain === state.highnoiseLoraChain) return // No change

        _uiState.value = state.copy(highnoiseLoraChain = newChain)
        savePreferences()
    }

    // Low noise LoRA chain operations
    fun onAddLownoiseLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.lownoiseLoraChain, state.availableLoras)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveLownoiseLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.lownoiseLoraChain, index)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onLownoiseLoraChainNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.lownoiseLoraChain, index, name)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onLownoiseLoraChainStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.lownoiseLoraChain, index, strength)
        if (newChain === state.lownoiseLoraChain) return // No change

        _uiState.value = state.copy(lownoiseLoraChain = newChain)
        savePreferences()
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        saveLastPreviewImage(bitmap)
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
    }

    /**
     * Video length validation with special requirement for "steps of 4" (1, 5, 9, 13...).
     * This is specific to video generation workflows.
     */
    private fun validateVideoLength(value: String): String? {
        val num = value.toIntOrNull()
        return when {
            value.isEmpty() -> applicationContext?.getString(R.string.error_required)
                ?: "Required"
            num == null -> applicationContext?.getString(R.string.error_invalid_number)
                ?: "Invalid number"
            num !in 1..129 -> applicationContext?.getString(R.string.error_length_range)
                ?: "Must be 1-129"
            (num - 1) % 4 != 0 -> applicationContext?.getString(R.string.error_length_step)
                ?: "Must be 1, 5, 9, 13... (steps of 4)"
            else -> null
        }
    }

    fun prepareWorkflow(): String? {
        val state = _uiState.value
        val wm = workflowManager ?: return null

        // Validate all fields
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        val width = state.width.toIntOrNull() ?: return null
        val height = state.height.toIntOrNull() ?: return null
        val length = state.length.toIntOrNull() ?: return null
        val fps = state.fps.toIntOrNull() ?: return null

        val baseWorkflow = wm.prepareVideoWorkflow(
            workflowName = state.selectedWorkflow,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
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
        ) ?: return null

        // Inject additional LoRAs if configured (separate chains for high noise and low noise)
        var workflow = baseWorkflow
        if (state.highnoiseLoraChain.isNotEmpty()) {
            workflow = wm.injectAdditionalVideoLoras(workflow, state.highnoiseLoraChain, isHighNoise = true)
        }
        if (state.lownoiseLoraChain.isNotEmpty()) {
            workflow = wm.injectAdditionalVideoLoras(workflow, state.lownoiseLoraChain, isHighNoise = false)
        }
        return workflow
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
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel

        // Retry loading video if not loaded during initialize()
        // This handles the race condition where MediaStateHolder.loadFromDisk()
        // completes after initialize() but before startListening()
        if (_uiState.value.currentVideoUri == null) {
            loadLastGeneratedVideo()
        }

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
            }
        }
    }

    /**
     * Handle generation events from the GenerationViewModel.
     */
    private fun handleGenerationEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.PreviewImage -> {
                onPreviewBitmapChange(event.bitmap)
            }
            is GenerationEvent.VideoGenerated -> {
                fetchGeneratedVideo(event.promptId)
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(TextToVideoEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(TextToVideoEvent.ShowToastMessage(event.message))
                }
                // DON'T call completeGeneration() here - this may just be a connection error
                // The server might still complete the generation
            }
            is GenerationEvent.ClearPreviewForResume -> {
                // Don't clear - we want to keep the restored preview visible until video loads
            }
            else -> {}
        }
    }

    /**
     * Fetch the generated video from the server and update the UI state.
     * Called when VideoGenerated event is received.
     */
    private fun fetchGeneratedVideo(promptId: String) {
        val context = applicationContext ?: return
        val client = comfyUIClient ?: return

        VideoUtils.fetchVideoFromHistory(
            context = context,
            client = client,
            promptId = promptId,
            filePrefix = VideoUtils.FilePrefix.TEXT_TO_VIDEO
        ) { uri ->
            if (uri != null) {
                // Clear preview bitmap so video player takes display precedence
                _uiState.value = _uiState.value.copy(currentVideoUri = uri, previewBitmap = null)
                deleteLastPreviewImage()
                generationViewModelRef?.completeGeneration(promptId)
            }
            // If uri is null, don't complete generation - will retry on next return
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtvPreview, bitmap, applicationContext)
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtvPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    private fun deleteLastPreviewImage() {
        // Remove from in-memory cache AND delete from disk
        // This prevents stale preview from being restored on app restart
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.TtvPreview)
        }
    }
}
