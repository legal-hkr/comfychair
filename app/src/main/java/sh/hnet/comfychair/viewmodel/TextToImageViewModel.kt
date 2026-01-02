package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel
import java.io.File
import java.io.IOException

/**
 * Represents a workflow item in the unified workflow dropdown
 */
data class WorkflowItem(
    val id: String,             // Workflow ID for editor (e.g., "tti_checkpoint_sdxl")
    override val name: String,           // User-friendly workflow name (e.g., "SDXL")
    override val displayName: String,    // Display name with type prefix (e.g., "[Checkpoint] SDXL")
    val type: WorkflowType      // Workflow type for mode detection
) : WorkflowItemBase

/**
 * UI state for the Text-to-Image screen
 */
data class TextToImageUiState(
    // Unified workflow selection (mode is derived from workflow type)
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<WorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    // Used to determine which fields should be visible in the UI
    val workflowPlaceholders: Set<String> = emptySet(),

    // Derived mode (computed from placeholders: ckpt_name = checkpoint, unet_name = UNET)
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
    val checkpointRandomSeed: Boolean = true,
    val checkpointSeed: String = "0",
    val checkpointDenoise: String = "1.0",
    val checkpointBatchSize: String = "1",
    val checkpointUpscaleMethod: String = "nearest-exact",
    val checkpointScaleBy: String = "1.5",
    val checkpointStopAtClipLayer: String = "-1",

    // UNET mode configuration
    val selectedUnet: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",   // For single CLIP
    val selectedClip1: String = "",  // For multi-CLIP slot 1
    val selectedClip2: String = "",  // For multi-CLIP slot 2
    val selectedClip3: String = "",  // For multi-CLIP slot 3
    val selectedClip4: String = "",  // For multi-CLIP slot 4
    val unetWidth: String = "832",
    val unetHeight: String = "1216",
    val unetSteps: String = "9",
    val unetCfg: String = "1.0",
    val unetSampler: String = "euler",
    val unetScheduler: String = "simple",
    val unetRandomSeed: Boolean = true,
    val unetSeed: String = "0",
    val unetDenoise: String = "1.0",
    val unetBatchSize: String = "1",
    val unetUpscaleMethod: String = "nearest-exact",
    val unetScaleBy: String = "1.5",
    val unetStopAtClipLayer: String = "-1",

    // Current workflow capabilities (for conditional UI)
    val currentWorkflowHasNegativePrompt: Boolean = true,
    val currentWorkflowHasCfg: Boolean = true,

    // Field presence flags (for conditional UI - only show fields that are mapped in the workflow)
    val currentWorkflowHasWidth: Boolean = true,
    val currentWorkflowHasHeight: Boolean = true,
    val currentWorkflowHasSteps: Boolean = true,
    val currentWorkflowHasSamplerName: Boolean = true,
    val currentWorkflowHasScheduler: Boolean = true,
    val currentWorkflowHasVaeName: Boolean = true,
    val currentWorkflowHasClipName: Boolean = true,      // For single CLIP
    val currentWorkflowHasClipName1: Boolean = false,    // For multi-CLIP slot 1
    val currentWorkflowHasClipName2: Boolean = false,    // For multi-CLIP slot 2
    val currentWorkflowHasClipName3: Boolean = false,    // For multi-CLIP slot 3
    val currentWorkflowHasClipName4: Boolean = false,    // For multi-CLIP slot 4
    val currentWorkflowHasLoraName: Boolean = true,
    val currentWorkflowHasSeed: Boolean = false,
    val currentWorkflowHasDenoise: Boolean = false,
    val currentWorkflowHasBatchSize: Boolean = false,
    val currentWorkflowHasUpscaleMethod: Boolean = false,
    val currentWorkflowHasScaleBy: Boolean = false,
    val currentWorkflowHasStopAtClipLayer: Boolean = false,

    // Model presence flags (for conditional model dropdowns)
    val currentWorkflowHasCheckpointName: Boolean = false,
    val currentWorkflowHasUnetName: Boolean = false,

    // LoRA chains (optional, separate for each mode)
    val checkpointLoraChain: List<LoraSelection> = emptyList(),
    val unetLoraChain: List<LoraSelection> = emptyList(),

    // Deferred model selections (for restoring after models load)
    val deferredCheckpoint: String? = null,
    val deferredUnet: String? = null,
    val deferredVae: String? = null,
    val deferredClip: String? = null,
    val deferredClip1: String? = null,
    val deferredClip2: String? = null,
    val deferredClip3: String? = null,
    val deferredClip4: String? = null,

    // Available models (loaded from server)
    val availableCheckpoints: List<String> = emptyList(),
    val availableUnets: List<String> = emptyList(),
    val availableVaes: List<String> = emptyList(),
    val availableClips: List<String> = emptyList(),
    val availableLoras: List<String> = emptyList(),
    val availableUpscaleMethods: List<String> = emptyList(),

    // Workflow-specific filtered options (from actual node type)
    val filteredCheckpoints: List<String>? = null,
    val filteredUnets: List<String>? = null,
    val filteredVaes: List<String>? = null,
    val filteredClips: List<String>? = null,      // For single CLIP
    val filteredClips1: List<String>? = null,     // For multi-CLIP slot 1
    val filteredClips2: List<String>? = null,     // For multi-CLIP slot 2
    val filteredClips3: List<String>? = null,     // For multi-CLIP slot 3
    val filteredClips4: List<String>? = null,     // For multi-CLIP slot 4

    // Generated image (preview)
    val previewBitmap: Bitmap? = null,

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
    val cfgError: String? = null,
    val seedError: String? = null,
    val denoiseError: String? = null,
    val batchSizeError: String? = null,
    val scaleByError: String? = null,
    val stopAtClipLayerError: String? = null
)

/**
 * One-time events for Text-to-Image screen
 */
sealed class TextToImageEvent {
    data class ShowToast(val messageResId: Int) : TextToImageEvent()
    data class ShowToastMessage(val message: String) : TextToImageEvent()
}

/**
 * ViewModel for the Text-to-Image screen.
 * Manages configuration state, model selection, and image generation.
 */
class TextToImageViewModel : BaseGenerationViewModel<TextToImageUiState, TextToImageEvent>() {

    override val initialState = TextToImageUiState()

    // Constants
    companion object {
        private const val TAG = "TextToImage"
        const val OWNER_ID = "TEXT_TO_IMAGE"
        private const val PREFS_NAME = "TextToImageFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val PREF_POSITIVE_PROMPT = "positivePrompt"
        private const val PREF_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
    }

    init {
        // Observe model cache from ConnectionManager
        viewModelScope.launch {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state ->
                    // Apply deferred selections first, then validate or fall back to first available
                    val checkpoint = state.deferredCheckpoint?.takeIf { it in cache.checkpoints }
                        ?: validateModelSelection(state.selectedCheckpoint, cache.checkpoints)
                    val unet = state.deferredUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedUnet, cache.unets)
                    val vae = state.deferredVae?.takeIf { it in cache.vaes }
                        ?: validateModelSelection(state.selectedVae, cache.vaes)
                    val clip = state.deferredClip?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip, cache.clips)
                    val clip1 = state.deferredClip1?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip1, cache.clips)
                    val clip2 = state.deferredClip2?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip2, cache.clips)
                    val clip3 = state.deferredClip3?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip3, cache.clips)
                    val clip4 = state.deferredClip4?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedClip4, cache.clips)

                    state.copy(
                        availableCheckpoints = cache.checkpoints,
                        availableUnets = cache.unets,
                        availableVaes = cache.vaes,
                        availableClips = cache.clips,
                        availableLoras = cache.loras,
                        availableUpscaleMethods = cache.upscaleMethods,
                        isLoadingModels = cache.isLoading,
                        modelsLoaded = cache.isLoaded,
                        // Apply validated model selections
                        selectedCheckpoint = checkpoint,
                        selectedUnet = unet,
                        selectedVae = vae,
                        selectedClip = clip,
                        selectedClip1 = clip1,
                        selectedClip2 = clip2,
                        selectedClip3 = clip3,
                        selectedClip4 = clip4,
                        // Clear deferred values once applied
                        deferredCheckpoint = null,
                        deferredUnet = null,
                        deferredVae = null,
                        deferredClip = null,
                        deferredClip1 = null,
                        deferredClip2 = null,
                        deferredClip3 = null,
                        deferredClip4 = null,
                        checkpointLoraChain = LoraChainManager.filterUnavailable(state.checkpointLoraChain, cache.loras),
                        unetLoraChain = LoraChainManager.filterUnavailable(state.unetLoraChain, cache.loras)
                    )
                }
            }
        }

        // Observe workflow changes to refresh list when workflows are added/updated/deleted
        viewModelScope.launch {
            WorkflowManager.workflowsVersion.collect {
                loadWorkflows()
            }
        }
    }

    /**
     * Called after base initialization is complete.
     */
    override fun onInitialize() {
        DebugLogger.i(TAG, "Initializing")

        // Load workflows from resources
        loadWorkflows()

        // Load saved configuration
        restorePreferences()

        // Restore last generated image
        restoreLastGeneratedImage()
    }

    /**
     * Load available workflows from WorkflowManager and create unified list
     */
    private fun loadWorkflows() {
        val ctx = applicationContext ?: run {
            DebugLogger.w(TAG, "loadWorkflows: Context not available")
            return
        }

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        val workflows = WorkflowManager.getWorkflowsByType(WorkflowType.TTI)
            .filter { showBuiltIn || !it.isBuiltIn }

        // Create workflow list
        val unifiedWorkflows = workflows.map { workflow ->
            WorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.TTI
            )
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val selectedWorkflowItem = if (currentSelection.isEmpty())
            sortedWorkflows.firstOrNull()
        else
            sortedWorkflows.find { it.name == currentSelection } ?: sortedWorkflows.firstOrNull()

        // Determine checkpoint mode based on workflow placeholders
        val placeholders = selectedWorkflowItem?.let { WorkflowManager.getWorkflowPlaceholders(it.id) } ?: emptySet()
        val isCheckpoint = "ckpt_name" in placeholders

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = selectedWorkflowItem?.name ?: "",
            selectedWorkflowId = selectedWorkflowItem?.id ?: "",
            isCheckpointMode = isCheckpoint
        )

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        if (selectedWorkflowItem != null) {
            loadWorkflowValues(selectedWorkflowItem)
        }
    }

    /**
     * Fetch models from the server.
     * Models are now loaded automatically via ConnectionManager on connection.
     * This method is kept for API compatibility but is effectively a no-op.
     */
    @Suppress("unused")
    fun fetchModels() {
        // Models are now loaded automatically via ConnectionManager.modelCache
        // which is observed in the init block above.
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

        // Find workflow item to determine type
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId, state.isCheckpointMode)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

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

    fun onClip3Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip3 = clip)
        saveConfiguration()
    }

    fun onClip4Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip4 = clip)
        saveConfiguration()
    }

    // Unified parameter callbacks - route to appropriate mode-specific state

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointWidth = width, widthError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetWidth = width, widthError = error)
        }
        saveConfiguration()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointHeight = height, heightError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetHeight = height, heightError = error)
        }
        saveConfiguration()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointSteps = steps, stepsError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetSteps = steps, stepsError = error)
        }
        saveConfiguration()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
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

    fun onRandomSeedToggle() {
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointRandomSeed = !_uiState.value.checkpointRandomSeed)
        } else {
            _uiState.value = _uiState.value.copy(unetRandomSeed = !_uiState.value.unetRandomSeed)
        }
        saveConfiguration()
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointSeed = seed, seedError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetSeed = seed, seedError = error)
        }
        saveConfiguration()
    }

    fun onRandomizeSeed() {
        val randomSeed = kotlin.random.Random.nextLong(0, Long.MAX_VALUE).toString()
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointSeed = randomSeed, seedError = null)
        } else {
            _uiState.value = _uiState.value.copy(unetSeed = randomSeed, seedError = null)
        }
        saveConfiguration()
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointDenoise = denoise, denoiseError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetDenoise = denoise, denoiseError = error)
        }
        saveConfiguration()
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointBatchSize = batchSize, batchSizeError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetBatchSize = batchSize, batchSizeError = error)
        }
        saveConfiguration()
    }

    fun onUpscaleMethodChange(method: String) {
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointUpscaleMethod = method)
        } else {
            _uiState.value = _uiState.value.copy(unetUpscaleMethod = method)
        }
        saveConfiguration()
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointScaleBy = scaleBy, scaleByError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetScaleBy = scaleBy, scaleByError = error)
        }
        saveConfiguration()
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        if (_uiState.value.isCheckpointMode) {
            _uiState.value = _uiState.value.copy(checkpointStopAtClipLayer = layer, stopAtClipLayerError = error)
        } else {
            _uiState.value = _uiState.value.copy(unetStopAtClipLayer = layer, stopAtClipLayerError = error)
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
    fun onPreviewBitmapChange(bitmap: Bitmap?) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        bitmap?.let { saveLastGeneratedImage(it) }
    }

    // Preview management
    /**
     * Clear the preview image when starting a new generation.
     */
    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return // Already cleared for this promptId
        }
        lastClearedForPromptId = promptId
        // Evict from cache so restoreLastGeneratedImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.TtiPreview)
        _uiState.value = _uiState.value.copy(
            previewBitmap = null,
            currentImageFilename = null,
            currentImageSubfolder = null,
            currentImageType = null
        )
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(
            previewBitmap = null,
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
            previewBitmap = bitmap,
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
            is GenerationEvent.PreviewImage -> {
                onPreviewBitmapChange(event.bitmap)
            }
            is GenerationEvent.ImageGenerated -> {
                val promptId = event.promptId
                DebugLogger.i(TAG, "ImageGenerated: ${Obfuscator.promptId(promptId)}")
                fetchGeneratedImage(promptId) { success ->
                    if (success) {
                        generationViewModelRef?.completeGeneration(promptId)
                    }
                }
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                DebugLogger.w(TAG, "ConnectionLostDuringGeneration")
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(TextToImageEvent.ShowToastMessage(message))
                }
            }
            is GenerationEvent.Error -> {
                DebugLogger.e(TAG, "Generation error")
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
     * Validate current configuration before generation.
     * Only checks for validation errors - model selections are optional.
     */
    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        if (state.positivePrompt.isBlank()) {
            return false
        }

        // Only check for validation errors in numeric fields
        return state.widthError == null &&
               state.heightError == null &&
               state.stepsError == null &&
               state.cfgError == null &&
               state.seedError == null &&
               state.denoiseError == null &&
               state.batchSizeError == null &&
               state.scaleByError == null &&
               state.stopAtClipLayerError == null
    }

    /**
     * Prepare workflow JSON for generation
     */
    fun prepareWorkflowJson(): String? {
        val state = _uiState.value

        DebugLogger.i(TAG, "Preparing workflow: ${Obfuscator.workflowName(state.selectedWorkflow)}")

        val baseWorkflow = if (state.isCheckpointMode) {
            WorkflowManager.prepareWorkflowById(
                workflowId = state.selectedWorkflowId,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.checkpointNegativePrompt,
                checkpoint = state.selectedCheckpoint,
                width = state.checkpointWidth.toIntOrNull() ?: 1024,
                height = state.checkpointHeight.toIntOrNull() ?: 1024,
                steps = state.checkpointSteps.toIntOrNull() ?: 20,
                cfg = state.checkpointCfg.toFloatOrNull() ?: 8.0f,
                samplerName = state.checkpointSampler,
                scheduler = state.checkpointScheduler,
                seed = state.checkpointSeed.toLongOrNull(),
                randomSeed = state.checkpointRandomSeed,
                denoise = state.checkpointDenoise.toFloatOrNull(),
                batchSize = state.checkpointBatchSize.toIntOrNull(),
                upscaleMethod = state.checkpointUpscaleMethod.takeIf { it.isNotEmpty() },
                scaleBy = state.checkpointScaleBy.toFloatOrNull(),
                stopAtClipLayer = state.checkpointStopAtClipLayer.toIntOrNull()
            )
        } else {
            WorkflowManager.prepareWorkflowById(
                workflowId = state.selectedWorkflowId,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.unetNegativePrompt,
                unet = state.selectedUnet,
                vae = state.selectedVae,
                clip = state.selectedClip.takeIf { it.isNotEmpty() },
                clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
                clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
                clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
                clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
                width = state.unetWidth.toIntOrNull() ?: 832,
                height = state.unetHeight.toIntOrNull() ?: 1216,
                steps = state.unetSteps.toIntOrNull() ?: 9,
                cfg = state.unetCfg.toFloatOrNull() ?: 1.0f,
                samplerName = state.unetSampler,
                scheduler = state.unetScheduler,
                seed = state.unetSeed.toLongOrNull(),
                randomSeed = state.unetRandomSeed,
                denoise = state.unetDenoise.toFloatOrNull(),
                batchSize = state.unetBatchSize.toIntOrNull(),
                upscaleMethod = state.unetUpscaleMethod.takeIf { it.isNotEmpty() },
                scaleBy = state.unetScaleBy.toFloatOrNull(),
                stopAtClipLayer = state.unetStopAtClipLayer.toIntOrNull()
            )
        } ?: return null

        // Inject LoRA chain if present (using the appropriate chain for the mode)
        val loraChain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        return WorkflowManager.injectLoraChain(baseWorkflow, loraChain, WorkflowType.TTI)
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
    private fun saveWorkflowValues(workflowId: String, isCheckpointMode: Boolean) {
        val storage = workflowValuesStorage ?: return
        val state = _uiState.value

        // Use workflow ID as storage key (UUID-based)
        val serverId = ConnectionManager.currentServerId ?: return

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        val values = if (isCheckpointMode) {
            WorkflowValues(
                width = state.checkpointWidth.toIntOrNull(),
                height = state.checkpointHeight.toIntOrNull(),
                steps = state.checkpointSteps.toIntOrNull(),
                cfg = state.checkpointCfg.toFloatOrNull(),
                samplerName = state.checkpointSampler,
                scheduler = state.checkpointScheduler,
                negativePrompt = state.checkpointNegativePrompt.takeIf { it.isNotEmpty() },
                model = state.selectedCheckpoint.takeIf { it.isNotEmpty() },
                loraChain = LoraSelection.toJsonString(state.checkpointLoraChain).takeIf { state.checkpointLoraChain.isNotEmpty() },
                seed = state.checkpointSeed.toLongOrNull(),
                randomSeed = state.checkpointRandomSeed,
                denoise = state.checkpointDenoise.toFloatOrNull(),
                batchSize = state.checkpointBatchSize.toIntOrNull(),
                upscaleMethod = state.checkpointUpscaleMethod.takeIf { it.isNotEmpty() },
                scaleBy = state.checkpointScaleBy.toFloatOrNull(),
                stopAtClipLayer = state.checkpointStopAtClipLayer.toIntOrNull(),
                nodeAttributeEdits = existingValues?.nodeAttributeEdits
            )
        } else {
            WorkflowValues(
                width = state.unetWidth.toIntOrNull(),
                height = state.unetHeight.toIntOrNull(),
                steps = state.unetSteps.toIntOrNull(),
                cfg = state.unetCfg.toFloatOrNull(),
                samplerName = state.unetSampler,
                scheduler = state.unetScheduler,
                negativePrompt = state.unetNegativePrompt.takeIf { it.isNotEmpty() },
                model = state.selectedUnet.takeIf { it.isNotEmpty() },
                vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
                clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
                clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
                clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
                loraChain = LoraSelection.toJsonString(state.unetLoraChain).takeIf { state.unetLoraChain.isNotEmpty() },
                seed = state.unetSeed.toLongOrNull(),
                randomSeed = state.unetRandomSeed,
                denoise = state.unetDenoise.toFloatOrNull(),
                batchSize = state.unetBatchSize.toIntOrNull(),
                upscaleMethod = state.unetUpscaleMethod.takeIf { it.isNotEmpty() },
                scaleBy = state.unetScaleBy.toFloatOrNull(),
                stopAtClipLayer = state.unetStopAtClipLayer.toIntOrNull(),
                nodeAttributeEdits = existingValues?.nodeAttributeEdits
            )
        }

        storage.saveValues(serverId, workflowId, values)
    }

    private fun saveConfiguration() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences with serverId prefix
        prefs.edit().apply {
            putString("${serverId}_$PREF_POSITIVE_PROMPT", state.positivePrompt)
            putString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            apply()
        }

        // Save per-workflow values for the currently selected workflow (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId, state.isCheckpointMode)
        }
    }

    private fun restorePreferences() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val defaultPositivePrompt = SeasonalPrompts.getTextToImagePrompt()

        // Load global preferences with serverId prefix
        val positivePrompt = prefs.getString("${serverId}_$PREF_POSITIVE_PROMPT", null) ?: defaultPositivePrompt
        val savedWorkflowId = prefs.getString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", null)

        // Update positive prompt first
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)

        // Determine which workflow to select (by ID)
        val state = _uiState.value
        val workflowToLoad = when {
            // Use saved workflow if it exists in available workflows (by ID)
            savedWorkflowId != null && state.availableWorkflows.any { it.id == savedWorkflowId } ->
                state.availableWorkflows.find { it.id == savedWorkflowId }
            // Otherwise use the current selection (set by loadWorkflows)
            state.selectedWorkflow.isNotEmpty() ->
                state.availableWorkflows.find { it.name == state.selectedWorkflow }
            // Fallback to first available
            state.availableWorkflows.isNotEmpty() -> state.availableWorkflows.first()
            else -> null
        }

        // Load workflow and its values (this sets mode, capability flags, and values)
        if (workflowToLoad != null) {
            // Load workflow values without saving (to avoid circular save)
            loadWorkflowValues(workflowToLoad)
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization)
     */
    private fun loadWorkflowValues(workflow: WorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values by workflow ID, defaults by workflow name
        val savedValues = storage.loadValues(serverId, workflow.id)
        val defaults = WorkflowManager.getWorkflowDefaults(workflow.name)

        // Get workflow placeholders - these determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflow.id)

        // Determine checkpoint vs UNET mode based on placeholders
        val isCheckpoint = "ckpt_name" in placeholders
        val state = _uiState.value

        // Get current model cache to validate saved selections
        val cache = ConnectionManager.modelCache.value

        // Get saved model (unified field)
        val savedModel = savedValues?.model
        val savedVae = savedValues?.vaeModel
        val savedClip = savedValues?.clipModel
        val savedClip1 = savedValues?.clip1Model
        val savedClip2 = savedValues?.clip2Model
        val savedClip3 = savedValues?.clip3Model
        val savedClip4 = savedValues?.clip4Model

        if (isCheckpoint) {
            _uiState.value = state.copy(
                selectedWorkflow = workflow.name,
                selectedWorkflowId = workflow.id,
                workflowPlaceholders = placeholders,
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
                // Apply model selections immediately if models are loaded, otherwise use validated empty
                selectedCheckpoint = savedModel?.takeIf { it in cache.checkpoints }
                    ?: validateModelSelection("", cache.checkpoints),
                // Set deferred values - these will be applied when model cache updates
                deferredCheckpoint = savedModel,
                checkpointLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
                // New generation parameters
                checkpointRandomSeed = savedValues?.randomSeed ?: true,
                checkpointSeed = savedValues?.seed?.toString()
                    ?: defaults?.seed?.toString() ?: "0",
                checkpointDenoise = savedValues?.denoise?.toString()
                    ?: defaults?.denoise?.toString() ?: "1.0",
                checkpointBatchSize = savedValues?.batchSize?.toString()
                    ?: defaults?.batchSize?.toString() ?: "1",
                checkpointUpscaleMethod = savedValues?.upscaleMethod
                    ?: defaults?.upscaleMethod ?: "nearest-exact",
                checkpointScaleBy = savedValues?.scaleBy?.toString()
                    ?: defaults?.scaleBy?.toString() ?: "1.5",
                checkpointStopAtClipLayer = savedValues?.stopAtClipLayer?.toString()
                    ?: defaults?.stopAtClipLayer?.toString() ?: "-1",
                // Set workflow capability flags from placeholders
                currentWorkflowHasNegativePrompt = "negative_prompt" in placeholders,
                currentWorkflowHasCfg = "cfg" in placeholders,
                currentWorkflowHasWidth = "width" in placeholders,
                currentWorkflowHasHeight = "height" in placeholders,
                currentWorkflowHasSteps = "steps" in placeholders,
                currentWorkflowHasSamplerName = "sampler_name" in placeholders,
                currentWorkflowHasScheduler = "scheduler" in placeholders,
                currentWorkflowHasVaeName = "vae_name" in placeholders,
                currentWorkflowHasClipName = "clip_name" in placeholders,
                currentWorkflowHasClipName1 = "clip_name1" in placeholders,
                currentWorkflowHasClipName2 = "clip_name2" in placeholders,
                currentWorkflowHasClipName3 = "clip_name3" in placeholders,
                currentWorkflowHasClipName4 = "clip_name4" in placeholders,
                currentWorkflowHasLoraName = "lora_name" in placeholders,
                currentWorkflowHasSeed = "seed" in placeholders,
                currentWorkflowHasDenoise = "denoise" in placeholders,
                currentWorkflowHasBatchSize = "batch_size" in placeholders,
                currentWorkflowHasUpscaleMethod = "upscale_method" in placeholders,
                currentWorkflowHasScaleBy = "scale_by" in placeholders,
                currentWorkflowHasStopAtClipLayer = "stop_at_clip_layer" in placeholders,
                // Model presence flags
                currentWorkflowHasCheckpointName = "ckpt_name" in placeholders,
                currentWorkflowHasUnetName = "unet_name" in placeholders,
                // Workflow-specific filtered options
                filteredCheckpoints = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "ckpt_name"),
                filteredUnets = null,
                filteredVaes = null,
                filteredClips = null,
                filteredClips1 = null,
                filteredClips2 = null,
                filteredClips3 = null,
                filteredClips4 = null
            )
        } else {
            _uiState.value = state.copy(
                selectedWorkflow = workflow.name,
                selectedWorkflowId = workflow.id,
                workflowPlaceholders = placeholders,
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
                // Apply model selections immediately if models are loaded, otherwise use validated empty
                selectedUnet = savedModel?.takeIf { it in cache.unets }
                    ?: validateModelSelection("", cache.unets),
                selectedVae = savedVae?.takeIf { it in cache.vaes }
                    ?: validateModelSelection("", cache.vaes),
                selectedClip = savedClip?.takeIf { it in cache.clips }
                    ?: validateModelSelection("", cache.clips),
                selectedClip1 = savedClip1?.takeIf { it in cache.clips }
                    ?: validateModelSelection("", cache.clips),
                selectedClip2 = savedClip2?.takeIf { it in cache.clips }
                    ?: validateModelSelection("", cache.clips),
                selectedClip3 = savedClip3?.takeIf { it in cache.clips }
                    ?: validateModelSelection("", cache.clips),
                selectedClip4 = savedClip4?.takeIf { it in cache.clips }
                    ?: validateModelSelection("", cache.clips),
                // Set deferred values - these will be applied when model cache updates
                deferredUnet = savedModel,
                deferredVae = savedVae,
                deferredClip = savedClip,
                deferredClip1 = savedClip1,
                deferredClip2 = savedClip2,
                deferredClip3 = savedClip3,
                deferredClip4 = savedClip4,
                unetLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
                // New generation parameters
                unetRandomSeed = savedValues?.randomSeed ?: true,
                unetSeed = savedValues?.seed?.toString()
                    ?: defaults?.seed?.toString() ?: "0",
                unetDenoise = savedValues?.denoise?.toString()
                    ?: defaults?.denoise?.toString() ?: "1.0",
                unetBatchSize = savedValues?.batchSize?.toString()
                    ?: defaults?.batchSize?.toString() ?: "1",
                unetUpscaleMethod = savedValues?.upscaleMethod
                    ?: defaults?.upscaleMethod ?: "nearest-exact",
                unetScaleBy = savedValues?.scaleBy?.toString()
                    ?: defaults?.scaleBy?.toString() ?: "1.5",
                unetStopAtClipLayer = savedValues?.stopAtClipLayer?.toString()
                    ?: defaults?.stopAtClipLayer?.toString() ?: "-1",
                // Set workflow capability flags from placeholders
                currentWorkflowHasNegativePrompt = "negative_prompt" in placeholders,
                currentWorkflowHasCfg = "cfg" in placeholders,
                currentWorkflowHasWidth = "width" in placeholders,
                currentWorkflowHasHeight = "height" in placeholders,
                currentWorkflowHasSteps = "steps" in placeholders,
                currentWorkflowHasSamplerName = "sampler_name" in placeholders,
                currentWorkflowHasScheduler = "scheduler" in placeholders,
                currentWorkflowHasVaeName = "vae_name" in placeholders,
                currentWorkflowHasClipName = "clip_name" in placeholders,
                currentWorkflowHasClipName1 = "clip_name1" in placeholders,
                currentWorkflowHasClipName2 = "clip_name2" in placeholders,
                currentWorkflowHasClipName3 = "clip_name3" in placeholders,
                currentWorkflowHasClipName4 = "clip_name4" in placeholders,
                currentWorkflowHasLoraName = "lora_name" in placeholders,
                currentWorkflowHasSeed = "seed" in placeholders,
                currentWorkflowHasDenoise = "denoise" in placeholders,
                currentWorkflowHasBatchSize = "batch_size" in placeholders,
                currentWorkflowHasUpscaleMethod = "upscale_method" in placeholders,
                currentWorkflowHasScaleBy = "scale_by" in placeholders,
                currentWorkflowHasStopAtClipLayer = "stop_at_clip_layer" in placeholders,
                // Model presence flags
                currentWorkflowHasCheckpointName = "ckpt_name" in placeholders,
                currentWorkflowHasUnetName = "unet_name" in placeholders,
                // Workflow-specific filtered options (each CLIP field queried independently)
                filteredCheckpoints = null,
                filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "unet_name"),
                filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "vae_name"),
                filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name"),
                filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name1"),
                filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name2"),
                filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name3"),
                filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name4")
            )
        }
    }

    private fun saveLastGeneratedImage(bitmap: Bitmap) {
        // Store in memory (or disk if disk-first mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtiPreview, bitmap, applicationContext)
    }

    private fun restoreLastGeneratedImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtiPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    // Image operations

    fun saveToGallery(onResult: (success: Boolean) -> Unit) {
        val ctx = applicationContext ?: run { onResult(false); return }
        val bitmap = _uiState.value.previewBitmap ?: run { onResult(false); return }

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
                DebugLogger.i(TAG, "saveToGallery: Image saved successfully")
                onResult(true)
            } catch (e: IOException) {
                DebugLogger.e(TAG, "saveToGallery: Failed to save image - ${e.message}")
                onResult(false)
            }
        } ?: run {
            DebugLogger.e(TAG, "saveToGallery: Failed to create media URI")
            onResult(false)
        }
    }

    fun saveToUri(uri: Uri, onResult: (success: Boolean) -> Unit) {
        val ctx = applicationContext ?: run { onResult(false); return }
        val bitmap = _uiState.value.previewBitmap ?: run { onResult(false); return }

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
        val ctx = applicationContext ?: return null
        val bitmap = _uiState.value.previewBitmap ?: return null

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
