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
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowCapabilities
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
 *
 * Architecture: Unified Field Visibility
 * - ALL workflows use the SAME set of fields
 * - Field VISIBILITY is controlled by WorkflowCapabilities (derived from placeholders)
 * - No separate checkpoint/UNET field sets - a QuadrupleCLIPLoader checkpoint workflow
 *   can use clip1-4 just like a UNET workflow
 */
data class TextToImageUiState(
    // Workflow selection
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<WorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    // Used to determine which fields should be visible in the UI
    val workflowPlaceholders: Set<String> = emptySet(),

    // Workflow capabilities (unified flags derived from placeholders)
    // Controls field visibility - ANY workflow can use ANY field if placeholder exists
    val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Prompts
    val positivePrompt: String = "",
    val negativePrompt: String = "",

    // Model selections - visibility driven by capabilities (hasCheckpointName, hasUnetName, etc.)
    val selectedCheckpoint: String = "",
    val selectedUnet: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",   // For single CLIP (clip_name placeholder)
    val selectedClip1: String = "",  // For multi-CLIP slot 1 (clip_name1 placeholder)
    val selectedClip2: String = "",  // For multi-CLIP slot 2 (clip_name2 placeholder)
    val selectedClip3: String = "",  // For multi-CLIP slot 3 (clip_name3 placeholder)
    val selectedClip4: String = "",  // For multi-CLIP slot 4 (clip_name4 placeholder)

    // Unified generation parameters - single set, visibility controlled by capabilities
    val width: String = "1024",
    val height: String = "1024",
    val steps: String = "20",
    val cfg: String = "7.0",
    val sampler: String = "euler",
    val scheduler: String = "normal",
    val randomSeed: Boolean = true,
    val seed: String = "0",
    val denoise: String = "1.0",
    val batchSize: String = "1",
    val upscaleMethod: String = "nearest-exact",
    val scaleBy: String = "1.5",
    val stopAtClipLayer: String = "-1",

    // Unified LoRA chain - visibility controlled by capabilities.hasLora
    val loraChain: List<LoraSelection> = emptyList(),

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
                        loraChain = LoraChainManager.filterUnavailable(state.loraChain, cache.loras)
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

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = selectedWorkflowItem?.name ?: "",
            selectedWorkflowId = selectedWorkflowItem?.id ?: ""
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

    fun onNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
        saveConfiguration()
    }

    /**
     * Unified workflow change - loads values for the new workflow
     */
    fun onWorkflowChange(workflowName: String) {
        val state = _uiState.value

        // Find workflow item
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
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

    // Unified parameter callbacks

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, applicationContext)
        _uiState.value = _uiState.value.copy(width = width, widthError = error)
        saveConfiguration()
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        _uiState.value = _uiState.value.copy(height = height, heightError = error)
        saveConfiguration()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        _uiState.value = _uiState.value.copy(steps = steps, stepsError = error)
        saveConfiguration()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(cfg = cfg, cfgError = error)
        saveConfiguration()
    }

    fun onSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(sampler = sampler)
        saveConfiguration()
    }

    fun onSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(scheduler = scheduler)
        saveConfiguration()
    }

    fun onRandomSeedToggle() {
        _uiState.value = _uiState.value.copy(randomSeed = !_uiState.value.randomSeed)
        saveConfiguration()
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        _uiState.value = _uiState.value.copy(seed = seed, seedError = error)
        saveConfiguration()
    }

    fun onRandomizeSeed() {
        val randomSeed = kotlin.random.Random.nextLong(0, Long.MAX_VALUE).toString()
        _uiState.value = _uiState.value.copy(seed = randomSeed, seedError = null)
        saveConfiguration()
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        _uiState.value = _uiState.value.copy(denoise = denoise, denoiseError = error)
        saveConfiguration()
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        _uiState.value = _uiState.value.copy(batchSize = batchSize, batchSizeError = error)
        saveConfiguration()
    }

    fun onUpscaleMethodChange(method: String) {
        _uiState.value = _uiState.value.copy(upscaleMethod = method)
        saveConfiguration()
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        _uiState.value = _uiState.value.copy(scaleBy = scaleBy, scaleByError = error)
        saveConfiguration()
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        _uiState.value = _uiState.value.copy(stopAtClipLayer = layer, stopAtClipLayerError = error)
        saveConfiguration()
    }

    // Unified LoRA chain management

    fun onAddLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.loraChain, state.availableLoras)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        saveConfiguration()
    }

    fun onRemoveLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.loraChain, index)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        saveConfiguration()
    }

    fun onLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.loraChain, index, name)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
        saveConfiguration()
    }

    fun onLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.loraChain, index, strength)
        if (newChain === state.loraChain) return // No change

        _uiState.value = state.copy(loraChain = newChain)
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
     * Prepare workflow JSON for generation.
     *
     * Uses unified fields - WorkflowManager.prepareWorkflowById() only substitutes
     * placeholders that exist in the workflow JSON, so we can pass ALL selections
     * and let the placeholder replacement handle which ones are actually used.
     */
    fun prepareWorkflowJson(): String? {
        val state = _uiState.value

        DebugLogger.i(TAG, "Preparing workflow: ${Obfuscator.workflowName(state.selectedWorkflow)}")

        // Pass ALL model selections and parameters - only those with matching
        // placeholders in the workflow will be substituted
        val baseWorkflow = WorkflowManager.prepareWorkflowById(
            workflowId = state.selectedWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
            // Model selections - workflow placeholders determine which are used
            checkpoint = state.selectedCheckpoint,
            unet = state.selectedUnet,
            vae = state.selectedVae,
            clip = state.selectedClip.takeIf { it.isNotEmpty() },
            clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
            // Unified generation parameters
            width = state.width.toIntOrNull() ?: 1024,
            height = state.height.toIntOrNull() ?: 1024,
            steps = state.steps.toIntOrNull() ?: 20,
            cfg = state.cfg.toFloatOrNull() ?: 7.0f,
            samplerName = state.sampler,
            scheduler = state.scheduler,
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            denoise = state.denoise.toFloatOrNull(),
            batchSize = state.batchSize.toIntOrNull(),
            upscaleMethod = state.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = state.scaleBy.toFloatOrNull(),
            stopAtClipLayer = state.stopAtClipLayer.toIntOrNull()
        ) ?: return null

        // Inject LoRA chain if present
        return WorkflowManager.injectLoraChain(baseWorkflow, state.loraChain, WorkflowType.TTI)
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

                            client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                                viewModelScope.launch {
                                    if (bitmap != null) {
                                        setCurrentImage(bitmap, filename, subfolder, type)
                                        onComplete(true)
                                    } else if (failureType == ConnectionFailure.STALLED) {
                                        applicationContext?.let { ctx ->
                                            ConnectionManager.showConnectionAlert(ctx, failureType)
                                        }
                                        onComplete(false)
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
     * Save current workflow values to per-workflow storage.
     *
     * Saves ALL field values unconditionally - WorkflowValues is the unified storage format.
     * Uses checkpoint OR unet model field based on which is set (capabilities determine which
     * is visible in UI, so only one should be populated at a time).
     */
    private fun saveWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val state = _uiState.value

        // Use workflow ID as storage key (UUID-based)
        val serverId = ConnectionManager.currentServerId ?: return

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        // Save unified field values - model is whichever one is set (checkpoint or unet)
        val values = WorkflowValues(
            width = state.width.toIntOrNull(),
            height = state.height.toIntOrNull(),
            steps = state.steps.toIntOrNull(),
            cfg = state.cfg.toFloatOrNull(),
            samplerName = state.sampler,
            scheduler = state.scheduler,
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            // Save checkpoint OR unet - whichever is set (capabilities control which is visible)
            model = state.selectedCheckpoint.takeIf { it.isNotEmpty() }
                ?: state.selectedUnet.takeIf { it.isNotEmpty() },
            // Save ALL model selections unconditionally
            vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
            clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedClip4.takeIf { it.isNotEmpty() },
            // Unified LoRA chain
            loraChain = LoraSelection.toJsonString(state.loraChain).takeIf { state.loraChain.isNotEmpty() },
            // Unified generation parameters
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            denoise = state.denoise.toFloatOrNull(),
            batchSize = state.batchSize.toIntOrNull(),
            upscaleMethod = state.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = state.scaleBy.toFloatOrNull(),
            stopAtClipLayer = state.stopAtClipLayer.toIntOrNull(),
            nodeAttributeEdits = existingValues?.nodeAttributeEdits
        )

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
            saveWorkflowValues(state.selectedWorkflowId)
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
     * Load workflow values without triggering save (used during initialization).
     *
     * Uses unified fields - capabilities (derived from placeholders) control which
     * fields are visible in the UI. The saved model value is loaded into the appropriate
     * selection field (checkpoint OR unet) based on capabilities.
     */
    private fun loadWorkflowValues(workflow: WorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values by workflow ID, defaults by workflow name
        val savedValues = storage.loadValues(serverId, workflow.id)
        val defaults = WorkflowManager.getWorkflowDefaults(workflow.name)

        // Get workflow placeholders - these determine field visibility via capabilities
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflow.id)
        val capabilities = WorkflowCapabilities.fromPlaceholders(placeholders)

        val state = _uiState.value

        // Get current model cache to validate saved selections
        val cache = ConnectionManager.modelCache.value

        // Get saved model selections
        val savedModel = savedValues?.model
        val savedVae = savedValues?.vaeModel
        val savedClip = savedValues?.clipModel
        val savedClip1 = savedValues?.clip1Model
        val savedClip2 = savedValues?.clip2Model
        val savedClip3 = savedValues?.clip3Model
        val savedClip4 = savedValues?.clip4Model

        // Load model into appropriate field based on capabilities
        // If workflow has ckpt_name placeholder, savedModel is a checkpoint
        // If workflow has unet_name placeholder, savedModel is a UNET
        val selectedCheckpoint = if (capabilities.hasCheckpointName) {
            savedModel?.takeIf { it in cache.checkpoints }
                ?: validateModelSelection("", cache.checkpoints)
        } else ""

        val selectedUnet = if (capabilities.hasUnetName) {
            savedModel?.takeIf { it in cache.unets }
                ?: validateModelSelection("", cache.unets)
        } else ""

        _uiState.value = state.copy(
            // Workflow info
            selectedWorkflow = workflow.name,
            selectedWorkflowId = workflow.id,
            workflowPlaceholders = placeholders,
            capabilities = capabilities,

            // Unified generation parameters
            width = savedValues?.width?.toString()
                ?: defaults?.width?.toString() ?: "1024",
            height = savedValues?.height?.toString()
                ?: defaults?.height?.toString() ?: "1024",
            steps = savedValues?.steps?.toString()
                ?: defaults?.steps?.toString() ?: "20",
            cfg = savedValues?.cfg?.toString()
                ?: defaults?.cfg?.toString() ?: "7.0",
            sampler = savedValues?.samplerName
                ?: defaults?.samplerName ?: "euler",
            scheduler = savedValues?.scheduler
                ?: defaults?.scheduler ?: "normal",
            negativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",

            // Model selections - load into appropriate fields based on capabilities
            selectedCheckpoint = selectedCheckpoint,
            selectedUnet = selectedUnet,
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

            // Deferred values - applied when model cache updates
            deferredCheckpoint = if (capabilities.hasCheckpointName) savedModel else null,
            deferredUnet = if (capabilities.hasUnetName) savedModel else null,
            deferredVae = savedVae,
            deferredClip = savedClip,
            deferredClip1 = savedClip1,
            deferredClip2 = savedClip2,
            deferredClip3 = savedClip3,
            deferredClip4 = savedClip4,

            // Unified LoRA chain
            loraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),

            // Unified generation parameters
            randomSeed = savedValues?.randomSeed ?: true,
            seed = savedValues?.seed?.toString()
                ?: defaults?.seed?.toString() ?: "0",
            denoise = savedValues?.denoise?.toString()
                ?: defaults?.denoise?.toString() ?: "1.0",
            batchSize = savedValues?.batchSize?.toString()
                ?: defaults?.batchSize?.toString() ?: "1",
            upscaleMethod = savedValues?.upscaleMethod
                ?: defaults?.upscaleMethod ?: "nearest-exact",
            scaleBy = savedValues?.scaleBy?.toString()
                ?: defaults?.scaleBy?.toString() ?: "1.5",
            stopAtClipLayer = savedValues?.stopAtClipLayer?.toString()
                ?: defaults?.stopAtClipLayer?.toString() ?: "-1",

            // Workflow-specific filtered options (query for ALL possible fields)
            filteredCheckpoints = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "ckpt_name"),
            filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "unet_name"),
            filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "vae_name"),
            filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name"),
            filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name1"),
            filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name2"),
            filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name3"),
            filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name4")
        )
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
