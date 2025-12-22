package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import java.io.File
import java.io.IOException

/**
 * Represents a workflow item in the unified workflow dropdown
 */
data class WorkflowItem(
    val name: String,           // Internal workflow name (e.g., "tti_checkpoint_sd.json")
    val displayName: String,    // Display name with type prefix (e.g., "[Checkpoint] SD 1.5")
    val type: WorkflowType      // Workflow type for mode detection
)

/**
 * UI state for the Text-to-Image screen
 */
data class TextToImageUiState(
    // Unified workflow selection (mode is derived from workflow type)
    val selectedWorkflow: String = "",
    val availableWorkflows: List<WorkflowItem> = emptyList(),

    // Derived mode (computed from selectedWorkflow's type, not user-selected)
    val isCheckpointMode: Boolean = true,

    // Positive prompt (global)
    val positivePrompt: String = "",

    // Negative prompts (per-workflow type)
    val checkpointNegativePrompt: String = "",
    val unetNegativePrompt: String = "",

    // Checkpoint mode configuration
    val selectedCheckpoint: String = "",
    val checkpointWidth: String = "1024",
    val checkpointHeight: String = "1024",
    val checkpointSteps: String = "20",
    val checkpointCfg: String = "8.0",
    val checkpointSampler: String = "euler",
    val checkpointScheduler: String = "normal",

    // UNET mode configuration
    val selectedUnet: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",
    val selectedClip1: String = "",  // For Flux dual CLIP
    val selectedClip2: String = "",  // For Flux dual CLIP
    val unetWidth: String = "832",
    val unetHeight: String = "1216",
    val unetSteps: String = "9",
    val unetCfg: String = "1.0",
    val unetSampler: String = "euler",
    val unetScheduler: String = "simple",

    // Current workflow capabilities (for conditional UI)
    val currentWorkflowHasNegativePrompt: Boolean = true,
    val currentWorkflowHasCfg: Boolean = true,
    val currentWorkflowHasDualClip: Boolean = false,

    // LoRA chains (optional, separate for each mode)
    val checkpointLoraChain: List<LoraSelection> = emptyList(),
    val unetLoraChain: List<LoraSelection> = emptyList(),

    // Available models (loaded from server)
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

    // State
    private val _uiState = MutableStateFlow(TextToImageUiState())
    val uiState: StateFlow<TextToImageUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TextToImageEvent>()
    val events: SharedFlow<TextToImageEvent> = _events.asSharedFlow()

    private var workflowManager: WorkflowManager? = null
    private var comfyUIClient: ComfyUIClient? = null
    private var context: Context? = null
    private var workflowValuesStorage: WorkflowValuesStorage? = null

    // Reference to GenerationViewModel for event handling
    private var generationViewModelRef: GenerationViewModel? = null

    // Constants
    companion object {
        private const val TAG = "TextToImage"
        const val OWNER_ID = "TEXT_TO_IMAGE"
        private const val PREFS_NAME = "TextToImageFragmentPrefs"

        // Global preferences
        private const val PREF_POSITIVE_PROMPT = "positive_prompt"
        private const val PREF_SELECTED_WORKFLOW = "selectedWorkflow"
    }

    // Initialization
    /**
     * Initialize the ViewModel with dependencies
     */
    fun initialize(context: Context, client: ComfyUIClient) {
        if (this.workflowManager != null) return // Already initialized

        DebugLogger.i(TAG, "Initializing")

        this.context = context.applicationContext
        this.comfyUIClient = client
        this.workflowManager = WorkflowManager(context)
        this.workflowValuesStorage = WorkflowValuesStorage(context)

        // Load workflows from resources
        loadWorkflows()

        // Load saved configuration
        loadConfiguration()

        // Restore last generated image
        restoreLastGeneratedImage()

    }

    /**
     * Load available workflows from WorkflowManager and create unified list
     */
    private fun loadWorkflows() {
        val manager = workflowManager ?: run {
            return
        }
        val ctx = context ?: run {
            return
        }

        val checkpointWorkflows = manager.getCheckpointWorkflowNames()
        val unetWorkflows = manager.getUNETWorkflowNames()


        // Create unified workflow list with type prefix for display
        val checkpointPrefix = ctx.getString(R.string.mode_checkpoint)
        val unetPrefix = ctx.getString(R.string.mode_unet)

        val unifiedWorkflows = mutableListOf<WorkflowItem>()

        // Add checkpoint workflows
        checkpointWorkflows.forEach { name ->
            unifiedWorkflows.add(WorkflowItem(
                name = name,
                displayName = "[$checkpointPrefix] $name",
                type = WorkflowType.TTI_CHECKPOINT
            ))
        }

        // Add UNET workflows
        unetWorkflows.forEach { name ->
            unifiedWorkflows.add(WorkflowItem(
                name = name,
                displayName = "[$unetPrefix] $name",
                type = WorkflowType.TTI_UNET
            ))
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val defaultWorkflow = sortedWorkflows.firstOrNull()?.name ?: ""
        val selectedWorkflow = if (_uiState.value.selectedWorkflow.isEmpty()) defaultWorkflow else _uiState.value.selectedWorkflow
        val isCheckpoint = sortedWorkflows.find { it.name == selectedWorkflow }?.type == WorkflowType.TTI_CHECKPOINT

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = selectedWorkflow,
            isCheckpointMode = isCheckpoint
        )
    }

    /**
     * Fetch models from the server
     */
    fun fetchModels() {
        val client = comfyUIClient ?: run {
            return
        }

        if (_uiState.value.modelsLoaded) {
            return
        }

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
            // Use atomic update to avoid race conditions with concurrent callbacks
            _uiState.update { state ->
                state.copy(
                    availableCheckpoints = checkpoints,
                    selectedCheckpoint = validateModelSelection(
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
            _uiState.update { state ->
                state.copy(
                    availableUnets = unets,
                    selectedUnet = validateModelSelection(
                        state.selectedUnet,
                        unets
                    )
                )
            }
        }
    }

    private fun fetchVaes(client: ComfyUIClient) {
        client.fetchVAEs { vaes ->
            _uiState.update { state ->
                state.copy(
                    availableVaes = vaes,
                    selectedVae = validateModelSelection(
                        state.selectedVae,
                        vaes
                    )
                )
            }
        }
    }

    private fun fetchClips(client: ComfyUIClient) {
        client.fetchCLIPs { clips ->
            _uiState.update { state ->
                state.copy(
                    availableClips = clips,
                    selectedClip = validateModelSelection(
                        state.selectedClip,
                        clips
                    ),
                    selectedClip1 = validateModelSelection(
                        state.selectedClip1,
                        clips
                    ),
                    selectedClip2 = validateModelSelection(
                        state.selectedClip2,
                        clips
                    )
                )
            }
        }
    }

    private fun fetchLoras(client: ComfyUIClient) {
        client.fetchLoRAs { loras ->
            _uiState.update { state ->
                state.copy(
                    availableLoras = loras,
                    checkpointLoraChain = LoraChainManager.filterUnavailable(state.checkpointLoraChain, loras),
                    unetLoraChain = LoraChainManager.filterUnavailable(state.unetLoraChain, loras)
                )
            }
        }
    }

    /**
     * Validate model selection against available models
     * Returns current if valid, otherwise defaults to first available
     */
    private fun validateModelSelection(current: String, available: List<String>): String {
        return ValidationUtils.validateModelSelection(current, available)
    }

    // State management

    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        saveConfiguration()
    }

    /**
     * Unified negative prompt change - routes to appropriate mode-specific state
     */
    fun onNegativePromptChange(negativePrompt: String) {
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointNegativePrompt = negativePrompt)
        } else {
            _uiState.value = _uiState.value.copy(unetNegativePrompt = negativePrompt)
        }
        saveConfiguration()
    }

    /**
     * Unified workflow change - determines mode from workflow type and loads values
     */
    fun onWorkflowChange(workflowName: String) {
        val state = _uiState.value
        val manager = workflowManager ?: return
        val storage = workflowValuesStorage ?: return

        // Find workflow item to determine type
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return
        val isCheckpoint = workflowItem.type == WorkflowType.TTI_CHECKPOINT

        // Save current workflow values before switching
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow, state.isCheckpointMode)
        }

        // Load new workflow's saved values or defaults
        val savedValues = storage.loadValues(workflowName)
        val defaults = manager.getWorkflowDefaults(workflowName)

        if (isCheckpoint) {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = true,
                checkpointWidth = savedValues?.width?.toString()
                    ?: defaults?.width?.toString() ?: "1024",
                checkpointHeight = savedValues?.height?.toString()
                    ?: defaults?.height?.toString() ?: "1024",
                checkpointSteps = savedValues?.steps?.toString()
                    ?: defaults?.steps?.toString() ?: "20",
                checkpointCfg = savedValues?.cfg?.toString()
                    ?: defaults?.cfg?.toString() ?: "8.0",
                checkpointSampler = savedValues?.samplerName
                    ?: defaults?.samplerName ?: "euler",
                checkpointScheduler = savedValues?.scheduler
                    ?: defaults?.scheduler ?: "normal",
                checkpointNegativePrompt = savedValues?.negativePrompt
                    ?: defaults?.negativePrompt ?: "",
                selectedCheckpoint = savedValues?.checkpointModel ?: "",
                checkpointLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
                // Checkpoint workflows always have these capabilities
                currentWorkflowHasNegativePrompt = true,
                currentWorkflowHasCfg = true,
                currentWorkflowHasDualClip = false
            )
        } else {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = false,
                unetWidth = savedValues?.width?.toString()
                    ?: defaults?.width?.toString() ?: "832",
                unetHeight = savedValues?.height?.toString()
                    ?: defaults?.height?.toString() ?: "1216",
                unetSteps = savedValues?.steps?.toString()
                    ?: defaults?.steps?.toString() ?: "9",
                unetCfg = savedValues?.cfg?.toString()
                    ?: defaults?.cfg?.toString() ?: "1.0",
                unetSampler = savedValues?.samplerName
                    ?: defaults?.samplerName ?: "euler",
                unetScheduler = savedValues?.scheduler
                    ?: defaults?.scheduler ?: "simple",
                unetNegativePrompt = savedValues?.negativePrompt
                    ?: defaults?.negativePrompt ?: "",
                selectedUnet = savedValues?.unetModel ?: "",
                selectedVae = savedValues?.vaeModel ?: "",
                selectedClip = savedValues?.clipModel ?: "",
                selectedClip1 = savedValues?.clip1Model ?: "",
                selectedClip2 = savedValues?.clip2Model ?: "",
                unetLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
                // Set workflow capability flags from defaults
                currentWorkflowHasNegativePrompt = defaults?.hasNegativePrompt ?: true,
                currentWorkflowHasCfg = defaults?.hasCfg ?: true,
                currentWorkflowHasDualClip = defaults?.hasDualClip ?: false
            )
        }
        saveConfiguration()
    }

    // Unified model selection callbacks

    fun onCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoint)
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

    fun onClip1Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip1 = clip)
        saveConfiguration()
    }

    fun onClip2Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip2 = clip)
        saveConfiguration()
    }

    // Unified parameter callbacks - route to appropriate mode-specific state

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, context)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointWidth = width, widthError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetWidth = width, widthError = error)
        }
        saveConfiguration()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, context)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointHeight = height, heightError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetHeight = height, heightError = error)
        }
        saveConfiguration()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, context)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointSteps = steps, stepsError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetSteps = steps, stepsError = error)
        }
        saveConfiguration()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, context)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointCfg = cfg, cfgError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetCfg = cfg, cfgError = error)
        }
        saveConfiguration()
    }

    fun onSamplerChange(sampler: String) {
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointSampler = sampler)
        } else {
            _uiState.value = _uiState.value.copy(unetSampler = sampler)
        }
        saveConfiguration()
    }

    fun onSchedulerChange(scheduler: String) {
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointScheduler = scheduler)
        } else {
            _uiState.value = _uiState.value.copy(unetScheduler = scheduler)
        }
        saveConfiguration()
    }

    // Unified LoRA chain management - routes to appropriate mode-specific chain

    fun onAddLora() {
        val state = _uiState.value
        val chain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        val newChain = LoraChainManager.addLora(chain, state.availableLoras)
        if (newChain === chain) return // No change

        if (state.isCheckpointMode) {
            _uiState.value = state.copy(checkpointLoraChain = newChain)
        } else {
            _uiState.value = state.copy(unetLoraChain = newChain)
        }
        saveConfiguration()
    }

    fun onRemoveLora(index: Int) {
        val state = _uiState.value
        val chain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        val newChain = LoraChainManager.removeLora(chain, index)
        if (newChain === chain) return // No change

        if (state.isCheckpointMode) {
            _uiState.value = state.copy(checkpointLoraChain = newChain)
        } else {
            _uiState.value = state.copy(unetLoraChain = newChain)
        }
        saveConfiguration()
    }

    fun onLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val chain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        val newChain = LoraChainManager.updateLoraName(chain, index, name)
        if (newChain === chain) return // No change

        if (state.isCheckpointMode) {
            _uiState.value = state.copy(checkpointLoraChain = newChain)
        } else {
            _uiState.value = state.copy(unetLoraChain = newChain)
        }
        saveConfiguration()
    }

    fun onLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val chain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        val newChain = LoraChainManager.updateLoraStrength(chain, index, strength)
        if (newChain === chain) return // No change

        if (state.isCheckpointMode) {
            _uiState.value = state.copy(checkpointLoraChain = newChain)
        } else {
            _uiState.value = state.copy(unetLoraChain = newChain)
        }
        saveConfiguration()
    }

    /**
     * Update the current bitmap (e.g., from preview or final image)
     */
    fun onCurrentBitmapChange(bitmap: Bitmap?) {
        _uiState.value = _uiState.value.copy(currentBitmap = bitmap)
        bitmap?.let { saveLastGeneratedImage(it) }
    }

    // Preview management
    /**
     * Clear the preview image when starting a new generation.
     */
    fun clearPreview() {
        _uiState.value = _uiState.value.copy(
            currentBitmap = null,
            currentImageFilename = null,
            currentImageSubfolder = null,
            currentImageType = null
        )
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

    // Event handling

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
        if (!generationViewModel.generationState.value.isGenerating) {
            if (generationViewModelRef == generationViewModel) {
                generationViewModelRef = null
            }
        }
    }

    private fun handleGenerationEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.PreviewImage -> onCurrentBitmapChange(event.bitmap)
            is GenerationEvent.ImageGenerated -> {
                fetchGeneratedImage(event.promptId) { success ->
                    if (success) {
                        generationViewModelRef?.completeGeneration()
                    }
                }
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                viewModelScope.launch {
                    val message = context?.getString(R.string.connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(TextToImageEvent.ShowToastMessage(message))
                }
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(TextToImageEvent.ShowToastMessage(event.message))
                }
            }
            is GenerationEvent.ClearPreviewForResume -> {
                // Don't clear - keep the preview visible during navigation
                // New live previews will naturally replace the current one
            }
            else -> {}
        }
    }

    // Validation

    /**
     * Validate current configuration before generation
     */
    fun validateConfiguration(): Boolean {
        val state = _uiState.value

        if (state.positivePrompt.isBlank()) return false

        return if (state.isCheckpointMode) {
            state.selectedCheckpoint.isNotEmpty() &&
            ValidationUtils.validateDimension(state.checkpointWidth) == null &&
            ValidationUtils.validateDimension(state.checkpointHeight) == null &&
            ValidationUtils.validateSteps(state.checkpointSteps) == null &&
            ValidationUtils.validateCfg(state.checkpointCfg) == null
        } else {
            // CLIP validation: dual CLIP or single CLIP based on workflow
            val clipValid = if (state.currentWorkflowHasDualClip) {
                state.selectedClip1.isNotEmpty() && state.selectedClip2.isNotEmpty()
            } else {
                state.selectedClip.isNotEmpty()
            }
            // CFG validation: skip if workflow doesn't have CFG
            val cfgValid = !state.currentWorkflowHasCfg || ValidationUtils.validateCfg(state.unetCfg) == null

            state.selectedUnet.isNotEmpty() &&
            state.selectedVae.isNotEmpty() &&
            clipValid &&
            ValidationUtils.validateDimension(state.unetWidth) == null &&
            ValidationUtils.validateDimension(state.unetHeight) == null &&
            ValidationUtils.validateSteps(state.unetSteps) == null &&
            cfgValid
        }
    }

    /**
     * Prepare workflow JSON for generation
     */
    fun prepareWorkflowJson(): String? {
        val manager = workflowManager ?: return null
        val state = _uiState.value

        DebugLogger.i(TAG, "Preparing workflow: ${state.selectedWorkflow}")
        DebugLogger.d(TAG, "Prompt: ${Obfuscator.prompt(state.positivePrompt)}")
        if (state.isCheckpointMode) {
            DebugLogger.d(TAG, "Dimensions: ${state.checkpointWidth}x${state.checkpointHeight}, Steps: ${state.checkpointSteps}")
        } else {
            DebugLogger.d(TAG, "Dimensions: ${state.unetWidth}x${state.unetHeight}, Steps: ${state.unetSteps}")
        }

        val baseWorkflow = if (state.isCheckpointMode) {
            manager.prepareWorkflow(
                workflowName = state.selectedWorkflow,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.checkpointNegativePrompt,
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
                workflowName = state.selectedWorkflow,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.unetNegativePrompt,
                unet = state.selectedUnet,
                vae = state.selectedVae,
                clip = if (state.currentWorkflowHasDualClip) null else state.selectedClip,
                clip1 = if (state.currentWorkflowHasDualClip) state.selectedClip1 else null,
                clip2 = if (state.currentWorkflowHasDualClip) state.selectedClip2 else null,
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
     * @param promptId The prompt ID to fetch
     * @param onComplete Callback with success boolean (true if image was fetched and set)
     */
    fun fetchGeneratedImage(promptId: String, onComplete: (success: Boolean) -> Unit) {
        val client = comfyUIClient ?: run {
            onComplete(false)
            return
        }

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
                                        onComplete(true)
                                    } else {
                                        onComplete(false)
                                    }
                                }
                            }
                            return@fetchHistory
                        }
                    }
                    onComplete(false)
                } catch (_: Exception) {
                    onComplete(false)
                }
            } else {
                onComplete(false)
            }
        }
    }

    // Persistence

    /**
     * Save current workflow values to per-workflow storage
     */
    private fun saveWorkflowValues(workflowName: String, isCheckpointMode: Boolean) {
        val storage = workflowValuesStorage ?: return
        val state = _uiState.value

        // Use workflow name directly as storage key for consistency
        // Workflow names are unique and always available
        val values = if (isCheckpointMode) {
            WorkflowValues(
                workflowId = workflowName,
                width = state.checkpointWidth.toIntOrNull(),
                height = state.checkpointHeight.toIntOrNull(),
                steps = state.checkpointSteps.toIntOrNull(),
                cfg = state.checkpointCfg.toFloatOrNull(),
                samplerName = state.checkpointSampler,
                scheduler = state.checkpointScheduler,
                negativePrompt = state.checkpointNegativePrompt.takeIf { it.isNotEmpty() },
                checkpointModel = state.selectedCheckpoint.takeIf { it.isNotEmpty() },
                loraChain = LoraSelection.toJsonString(state.checkpointLoraChain).takeIf { state.checkpointLoraChain.isNotEmpty() }
            )
        } else {
            WorkflowValues(
                workflowId = workflowName,
                width = state.unetWidth.toIntOrNull(),
                height = state.unetHeight.toIntOrNull(),
                steps = state.unetSteps.toIntOrNull(),
                cfg = state.unetCfg.toFloatOrNull(),
                samplerName = state.unetSampler,
                scheduler = state.unetScheduler,
                negativePrompt = state.unetNegativePrompt.takeIf { it.isNotEmpty() },
                unetModel = state.selectedUnet.takeIf { it.isNotEmpty() },
                vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
                clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
                clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
                clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
                loraChain = LoraSelection.toJsonString(state.unetLoraChain).takeIf { state.unetLoraChain.isNotEmpty() }
            )
        }

        storage.saveValues(workflowName, values)
    }

    private fun saveConfiguration() {
        val ctx = context ?: return
        val state = _uiState.value
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences (prompt and selected workflow)
        prefs.edit().apply {
            putString(PREF_POSITIVE_PROMPT, state.positivePrompt)
            putString(PREF_SELECTED_WORKFLOW, state.selectedWorkflow)
            apply()
        }

        // Save per-workflow values for the currently selected workflow
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow, state.isCheckpointMode)
        }
    }

    private fun loadConfiguration() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val defaultPositivePrompt = SeasonalPrompts.getTextToImagePrompt()

        // Load global preferences
        val positivePrompt = prefs.getString(PREF_POSITIVE_PROMPT, null) ?: defaultPositivePrompt
        val savedWorkflow = prefs.getString(PREF_SELECTED_WORKFLOW, null)

        // Update positive prompt first
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)

        // Determine which workflow to select
        val state = _uiState.value
        val workflowToLoad = when {
            // Use saved workflow if it exists in available workflows
            savedWorkflow != null && state.availableWorkflows.any { it.name == savedWorkflow } -> savedWorkflow
            // Otherwise use the current selection (set by loadWorkflows)
            state.selectedWorkflow.isNotEmpty() -> state.selectedWorkflow
            // Fallback to first available
            state.availableWorkflows.isNotEmpty() -> state.availableWorkflows.first().name
            else -> ""
        }

        // Load workflow and its values (this sets mode, capability flags, and values)
        if (workflowToLoad.isNotEmpty()) {
            // Load workflow values without saving (to avoid circular save)
            loadWorkflowValues(workflowToLoad)
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization)
     */
    private fun loadWorkflowValues(workflowName: String) {
        val manager = workflowManager ?: return
        val storage = workflowValuesStorage ?: return

        // Find workflow item to determine type
        val state = _uiState.value
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return
        val isCheckpoint = workflowItem.type == WorkflowType.TTI_CHECKPOINT

        // Load saved values or defaults
        val savedValues = storage.loadValues(workflowName)
        val defaults = manager.getWorkflowDefaults(workflowName)

        if (isCheckpoint) {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = true,
                checkpointWidth = savedValues?.width?.toString()
                    ?: defaults?.width?.toString() ?: "1024",
                checkpointHeight = savedValues?.height?.toString()
                    ?: defaults?.height?.toString() ?: "1024",
                checkpointSteps = savedValues?.steps?.toString()
                    ?: defaults?.steps?.toString() ?: "20",
                checkpointCfg = savedValues?.cfg?.toString()
                    ?: defaults?.cfg?.toString() ?: "8.0",
                checkpointSampler = savedValues?.samplerName
                    ?: defaults?.samplerName ?: "euler",
                checkpointScheduler = savedValues?.scheduler
                    ?: defaults?.scheduler ?: "normal",
                checkpointNegativePrompt = savedValues?.negativePrompt
                    ?: defaults?.negativePrompt ?: "",
                selectedCheckpoint = savedValues?.checkpointModel ?: "",
                checkpointLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
                // Checkpoint workflows always have these capabilities
                currentWorkflowHasNegativePrompt = true,
                currentWorkflowHasCfg = true,
                currentWorkflowHasDualClip = false
            )
        } else {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = false,
                unetWidth = savedValues?.width?.toString()
                    ?: defaults?.width?.toString() ?: "832",
                unetHeight = savedValues?.height?.toString()
                    ?: defaults?.height?.toString() ?: "1216",
                unetSteps = savedValues?.steps?.toString()
                    ?: defaults?.steps?.toString() ?: "9",
                unetCfg = savedValues?.cfg?.toString()
                    ?: defaults?.cfg?.toString() ?: "1.0",
                unetSampler = savedValues?.samplerName
                    ?: defaults?.samplerName ?: "euler",
                unetScheduler = savedValues?.scheduler
                    ?: defaults?.scheduler ?: "simple",
                unetNegativePrompt = savedValues?.negativePrompt
                    ?: defaults?.negativePrompt ?: "",
                selectedUnet = savedValues?.unetModel ?: "",
                selectedVae = savedValues?.vaeModel ?: "",
                selectedClip = savedValues?.clipModel ?: "",
                selectedClip1 = savedValues?.clip1Model ?: "",
                selectedClip2 = savedValues?.clip2Model ?: "",
                unetLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
                // Set workflow capability flags from defaults
                currentWorkflowHasNegativePrompt = defaults?.hasNegativePrompt ?: true,
                currentWorkflowHasCfg = defaults?.hasCfg ?: true,
                currentWorkflowHasDualClip = defaults?.hasDualClip ?: false
            )
        }
    }

    private fun saveLastGeneratedImage(bitmap: Bitmap) {
        // Store in memory (or disk if disk-first mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtiPreview, bitmap, context)
    }

    private fun restoreLastGeneratedImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtiPreview, context)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(currentBitmap = bitmap)
        }
    }

    // Image operations

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
