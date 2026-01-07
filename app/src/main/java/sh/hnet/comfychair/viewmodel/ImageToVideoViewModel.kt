package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

/**
 * UI state for the view mode toggle
 */
enum class ImageToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * Represents a workflow item with display name
 */
data class ItvWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name
    val type: WorkflowType      // ITV
) : WorkflowItemBase

/**
 * UI state for the Image-to-Video screen
 */
data class ImageToVideoUiState(
    // View state
    val viewMode: ImageToVideoViewMode = ImageToVideoViewMode.SOURCE,
    val sourceImage: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val currentVideoUri: android.net.Uri? = null,

    // Workflow selection
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<ItvWorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    val workflowPlaceholders: Set<String> = emptySet(),

    // Model selections
    val selectedHighnoiseUnet: String = "",
    val selectedLownoiseUnet: String = "",
    val selectedHighnoiseLora: String = "",
    val selectedLownoiseLora: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",
    val selectedClip1: String = "",
    val selectedClip2: String = "",
    val selectedClip3: String = "",
    val selectedClip4: String = "",

    // Available models
    val availableUnets: List<String> = emptyList(),
    val availableLoras: List<String> = emptyList(),
    val availableVaes: List<String> = emptyList(),
    val availableClips: List<String> = emptyList(),
    val availableUpscaleMethods: List<String> = emptyList(),

    // Workflow-specific filtered options (from actual node type in workflow)
    val filteredUnets: List<String>? = null,
    val filteredVaes: List<String>? = null,
    val filteredClips: List<String>? = null,
    val filteredClips1: List<String>? = null,
    val filteredClips2: List<String>? = null,
    val filteredClips3: List<String>? = null,
    val filteredClips4: List<String>? = null,

    // Generation parameters
    val width: String = "848",
    val height: String = "480",
    val length: String = "33",
    val fps: String = "16",
    val randomSeed: Boolean = true,
    val seed: String = "0",
    val denoise: String = "1.0",
    val batchSize: String = "1",
    val upscaleMethod: String = "nearest-exact",
    val scaleBy: String = "1.5",
    val stopAtClipLayer: String = "-1",

    // Validation errors
    val widthError: String? = null,
    val heightError: String? = null,
    val lengthError: String? = null,
    val fpsError: String? = null,
    val seedError: String? = null,
    val denoiseError: String? = null,
    val batchSizeError: String? = null,
    val scaleByError: String? = null,
    val stopAtClipLayerError: String? = null,

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
    val deferredClip1: String? = null,
    val deferredClip2: String? = null,
    val deferredClip3: String? = null,
    val deferredClip4: String? = null,

    // Additional LoRA chains (optional, 0-5 LoRAs on top of mandatory LightX2V LoRAs)
    val highnoiseLoraChain: List<LoraSelection> = emptyList(),
    val lownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Workflow capabilities (unified flags derived from placeholders)
    val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Upload state
    val isUploading: Boolean = false
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
class ImageToVideoViewModel : BaseGenerationViewModel<ImageToVideoUiState, ImageToVideoEvent>() {

    override val initialState = ImageToVideoUiState()

    // Constants
    companion object {
        private const val TAG = "ImageToVideo"
        const val OWNER_ID = "IMAGE_TO_VIDEO"
        private const val PREFS_NAME = "ImageToVideoFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val KEY_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val KEY_POSITIVE_PROMPT = "positivePrompt"
    }

    init {
        // Observe model cache from ConnectionManager
        viewModelScope.launch {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state ->
                    // Apply deferred selections first, then validate or fall back to first available
                    val highnoiseUnet = state.deferredHighnoiseUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedHighnoiseUnet, cache.unets)
                    val lownoiseUnet = state.deferredLownoiseUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedLownoiseUnet, cache.unets)
                    val highnoiseLora = state.deferredHighnoiseLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedHighnoiseLora, cache.loras)
                    val lownoiseLora = state.deferredLownoiseLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedLownoiseLora, cache.loras)
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
                        availableUnets = cache.unets,
                        availableLoras = cache.loras,
                        availableVaes = cache.vaes,
                        availableClips = cache.clips,
                        selectedHighnoiseUnet = highnoiseUnet,
                        selectedLownoiseUnet = lownoiseUnet,
                        selectedHighnoiseLora = highnoiseLora,
                        selectedLownoiseLora = lownoiseLora,
                        selectedVae = vae,
                        selectedClip = clip,
                        selectedClip1 = clip1,
                        selectedClip2 = clip2,
                        selectedClip3 = clip3,
                        selectedClip4 = clip4,
                        // Clear deferred values once applied
                        deferredHighnoiseUnet = null,
                        deferredLownoiseUnet = null,
                        deferredHighnoiseLora = null,
                        deferredLownoiseLora = null,
                        deferredVae = null,
                        deferredClip = null,
                        deferredClip1 = null,
                        deferredClip2 = null,
                        deferredClip3 = null,
                        deferredClip4 = null,
                        // Filter LoRA chains
                        highnoiseLoraChain = LoraChainManager.filterUnavailable(state.highnoiseLoraChain, cache.loras),
                        lownoiseLoraChain = LoraChainManager.filterUnavailable(state.lownoiseLoraChain, cache.loras)
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

    override fun onInitialize() {
        DebugLogger.i(TAG, "Initializing")

        loadWorkflows()
        restorePreferences()
        loadSavedSourceImage()
        restoreLastPreviewImage()
        loadLastGeneratedVideo()
        // Models are now loaded automatically via ConnectionManager
    }

    /**
     * Load the last generated video from cache.
     * This restores the video preview when the screen is recreated.
     * Uses runBlocking to ensure synchronous restoration like TTI/ITI.
     */
    private fun loadLastGeneratedVideo() {
        val context = applicationContext ?: return
        val promptId = MediaStateHolder.getCurrentItvPromptId() ?: return

        val key = MediaStateHolder.MediaKey.ItvVideo(promptId)
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
        val ctx = applicationContext ?: return

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        val workflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV)
            .filter { showBuiltIn || !it.isBuiltIn }

        val unifiedWorkflows = workflows.map { workflow ->
            ItvWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.ITV
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
     * Load workflow values without triggering save (used during initialization and workflow changes)
     */
    private fun loadWorkflowValues(workflowItem: ItvWorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values by workflow ID, defaults by workflow name
        val savedValues = storage.loadValues(serverId, workflowItem.id)
        val defaults = WorkflowManager.getWorkflowDefaults(workflowItem.name)
        val cache = ConnectionManager.modelCache.value

        // Get placeholders from workflow JSON to determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflowItem.id)

        val state = _uiState.value
        _uiState.value = state.copy(
            selectedWorkflow = workflowItem.name,
            selectedWorkflowId = workflowItem.id,
            workflowPlaceholders = placeholders,
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
            // Apply model selections with deferred mechanism
            selectedHighnoiseUnet = savedValues?.highnoiseUnetModel?.takeIf { it in cache.unets }
                ?: state.selectedHighnoiseUnet,
            selectedLownoiseUnet = savedValues?.lownoiseUnetModel?.takeIf { it in cache.unets }
                ?: state.selectedLownoiseUnet,
            selectedHighnoiseLora = savedValues?.highnoiseLoraModel?.takeIf { it in cache.loras }
                ?: state.selectedHighnoiseLora,
            selectedLownoiseLora = savedValues?.lownoiseLoraModel?.takeIf { it in cache.loras }
                ?: state.selectedLownoiseLora,
            selectedVae = savedValues?.vaeModel?.takeIf { it in cache.vaes }
                ?: state.selectedVae,
            selectedClip = savedValues?.clipModel?.takeIf { it in cache.clips }
                ?: state.selectedClip,
            selectedClip1 = savedValues?.clip1Model?.takeIf { it in cache.clips }
                ?: state.selectedClip1,
            selectedClip2 = savedValues?.clip2Model?.takeIf { it in cache.clips }
                ?: state.selectedClip2,
            selectedClip3 = savedValues?.clip3Model?.takeIf { it in cache.clips }
                ?: state.selectedClip3,
            selectedClip4 = savedValues?.clip4Model?.takeIf { it in cache.clips }
                ?: state.selectedClip4,
            // Deferred values for when cache updates
            deferredHighnoiseUnet = savedValues?.highnoiseUnetModel,
            deferredLownoiseUnet = savedValues?.lownoiseUnetModel,
            deferredHighnoiseLora = savedValues?.highnoiseLoraModel,
            deferredLownoiseLora = savedValues?.lownoiseLoraModel,
            deferredVae = savedValues?.vaeModel,
            deferredClip = savedValues?.clipModel,
            deferredClip1 = savedValues?.clip1Model,
            deferredClip2 = savedValues?.clip2Model,
            deferredClip3 = savedValues?.clip3Model,
            deferredClip4 = savedValues?.clip4Model,
            highnoiseLoraChain = savedValues?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Workflow-specific filtered options (video workflows use highnoise_unet_name for UNET)
            filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "highnoise_unet_name"),
            filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "vae_name"),
            filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name"),
            filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name1"),
            filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name2"),
            filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name3"),
            filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflowItem.id, "clip_name4"),
            // Workflow capabilities from placeholders
            capabilities = WorkflowCapabilities.fromPlaceholders(placeholders)
        )
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load global preferences with serverId prefix
        val savedWorkflowId = prefs.getString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", "") ?: ""
        val defaultPositivePrompt = SeasonalPrompts.getImageToVideoPrompt()
        val savedPositivePrompt = prefs.getString("${serverId}_$KEY_POSITIVE_PROMPT", null) ?: defaultPositivePrompt

        // Update positive prompt first
        _uiState.value = _uiState.value.copy(positivePrompt = savedPositivePrompt)

        // Find workflow by ID
        val workflowItem = if (savedWorkflowId.isNotEmpty()) {
            _uiState.value.availableWorkflows.find { it.id == savedWorkflowId }
        } else {
            _uiState.value.availableWorkflows.find { it.name == _uiState.value.selectedWorkflow }
                ?: _uiState.value.availableWorkflows.firstOrNull()
        }

        // Load workflow values (single source of truth)
        if (workflowItem != null) {
            loadWorkflowValues(workflowItem)
        }
    }

    /**
     * Save current workflow values to per-workflow storage
     */
    private fun saveWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        // Use workflow ID as storage key (UUID-based)
        val values = WorkflowValues(
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
            clip1Model = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedClip4.takeIf { it.isNotEmpty() },
            highnoiseLoraChain = LoraSelection.toJsonString(state.highnoiseLoraChain).takeIf { state.highnoiseLoraChain.isNotEmpty() },
            lownoiseLoraChain = LoraSelection.toJsonString(state.lownoiseLoraChain).takeIf { state.lownoiseLoraChain.isNotEmpty() },
            nodeAttributeEdits = existingValues?.nodeAttributeEdits
        )

        storage.saveValues(serverId, workflowId, values)
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Save global preferences with serverId prefix
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            .putString("${serverId}_$KEY_POSITIVE_PROMPT", state.positivePrompt)
            .apply()

        // Save per-workflow values using workflow ID
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }
    }

    private fun loadSavedSourceImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItvSource, applicationContext)
        if (sourceImage != null) {
            _uiState.value = _uiState.value.copy(sourceImage = sourceImage)
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
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItvSource, bitmap, context)

                    _uiState.value = _uiState.value.copy(sourceImage = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(ImageToVideoEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    fun onWorkflowChange(workflow: String) {
        val state = _uiState.value

        // Find workflow item to get its ID
        val workflowItem = state.availableWorkflows.find { it.name == workflow } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflow)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

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

    fun onClip1Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip1 = clip)
        savePreferences()
    }

    fun onClip2Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip2 = clip)
        savePreferences()
    }

    fun onClip3Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip3 = clip)
        savePreferences()
    }

    fun onClip4Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedClip4 = clip)
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

    fun onRandomSeedToggle() {
        _uiState.value = _uiState.value.copy(randomSeed = !_uiState.value.randomSeed)
        savePreferences()
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        _uiState.value = _uiState.value.copy(seed = seed, seedError = error)
        if (error == null) savePreferences()
    }

    fun onRandomizeSeed() {
        val randomSeed = kotlin.random.Random.nextLong(0, Long.MAX_VALUE).toString()
        _uiState.value = _uiState.value.copy(seed = randomSeed, seedError = null)
        savePreferences()
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        _uiState.value = _uiState.value.copy(denoise = denoise, denoiseError = error)
        if (error == null) savePreferences()
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        _uiState.value = _uiState.value.copy(batchSize = batchSize, batchSizeError = error)
        if (error == null) savePreferences()
    }

    fun onUpscaleMethodChange(method: String) {
        _uiState.value = _uiState.value.copy(upscaleMethod = method)
        savePreferences()
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        _uiState.value = _uiState.value.copy(scaleBy = scaleBy, scaleByError = error)
        if (error == null) savePreferences()
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        _uiState.value = _uiState.value.copy(stopAtClipLayer = layer, stopAtClipLayerError = error)
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
                    client.uploadImage(imageBytes, "itv_source.png") { success, filename, _, failureType ->
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
                    _events.emit(ImageToVideoEvent.ShowToast(R.string.failed_save_image))
                }
                return null
            }
            val uploadedFilename = uploadResult.filename

            val baseWorkflow = WorkflowManager.prepareImageToVideoWorkflowById(
                workflowId = state.selectedWorkflowId,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.negativePrompt,
                highnoiseUnet = state.selectedHighnoiseUnet,
                lownoiseUnet = state.selectedLownoiseUnet,
                highnoiseLora = state.selectedHighnoiseLora,
                lownoiseLora = state.selectedLownoiseLora,
                vae = state.selectedVae,
                clip = state.selectedClip,
                clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
                clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
                clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
                clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
                width = width,
                height = height,
                length = length,
                fps = fps,
                imageFilename = uploadedFilename
            ) ?: return null

            // Inject additional LoRAs if configured (separate chains for high noise and low noise)
            var workflow = baseWorkflow
            if (state.highnoiseLoraChain.isNotEmpty()) {
                workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.highnoiseLoraChain, isHighNoise = true)
            }
            if (state.lownoiseLoraChain.isNotEmpty()) {
                workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.lownoiseLoraChain, isHighNoise = false)
            }
            workflow
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        // Source image is required for Image-to-Video
        if (state.sourceImage == null) {
            return false
        }

        if (state.positivePrompt.isBlank()) {
            return false
        }

        // Only check for validation errors in numeric fields
        return state.widthError == null &&
                state.heightError == null &&
                state.lengthError == null &&
                state.fpsError == null
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        saveLastPreviewImage(bitmap)
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) return
        lastClearedForPromptId = promptId
        // Evict preview from cache so restoreLastPreviewImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.ItvPreview)
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentItvPromptId()
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentItvPromptId()
    }

    // Event listener management

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     * @param generationViewModel The shared GenerationViewModel
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel

        // If generation is running for this screen, switch to preview mode
        val state = generationViewModel.generationState.value
        if (state.isGenerating && state.ownerId == OWNER_ID) {
            _uiState.value = _uiState.value.copy(viewMode = ImageToVideoViewMode.PREVIEW)
        }

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
                DebugLogger.i(TAG, "VideoGenerated: ${Obfuscator.promptId(event.promptId)}")
                fetchGeneratedVideo(event.promptId)
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(ImageToVideoEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(ImageToVideoEvent.ShowToastMessage(event.message))
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
            filePrefix = VideoUtils.FilePrefix.IMAGE_TO_VIDEO
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
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItvPreview, bitmap, applicationContext)
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItvPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    private fun deleteLastPreviewImage() {
        // Remove from in-memory cache AND delete from disk
        // This prevents stale preview from being restored on app restart
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.ItvPreview)
        }
    }
}
