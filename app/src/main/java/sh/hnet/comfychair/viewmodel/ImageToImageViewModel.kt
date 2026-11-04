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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
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
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.util.GenerationOutputs
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.UuidUtils
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
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
 * Fallback values of the Editing mode; a workflow's saved values and template defaults come first.
 */
private val editingDefaults = GenerationParams(megapixels = "2.0", steps = "4", cfg = "1.0", scheduler = "simple")

/**
 * UI state for the Image-to-image screen.
 *
 * Each mode has its own workflow and generation parameters ([inpaintingParams], [editingParams]); the
 * positive prompt is shared. The state exposes the current mode's params directly through delegation, so
 * `state.steps` etc. always read the mode on screen. Field visibility is controlled by the workflow's
 * placeholders (`capabilities`).
 */
data class ImageToImageUiState(
    val mode: ImageToImageMode = ImageToImageMode.EDITING,
    val inpaintingParams: GenerationParams = GenerationParams(),
    val editingParams: GenerationParams = editingDefaults,

    // View state
    val viewMode: ImageToImageViewMode = ImageToImageViewMode.SOURCE,
    val sourceImage: Bitmap? = null,
    val previewImage: Bitmap? = null,
    val maskPaths: List<MaskPathData> = emptyList(),
    val brushSize: Float = 50f,
    val isEraserMode: Boolean = false,

    // All result files of the last job, in server order (the first image is the one shown)
    val generationOutputs: List<GenerationOutput> = emptyList(),
    // Result shown in the preview (index into generationOutputs)
    val currentResultIndex: Int = 0,

    // Preview image file info (for metadata extraction)
    val previewImageFilename: String? = null,
    val previewImageSubfolder: String? = null,
    val previewImageType: String? = null,

    // Workflows offered in each mode
    val availableWorkflows: List<WorkflowItem> = emptyList(),
    val editingWorkflows: List<WorkflowItem> = emptyList(),

    // Reference images (Editing mode)
    val referenceImage1: Bitmap? = null,
    val referenceImage2: Bitmap? = null,

    val isUploading: Boolean = false,
    val isFetching: Boolean = false
) : CommonGenerationState by (if (mode == ImageToImageMode.EDITING) editingParams else inpaintingParams) {

    /** Workflows offered in the current mode. */
    val currentModeWorkflows: List<WorkflowItem>
        get() = if (mode == ImageToImageMode.EDITING) editingWorkflows else availableWorkflows
}

/**
 * ViewModel for the Image-to-image screen
 */
class ImageToImageViewModel : BaseGenerationViewModel<ImageToImageUiState>() {

    override val initialState = ImageToImageUiState()

    override fun ImageToImageUiState.params() =
        if (mode == ImageToImageMode.EDITING) editingParams else inpaintingParams

    // The positive prompt is shared by both modes
    override fun ImageToImageUiState.withParams(params: GenerationParams) =
        if (mode == ImageToImageMode.EDITING) {
            copy(editingParams = params, inpaintingParams = inpaintingParams.copy(positivePrompt = params.positivePrompt))
        } else {
            copy(inpaintingParams = params, editingParams = editingParams.copy(positivePrompt = params.positivePrompt))
        }

    override fun ImageToImageUiState.mapAllParams(transform: (GenerationParams) -> GenerationParams) =
        copy(inpaintingParams = transform(inpaintingParams), editingParams = transform(editingParams))

    override val fallbackParams: GenerationParams
        get() = if (_uiState.value.mode == ImageToImageMode.EDITING) editingDefaults else GenerationParams()

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
        // Keep the result list for process death (disk-first mode only, see MediaStateHolder)
        viewModelScope.launch {
            _uiState
                .map { it.generationOutputs to it.currentResultIndex }
                .distinctUntilChanged()
                .drop(1)
                .collect { (outputs, index) ->
                    MediaStateHolder.saveGenerationOutputs(applicationContext, OWNER_ID, outputs, index)
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
        // Load inpainting workflows (ITI_INPAINTING)
        val inpaintingWorkflows = WorkflowManager.getEnabledWorkflowsByType(WorkflowType.ITI_INPAINTING)
            .map { WorkflowItem(id = it.id, name = it.name) }
            .sortedBy { it.displayName }
        // Load editing workflows (ITI_EDITING)
        val editingWorkflows = WorkflowManager.getEnabledWorkflowsByType(WorkflowType.ITI_EDITING)
            .map { WorkflowItem(id = it.id, name = it.name) }
            .sortedBy { it.displayName }

        val state = _uiState.value
        val inpaintingItem = inpaintingWorkflows.find { it.name == state.inpaintingParams.selectedWorkflow }
            ?: inpaintingWorkflows.firstOrNull()
        val editingItem = editingWorkflows.find { it.name == state.editingParams.selectedWorkflow }
            ?: editingWorkflows.firstOrNull()

        _uiState.update { it.copy(availableWorkflows = inpaintingWorkflows, editingWorkflows = editingWorkflows) }

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        loadModeWorkflows(inpaintingItem, editingItem)
    }

    /** Load each mode's workflow values (or clear the selection when the mode has no workflow). */
    private fun loadModeWorkflows(inpaintingItem: WorkflowItem?, editingItem: WorkflowItem?) {
        _uiState.update { state ->
            state.copy(
                inpaintingParams = inpaintingItem
                    ?.let { paramsForWorkflow(state.inpaintingParams, it.id, it.name, GenerationParams()) }
                    ?: state.inpaintingParams.copy(selectedWorkflow = "", selectedWorkflowId = ""),
                editingParams = editingItem
                    ?.let { paramsForWorkflow(state.editingParams, it.id, it.name, editingDefaults) }
                    ?: state.editingParams.copy(selectedWorkflow = "", selectedWorkflowId = "")
            )
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

        // Mode and the shared positive prompt first
        _uiState.update {
            it.copy(
                mode = mode,
                inpaintingParams = it.inpaintingParams.copy(positivePrompt = positivePrompt),
                editingParams = it.editingParams.copy(positivePrompt = positivePrompt)
            )
        }

        // Saved workflow of each mode, else the current selection (set by loadWorkflows), else the first one
        val state = _uiState.value
        val inpaintingItem = state.availableWorkflows.find { it.id == savedWorkflowId }
            ?: state.availableWorkflows.find { it.name == state.inpaintingParams.selectedWorkflow }
            ?: state.availableWorkflows.firstOrNull()
        val editingItem = state.editingWorkflows.find { it.id == savedEditingWorkflowId }
            ?: state.editingWorkflows.find { it.name == state.editingParams.selectedWorkflow }
            ?: state.editingWorkflows.firstOrNull()

        loadModeWorkflows(inpaintingItem, editingItem)
    }

    override fun saveConfiguration() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Save global preferences with serverId prefix
        prefs.edit()
            .putString("${serverId}_$PREF_POSITIVE_PROMPT", state.positivePrompt)
            .putString("${serverId}_$PREF_MODE", state.mode.name)
            .putString("${serverId}_$PREF_SELECTED_WORKFLOW_ID", state.inpaintingParams.selectedWorkflowId)
            .putString("${serverId}_$PREF_SELECTED_EDITING_WORKFLOW_ID", state.editingParams.selectedWorkflowId)
            .apply()

        // Per-workflow values of both modes' workflows (the positive prompt change touches both)
        saveWorkflowValues(state.inpaintingParams)
        saveWorkflowValues(state.editingParams)
    }

    private fun loadSavedImages() {
        // Results strip of the last job (disk-first mode only)
        if (_uiState.value.generationOutputs.isEmpty()) {
            MediaStateHolder.loadGenerationOutputs(applicationContext, OWNER_ID)?.let { (outputs, index) ->
                _uiState.update { it.copy(generationOutputs = outputs, currentResultIndex = index) }
            }
        }
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiSource, applicationContext)
        val previewImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItiPreview, applicationContext)
        val referenceImage1 = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.IteReferenceImage1, applicationContext)
        val referenceImage2 = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.IteReferenceImage2, applicationContext)

        // Fill in only what isn't shown yet: an image chosen or generated meanwhile wins
        _uiState.update {
            it.copy(
                sourceImage = it.sourceImage ?: sourceImage,
                previewImage = it.previewImage ?: previewImage,
                referenceImage1 = it.referenceImage1 ?: referenceImage1,
                referenceImage2 = it.referenceImage2 ?: referenceImage2
            )
        }
    }

    override fun onStoredMediaAvailable() = loadSavedImages()


    // View mode
    fun onViewModeChange(mode: ImageToImageViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    // Image-to-Image mode (Inpainting vs Editing)
    fun onModeChange(mode: ImageToImageMode) {
        // Each mode keeps its own workflow and values, so switching only changes which ones are shown
        _uiState.update { it.copy(mode = mode) }
        saveConfiguration()
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
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
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
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
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

    /**
     * The reference slot a result could fill: in Editing mode, the first slot the workflow uses that is still empty.
     * Null when there is none, which hides "Use as reference".
     */
    fun freeReferenceSlot(state: ImageToImageUiState): Int? = when {
        state.mode != ImageToImageMode.EDITING -> null
        state.capabilities.hasReferenceImage1 && state.referenceImage1 == null -> 1
        state.capabilities.hasReferenceImage2 && state.referenceImage2 == null -> 2
        else -> null
    }

    /** Put a generated image into the first free reference slot (see [freeReferenceSlot]). */
    fun useAsReference(bitmap: Bitmap) {
        val context = applicationContext ?: return
        when (freeReferenceSlot(_uiState.value)) {
            1 -> {
                MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.IteReferenceImage1, bitmap, context)
                _uiState.update { it.copy(referenceImage1 = bitmap) }
            }
            2 -> {
                MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.IteReferenceImage2, bitmap, context)
                _uiState.update { it.copy(referenceImage2 = bitmap) }
            }
        }
    }

    /**
     * Use a generated image as the source image (clearing the mask) and switch to the Source tab.
     *
     * May run before the screen was first opened, i.e. before [initialize]: the context is then
     * passed in so the image is stored where [loadSavedImages] will read it, otherwise the
     * initialization would bring back the previous source.
     */
    fun useAsSource(context: Context, bitmap: Bitmap) {
        val appContext = applicationContext ?: context.applicationContext
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiSource, bitmap, appContext)
        _uiState.update {
            it.copy(sourceImage = bitmap, maskPaths = emptyList(), viewMode = ImageToImageViewMode.SOURCE)
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
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
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

    /**
     * Workflow selection in the current mode.
     */
    override fun onWorkflowChange(workflowName: String) {
        val workflowItem = _uiState.value.currentModeWorkflows.find { it.name == workflowName } ?: return
        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")
        switchWorkflow(workflowItem.id, workflowItem.name)
    }

    /**
     * Reset prompt to seasonal default.
     * Clears saved positive prompt and reloads with seasonal default.
     */
    override fun resetPromptToDefault() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Clear saved positive prompt
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove("${serverId}_$PREF_POSITIVE_PROMPT").apply()
            }

            // Set prompt to seasonal default
            val defaultPrompt = SeasonalPrompts.getImageToImagePrompt()
            updateParams(save = false) { it.copy(positivePrompt = defaultPrompt) }

            // Emit toast event
            _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    // Validation

    override fun hasValidConfiguration(): Boolean {
        val state = _uiState.value
        // Both modes need a source image (Inpainting also a mask, checked when the workflow is prepared)
        return state.sourceImage != null && isReadyToGenerate(state.params())
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
                client.uploadImage(sourceBytes, UuidUtils.generateUniqueUploadFilename("editing_source")) { success, filename, _, failureType ->
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
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
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
                    client.uploadImage(ref1Bytes, UuidUtils.generateUniqueUploadFilename("reference_1")) { success, filename, _, failureType ->
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
                    client.uploadImage(ref2Bytes, UuidUtils.generateUniqueUploadFilename("reference_2")) { success, filename, _, failureType ->
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

        val p = state.editingParams
        return WorkflowManager.prepareWorkflow(
            p.selectedWorkflowId, p,
            WorkflowManager.WorkflowImages(source = uploadedSource, reference1 = uploadedRef1, reference2 = uploadedRef2)
        )
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
            _events.emit(GenerationScreenEvent.ShowToast(R.string.hint_paint_mask))
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
                client.uploadImage(imageBytes, UuidUtils.generateUniqueUploadFilename("inpaint_source")) { success, filename, _, failureType ->
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
                _events.emit(GenerationScreenEvent.ShowToast(R.string.error_save_image))
            }
            return null
        }
        val uploadedFilename = uploadResult.filename

        val p = state.inpaintingParams
        return WorkflowManager.prepareWorkflow(
            p.selectedWorkflowId, p, WorkflowManager.WorkflowImages(source = uploadedFilename)
        )
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

        _uiState.update { it.copy(isFetching = true) }

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) {
                _uiState.update { it.copy(isFetching = false) }
                onComplete(false)
                return@fetchHistory
            }

            val outputs = GenerationOutputs.fromHistory(historyJson, promptId)
            val image = outputs.firstOrNull { !it.isVideo }
            if (image != null) {
                val filename = image.filename
                val subfolder = image.subfolder
                val type = image.type

                client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                    _uiState.update { it.copy(isFetching = false) }
                    if (bitmap != null) {
                        // Store in cache (memory or disk based on mode)
                        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItiPreview, bitmap, context)

                        _uiState.value = _uiState.value.copy(
                            previewImage = bitmap,
                            generationOutputs = outputs,
                            currentResultIndex = 0,
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

            _uiState.update { it.copy(isFetching = false) }
            onComplete(false)
        }
    }

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewImage = bitmap)
        saveLastPreviewImage(bitmap)
    }

    /**
     * Select the result shown in the preview when a job produced several files.
     */
    fun onCurrentResultChange(index: Int) {
        _uiState.update { it.copy(currentResultIndex = index.coerceIn(0, (it.generationOutputs.size - 1).coerceAtLeast(0))) }
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    override fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return // Already cleared for this promptId
        }
        lastClearedForPromptId = promptId
        // Evict from cache so loadSavedImages() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.ItiPreview)
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            generationOutputs = emptyList(),
            currentResultIndex = 0,
            previewImageFilename = null,
            previewImageSubfolder = null,
            previewImageType = null
        )
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(
            previewImage = null,
            generationOutputs = emptyList(),
            currentResultIndex = 0,
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
    override fun startListening(generationViewModel: GenerationViewModel) {
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
    override fun stopListening(generationViewModel: GenerationViewModel) {
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
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(GenerationScreenEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(GenerationScreenEvent.ShowToastMessage(event.message))
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
