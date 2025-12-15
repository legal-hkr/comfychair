package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.LoraSelection
import java.io.File
import java.io.IOException

/**
 * UI state for the Text-to-Image screen
 */
data class TextToImageUiState(
    // Mode selection
    val isCheckpointMode: Boolean = true,

    // Positive prompt
    val positivePrompt: String = "",

    // Checkpoint mode configuration
    val checkpointWorkflow: String = "",
    val selectedCheckpoint: String = "",
    val checkpointWidth: String = "1024",
    val checkpointHeight: String = "1024",
    val checkpointSteps: String = "20",
    val checkpointCfg: String = "8.0",
    val checkpointSampler: String = "euler",
    val checkpointScheduler: String = "normal",

    // UNET mode configuration
    val unetWorkflow: String = "",
    val selectedUnet: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",
    val unetWidth: String = "832",
    val unetHeight: String = "1216",
    val unetSteps: String = "9",
    val unetCfg: String = "1.0",
    val unetSampler: String = "euler",
    val unetScheduler: String = "simple",

    // LoRA chains (optional, separate for each mode)
    val checkpointLoraChain: List<LoraSelection> = emptyList(),
    val unetLoraChain: List<LoraSelection> = emptyList(),

    // Available models (loaded from server)
    val availableCheckpointWorkflows: List<String> = emptyList(),
    val availableUnetWorkflows: List<String> = emptyList(),
    val availableCheckpoints: List<String> = emptyList(),
    val availableUnets: List<String> = emptyList(),
    val availableVaes: List<String> = emptyList(),
    val availableClips: List<String> = emptyList(),
    val availableLoras: List<String> = emptyList(),

    // Generated image
    val currentBitmap: Bitmap? = null,

    // Current image file info (for metadata extraction)
    val currentImageFilename: String? = null,
    val currentImageSubfolder: String? = null,
    val currentImageType: String? = null,

    // Loading states
    val isLoadingModels: Boolean = false,
    val modelsLoaded: Boolean = false,

    // Validation errors
    val widthError: String? = null,
    val heightError: String? = null,
    val stepsError: String? = null,
    val cfgError: String? = null
)

/**
 * One-time events for Text-to-Image screen
 */
sealed class TextToImageEvent {
    data class ShowToastMessage(val message: String) : TextToImageEvent()
}

/**
 * ViewModel for the Text-to-Image screen.
 * Manages configuration state, model selection, and image generation.
 */
class TextToImageViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TextToImageUiState())
    val uiState: StateFlow<TextToImageUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TextToImageEvent>()
    val events: SharedFlow<TextToImageEvent> = _events.asSharedFlow()

    private var workflowManager: WorkflowManager? = null
    private var comfyUIClient: ComfyUIClient? = null
    private var context: Context? = null

    // Reference to GenerationViewModel for event handling
    private var generationViewModelRef: GenerationViewModel? = null

    companion object {
        const val OWNER_ID = "TEXT_TO_IMAGE"
        private const val PREFS_NAME = "TextToImageFragmentPrefs"
        private const val PREF_IS_CHECKPOINT_MODE = "isCheckpointMode"
        private const val PREF_POSITIVE_PROMPT = "positive_prompt"

        // Checkpoint mode preferences
        private const val PREF_CHECKPOINT_WORKFLOW = "checkpointWorkflow"
        private const val PREF_CHECKPOINT_MODEL = "checkpointModel"
        private const val PREF_CHECKPOINT_WIDTH = "checkpointWidth"
        private const val PREF_CHECKPOINT_HEIGHT = "checkpointHeight"
        private const val PREF_CHECKPOINT_STEPS = "checkpointSteps"

        // UNET mode preferences
        private const val PREF_UNET_WORKFLOW = "unetWorkflow"
        private const val PREF_UNET_MODEL = "unetModel"
        private const val PREF_UNET_VAE = "unetVAE"
        private const val PREF_UNET_CLIP = "unetCLIP"
        private const val PREF_UNET_WIDTH = "unetWidth"
        private const val PREF_UNET_HEIGHT = "unetHeight"
        private const val PREF_UNET_STEPS = "unetSteps"

        // CFG, sampler, scheduler preferences
        private const val PREF_CHECKPOINT_CFG = "checkpointCfg"
        private const val PREF_CHECKPOINT_SAMPLER = "checkpointSampler"
        private const val PREF_CHECKPOINT_SCHEDULER = "checkpointScheduler"
        private const val PREF_UNET_CFG = "unetCfg"
        private const val PREF_UNET_SAMPLER = "unetSampler"
        private const val PREF_UNET_SCHEDULER = "unetScheduler"

        // LoRA chain preferences
        private const val PREF_CHECKPOINT_LORA_CHAIN = "checkpointLoraChain"
        private const val PREF_UNET_LORA_CHAIN = "unetLoraChain"

        private const val LAST_IMAGE_FILENAME = "last_generated_image.png"
    }

    /**
     * Initialize the ViewModel with dependencies
     */
    fun initialize(context: Context, client: ComfyUIClient) {
        if (this.workflowManager != null) return // Already initialized

        this.context = context.applicationContext
        this.comfyUIClient = client
        this.workflowManager = WorkflowManager(context)

        // Load workflows from resources
        loadWorkflows()

        // Load saved configuration
        loadConfiguration()

        // Restore last generated image
        restoreLastGeneratedImage()
    }

    /**
     * Load available workflows from WorkflowManager
     */
    private fun loadWorkflows() {
        val manager = workflowManager ?: return

        val checkpointWorkflows = manager.getCheckpointWorkflowNames()
        val unetWorkflows = manager.getUNETWorkflowNames()

        _uiState.value = _uiState.value.copy(
            availableCheckpointWorkflows = checkpointWorkflows,
            availableUnetWorkflows = unetWorkflows,
            checkpointWorkflow = if (_uiState.value.checkpointWorkflow.isEmpty() && checkpointWorkflows.isNotEmpty())
                checkpointWorkflows[0] else _uiState.value.checkpointWorkflow,
            unetWorkflow = if (_uiState.value.unetWorkflow.isEmpty() && unetWorkflows.isNotEmpty())
                unetWorkflows[0] else _uiState.value.unetWorkflow
        )
    }

    /**
     * Fetch models from the server
     */
    fun fetchModels() {
        val client = comfyUIClient ?: return

        if (_uiState.value.modelsLoaded) return

        _uiState.value = _uiState.value.copy(isLoadingModels = true)

        // Fetch all model types
        fetchCheckpoints(client)
        fetchUnets(client)
        fetchVaes(client)
        fetchClips(client)
        fetchLoras(client)
    }

    private fun fetchCheckpoints(client: ComfyUIClient) {
        client.fetchCheckpoints { checkpoints ->
            viewModelScope.launch {
                val state = _uiState.value
                _uiState.value = state.copy(
                    availableCheckpoints = checkpoints,
                    selectedCheckpoint = restoreModelSelection(
                        PREF_CHECKPOINT_MODEL,
                        state.selectedCheckpoint,
                        checkpoints
                    ),
                    isLoadingModels = false,
                    modelsLoaded = true
                )
            }
        }
    }

    private fun fetchUnets(client: ComfyUIClient) {
        client.fetchUNETs { unets ->
            viewModelScope.launch {
                val state = _uiState.value
                _uiState.value = state.copy(
                    availableUnets = unets,
                    selectedUnet = restoreModelSelection(
                        PREF_UNET_MODEL,
                        state.selectedUnet,
                        unets
                    )
                )
            }
        }
    }

    private fun fetchVaes(client: ComfyUIClient) {
        client.fetchVAEs { vaes ->
            viewModelScope.launch {
                val state = _uiState.value
                _uiState.value = state.copy(
                    availableVaes = vaes,
                    selectedVae = restoreModelSelection(
                        PREF_UNET_VAE,
                        state.selectedVae,
                        vaes
                    )
                )
            }
        }
    }

    private fun fetchClips(client: ComfyUIClient) {
        client.fetchCLIPs { clips ->
            viewModelScope.launch {
                val state = _uiState.value
                _uiState.value = state.copy(
                    availableClips = clips,
                    selectedClip = restoreModelSelection(
                        PREF_UNET_CLIP,
                        state.selectedClip,
                        clips
                    )
                )
            }
        }
    }

    private fun fetchLoras(client: ComfyUIClient) {
        client.fetchLoRAs { loras ->
            viewModelScope.launch {
                val state = _uiState.value
                // Filter out any LoRAs in the chains that are no longer available
                val filteredCheckpointChain = state.checkpointLoraChain.filter { it.name in loras }
                val filteredUnetChain = state.unetLoraChain.filter { it.name in loras }
                _uiState.value = state.copy(
                    availableLoras = loras,
                    checkpointLoraChain = filteredCheckpointChain,
                    unetLoraChain = filteredUnetChain
                )
            }
        }
    }

    /**
     * Restore model selection from preferences if available in list
     */
    private fun restoreModelSelection(prefKey: String, current: String, available: List<String>): String {
        val ctx = context ?: return current
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(prefKey, null)

        return when {
            saved != null && available.contains(saved) -> saved
            current.isNotEmpty() && available.contains(current) -> current
            available.isNotEmpty() -> available[0]
            else -> ""
        }
    }

    // Update functions for UI state

    fun onModeChange(isCheckpointMode: Boolean) {
        _uiState.value = _uiState.value.copy(isCheckpointMode = isCheckpointMode)
        saveConfiguration()
    }

    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        saveConfiguration()
    }

    fun onCheckpointWorkflowChange(workflow: String) {
        _uiState.value = _uiState.value.copy(checkpointWorkflow = workflow)
        saveConfiguration()
    }

    fun onCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoint)
        saveConfiguration()
    }

    fun onCheckpointWidthChange(width: String) {
        val error = validateDimension(width)
        _uiState.value = _uiState.value.copy(
            checkpointWidth = width,
            widthError = if (_uiState.value.isCheckpointMode) error else _uiState.value.widthError
        )
        saveConfiguration()
    }

    fun onCheckpointHeightChange(height: String) {
        val error = validateDimension(height)
        _uiState.value = _uiState.value.copy(
            checkpointHeight = height,
            heightError = if (_uiState.value.isCheckpointMode) error else _uiState.value.heightError
        )
        saveConfiguration()
    }

    fun onCheckpointStepsChange(steps: String) {
        val error = validateSteps(steps)
        _uiState.value = _uiState.value.copy(
            checkpointSteps = steps,
            stepsError = if (_uiState.value.isCheckpointMode) error else _uiState.value.stepsError
        )
        saveConfiguration()
    }

    fun onCheckpointCfgChange(cfg: String) {
        val error = validateCfg(cfg)
        _uiState.value = _uiState.value.copy(
            checkpointCfg = cfg,
            cfgError = if (_uiState.value.isCheckpointMode) error else _uiState.value.cfgError
        )
        saveConfiguration()
    }

    fun onCheckpointSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(checkpointSampler = sampler)
        saveConfiguration()
    }

    fun onCheckpointSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(checkpointScheduler = scheduler)
        saveConfiguration()
    }

    fun onUnetWorkflowChange(workflow: String) {
        _uiState.value = _uiState.value.copy(unetWorkflow = workflow)
        saveConfiguration()
    }

    fun onUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedUnet = unet)
        saveConfiguration()
    }

    fun onVaeChange(vae: String) {
        _uiState.value = _uiState.value.copy(selectedVae = vae)
        saveConfiguration()
    }

    fun onClipChange(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip = clip)
        saveConfiguration()
    }

    fun onUnetWidthChange(width: String) {
        val error = validateDimension(width)
        _uiState.value = _uiState.value.copy(
            unetWidth = width,
            widthError = if (!_uiState.value.isCheckpointMode) error else _uiState.value.widthError
        )
        saveConfiguration()
    }

    fun onUnetHeightChange(height: String) {
        val error = validateDimension(height)
        _uiState.value = _uiState.value.copy(
            unetHeight = height,
            heightError = if (!_uiState.value.isCheckpointMode) error else _uiState.value.heightError
        )
        saveConfiguration()
    }

    fun onUnetStepsChange(steps: String) {
        val error = validateSteps(steps)
        _uiState.value = _uiState.value.copy(
            unetSteps = steps,
            stepsError = if (!_uiState.value.isCheckpointMode) error else _uiState.value.stepsError
        )
        saveConfiguration()
    }

    fun onUnetCfgChange(cfg: String) {
        val error = validateCfg(cfg)
        _uiState.value = _uiState.value.copy(
            unetCfg = cfg,
            cfgError = if (!_uiState.value.isCheckpointMode) error else _uiState.value.cfgError
        )
        saveConfiguration()
    }

    fun onUnetSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(unetSampler = sampler)
        saveConfiguration()
    }

    fun onUnetSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(unetScheduler = scheduler)
        saveConfiguration()
    }

    // Checkpoint LoRA chain management

    fun onAddCheckpointLora() {
        val state = _uiState.value
        if (state.checkpointLoraChain.size >= LoraSelection.MAX_CHAIN_LENGTH) return
        if (state.availableLoras.isEmpty()) return

        val newLora = LoraSelection(
            name = state.availableLoras.first(),
            strength = LoraSelection.DEFAULT_STRENGTH
        )
        _uiState.value = state.copy(checkpointLoraChain = state.checkpointLoraChain + newLora)
        saveConfiguration()
    }

    fun onRemoveCheckpointLora(index: Int) {
        val state = _uiState.value
        if (index !in state.checkpointLoraChain.indices) return

        _uiState.value = state.copy(checkpointLoraChain = state.checkpointLoraChain.toMutableList().apply { removeAt(index) })
        saveConfiguration()
    }

    fun onCheckpointLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        if (index !in state.checkpointLoraChain.indices) return

        val updatedChain = state.checkpointLoraChain.toMutableList()
        updatedChain[index] = updatedChain[index].copy(name = name)
        _uiState.value = state.copy(checkpointLoraChain = updatedChain)
        saveConfiguration()
    }

    fun onCheckpointLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        if (index !in state.checkpointLoraChain.indices) return

        val clampedStrength = strength.coerceIn(LoraSelection.MIN_STRENGTH, LoraSelection.MAX_STRENGTH)
        val updatedChain = state.checkpointLoraChain.toMutableList()
        updatedChain[index] = updatedChain[index].copy(strength = clampedStrength)
        _uiState.value = state.copy(checkpointLoraChain = updatedChain)
        saveConfiguration()
    }

    // UNET LoRA chain management

    fun onAddUnetLora() {
        val state = _uiState.value
        if (state.unetLoraChain.size >= LoraSelection.MAX_CHAIN_LENGTH) return
        if (state.availableLoras.isEmpty()) return

        val newLora = LoraSelection(
            name = state.availableLoras.first(),
            strength = LoraSelection.DEFAULT_STRENGTH
        )
        _uiState.value = state.copy(unetLoraChain = state.unetLoraChain + newLora)
        saveConfiguration()
    }

    fun onRemoveUnetLora(index: Int) {
        val state = _uiState.value
        if (index !in state.unetLoraChain.indices) return

        _uiState.value = state.copy(unetLoraChain = state.unetLoraChain.toMutableList().apply { removeAt(index) })
        saveConfiguration()
    }

    fun onUnetLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        if (index !in state.unetLoraChain.indices) return

        val updatedChain = state.unetLoraChain.toMutableList()
        updatedChain[index] = updatedChain[index].copy(name = name)
        _uiState.value = state.copy(unetLoraChain = updatedChain)
        saveConfiguration()
    }

    fun onUnetLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        if (index !in state.unetLoraChain.indices) return

        val clampedStrength = strength.coerceIn(LoraSelection.MIN_STRENGTH, LoraSelection.MAX_STRENGTH)
        val updatedChain = state.unetLoraChain.toMutableList()
        updatedChain[index] = updatedChain[index].copy(strength = clampedStrength)
        _uiState.value = state.copy(unetLoraChain = updatedChain)
        saveConfiguration()
    }

    /**
     * Update the current bitmap (e.g., from preview or final image)
     */
    fun onCurrentBitmapChange(bitmap: Bitmap?) {
        _uiState.value = _uiState.value.copy(currentBitmap = bitmap)
        bitmap?.let { saveLastGeneratedImage(it) }
    }

    /**
     * Clear the preview image when starting a new generation.
     */
    fun clearPreview() {
        _uiState.value = _uiState.value.copy(currentBitmap = null)
    }

    /**
     * Update the current bitmap with file info for metadata extraction.
     */
    private fun setCurrentImage(bitmap: Bitmap, filename: String, subfolder: String, type: String) {
        _uiState.value = _uiState.value.copy(
            currentBitmap = bitmap,
            currentImageFilename = filename,
            currentImageSubfolder = subfolder,
            currentImageType = type
        )
        saveLastGeneratedImage(bitmap)
    }

    // Event listener management

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel
        generationViewModel.registerEventHandler(OWNER_ID) { event ->
            handleGenerationEvent(event)
        }
    }

    /**
     * Stop listening for generation events.
     * Note: We keep the generationViewModelRef if generation is still running,
     * as the handler may still be called for completion events.
     */
    fun stopListening(generationViewModel: GenerationViewModel) {
        generationViewModel.unregisterEventHandler(OWNER_ID)
        // Only clear ref if no generation is active (handler was actually unregistered)
        // If generation is running, the handler is kept and needs the ref
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
                onCurrentBitmapChange(event.bitmap)
            }
            is GenerationEvent.ImageGenerated -> {
                fetchGeneratedImage(event.promptId) {
                    generationViewModelRef?.completeGeneration()
                }
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(TextToImageEvent.ShowToastMessage(event.message))
                }
                generationViewModelRef?.completeGeneration()
            }
            else -> {}
        }
    }

    // Validation

    private fun validateDimension(value: String): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in 1..4096) {
            context?.getString(R.string.error_dimension_range) ?: "Must be 1-4096"
        } else null
    }

    private fun validateSteps(value: String): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in 1..255) {
            context?.getString(R.string.error_steps_range) ?: "Must be 1-255"
        } else null
    }

    private fun validateCfg(value: String): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
        return if (floatValue == null || floatValue !in 0.0f..100.0f) {
            context?.getString(R.string.error_cfg_range) ?: "Must be 0.0-100.0"
        } else null
    }

    /**
     * Validate current configuration before generation
     */
    fun validateConfiguration(): Boolean {
        val state = _uiState.value

        if (state.positivePrompt.isBlank()) return false

        return if (state.isCheckpointMode) {
            state.selectedCheckpoint.isNotEmpty() &&
            validateDimension(state.checkpointWidth) == null &&
            validateDimension(state.checkpointHeight) == null &&
            validateSteps(state.checkpointSteps) == null &&
            validateCfg(state.checkpointCfg) == null
        } else {
            state.selectedUnet.isNotEmpty() &&
            state.selectedVae.isNotEmpty() &&
            state.selectedClip.isNotEmpty() &&
            validateDimension(state.unetWidth) == null &&
            validateDimension(state.unetHeight) == null &&
            validateSteps(state.unetSteps) == null &&
            validateCfg(state.unetCfg) == null
        }
    }

    /**
     * Prepare workflow JSON for generation
     */
    fun prepareWorkflowJson(): String? {
        val manager = workflowManager ?: return null
        val state = _uiState.value

        val baseWorkflow = if (state.isCheckpointMode) {
            manager.prepareWorkflow(
                workflowName = state.checkpointWorkflow,
                positivePrompt = state.positivePrompt,
                checkpoint = state.selectedCheckpoint,
                width = state.checkpointWidth.toIntOrNull() ?: 1024,
                height = state.checkpointHeight.toIntOrNull() ?: 1024,
                steps = state.checkpointSteps.toIntOrNull() ?: 20,
                cfg = state.checkpointCfg.toFloatOrNull() ?: 8.0f,
                samplerName = state.checkpointSampler,
                scheduler = state.checkpointScheduler
            )
        } else {
            manager.prepareWorkflow(
                workflowName = state.unetWorkflow,
                positivePrompt = state.positivePrompt,
                unet = state.selectedUnet,
                vae = state.selectedVae,
                clip = state.selectedClip,
                width = state.unetWidth.toIntOrNull() ?: 832,
                height = state.unetHeight.toIntOrNull() ?: 1216,
                steps = state.unetSteps.toIntOrNull() ?: 9,
                cfg = state.unetCfg.toFloatOrNull() ?: 1.0f,
                samplerName = state.unetSampler,
                scheduler = state.unetScheduler
            )
        } ?: return null

        // Inject LoRA chain if present (using the appropriate chain for the mode)
        val workflowType = if (state.isCheckpointMode) WorkflowType.TTI_CHECKPOINT else WorkflowType.TTI_UNET
        val loraChain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        return manager.injectLoraChain(baseWorkflow, loraChain, workflowType)
    }

    /**
     * Fetch the generated image after completion
     */
    fun fetchGeneratedImage(promptId: String, onComplete: () -> Unit) {
        val client = comfyUIClient ?: return

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                try {
                    val promptData = historyJson.optJSONObject(promptId)
                    val outputs = promptData?.optJSONObject("outputs")

                    outputs?.keys()?.forEach { nodeId ->
                        val nodeOutput = outputs.getJSONObject(nodeId)
                        val images = nodeOutput.optJSONArray("images")

                        if (images != null && images.length() > 0) {
                            val imageInfo = images.getJSONObject(0)
                            val filename = imageInfo.optString("filename")
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            client.fetchImage(filename, subfolder, type) { bitmap ->
                                viewModelScope.launch {
                                    if (bitmap != null) {
                                        setCurrentImage(bitmap, filename, subfolder, type)
                                    }
                                    onComplete()
                                }
                            }
                            return@fetchHistory
                        }
                    }
                    onComplete()
                } catch (e: Exception) {
                    onComplete()
                }
            } else {
                onComplete()
            }
        }
    }

    // Persistence

    private fun saveConfiguration() {
        val ctx = context ?: return
        val state = _uiState.value
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().apply {
            putBoolean(PREF_IS_CHECKPOINT_MODE, state.isCheckpointMode)
            putString(PREF_POSITIVE_PROMPT, state.positivePrompt)

            // Checkpoint mode
            putString(PREF_CHECKPOINT_WORKFLOW, state.checkpointWorkflow)
            putString(PREF_CHECKPOINT_MODEL, state.selectedCheckpoint)
            putString(PREF_CHECKPOINT_WIDTH, state.checkpointWidth)
            putString(PREF_CHECKPOINT_HEIGHT, state.checkpointHeight)
            putString(PREF_CHECKPOINT_STEPS, state.checkpointSteps)

            // UNET mode
            putString(PREF_UNET_WORKFLOW, state.unetWorkflow)
            putString(PREF_UNET_MODEL, state.selectedUnet)
            putString(PREF_UNET_VAE, state.selectedVae)
            putString(PREF_UNET_CLIP, state.selectedClip)
            putString(PREF_UNET_WIDTH, state.unetWidth)
            putString(PREF_UNET_HEIGHT, state.unetHeight)
            putString(PREF_UNET_STEPS, state.unetSteps)

            // CFG, sampler, scheduler
            putString(PREF_CHECKPOINT_CFG, state.checkpointCfg)
            putString(PREF_CHECKPOINT_SAMPLER, state.checkpointSampler)
            putString(PREF_CHECKPOINT_SCHEDULER, state.checkpointScheduler)
            putString(PREF_UNET_CFG, state.unetCfg)
            putString(PREF_UNET_SAMPLER, state.unetSampler)
            putString(PREF_UNET_SCHEDULER, state.unetScheduler)

            // LoRA chains
            putString(PREF_CHECKPOINT_LORA_CHAIN, LoraSelection.toJsonString(state.checkpointLoraChain))
            putString(PREF_UNET_LORA_CHAIN, LoraSelection.toJsonString(state.unetLoraChain))

            apply()
        }
    }

    private fun loadConfiguration() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val defaultPositivePrompt = ctx.getString(R.string.default_prompt_text_to_image)
        _uiState.value = _uiState.value.copy(
            isCheckpointMode = prefs.getBoolean(PREF_IS_CHECKPOINT_MODE, true),
            positivePrompt = prefs.getString(PREF_POSITIVE_PROMPT, null) ?: defaultPositivePrompt,

            // Checkpoint mode
            checkpointWorkflow = prefs.getString(PREF_CHECKPOINT_WORKFLOW, _uiState.value.checkpointWorkflow) ?: _uiState.value.checkpointWorkflow,
            checkpointWidth = prefs.getString(PREF_CHECKPOINT_WIDTH, "1024") ?: "1024",
            checkpointHeight = prefs.getString(PREF_CHECKPOINT_HEIGHT, "1024") ?: "1024",
            checkpointSteps = prefs.getString(PREF_CHECKPOINT_STEPS, "20") ?: "20",

            // UNET mode
            unetWorkflow = prefs.getString(PREF_UNET_WORKFLOW, _uiState.value.unetWorkflow) ?: _uiState.value.unetWorkflow,
            unetWidth = prefs.getString(PREF_UNET_WIDTH, "832") ?: "832",
            unetHeight = prefs.getString(PREF_UNET_HEIGHT, "1216") ?: "1216",
            unetSteps = prefs.getString(PREF_UNET_STEPS, "9") ?: "9",

            // CFG, sampler, scheduler
            checkpointCfg = prefs.getString(PREF_CHECKPOINT_CFG, "8.0") ?: "8.0",
            checkpointSampler = prefs.getString(PREF_CHECKPOINT_SAMPLER, "euler") ?: "euler",
            checkpointScheduler = prefs.getString(PREF_CHECKPOINT_SCHEDULER, "normal") ?: "normal",
            unetCfg = prefs.getString(PREF_UNET_CFG, "1.0") ?: "1.0",
            unetSampler = prefs.getString(PREF_UNET_SAMPLER, "euler") ?: "euler",
            unetScheduler = prefs.getString(PREF_UNET_SCHEDULER, "simple") ?: "simple",

            // LoRA chains
            checkpointLoraChain = LoraSelection.fromJsonString(prefs.getString(PREF_CHECKPOINT_LORA_CHAIN, null)),
            unetLoraChain = LoraSelection.fromJsonString(prefs.getString(PREF_UNET_LORA_CHAIN, null))
        )
    }

    private fun saveLastGeneratedImage(bitmap: Bitmap) {
        val ctx = context ?: return
        try {
            ctx.openFileOutput(LAST_IMAGE_FILENAME, Context.MODE_PRIVATE).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } catch (e: Exception) {
            // Failed to save image
        }
    }

    private fun restoreLastGeneratedImage() {
        val ctx = context ?: return
        try {
            val file = ctx.getFileStreamPath(LAST_IMAGE_FILENAME)
            if (file.exists()) {
                ctx.openFileInput(LAST_IMAGE_FILENAME).use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        _uiState.value = _uiState.value.copy(currentBitmap = bitmap)
                    }
                }
            }
        } catch (e: Exception) {
            // Failed to restore image
        }
    }

    // Image save/share operations

    fun saveToGallery(onResult: (success: Boolean) -> Unit) {
        val ctx = context ?: run { onResult(false); return }
        val bitmap = _uiState.value.currentBitmap ?: run { onResult(false); return }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ComfyChair")
        }

        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                ctx.contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }
                onResult(true)
            } catch (e: IOException) {
                onResult(false)
            }
        } ?: onResult(false)
    }

    fun saveToUri(uri: Uri, onResult: (success: Boolean) -> Unit) {
        val ctx = context ?: run { onResult(false); return }
        val bitmap = _uiState.value.currentBitmap ?: run { onResult(false); return }

        try {
            ctx.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            onResult(true)
        } catch (e: IOException) {
            onResult(false)
        }
    }

    fun getShareIntent(): Intent? {
        val ctx = context ?: return null
        val bitmap = _uiState.value.currentBitmap ?: return null

        val cachePath = ctx.cacheDir
        val filename = "share_image_${System.currentTimeMillis()}.png"
        val file = File(cachePath, filename)

        return try {
            file.outputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file
            )

            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }
}
