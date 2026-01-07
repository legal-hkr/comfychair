package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
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

/**
 * Data class representing a path drawn on the mask canvas
 */
data class MaskPathData(
    val path: Path,
    val isEraser: Boolean,
    val brushSize: Float
)

/**
 * Represents a workflow item in the unified workflow dropdown for Image-to-Image
 */
data class ItiWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name with type prefix
    val type: WorkflowType      // Workflow type for mode detection
) : WorkflowItemBase

/**
 * UI state for the view mode toggle
 */
enum class ImageToImageViewMode {
    SOURCE,
    PREVIEW
}

/**
 * UI state for the Image-to-Image mode (Inpainting vs Editing)
 */
enum class ImageToImageMode {
    INPAINTING,  // Existing: requires mask
    EDITING      // New: no mask, optional reference images
}

/**
 * Represents a workflow item for Image Editing
 */
data class IteWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name for dropdown
    val type: WorkflowType      // ITI_EDITING
) : WorkflowItemBase

/**
 * UI state for the Image-to-image screen
 *
 * Architecture: Unified Field Visibility
 * - Inpainting mode uses unified fields (no checkpoint/UNET distinction)
 * - Editing mode has its own set of fields (different workflow type)
 * - Field visibility is controlled by WorkflowCapabilities (derived from placeholders)
 */
data class ImageToImageUiState(
    // View state
    val viewMode: ImageToImageViewMode = ImageToImageViewMode.SOURCE,
    val sourceImage: Bitmap? = null,
    val previewImage: Bitmap? = null,
    val maskPaths: List<MaskPathData> = emptyList(),
    val brushSize: Float = 50f,
    val isEraserMode: Boolean = false,

    // Preview image file info (for metadata extraction)
    val previewImageFilename: String? = null,
    val previewImageSubfolder: String? = null,
    val previewImageType: String? = null,

    // Inpainting workflow selection
    val selectedWorkflow: String = "",
    val selectedWorkflowId: String = "",  // Workflow ID for storage
    val availableWorkflows: List<ItiWorkflowItem> = emptyList(),

    // Workflow placeholders - detected from {{placeholder}} patterns in workflow JSON
    val workflowPlaceholders: Set<String> = emptySet(),

    // Workflow capabilities (unified flags derived from placeholders)
    val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Available models (from server)
    val checkpoints: List<String> = emptyList(),
    val unets: List<String> = emptyList(),
    val vaes: List<String> = emptyList(),
    val clips: List<String> = emptyList(),
    val availableLoras: List<String> = emptyList(),
    val availableUpscaleMethods: List<String> = emptyList(),

    // Inpainting mode - unified model selections (visibility driven by capabilities)
    val selectedCheckpoint: String = "",
    val selectedUnet: String = "",
    val selectedVae: String = "",
    val selectedClip: String = "",
    val selectedClip1: String = "",
    val selectedClip2: String = "",
    val selectedClip3: String = "",
    val selectedClip4: String = "",

    // Workflow-specific filtered options (from actual node type in workflow)
    val filteredCheckpoints: List<String>? = null,
    val filteredUnets: List<String>? = null,
    val filteredVaes: List<String>? = null,
    val filteredClips: List<String>? = null,
    val filteredClips1: List<String>? = null,
    val filteredClips2: List<String>? = null,
    val filteredClips3: List<String>? = null,
    val filteredClips4: List<String>? = null,

    // Inpainting mode - unified generation parameters
    val positivePrompt: String = "",
    val negativePrompt: String = "",
    val megapixels: String = "1.0",
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

    // Inpainting mode - unified LoRA chain
    val loraChain: List<LoraSelection> = emptyList(),

    // Validation errors (shared between modes)
    val megapixelsError: String? = null,
    val cfgError: String? = null,
    val stepsError: String? = null,
    val seedError: String? = null,
    val denoiseError: String? = null,
    val batchSizeError: String? = null,
    val scaleByError: String? = null,
    val stopAtClipLayerError: String? = null,

    // Deferred model selections for inpainting (for restoring after models load)
    val deferredCheckpoint: String? = null,
    val deferredUnet: String? = null,
    val deferredVae: String? = null,
    val deferredClip: String? = null,
    val deferredClip1: String? = null,
    val deferredClip2: String? = null,
    val deferredClip3: String? = null,
    val deferredClip4: String? = null,

    // ========== EDITING MODE STATE ==========
    // (Editing is a different workflow type, so it has its own set of fields)

    // Mode selection (Editing vs Inpainting)
    val mode: ImageToImageMode = ImageToImageMode.EDITING,

    // Editing mode workflows (ITI_EDITING)
    val editingWorkflows: List<IteWorkflowItem> = emptyList(),
    val selectedEditingWorkflow: String = "",
    val selectedEditingWorkflowId: String = "",  // Editing workflow ID for storage

    // Editing mode models
    val selectedEditingUnet: String = "",
    val selectedEditingLora: String = "",  // Mandatory LoRA for editing
    val selectedEditingVae: String = "",
    val selectedEditingClip: String = "",
    val selectedEditingClip1: String = "",
    val selectedEditingClip2: String = "",
    val selectedEditingClip3: String = "",
    val selectedEditingClip4: String = "",

    // Editing parameters
    val editingMegapixels: String = "2.0",
    val editingSteps: String = "4",
    val editingCfg: String = "1.0",
    val editingSampler: String = "euler",
    val editingScheduler: String = "simple",
    val editingNegativePrompt: String = "",
    val editingRandomSeed: Boolean = true,
    val editingSeed: String = "0",
    val editingDenoise: String = "1.0",
    val editingBatchSize: String = "1",
    val editingUpscaleMethod: String = "nearest-exact",
    val editingScaleBy: String = "1.5",
    val editingStopAtClipLayer: String = "-1",

    // Reference images (optional, for editing mode)
    val referenceImage1: Bitmap? = null,
    val referenceImage2: Bitmap? = null,

    // Optional LoRA chain for editing (in addition to mandatory LoRA)
    val editingLoraChain: List<LoraSelection> = emptyList(),

    // Deferred model selections for editing
    val deferredEditingUnet: String? = null,
    val deferredEditingVae: String? = null,
    val deferredEditingClip: String? = null,
    val deferredEditingClip1: String? = null,
    val deferredEditingClip2: String? = null,
    val deferredEditingClip3: String? = null,
    val deferredEditingClip4: String? = null,
    val deferredEditingLora: String? = null,

    // Upload state
    val isUploading: Boolean = false
)

/**
 * Events emitted by the Image-to-image screen
 */
sealed class ImageToImageEvent {
    data class ShowToast(val messageResId: Int) : ImageToImageEvent()
    data class ShowToastMessage(val message: String) : ImageToImageEvent()
}

/**
 * ViewModel for the Image-to-image screen
 */
class ImageToImageViewModel : BaseGenerationViewModel<ImageToImageUiState, ImageToImageEvent>() {

    override val initialState = ImageToImageUiState()

    // Constants
    companion object {
        private const val TAG = "ImageToImage"
        const val OWNER_ID = "IMAGE_TO_IMAGE"
        private const val PREFS_NAME = "ImageToImageFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val PREF_POSITIVE_PROMPT = "positivePrompt"
        private const val PREF_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val PREF_MODE = "mode"
        private const val PREF_SELECTED_EDITING_WORKFLOW_ID = "selectedEditingWorkflowId"

        private const val FEATHER_RADIUS = 8
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
                    val editingUnet = state.deferredEditingUnet?.takeIf { it in cache.unets }
                        ?: validateModelSelection(state.selectedEditingUnet, cache.unets)
                    val editingVae = state.deferredEditingVae?.takeIf { it in cache.vaes }
                        ?: validateModelSelection(state.selectedEditingVae, cache.vaes)
                    val editingClip = state.deferredEditingClip?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedEditingClip, cache.clips)
                    val editingClip1 = state.deferredEditingClip1?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedEditingClip1, cache.clips)
                    val editingClip2 = state.deferredEditingClip2?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedEditingClip2, cache.clips)
                    val editingClip3 = state.deferredEditingClip3?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedEditingClip3, cache.clips)
                    val editingClip4 = state.deferredEditingClip4?.takeIf { it in cache.clips }
                        ?: validateModelSelection(state.selectedEditingClip4, cache.clips)
                    val editingLora = state.deferredEditingLora?.takeIf { it in cache.loras }
                        ?: validateModelSelection(state.selectedEditingLora, cache.loras)

                    state.copy(
                        checkpoints = cache.checkpoints,
                        unets = cache.unets,
                        vaes = cache.vaes,
                        clips = cache.clips,
                        availableLoras = cache.loras,
                        // Apply validated model selections
                        selectedCheckpoint = checkpoint,
                        selectedUnet = unet,
                        selectedVae = vae,
                        selectedClip = clip,
                        selectedClip1 = clip1,
                        selectedClip2 = clip2,
                        selectedClip3 = clip3,
                        selectedClip4 = clip4,
                        selectedEditingUnet = editingUnet,
                        selectedEditingVae = editingVae,
                        selectedEditingClip = editingClip,
                        selectedEditingClip1 = editingClip1,
                        selectedEditingClip2 = editingClip2,
                        selectedEditingClip3 = editingClip3,
                        selectedEditingClip4 = editingClip4,
                        selectedEditingLora = editingLora,
                        // Clear deferred values once applied
                        deferredCheckpoint = null,
                        deferredUnet = null,
                        deferredVae = null,
                        deferredClip = null,
                        deferredClip1 = null,
                        deferredClip2 = null,
                        deferredClip3 = null,
                        deferredClip4 = null,
                        deferredEditingUnet = null,
                        deferredEditingVae = null,
                        deferredEditingClip = null,
                        deferredEditingClip1 = null,
                        deferredEditingClip2 = null,
                        deferredEditingClip3 = null,
                        deferredEditingClip4 = null,
                        deferredEditingLora = null,
                        loraChain = LoraChainManager.filterUnavailable(state.loraChain, cache.loras),
                        editingLoraChain = LoraChainManager.filterUnavailable(state.editingLoraChain, cache.loras)
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
        loadSavedImages()
    }

    private fun loadWorkflows() {
        val ctx = applicationContext ?: return

        val showBuiltIn = AppSettings.isShowBuiltInWorkflows(ctx)
        // Load inpainting workflows (ITI_INPAINTING)
        val inpaintingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_INPAINTING)
            .filter { showBuiltIn || !it.isBuiltIn }
        // Load editing workflows (ITI_EDITING)
        val editingWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITI_EDITING)
            .filter { showBuiltIn || !it.isBuiltIn }

        // Create inpainting workflow list
        val unifiedWorkflows = inpaintingWorkflows.map { workflow ->
            ItiWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.ITI_INPAINTING
            )
        }

        // Create editing workflow list
        val editingWorkflowItems = editingWorkflows.map { workflow ->
            IteWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.ITI_EDITING
            )
        }.sortedBy { it.displayName }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val selectedWorkflowItem = if (currentSelection.isEmpty())
            sortedWorkflows.firstOrNull()
        else
            sortedWorkflows.find { it.name == currentSelection } ?: sortedWorkflows.firstOrNull()

        val currentEditingSelection = _uiState.value.selectedEditingWorkflow
        val selectedEditingItem = if (currentEditingSelection.isEmpty())
            editingWorkflowItems.firstOrNull()
        else
            editingWorkflowItems.find { it.name == currentEditingSelection } ?: editingWorkflowItems.firstOrNull()

        _uiState.value = _uiState.value.copy(
            availableWorkflows = sortedWorkflows,
            selectedWorkflow = selectedWorkflowItem?.name ?: "",
            selectedWorkflowId = selectedWorkflowItem?.id ?: "",
            editingWorkflows = editingWorkflowItems,
            selectedEditingWorkflow = selectedEditingItem?.name ?: "",
            selectedEditingWorkflowId = selectedEditingItem?.id ?: ""
        )

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        // IMPORTANT: Load the NON-active mode's workflow first, then the active mode's workflow.
        // Both functions set the shared currentWorkflowHas* capability flags, so the active
        // mode's workflow must be loaded LAST to ensure its flags are not overwritten.
        val currentMode = _uiState.value.mode
        if (currentMode == ImageToImageMode.INPAINTING) {
            // Inpainting is active - load editing first, then inpainting (so inpainting flags win)
            if (selectedEditingItem != null) {
                loadEditingWorkflowValues(selectedEditingItem)
            }
            if (selectedWorkflowItem != null) {
                loadWorkflowValues(selectedWorkflowItem)
            }
        } else {
            // Editing is active - load inpainting first, then editing (so editing flags win)
            if (selectedWorkflowItem != null) {
                loadWorkflowValues(selectedWorkflowItem)
            }
            if (selectedEditingItem != null) {
                loadEditingWorkflowValues(selectedEditingItem)
            }
        }
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val defaultPositivePrompt = SeasonalPrompts.getImageToImagePrompt()

        // Load global preferences with serverId prefix
        val positivePrompt = prefs.getString("${serverId}_$PREF_POSITIVE_PROMPT", null) ?: defaultPositivePrompt
        val savedWorkflowId = prefs.getString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", null)
        val savedMode = prefs.getString("${serverId}_$PREF_MODE", ImageToImageMode.EDITING.name)
        val savedEditingWorkflowId = prefs.getString("${serverId}_$PREF_SELECTED_EDITING_WORKFLOW_ID", null)

        // Restore mode
        val mode = try {
            ImageToImageMode.valueOf(savedMode ?: ImageToImageMode.EDITING.name)
        } catch (e: Exception) {
            ImageToImageMode.EDITING
        }

        // Update positive prompt and mode first
        _uiState.value = _uiState.value.copy(
            positivePrompt = positivePrompt,
            mode = mode
        )

        // Determine which inpainting workflow to select (by ID)
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

        // Determine which editing workflow to select (by ID)
        val editingWorkflowToLoad = when {
            savedEditingWorkflowId != null && state.editingWorkflows.any { it.id == savedEditingWorkflowId } ->
                state.editingWorkflows.find { it.id == savedEditingWorkflowId }
            state.selectedEditingWorkflow.isNotEmpty() ->
                state.editingWorkflows.find { it.name == state.selectedEditingWorkflow }
            state.editingWorkflows.isNotEmpty() -> state.editingWorkflows.first()
            else -> null
        }

        // Load workflow values - load the NON-active mode's workflow first, then the active mode's.
        // Both functions set the shared currentWorkflowHas* capability flags, so the active
        // mode's workflow must be loaded LAST to ensure its flags are not overwritten.
        if (mode == ImageToImageMode.INPAINTING) {
            // Inpainting is active - load editing first, then inpainting (so inpainting flags win)
            if (editingWorkflowToLoad != null) {
                loadEditingWorkflowValues(editingWorkflowToLoad)
            }
            if (workflowToLoad != null) {
                loadWorkflowValues(workflowToLoad)
            }
        } else {
            // Editing is active - load inpainting first, then editing (so editing flags win)
            if (workflowToLoad != null) {
                loadWorkflowValues(workflowToLoad)
            }
            if (editingWorkflowToLoad != null) {
                loadEditingWorkflowValues(editingWorkflowToLoad)
            }
        }
    }

    /**
     * Load workflow values without triggering save (used during initialization).
     *
     * Uses unified fields - capabilities (derived from placeholders) control which
     * fields are visible in the UI.
     */
    private fun loadWorkflowValues(workflow: ItiWorkflowItem) {
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
            megapixels = savedValues?.megapixels?.toString()
                ?: defaults?.megapixels?.toString() ?: "1.0",
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

    /**
     * Load editing workflow values without triggering save (used during initialization)
     */
    private fun loadEditingWorkflowValues(workflow: IteWorkflowItem) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        // Load saved values by workflow ID, defaults by workflow name
        val savedValues = storage.loadValues(serverId, workflow.id)
        val defaults = WorkflowManager.getWorkflowDefaults(workflow.name)

        // Get workflow placeholders - these determine field visibility
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflow.id)

        // Get current model cache to validate saved selections
        val cache = ConnectionManager.modelCache.value

        // Apply saved model selections - use deferred mechanism to handle race condition
        val savedEditingUnet = savedValues?.model  // unified field for editing UNET
        val savedEditingVae = savedValues?.vaeModel
        val savedEditingClip = savedValues?.clipModel
        val savedEditingClip1 = savedValues?.clip1Model
        val savedEditingClip2 = savedValues?.clip2Model
        val savedEditingClip3 = savedValues?.clip3Model
        val savedEditingClip4 = savedValues?.clip4Model
        val savedEditingLora = savedValues?.loraModel

        val state = _uiState.value
        _uiState.value = state.copy(
            selectedEditingWorkflow = workflow.name,
            selectedEditingWorkflowId = workflow.id,
            workflowPlaceholders = placeholders,
            editingMegapixels = savedValues?.megapixels?.toString()
                ?: defaults?.megapixels?.toString() ?: "2.0",
            editingSteps = savedValues?.steps?.toString()
                ?: defaults?.steps?.toString() ?: "4",
            editingCfg = savedValues?.cfg?.toString()
                ?: defaults?.cfg?.toString() ?: "1.0",
            editingSampler = savedValues?.samplerName
                ?: defaults?.samplerName ?: "euler",
            editingScheduler = savedValues?.scheduler
                ?: defaults?.scheduler ?: "simple",
            editingNegativePrompt = savedValues?.negativePrompt
                ?: defaults?.negativePrompt ?: "",
            // Apply model selections immediately if models are loaded, otherwise use validated empty
            selectedEditingUnet = savedEditingUnet?.takeIf { it in cache.unets }
                ?: validateModelSelection(state.selectedEditingUnet, cache.unets),
            selectedEditingLora = savedEditingLora?.takeIf { it in cache.loras }
                ?: validateModelSelection(state.selectedEditingLora, cache.loras),
            selectedEditingVae = savedEditingVae?.takeIf { it in cache.vaes }
                ?: validateModelSelection(state.selectedEditingVae, cache.vaes),
            selectedEditingClip = savedEditingClip?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip, cache.clips),
            selectedEditingClip1 = savedEditingClip1?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip1, cache.clips),
            selectedEditingClip2 = savedEditingClip2?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip2, cache.clips),
            selectedEditingClip3 = savedEditingClip3?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip3, cache.clips),
            selectedEditingClip4 = savedEditingClip4?.takeIf { it in cache.clips }
                ?: validateModelSelection(state.selectedEditingClip4, cache.clips),
            // Set deferred values - these will be applied when model cache updates
            deferredEditingUnet = savedEditingUnet,
            deferredEditingVae = savedEditingVae,
            deferredEditingClip = savedEditingClip,
            deferredEditingClip1 = savedEditingClip1,
            deferredEditingClip2 = savedEditingClip2,
            deferredEditingClip3 = savedEditingClip3,
            deferredEditingClip4 = savedEditingClip4,
            deferredEditingLora = savedEditingLora,
            editingLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Workflow-specific filtered options (editing mode uses UNET)
            filteredCheckpoints = null,
            filteredUnets = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "unet_name"),
            filteredVaes = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "vae_name"),
            filteredClips = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name"),
            filteredClips1 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name1"),
            filteredClips2 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name2"),
            filteredClips3 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name3"),
            filteredClips4 = WorkflowManager.getNodeSpecificOptionsForField(workflow.id, "clip_name4"),
            // Workflow capabilities from placeholders
            capabilities = WorkflowCapabilities.fromPlaceholders(placeholders)
        )
    }

    /**
     * Save current workflow values to per-workflow storage.
     *
     * Saves ALL field values unconditionally - WorkflowValues is the unified storage format.
     */
    private fun saveWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        // Save unified field values
        val values = WorkflowValues(
            megapixels = state.megapixels.toFloatOrNull(),
            steps = state.steps.toIntOrNull(),
            cfg = state.cfg.toFloatOrNull(),
            samplerName = state.sampler,
            scheduler = state.scheduler,
            negativePrompt = state.negativePrompt.takeIf { it.isNotEmpty() },
            // Save checkpoint OR unet - whichever is set
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

    /**
     * Save current editing workflow values to per-workflow storage
     */
    private fun saveEditingWorkflowValues(workflowId: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Load existing values to preserve nodeAttributeEdits from Workflow Editor
        val existingValues = storage.loadValues(serverId, workflowId)

        val values = WorkflowValues(
            megapixels = state.editingMegapixels.toFloatOrNull(),
            steps = state.editingSteps.toIntOrNull(),
            cfg = state.editingCfg.toFloatOrNull(),
            samplerName = state.editingSampler,
            scheduler = state.editingScheduler,
            negativePrompt = state.editingNegativePrompt.takeIf { it.isNotEmpty() },
            model = state.selectedEditingUnet.takeIf { it.isNotEmpty() },
            loraModel = state.selectedEditingLora.takeIf { it.isNotEmpty() },
            vaeModel = state.selectedEditingVae.takeIf { it.isNotEmpty() },
            clipModel = state.selectedEditingClip.takeIf { it.isNotEmpty() },
            clip1Model = state.selectedEditingClip1.takeIf { it.isNotEmpty() },
            clip2Model = state.selectedEditingClip2.takeIf { it.isNotEmpty() },
            clip3Model = state.selectedEditingClip3.takeIf { it.isNotEmpty() },
            clip4Model = state.selectedEditingClip4.takeIf { it.isNotEmpty() },
            loraChain = LoraSelection.toJsonString(state.editingLoraChain).takeIf { state.editingLoraChain.isNotEmpty() },
            nodeAttributeEdits = existingValues?.nodeAttributeEdits
        )

        storage.saveValues(serverId, workflowId, values)
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences with serverId prefix
        prefs.edit()
            .putString("${serverId}_$PREF_POSITIVE_PROMPT", state.positivePrompt)
            .putString("${serverId}_$PREF_MODE", state.mode.name)
            .putString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            .putString("${serverId}_$PREF_SELECTED_EDITING_WORKFLOW_ID", state.selectedEditingWorkflowId)
            .apply()

        // Save per-workflow values for the currently selected inpainting workflow (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Save per-workflow values for the currently selected editing workflow (using workflow ID)
        if (state.selectedEditingWorkflowId.isNotEmpty()) {
            saveEditingWorkflowValues(state.selectedEditingWorkflowId)
        }
    }

    private fun loadSavedImages() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiSource, applicationContext)
        val previewImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiPreview, applicationContext)
        val referenceImage1 = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.IteReferenceImage1, applicationContext)
        val referenceImage2 = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.IteReferenceImage2, applicationContext)

        _uiState.value = _uiState.value.copy(
            sourceImage = sourceImage,
            previewImage = previewImage,
            referenceImage1 = referenceImage1,
            referenceImage2 = referenceImage2
        )
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

    // View mode
    fun onViewModeChange(mode: ImageToImageViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    // Image-to-Image mode (Inpainting vs Editing)
    fun onModeChange(mode: ImageToImageMode) {
        val state = _uiState.value
        _uiState.value = state.copy(mode = mode)

        // Reload workflow values to restore field visibility flags for the new mode's workflow
        when (mode) {
            ImageToImageMode.EDITING -> {
                val editingWorkflow = state.editingWorkflows.find { it.name == state.selectedEditingWorkflow }
                if (editingWorkflow != null) {
                    loadEditingWorkflowValues(editingWorkflow)
                }
            }
            ImageToImageMode.INPAINTING -> {
                val workflow = state.availableWorkflows.find { it.name == state.selectedWorkflow }
                if (workflow != null) {
                    loadWorkflowValues(workflow)
                }
            }
        }

        savePreferences()
    }

    // Reference image handlers (for Editing mode)
    fun onReferenceImage1Change(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.IteReferenceImage1, bitmap, context)
                    _uiState.value = _uiState.value.copy(referenceImage1 = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    fun onReferenceImage2Change(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.IteReferenceImage2, bitmap, context)
                    _uiState.value = _uiState.value.copy(referenceImage2 = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    fun onClearReferenceImage1() {
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.IteReferenceImage1)
            _uiState.value = _uiState.value.copy(referenceImage1 = null)
        }
    }

    fun onClearReferenceImage2() {
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.IteReferenceImage2)
            _uiState.value = _uiState.value.copy(referenceImage2 = null)
        }
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
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiSource, bitmap, context)

                    _uiState.value = _uiState.value.copy(
                        sourceImage = bitmap,
                        maskPaths = emptyList() // Clear mask when new image is loaded
                    )
                }
            } catch (e: Exception) {
                _events.emit(ImageToImageEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    // Mask operations
    fun addMaskPath(path: Path, isEraser: Boolean, brushSize: Float) {
        val pathData = MaskPathData(path, isEraser, brushSize)
        _uiState.value = _uiState.value.copy(
            maskPaths = _uiState.value.maskPaths + pathData
        )
    }

    fun onBrushSizeChange(size: Float) {
        _uiState.value = _uiState.value.copy(brushSize = size)
    }

    fun onEraserModeChange(isEraser: Boolean) {
        _uiState.value = _uiState.value.copy(isEraserMode = isEraser)
    }

    fun clearMask() {
        _uiState.value = _uiState.value.copy(maskPaths = emptyList())
    }

    fun invertMask() {
        // Mark that mask is inverted - actual inversion happens when generating mask bitmap
        // For visual feedback, we'll use a special flag
        val sourceImage = _uiState.value.sourceImage ?: return

        viewModelScope.launch {
            // Create a full-coverage path and toggle all existing paths
            val fullPath = Path().apply {
                addRect(RectF(0f, 0f, sourceImage.width.toFloat(), sourceImage.height.toFloat()), Path.Direction.CW)
            }

            // Add inverted background
            val invertedPaths = mutableListOf<MaskPathData>()
            invertedPaths.add(MaskPathData(fullPath, false, 1f))

            // Add existing paths as erasers (to remove painted areas)
            _uiState.value.maskPaths.forEach { pathData ->
                invertedPaths.add(pathData.copy(isEraser = !pathData.isEraser))
            }

            _uiState.value = _uiState.value.copy(maskPaths = invertedPaths)
        }
    }

    fun hasMask(): Boolean {
        return _uiState.value.maskPaths.isNotEmpty()
    }

    /**
     * Generate the mask bitmap from current paths
     * Returns black/white bitmap where white = inpaint area
     */
    fun generateMaskBitmap(): Bitmap? {
        val sourceImage = _uiState.value.sourceImage ?: return null
        if (_uiState.value.maskPaths.isEmpty()) return null

        // Create mask at source image size
        val maskBitmap = Bitmap.createBitmap(sourceImage.width, sourceImage.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)

        // Start with black background
        canvas.drawColor(Color.BLACK)

        // Draw white for painted areas
        val paintPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        val erasePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        _uiState.value.maskPaths.forEach { pathData ->
            val paint = if (pathData.isEraser) erasePaint else paintPaint
            paint.strokeWidth = pathData.brushSize
            canvas.drawPath(pathData.path, paint)
        }

        // Apply feathering
        return applyFeathering(maskBitmap, FEATHER_RADIUS)
    }

    private fun applyFeathering(mask: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return mask

        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to grayscale values (0-255)
        val values = IntArray(width * height) { i ->
            Color.red(pixels[i]) // Since it's black/white, R=G=B
        }

        // Horizontal pass
        val tempValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until width) {
                        sum += values[y * width + nx]
                        count++
                    }
                }
                tempValues[y * width + x] = sum / count
            }
        }

        // Vertical pass
        val blurredValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until height) {
                        sum += tempValues[ny * width + x]
                        count++
                    }
                }
                blurredValues[y * width + x] = sum / count
            }
        }

        // Convert back to pixels
        for (i in pixels.indices) {
            val v = blurredValues[i]
            pixels[i] = Color.rgb(v, v, v)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        return result
    }

    // Unified workflow change

    /**
     * Unified workflow selection - automatically determines mode from workflow type
     */
    fun onWorkflowChange(workflowName: String) {
        val state = _uiState.value

        // Find the workflow item to determine type
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current workflow values before switching (using workflow ID)
        if (state.selectedWorkflowId.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflowId)
        }

        // Load new workflow values (single source of truth)
        loadWorkflowValues(workflowItem)

        savePreferences()
    }

    // Model selection callbacks

    fun onCheckpointChange(checkpoint: String) {
        _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoint)
        savePreferences()
    }

    fun onUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedUnet = unet)
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

    // Unified parameter callbacks

    fun onMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        _uiState.value = _uiState.value.copy(
            megapixels = megapixels,
            megapixelsError = error
        )
        savePreferences()
    }

    // Unified parameter callbacks for inpainting mode

    fun onNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
        savePreferences()
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        _uiState.value = _uiState.value.copy(steps = steps, stepsError = error)
        savePreferences()
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(cfg = cfg, cfgError = error)
        savePreferences()
    }

    fun onSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(sampler = sampler)
        savePreferences()
    }

    fun onSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(scheduler = scheduler)
        savePreferences()
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

    // Positive prompt
    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        savePreferences()
    }

    // Unified LoRA chain callbacks

    fun onAddLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.loraChain, state.availableLoras)
        if (newChain === state.loraChain) return // No change
        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    fun onRemoveLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.loraChain, index)
        if (newChain === state.loraChain) return // No change
        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    fun onLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.loraChain, index, name)
        if (newChain === state.loraChain) return // No change
        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    fun onLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.loraChain, index, strength)
        if (newChain === state.loraChain) return // No change
        _uiState.value = state.copy(loraChain = newChain)
        savePreferences()
    }

    // Editing mode callbacks

    fun onEditingWorkflowChange(workflowName: String) {
        val state = _uiState.value

        // Find the workflow item to get its ID
        val workflowItem = state.editingWorkflows.find { it.name == workflowName } ?: return

        DebugLogger.d(TAG, "onEditingWorkflowChange: ${Obfuscator.workflowName(workflowName)}")

        // Save current editing workflow values before switching (using workflow ID)
        if (state.selectedEditingWorkflowId.isNotEmpty()) {
            saveEditingWorkflowValues(state.selectedEditingWorkflowId)
        }

        // Load new editing workflow values (single source of truth)
        loadEditingWorkflowValues(workflowItem)

        savePreferences()
    }

    fun onEditingUnetChange(unet: String) {
        _uiState.value = _uiState.value.copy(selectedEditingUnet = unet)
        savePreferences()
    }

    fun onEditingLoraChange(lora: String) {
        _uiState.value = _uiState.value.copy(selectedEditingLora = lora)
        savePreferences()
    }

    fun onEditingVaeChange(vae: String) {
        _uiState.value = _uiState.value.copy(selectedEditingVae = vae)
        savePreferences()
    }

    fun onEditingClipChange(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip = clip)
        savePreferences()
    }

    fun onEditingClip1Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip1 = clip)
        savePreferences()
    }

    fun onEditingClip2Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip2 = clip)
        savePreferences()
    }

    fun onEditingClip3Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip3 = clip)
        savePreferences()
    }

    fun onEditingClip4Change(clip: String) {
        _uiState.value = _uiState.value.copy(selectedEditingClip4 = clip)
        savePreferences()
    }

    fun onEditingMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        _uiState.value = _uiState.value.copy(
            editingMegapixels = megapixels,
            megapixelsError = error
        )
        savePreferences()
    }

    fun onEditingStepsChange(steps: String) {
        _uiState.value = _uiState.value.copy(editingSteps = steps)
        savePreferences()
    }

    fun onEditingCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        _uiState.value = _uiState.value.copy(editingCfg = cfg, cfgError = error)
        savePreferences()
    }

    fun onEditingSamplerChange(sampler: String) {
        _uiState.value = _uiState.value.copy(editingSampler = sampler)
        savePreferences()
    }

    fun onEditingSchedulerChange(scheduler: String) {
        _uiState.value = _uiState.value.copy(editingScheduler = scheduler)
        savePreferences()
    }

    fun onEditingNegativePromptChange(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(editingNegativePrompt = negativePrompt)
        savePreferences()
    }

    fun onEditingRandomSeedToggle() {
        _uiState.value = _uiState.value.copy(editingRandomSeed = !_uiState.value.editingRandomSeed)
        savePreferences()
    }

    fun onEditingSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        _uiState.value = _uiState.value.copy(editingSeed = seed, seedError = error)
        if (error == null) savePreferences()
    }

    fun onEditingRandomizeSeed() {
        val randomSeed = kotlin.random.Random.nextLong(0, Long.MAX_VALUE).toString()
        _uiState.value = _uiState.value.copy(editingSeed = randomSeed, seedError = null)
        savePreferences()
    }

    fun onEditingDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        _uiState.value = _uiState.value.copy(editingDenoise = denoise, denoiseError = error)
        if (error == null) savePreferences()
    }

    fun onEditingBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        _uiState.value = _uiState.value.copy(editingBatchSize = batchSize, batchSizeError = error)
        if (error == null) savePreferences()
    }

    fun onEditingUpscaleMethodChange(method: String) {
        _uiState.value = _uiState.value.copy(editingUpscaleMethod = method)
        savePreferences()
    }

    fun onEditingScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        _uiState.value = _uiState.value.copy(editingScaleBy = scaleBy, scaleByError = error)
        if (error == null) savePreferences()
    }

    fun onEditingStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        _uiState.value = _uiState.value.copy(editingStopAtClipLayer = layer, stopAtClipLayerError = error)
        if (error == null) savePreferences()
    }

    // Editing mode LoRA chain callbacks

    fun onAddEditingLora() {
        val state = _uiState.value
        val newChain = LoraChainManager.addLora(state.editingLoraChain, state.availableLoras)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    fun onRemoveEditingLora(index: Int) {
        val state = _uiState.value
        val newChain = LoraChainManager.removeLora(state.editingLoraChain, index)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    fun onEditingLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraName(state.editingLoraChain, index, name)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    fun onEditingLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val newChain = LoraChainManager.updateLoraStrength(state.editingLoraChain, index, strength)
        if (newChain === state.editingLoraChain) return // No change

        _uiState.value = state.copy(editingLoraChain = newChain)
        savePreferences()
    }

    // Validation

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        if (state.positivePrompt.isBlank()) {
            return false
        }

        return when (state.mode) {
            ImageToImageMode.EDITING -> {
                // Editing mode: only check for validation errors
                state.stepsError == null &&
                state.megapixelsError == null &&
                state.cfgError == null
            }
            ImageToImageMode.INPAINTING -> {
                // Inpainting mode: source image with mask required, plus validation errors
                state.sourceImage != null &&
                state.megapixelsError == null &&
                state.stepsError == null &&
                state.cfgError == null
            }
        }
    }

    /**
     * Upload source image (with mask for inpainting, without for editing) to ComfyUI and prepare workflow
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
        val sourceImage = state.sourceImage ?: return null

        DebugLogger.i(TAG, "Preparing workflow (mode: ${state.mode})")

        _uiState.update { it.copy(isUploading = true) }
        return try {
            when (state.mode) {
                ImageToImageMode.EDITING -> prepareEditingWorkflow(client, sourceImage, state)
                ImageToImageMode.INPAINTING -> prepareInpaintingWorkflow(client, sourceImage, state)
            }
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }

    /**
     * Prepare workflow for Editing mode (no mask, optional reference images)
     */
    private suspend fun prepareEditingWorkflow(
        client: ComfyUIClient,
        sourceImage: Bitmap,
        state: ImageToImageUiState
    ): String? {
        // Convert source image to PNG bytes
        val sourceBytes = withContext(Dispatchers.IO) {
            val outputStream = java.io.ByteArrayOutputStream()
            sourceImage.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.toByteArray()
        }

        // Upload source image
        data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
        val sourceResult: UploadResult = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.uploadImage(sourceBytes, "editing_source.png") { success, filename, _, failureType ->
                    continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                }
            }
        }

        if (sourceResult.filename == null) {
            // Check for stall or auth failure - show dialog instead of toast
            if (sourceResult.failureType == ConnectionFailure.STALLED ||
                sourceResult.failureType == ConnectionFailure.AUTHENTICATION) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, sourceResult.failureType)
                }
            } else {
                _events.emit(ImageToImageEvent.ShowToast(R.string.failed_save_image))
            }
            return null
        }
        val uploadedSource = sourceResult.filename

        // Upload reference image 1 (if present)
        var uploadedRef1: String? = null
        if (state.referenceImage1 != null) {
            val ref1Bytes = withContext(Dispatchers.IO) {
                val outputStream = java.io.ByteArrayOutputStream()
                state.referenceImage1.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.toByteArray()
            }
            val ref1Result: UploadResult = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.uploadImage(ref1Bytes, "reference_1.png") { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }
            if (ref1Result.filename == null && (ref1Result.failureType == ConnectionFailure.STALLED ||
                ref1Result.failureType == ConnectionFailure.AUTHENTICATION)) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, ref1Result.failureType)
                }
                return null
            }
            uploadedRef1 = ref1Result.filename
        }

        // Upload reference image 2 (if present)
        var uploadedRef2: String? = null
        if (state.referenceImage2 != null) {
            val ref2Bytes = withContext(Dispatchers.IO) {
                val outputStream = java.io.ByteArrayOutputStream()
                state.referenceImage2.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.toByteArray()
            }
            val ref2Result: UploadResult = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.uploadImage(ref2Bytes, "reference_2.png") { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }
            if (ref2Result.filename == null && (ref2Result.failureType == ConnectionFailure.STALLED ||
                ref2Result.failureType == ConnectionFailure.AUTHENTICATION)) {
                applicationContext?.let { ctx ->
                    ConnectionManager.showConnectionAlert(ctx, ref2Result.failureType)
                }
                return null
            }
            uploadedRef2 = ref2Result.filename
        }

        // Prepare editing workflow JSON
        val baseWorkflow = WorkflowManager.prepareImageEditingWorkflowById(
            workflowId = state.selectedEditingWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.editingNegativePrompt,
            unet = state.selectedEditingUnet,
            lora = state.selectedEditingLora,
            vae = state.selectedEditingVae,
            clip = state.selectedEditingClip,
            clip1 = state.selectedEditingClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedEditingClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedEditingClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedEditingClip4.takeIf { it.isNotEmpty() },
            megapixels = state.editingMegapixels.toFloatOrNull() ?: 2.0f,
            steps = state.editingSteps.toIntOrNull() ?: 4,
            cfg = state.editingCfg.toFloatOrNull() ?: 1.0f,
            samplerName = state.editingSampler,
            scheduler = state.editingScheduler,
            denoise = state.editingDenoise.toFloatOrNull() ?: 1.0f,
            sourceImageFilename = uploadedSource,
            referenceImage1Filename = uploadedRef1,
            referenceImage2Filename = uploadedRef2
        ) ?: return null

        // Inject additional LoRA chain if configured
        return WorkflowManager.injectLoraChain(baseWorkflow, state.editingLoraChain, WorkflowType.ITI_EDITING)
    }

    /**
     * Prepare workflow for Inpainting mode (requires mask)
     */
    private suspend fun prepareInpaintingWorkflow(
        client: ComfyUIClient,
        sourceImage: Bitmap,
        state: ImageToImageUiState
    ): String? {
        // Generate mask
        val maskBitmap = generateMaskBitmap()
        if (maskBitmap == null) {
            _events.emit(ImageToImageEvent.ShowToast(R.string.paint_mask_hint))
            return null
        }

        // Combine source image with mask in alpha channel
        val imageWithMask = combineImageWithMask(sourceImage, maskBitmap)
        maskBitmap.recycle()

        // Convert bitmap to PNG byte array
        val imageBytes = withContext(Dispatchers.IO) {
            val outputStream = java.io.ByteArrayOutputStream()
            imageWithMask.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.toByteArray()
        }

        imageWithMask.recycle()

        // Upload to ComfyUI
        data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
        val uploadResult: UploadResult = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.uploadImage(imageBytes, "inpaint_source.png") { success, filename, _, failureType ->
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
                _events.emit(ImageToImageEvent.ShowToast(R.string.failed_save_image))
            }
            return null
        }
        val uploadedFilename = uploadResult.filename

        // Prepare workflow JSON using unified fields
        val baseWorkflow = WorkflowManager.prepareImageToImageWorkflowById(
            workflowId = state.selectedWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
            // Model selections - pass all, placeholder substitution handles which are used
            checkpoint = state.selectedCheckpoint,
            unet = state.selectedUnet,
            vae = state.selectedVae,
            clip = state.selectedClip,
            clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
            // Unified parameters
            megapixels = state.megapixels.toFloatOrNull() ?: 1.0f,
            steps = state.steps.toIntOrNull() ?: 20,
            cfg = state.cfg.toFloatOrNull() ?: 7.0f,
            samplerName = state.sampler,
            scheduler = state.scheduler,
            denoise = state.denoise.toFloatOrNull() ?: 1.0f,
            imageFilename = uploadedFilename
        ) ?: return null

        // Inject unified LoRA chain
        return WorkflowManager.injectLoraChain(baseWorkflow, state.loraChain, WorkflowType.ITI_INPAINTING)
    }

    /**
     * Combine source image with mask in alpha channel (mask white = transparent)
     */
    private fun combineImageWithMask(source: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        val sourcePixels = IntArray(source.width * source.height)
        val maskPixels = IntArray(mask.width * mask.height)

        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)

        for (i in sourcePixels.indices) {
            val r = Color.red(sourcePixels[i])
            val g = Color.green(sourcePixels[i])
            val b = Color.blue(sourcePixels[i])

            // Mask brightness - white = inpaint area = transparent alpha
            val maskBrightness = Color.red(maskPixels[i])
            val alpha = 255 - maskBrightness // Invert: white mask -> 0 alpha (transparent)

            sourcePixels[i] = Color.argb(alpha, r, g, b)
        }

        result.setPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        return result
    }

    /**
     * Fetch and save generated image from history
     * @param promptId The prompt ID to fetch
     * @param onComplete Callback with success boolean (true if image was fetched and set)
     */
    fun fetchGeneratedImage(promptId: String, onComplete: (success: Boolean) -> Unit) {
        val client = comfyUIClient ?: run {
            onComplete(false)
            return
        }
        val context = applicationContext ?: run {
            onComplete(false)
            return
        }

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) {
                onComplete(false)
                return@fetchHistory
            }

            val promptHistory = historyJson.optJSONObject(promptId)
            val outputs = promptHistory?.optJSONObject("outputs")

            if (outputs == null) {
                onComplete(false)
                return@fetchHistory
            }

            // Find image in outputs
            val outputKeys = outputs.keys()
            while (outputKeys.hasNext()) {
                val nodeId = outputKeys.next()
                val nodeOutput = outputs.optJSONObject(nodeId)
                val images = nodeOutput?.optJSONArray("images")

                if (images != null && images.length() > 0) {
                    val imageInfo = images.optJSONObject(0)
                    val filename = imageInfo?.optString("filename") ?: continue
                    val subfolder = imageInfo.optString("subfolder", "")
                    val type = imageInfo.optString("type", "output")

                    client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                        if (bitmap != null) {
                            // Store in cache (memory or disk based on mode)
                            MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap, context)

                            _uiState.value = _uiState.value.copy(
                                previewImage = bitmap,
                                previewImageFilename = filename,
                                previewImageSubfolder = subfolder,
                                previewImageType = type,
                                viewMode = ImageToImageViewMode.PREVIEW
                            )
                            onComplete(true)
                        } else if (failureType == ConnectionFailure.STALLED) {
                            ConnectionManager.showConnectionAlert(context, failureType)
                            onComplete(false)
                        } else {
                            onComplete(false)
                        }
                    }
                    return@fetchHistory
                }
            }

            onComplete(false)
        }
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewImage = bitmap)
        saveLastPreviewImage(bitmap)
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return // Already cleared for this promptId
        }
        lastClearedForPromptId = promptId
        // Evict from cache so loadSavedImages() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.ItiPreview)
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            previewImageFilename = null,
            previewImageSubfolder = null,
            previewImageType = null
        )
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            previewImageFilename = null,
            previewImageSubfolder = null,
            previewImageType = null
        )
    }

    // Event handling

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel

        // If generation is running for this screen, switch to preview mode
        val state = generationViewModel.generationState.value
        if (state.isGenerating && state.ownerId == OWNER_ID) {
            _uiState.value = _uiState.value.copy(viewMode = ImageToImageViewMode.PREVIEW)
        }

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
                onPreviewBitmapChange(event.bitmap)
            }
            is GenerationEvent.ImageGenerated -> {
                val promptId = event.promptId
                DebugLogger.i(TAG, "ImageGenerated: ${Obfuscator.promptId(promptId)}")
                fetchGeneratedImage(promptId) { success ->
                    if (success) {
                        generationViewModelRef?.completeGeneration(promptId)
                    }
                    // If not successful, don't complete - will retry on next return
                }
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                DebugLogger.w(TAG, "ConnectionLostDuringGeneration")
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(ImageToImageEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(ImageToImageEvent.ShowToastMessage(event.message))
                }
                // DON'T call completeGeneration() here - this may just be a connection error
                // The server might still complete the generation
            }
            is GenerationEvent.ClearPreviewForResume -> {
                // Don't clear - keep the preview visible during navigation
                // New live previews will naturally replace the current one
            }
            else -> {}
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap, applicationContext)
    }
}
