package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

/**
 * UI state for the view mode toggle
 */
enum class ImageToVideoViewMode {
    SOURCE,
    PREVIEW
}

/**
 * Represents a workflow item with display name and type prefix
 */
data class ItvWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // "[UNET] LightX2V"
    val type: WorkflowType      // ITV_UNET
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
    val availableWorkflows: List<ItvWorkflowItem> = emptyList(),

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
    val lownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Current workflow capabilities (for conditional UI)
    val currentWorkflowHasNegativePrompt: Boolean = true,

    // Field presence flags (for conditional UI - only show fields that are mapped in the workflow)
    val currentWorkflowHasWidth: Boolean = true,
    val currentWorkflowHasHeight: Boolean = true,
    val currentWorkflowHasLength: Boolean = true,
    val currentWorkflowHasFrameRate: Boolean = true,
    val currentWorkflowHasVaeName: Boolean = true,
    val currentWorkflowHasClipName: Boolean = true,
    val currentWorkflowHasLoraName: Boolean = true,

    // Model presence flags (for conditional model dropdowns)
    val currentWorkflowHasHighnoiseUnet: Boolean = false,

    // Dual-UNET/LoRA field presence flags (for video workflows)
    val currentWorkflowHasLownoiseUnet: Boolean = false,
    val currentWorkflowHasHighnoiseLora: Boolean = false,
    val currentWorkflowHasLownoiseLora: Boolean = false
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

        // Global preferences
        private const val KEY_WORKFLOW = "workflow"
        private const val KEY_POSITIVE_PROMPT = "positive_prompt"
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
                        // Clear deferred values once applied
                        deferredHighnoiseUnet = null,
                        deferredLownoiseUnet = null,
                        deferredHighnoiseLora = null,
                        deferredLownoiseLora = null,
                        deferredVae = null,
                        deferredClip = null,
                        // Filter LoRA chains
                        highnoiseLoraChain = LoraChainManager.filterUnavailable(state.highnoiseLoraChain, cache.loras),
                        lownoiseLoraChain = LoraChainManager.filterUnavailable(state.lownoiseLoraChain, cache.loras)
                    )
                }
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

        val unetWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV_UNET)
        DebugLogger.d(TAG, "loadWorkflows: Found ${unetWorkflows.size} UNET workflows")
        val unetPrefix = ctx.getString(R.string.mode_unet)

        val unifiedWorkflows = unetWorkflows.map { workflow ->
            ItvWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = "[$unetPrefix] ${workflow.name}",
                type = WorkflowType.ITV_UNET
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
        val storage = workflowValuesStorage ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        DebugLogger.d(TAG, "restorePreferences: Loading saved preferences")

        // Load global preferences
        val savedWorkflow = prefs.getString(KEY_WORKFLOW, "") ?: ""
        val defaultPositivePrompt = SeasonalPrompts.getImageToVideoPrompt()
        val savedPositivePrompt = prefs.getString(KEY_POSITIVE_PROMPT, null) ?: defaultPositivePrompt
        DebugLogger.d(TAG, "restorePreferences: savedWorkflow=$savedWorkflow")

        val workflow = if (savedWorkflow.isNotEmpty() && _uiState.value.availableWorkflows.any { it.name == savedWorkflow }) {
            savedWorkflow
        } else {
            _uiState.value.selectedWorkflow
        }

        // Load per-workflow values
        // Use workflow name directly as storage key for consistency
        val savedValues = storage.loadValues(workflow)
        val defaults = WorkflowManager.getWorkflowDefaults(workflow)

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
            lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Set workflow capability flags from defaults
            currentWorkflowHasNegativePrompt = defaults?.hasNegativePrompt ?: true,
            currentWorkflowHasWidth = defaults?.hasWidth ?: true,
            currentWorkflowHasHeight = defaults?.hasHeight ?: true,
            currentWorkflowHasLength = defaults?.hasLength ?: true,
            currentWorkflowHasFrameRate = defaults?.hasFrameRate ?: true,
            currentWorkflowHasVaeName = defaults?.hasVaeName ?: true,
            currentWorkflowHasClipName = defaults?.hasClipName ?: true,
            currentWorkflowHasLoraName = defaults?.hasLoraName ?: true,
            // Model presence flag
            currentWorkflowHasHighnoiseUnet = defaults?.hasHighnoiseUnet ?: false,
            // Dual-UNET/LoRA flags
            currentWorkflowHasLownoiseUnet = defaults?.hasLownoiseUnet ?: false,
            currentWorkflowHasHighnoiseLora = defaults?.hasHighnoiseLora ?: false,
            currentWorkflowHasLownoiseLora = defaults?.hasLownoiseLora ?: false
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
        val storage = workflowValuesStorage ?: return

        // Save current workflow values before switching
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow)
        }

        // Load new workflow's saved values or defaults
        // Use workflow name directly as storage key for consistency
        val savedValues = storage.loadValues(workflow)
        val defaults = WorkflowManager.getWorkflowDefaults(workflow)

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
            lownoiseLoraChain = savedValues?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Set workflow capability flags from defaults
            currentWorkflowHasNegativePrompt = defaults?.hasNegativePrompt ?: true,
            currentWorkflowHasWidth = defaults?.hasWidth ?: true,
            currentWorkflowHasHeight = defaults?.hasHeight ?: true,
            currentWorkflowHasLength = defaults?.hasLength ?: true,
            currentWorkflowHasFrameRate = defaults?.hasFrameRate ?: true,
            currentWorkflowHasVaeName = defaults?.hasVaeName ?: true,
            currentWorkflowHasClipName = defaults?.hasClipName ?: true,
            currentWorkflowHasLoraName = defaults?.hasLoraName ?: true,
            // Model presence flag
            currentWorkflowHasHighnoiseUnet = defaults?.hasHighnoiseUnet ?: false,
            // Dual-UNET/LoRA flags
            currentWorkflowHasLownoiseUnet = defaults?.hasLownoiseUnet ?: false,
            currentWorkflowHasHighnoiseLora = defaults?.hasHighnoiseLora ?: false,
            currentWorkflowHasLownoiseLora = defaults?.hasLownoiseLora ?: false
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

        val baseWorkflow = WorkflowManager.prepareImageToVideoWorkflow(
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
        return workflow
    }

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value
        return state.sourceImage != null &&
                state.selectedWorkflow.isNotEmpty() &&
                // High noise UNET required only if workflow has it mapped
                (!state.currentWorkflowHasHighnoiseUnet || state.selectedHighnoiseUnet.isNotEmpty()) &&
                // Low noise UNET required only if workflow has it mapped
                (!state.currentWorkflowHasLownoiseUnet || state.selectedLownoiseUnet.isNotEmpty()) &&
                // High noise LoRA required only if workflow has it mapped
                (!state.currentWorkflowHasHighnoiseLora || state.selectedHighnoiseLora.isNotEmpty()) &&
                // Low noise LoRA required only if workflow has it mapped
                (!state.currentWorkflowHasLownoiseLora || state.selectedLownoiseLora.isNotEmpty()) &&
                // VAE/CLIP required only if workflow has them
                (!state.currentWorkflowHasVaeName || state.selectedVae.isNotEmpty()) &&
                (!state.currentWorkflowHasClipName || state.selectedClip.isNotEmpty()) &&
                state.widthError == null &&
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
        if (promptId == lastClearedForPromptId) {
            DebugLogger.d(TAG, "clearPreviewForExecution: already cleared for $promptId, skipping")
            return
        }
        DebugLogger.d(TAG, "clearPreviewForExecution: clearing for new promptId=$promptId")
        lastClearedForPromptId = promptId
        // Evict preview from cache so restoreLastPreviewImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.ItvPreview)
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentItvPromptId()
    }

    fun clearPreview() {
        DebugLogger.d(TAG, "clearPreview called")
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
        DebugLogger.d(TAG, "startListening called")
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
        DebugLogger.d(TAG, "Generation event: ${event::class.simpleName}")
        when (event) {
            is GenerationEvent.PreviewImage -> {
                onPreviewBitmapChange(event.bitmap)
            }
            is GenerationEvent.VideoGenerated -> {
                DebugLogger.i(TAG, "Video generated, fetching result")
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
        DebugLogger.d(TAG, "fetchGeneratedVideo: promptId=$promptId")
        val context = applicationContext ?: run {
            DebugLogger.w(TAG, "fetchGeneratedVideo: no application context")
            return
        }
        val client = comfyUIClient ?: run {
            DebugLogger.w(TAG, "fetchGeneratedVideo: no client")
            return
        }

        VideoUtils.fetchVideoFromHistory(
            context = context,
            client = client,
            promptId = promptId,
            filePrefix = VideoUtils.FilePrefix.IMAGE_TO_VIDEO
        ) { uri ->
            if (uri != null) {
                DebugLogger.i(TAG, "Video fetch successful: $uri")
                // Clear preview bitmap so video player takes display precedence
                _uiState.value = _uiState.value.copy(currentVideoUri = uri, previewBitmap = null)
                deleteLastPreviewImage()
                generationViewModelRef?.completeGeneration(promptId)
            } else {
                DebugLogger.w(TAG, "Video fetch failed (uri is null)")
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
