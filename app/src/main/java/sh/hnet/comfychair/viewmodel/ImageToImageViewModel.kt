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
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.storage.WorkflowValuesStorage

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
    val name: String,           // Internal workflow name
    val displayName: String,    // Display name with type prefix
    val type: WorkflowType      // Workflow type for mode detection
)

/**
 * UI state for the view mode toggle
 */
enum class ImageToImageViewMode {
    SOURCE,
    PREVIEW
}

/**
 * UI state for the Image-to-image screen
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

    // Unified workflow selection (mode is derived from workflow type)
    val selectedWorkflow: String = "",
    val availableWorkflows: List<ItiWorkflowItem> = emptyList(),

    // Derived mode (computed from selectedWorkflow's type, not user-selected)
    val isCheckpointMode: Boolean = true,

    // Checkpoint mode settings
    val checkpoints: List<String> = emptyList(),
    val selectedCheckpoint: String = "",
    val megapixels: String = "1.0",
    val checkpointSteps: String = "20",
    val checkpointCfg: String = "8.0",
    val checkpointSampler: String = "euler",
    val checkpointScheduler: String = "normal",

    // UNET mode settings
    val unets: List<String> = emptyList(),
    val selectedUnet: String = "",
    val vaes: List<String> = emptyList(),
    val selectedVae: String = "",
    val clips: List<String> = emptyList(),
    val selectedClip: String = "",
    val unetSteps: String = "9",
    val unetCfg: String = "1.0",
    val unetSampler: String = "euler",
    val unetScheduler: String = "simple",

    // Positive prompt (global)
    val positivePrompt: String = "",

    // Negative prompts (per-workflow, stored per mode)
    val checkpointNegativePrompt: String = "",
    val unetNegativePrompt: String = "",

    // Validation errors
    val megapixelsError: String? = null,
    val cfgError: String? = null,
    val stepsError: String? = null,

    // LoRA chains (optional, separate for each mode)
    val checkpointLoraChain: List<LoraSelection> = emptyList(),
    val unetLoraChain: List<LoraSelection> = emptyList(),
    val availableLoras: List<String> = emptyList()
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
class ImageToImageViewModel : ViewModel() {

    private var comfyUIClient: ComfyUIClient? = null
    private var applicationContext: Context? = null
    private var workflowManager: WorkflowManager? = null
    private var workflowValuesStorage: WorkflowValuesStorage? = null

    private val _uiState = MutableStateFlow(ImageToImageUiState())
    val uiState: StateFlow<ImageToImageUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImageToImageEvent>()
    val events: SharedFlow<ImageToImageEvent> = _events.asSharedFlow()

    // Reference to GenerationViewModel for event handling
    private var generationViewModelRef: GenerationViewModel? = null

    companion object {
        const val OWNER_ID = "IMAGE_TO_IMAGE"
        private const val PREFS_NAME = "ImageToImageFragmentPrefs"

        // Global preferences
        private const val PREF_POSITIVE_PROMPT = "positive_prompt"
        private const val PREF_SELECTED_WORKFLOW = "selectedWorkflow"

        private const val FEATHER_RADIUS = 8
    }

    fun initialize(context: Context, client: ComfyUIClient) {
        applicationContext = context.applicationContext
        comfyUIClient = client
        workflowManager = WorkflowManager(context)
        workflowValuesStorage = WorkflowValuesStorage(context)

        loadWorkflowOptions()
        restorePreferences()
        loadSavedImages()
    }

    private fun loadWorkflowOptions() {
        val wm = workflowManager ?: run {
            return
        }
        val ctx = applicationContext ?: run {
            return
        }

        val checkpointWorkflows = wm.getImageToImageCheckpointWorkflowNames()
        val unetWorkflows = wm.getImageToImageUNETWorkflowNames()


        // Create unified workflow list with type prefix for display
        val checkpointPrefix = ctx.getString(R.string.mode_checkpoint)
        val unetPrefix = ctx.getString(R.string.mode_unet)

        val unifiedWorkflows = mutableListOf<ItiWorkflowItem>()

        // Add checkpoint workflows
        checkpointWorkflows.forEach { name ->
            unifiedWorkflows.add(ItiWorkflowItem(
                name = name,
                displayName = "[$checkpointPrefix] $name",
                type = WorkflowType.ITI_CHECKPOINT
            ))
        }

        // Add UNET workflows
        unetWorkflows.forEach { name ->
            unifiedWorkflows.add(ItiWorkflowItem(
                name = name,
                displayName = "[$unetPrefix] $name",
                type = WorkflowType.ITI_UNET
            ))
        }

        // Set default workflow if none selected
        val currentWorkflow = _uiState.value.selectedWorkflow
        val defaultWorkflow = when {
            currentWorkflow.isNotEmpty() && unifiedWorkflows.any { it.name == currentWorkflow } -> currentWorkflow
            unifiedWorkflows.isNotEmpty() -> unifiedWorkflows[0].name
            else -> ""
        }


        // Determine mode from selected workflow
        val isCheckpoint = unifiedWorkflows.find { it.name == defaultWorkflow }?.type == WorkflowType.ITI_CHECKPOINT

        _uiState.value = _uiState.value.copy(
            availableWorkflows = unifiedWorkflows,
            selectedWorkflow = defaultWorkflow,
            isCheckpointMode = isCheckpoint
        )
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val defaultPositivePrompt = context.getString(R.string.default_prompt_image_to_image)

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
            // Otherwise use the current selection (set by loadWorkflowOptions)
            state.selectedWorkflow.isNotEmpty() -> state.selectedWorkflow
            // Fallback to first available
            state.availableWorkflows.isNotEmpty() -> state.availableWorkflows.first().name
            else -> ""
        }

        // Load workflow and its values (this sets mode and values)
        if (workflowToLoad.isNotEmpty()) {
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
        val isCheckpoint = workflowItem.type == WorkflowType.ITI_CHECKPOINT

        // Load saved values or defaults
        val savedValues = storage.loadValues(workflowName)
        val defaults = manager.getWorkflowDefaults(workflowName)

        if (isCheckpoint) {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = true,
                megapixels = savedValues?.megapixels?.toString()
                    ?: defaults?.megapixels?.toString() ?: "1.0",
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
                checkpointLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
            )
        } else {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = false,
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
                unetLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
            )
        }
    }

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
                megapixels = state.megapixels.toFloatOrNull(),
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
                steps = state.unetSteps.toIntOrNull(),
                cfg = state.unetCfg.toFloatOrNull(),
                samplerName = state.unetSampler,
                scheduler = state.unetScheduler,
                negativePrompt = state.unetNegativePrompt.takeIf { it.isNotEmpty() },
                unetModel = state.selectedUnet.takeIf { it.isNotEmpty() },
                vaeModel = state.selectedVae.takeIf { it.isNotEmpty() },
                clipModel = state.selectedClip.takeIf { it.isNotEmpty() },
                loraChain = LoraSelection.toJsonString(state.unetLoraChain).takeIf { state.unetLoraChain.isNotEmpty() }
            )
        }

        storage.saveValues(workflowName, values)
    }

    private fun savePreferences() {
        val context = applicationContext ?: return
        val state = _uiState.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences (prompt and selected workflow)
        prefs.edit()
            .putString(PREF_POSITIVE_PROMPT, state.positivePrompt)
            .putString(PREF_SELECTED_WORKFLOW, state.selectedWorkflow)
            .apply()

        // Save per-workflow values for the currently selected workflow
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow, state.isCheckpointMode)
        }
    }

    private fun loadSavedImages() {
        // Restore from in-memory cache (loaded from disk on app startup)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiSource)
        val previewImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiPreview)

        _uiState.value = _uiState.value.copy(
            sourceImage = sourceImage,
            previewImage = previewImage
        )
    }

    fun fetchModels() {
        val client = comfyUIClient ?: run {
            return
        }

        viewModelScope.launch {
            // Fetch checkpoints
            client.fetchCheckpoints { checkpoints ->
                _uiState.value = _uiState.value.copy(
                    checkpoints = checkpoints ?: emptyList()
                )
                // Auto-select first if none selected
                if (_uiState.value.selectedCheckpoint.isEmpty() && checkpoints?.isNotEmpty() == true) {
                    _uiState.value = _uiState.value.copy(selectedCheckpoint = checkpoints.first())
                }
            }

            // Fetch UNETs
            client.fetchUNETs { unets ->
                _uiState.value = _uiState.value.copy(
                    unets = unets ?: emptyList()
                )
                if (_uiState.value.selectedUnet.isEmpty() && unets?.isNotEmpty() == true) {
                    _uiState.value = _uiState.value.copy(selectedUnet = unets.first())
                }
            }

            // Fetch VAEs
            client.fetchVAEs { vaes ->
                _uiState.value = _uiState.value.copy(
                    vaes = vaes ?: emptyList()
                )
                if (_uiState.value.selectedVae.isEmpty() && vaes?.isNotEmpty() == true) {
                    _uiState.value = _uiState.value.copy(selectedVae = vaes.first())
                }
            }

            // Fetch CLIPs
            client.fetchCLIPs { clips ->
                _uiState.value = _uiState.value.copy(
                    clips = clips ?: emptyList()
                )
                if (_uiState.value.selectedClip.isEmpty() && clips?.isNotEmpty() == true) {
                    _uiState.value = _uiState.value.copy(selectedClip = clips.first())
                }
            }

            // Fetch LoRAs
            client.fetchLoRAs { loras ->
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

    // View mode
    fun onViewModeChange(mode: ImageToImageViewMode) {
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
                    // Store in memory - will be persisted to disk on onStop
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiSource, bitmap)

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

    // ==================== Unified Workflow Change ====================

    /**
     * Unified workflow selection - automatically determines mode from workflow type
     */
    fun onWorkflowChange(workflowName: String) {
        val state = _uiState.value
        val manager = workflowManager ?: return
        val storage = workflowValuesStorage ?: return

        // Save current workflow values before switching
        if (state.selectedWorkflow.isNotEmpty()) {
            saveWorkflowValues(state.selectedWorkflow, state.isCheckpointMode)
        }

        // Find the workflow item to determine type
        val workflowItem = state.availableWorkflows.find { it.name == workflowName } ?: return
        val isCheckpoint = workflowItem.type == WorkflowType.ITI_CHECKPOINT

        // Load new workflow's saved values or defaults
        val savedValues = storage.loadValues(workflowName)
        val defaults = manager.getWorkflowDefaults(workflowName)

        if (isCheckpoint) {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = true,
                megapixels = savedValues?.megapixels?.toString()
                    ?: defaults?.megapixels?.toString() ?: "1.0",
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
                checkpointLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
            )
        } else {
            _uiState.value = state.copy(
                selectedWorkflow = workflowName,
                isCheckpointMode = false,
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
                unetLoraChain = savedValues?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList()
            )
        }
        savePreferences()
    }

    // ==================== Model Selection Callbacks ====================

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

    // ==================== Unified Parameter Callbacks ====================

    fun onMegapixelsChange(megapixels: String) {
        val error = validateMegapixels(megapixels)
        _uiState.value = _uiState.value.copy(
            megapixels = megapixels,
            megapixelsError = error
        )
        savePreferences()
    }

    /**
     * Unified negative prompt change - routes to mode-specific state
     */
    fun onNegativePromptChange(negativePrompt: String) {
        val state = _uiState.value
        _uiState.value = if (state.isCheckpointMode) {
            state.copy(checkpointNegativePrompt = negativePrompt)
        } else {
            state.copy(unetNegativePrompt = negativePrompt)
        }
        savePreferences()
    }

    /**
     * Unified steps change - routes to mode-specific state
     */
    fun onStepsChange(steps: String) {
        val state = _uiState.value
        _uiState.value = if (state.isCheckpointMode) {
            state.copy(checkpointSteps = steps)
        } else {
            state.copy(unetSteps = steps)
        }
        savePreferences()
    }

    /**
     * Unified CFG change - routes to mode-specific state
     */
    fun onCfgChange(cfg: String) {
        val state = _uiState.value
        val error = validateCfg(cfg)
        _uiState.value = if (state.isCheckpointMode) {
            state.copy(checkpointCfg = cfg, cfgError = error)
        } else {
            state.copy(unetCfg = cfg, cfgError = error)
        }
        savePreferences()
    }

    /**
     * Unified sampler change - routes to mode-specific state
     */
    fun onSamplerChange(sampler: String) {
        val state = _uiState.value
        _uiState.value = if (state.isCheckpointMode) {
            state.copy(checkpointSampler = sampler)
        } else {
            state.copy(unetSampler = sampler)
        }
        savePreferences()
    }

    /**
     * Unified scheduler change - routes to mode-specific state
     */
    fun onSchedulerChange(scheduler: String) {
        val state = _uiState.value
        _uiState.value = if (state.isCheckpointMode) {
            state.copy(checkpointScheduler = scheduler)
        } else {
            state.copy(unetScheduler = scheduler)
        }
        savePreferences()
    }

    // Positive prompt
    fun onPositivePromptChange(positivePrompt: String) {
        _uiState.value = _uiState.value.copy(positivePrompt = positivePrompt)
        savePreferences()
    }

    // ==================== Unified LoRA Chain Callbacks ====================

    fun onAddLora() {
        val state = _uiState.value
        val currentChain = if (state.isCheckpointMode) state.checkpointLoraChain else state.unetLoraChain
        if (currentChain.size >= LoraSelection.MAX_CHAIN_LENGTH) return
        val availableLoras = state.availableLoras
        if (availableLoras.isEmpty()) return

        val newLora = LoraSelection(
            name = availableLoras.first(),
            strength = LoraSelection.DEFAULT_STRENGTH
        )
        _uiState.value = if (state.isCheckpointMode) {
            state.copy(checkpointLoraChain = currentChain + newLora)
        } else {
            state.copy(unetLoraChain = currentChain + newLora)
        }
        savePreferences()
    }

    fun onRemoveLora(index: Int) {
        val state = _uiState.value
        val currentChain = if (state.isCheckpointMode) {
            state.checkpointLoraChain.toMutableList()
        } else {
            state.unetLoraChain.toMutableList()
        }
        if (index in currentChain.indices) {
            currentChain.removeAt(index)
            _uiState.value = if (state.isCheckpointMode) {
                state.copy(checkpointLoraChain = currentChain)
            } else {
                state.copy(unetLoraChain = currentChain)
            }
            savePreferences()
        }
    }

    fun onLoraNameChange(index: Int, name: String) {
        val state = _uiState.value
        val currentChain = if (state.isCheckpointMode) {
            state.checkpointLoraChain.toMutableList()
        } else {
            state.unetLoraChain.toMutableList()
        }
        if (index in currentChain.indices) {
            currentChain[index] = currentChain[index].copy(name = name)
            _uiState.value = if (state.isCheckpointMode) {
                state.copy(checkpointLoraChain = currentChain)
            } else {
                state.copy(unetLoraChain = currentChain)
            }
            savePreferences()
        }
    }

    fun onLoraStrengthChange(index: Int, strength: Float) {
        val state = _uiState.value
        val currentChain = if (state.isCheckpointMode) {
            state.checkpointLoraChain.toMutableList()
        } else {
            state.unetLoraChain.toMutableList()
        }
        if (index in currentChain.indices) {
            currentChain[index] = currentChain[index].copy(strength = strength)
            _uiState.value = if (state.isCheckpointMode) {
                state.copy(checkpointLoraChain = currentChain)
            } else {
                state.copy(unetLoraChain = currentChain)
            }
            savePreferences()
        }
    }

    private fun validateMegapixels(value: String): String? {
        val mp = value.toFloatOrNull()
            ?: return applicationContext?.getString(R.string.error_invalid_number) ?: "Invalid number"
        return if (mp < 0.1f || mp > 8.3f) {
            applicationContext?.getString(R.string.error_megapixels_range) ?: "Must be 0.1-8.3"
        } else null
    }

    private fun validateCfg(value: String): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
        return if (floatValue == null || floatValue !in 0.0f..100.0f) {
            applicationContext?.getString(R.string.error_cfg_range) ?: "Must be 0.0-100.0"
        } else null
    }

    fun hasValidConfiguration(): Boolean {
        val state = _uiState.value

        return if (state.isCheckpointMode) {
            state.selectedWorkflow.isNotEmpty() &&
            state.selectedCheckpoint.isNotEmpty() &&
            state.megapixels.toFloatOrNull() != null &&
            state.megapixelsError == null &&
            state.checkpointSteps.toIntOrNull() != null &&
            validateCfg(state.checkpointCfg) == null
        } else {
            state.selectedWorkflow.isNotEmpty() &&
            state.selectedUnet.isNotEmpty() &&
            state.selectedVae.isNotEmpty() &&
            state.selectedClip.isNotEmpty() &&
            state.unetSteps.toIntOrNull() != null &&
            validateCfg(state.unetCfg) == null
        }
    }

    /**
     * Upload source image with mask to ComfyUI and prepare workflow
     */
    suspend fun prepareWorkflow(): String? {
        val client = comfyUIClient ?: return null
        val wm = workflowManager ?: return null
        val context = applicationContext ?: return null
        val state = _uiState.value

        val sourceImage = state.sourceImage ?: return null

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
        val uploadedFilename: String? = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.uploadImage(imageBytes, "inpaint_source.png") { success, filename, errorMessage ->
                    continuation.resumeWith(Result.success(if (success) filename else null))
                }
            }
        }

        if (uploadedFilename == null) {
            _events.emit(ImageToImageEvent.ShowToast(R.string.failed_save_image))
            return null
        }

        // Prepare workflow JSON
        val baseWorkflow = if (state.isCheckpointMode) {
            wm.prepareImageToImageWorkflow(
                workflowName = state.selectedWorkflow,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.checkpointNegativePrompt,
                checkpoint = state.selectedCheckpoint,
                megapixels = state.megapixels.toFloatOrNull() ?: 1.0f,
                steps = state.checkpointSteps.toIntOrNull() ?: 20,
                cfg = state.checkpointCfg.toFloatOrNull() ?: 8.0f,
                samplerName = state.checkpointSampler,
                scheduler = state.checkpointScheduler,
                imageFilename = uploadedFilename
            )
        } else {
            wm.prepareImageToImageWorkflow(
                workflowName = state.selectedWorkflow,
                positivePrompt = state.positivePrompt,
                negativePrompt = state.unetNegativePrompt,
                unet = state.selectedUnet,
                vae = state.selectedVae,
                clip = state.selectedClip,
                steps = state.unetSteps.toIntOrNull() ?: 9,
                cfg = state.unetCfg.toFloatOrNull() ?: 1.0f,
                samplerName = state.unetSampler,
                scheduler = state.unetScheduler,
                imageFilename = uploadedFilename
            )
        } ?: return null

        // Inject LoRA chain if configured (use appropriate chain based on mode)
        val workflowType = if (state.isCheckpointMode) {
            WorkflowType.ITI_CHECKPOINT
        } else {
            WorkflowType.ITI_UNET
        }
        val loraChain = if (state.isCheckpointMode) {
            state.checkpointLoraChain
        } else {
            state.unetLoraChain
        }
        return wm.injectLoraChain(baseWorkflow, loraChain, workflowType)
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

                    client.fetchImage(filename, subfolder, type) { bitmap ->
                        if (bitmap != null) {
                            // Store in memory - will be persisted to disk on onStop
                            MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap)

                            _uiState.value = _uiState.value.copy(
                                previewImage = bitmap,
                                previewImageFilename = filename,
                                previewImageSubfolder = subfolder,
                                previewImageType = type,
                                viewMode = ImageToImageViewMode.PREVIEW
                            )
                            onComplete(true)
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

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            previewImageFilename = null,
            previewImageSubfolder = null,
            previewImageType = null
        )
    }

    // Event listener management

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
                fetchGeneratedImage(event.promptId) { success ->
                    if (success) {
                        generationViewModelRef?.completeGeneration()
                    }
                    // If not successful, don't complete - will retry on next return
                }
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
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
                clearPreview()
            }
            else -> {}
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in memory - will be persisted to disk on onStop
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap)
    }
}
