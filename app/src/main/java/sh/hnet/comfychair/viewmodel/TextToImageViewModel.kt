package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel
import java.io.File
import java.io.IOException

/**
 * UI state for the Text-to-Image screen.
 *
 * Generation parameters live in [params] (shared with the other screens, see [GenerationParams]); the state
 * exposes them directly through delegation, so `state.steps` etc. read from params. Field visibility is
 * controlled by the workflow's placeholders (`capabilities`), not by the screen.
 */
data class TextToImageUiState(
    val params: GenerationParams = GenerationParams(),
    val availableWorkflows: List<WorkflowItem> = emptyList(),

    // Generated image (preview)
    val previewBitmap: Bitmap? = null,

    // All result files of the last job, in server order (the first image is the one shown)
    val generationOutputs: List<GenerationOutput> = emptyList(),
    // Result shown in the preview (index into generationOutputs)
    val currentResultIndex: Int = 0,

    // Current image file info (for metadata extraction)
    val currentImageFilename: String? = null,
    val currentImageSubfolder: String? = null,
    val currentImageType: String? = null,

    val isFetching: Boolean = false
) : CommonGenerationState by params

/**
 * ViewModel for the Text-to-Image screen.
 * Manages configuration state, model selection, and image generation.
 */
class TextToImageViewModel : BaseGenerationViewModel<TextToImageUiState>() {

    override val initialState = TextToImageUiState()

    override fun TextToImageUiState.params() = params
    override fun TextToImageUiState.withParams(params: GenerationParams) = copy(params = params)

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

        val workflows = WorkflowManager.getEnabledWorkflowsByType(WorkflowType.TTI)

        // Create workflow list
        val unifiedWorkflows = workflows.map { workflow ->
            WorkflowItem(id = workflow.id, name = workflow.name)
        }

        val sortedWorkflows = unifiedWorkflows.sortedBy { it.displayName }
        val currentSelection = _uiState.value.selectedWorkflow
        val selectedWorkflowItem = if (currentSelection.isEmpty())
            sortedWorkflows.firstOrNull()
        else
            sortedWorkflows.find { it.name == currentSelection } ?: sortedWorkflows.firstOrNull()

        _uiState.update { it.copy(availableWorkflows = sortedWorkflows) }

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        if (selectedWorkflowItem != null) {
            loadWorkflowValues(selectedWorkflowItem.id, selectedWorkflowItem.name)
        } else {
            updateParams(save = false) { it.copy(selectedWorkflow = "", selectedWorkflowId = "") }
        }
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
            val defaultPrompt = SeasonalPrompts.getTextToImagePrompt()
            updateParams(save = false) { it.copy(positivePrompt = defaultPrompt) }

            // Emit toast event
            _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    /**
     * Unified workflow change - loads values for the new workflow
     */
    override fun onWorkflowChange(workflowName: String) {
        val workflowItem = _uiState.value.availableWorkflows.find { it.name == workflowName } ?: return
        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")
        switchWorkflow(workflowItem.id, workflowItem.name)
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
        // Evict from cache so restoreLastGeneratedImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.TtiPreview)
        _uiState.value = _uiState.value.copy(
            previewBitmap = null,
            generationOutputs = emptyList(),
            currentResultIndex = 0,
            currentImageFilename = null,
            currentImageSubfolder = null,
            currentImageType = null
        )
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(
            previewBitmap = null,
            generationOutputs = emptyList(),
            currentResultIndex = 0,
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
    override fun startListening(generationViewModel: GenerationViewModel) {
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
    override fun stopListening(generationViewModel: GenerationViewModel) {
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
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(GenerationScreenEvent.ShowToastMessage(message))
                }
            }
            is GenerationEvent.Error -> {
                DebugLogger.e(TAG, "Generation error")
                viewModelScope.launch {
                    _events.emit(GenerationScreenEvent.ShowToastMessage(event.message))
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
     */
    override fun hasValidConfiguration(): Boolean = isReadyToGenerate(_uiState.value.params)

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
            textEncoder = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            lora = state.selectedLoraName.takeIf { it.isNotEmpty() },
            highnoiseUnet = state.selectedHighnoiseUnet,
            lownoiseUnet = state.selectedLownoiseUnet,
            highnoiseLora = state.selectedHighnoiseLora,
            lownoiseLora = state.selectedLownoiseLora,
            // Unified generation parameters
            width = state.width.toIntOrNull() ?: 1024,
            height = state.height.toIntOrNull() ?: 1024,
            megapixels = state.megapixels.toFloatOrNull(),
            length = state.length.toIntOrNull(),
            frameRate = state.fps.toIntOrNull(),
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

        _uiState.update { it.copy(isFetching = true) }

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson != null) {
                try {
                    val outputs = GenerationOutputs.fromHistory(historyJson, promptId)
                    outputs.firstOrNull { !it.isVideo }?.let { image ->
                        val filename = image.filename
                        val subfolder = image.subfolder
                        val type = image.type

                        client.fetchImage(filename, subfolder, type) { bitmap, failureType ->
                            viewModelScope.launch {
                                _uiState.update { it.copy(isFetching = false) }
                                if (bitmap != null) {
                                    setCurrentImage(bitmap, filename, subfolder, type)
                                    _uiState.update { it.copy(generationOutputs = outputs, currentResultIndex = 0) }
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
                    _uiState.update { it.copy(isFetching = false) }
                    onComplete(false)
                } catch (_: Exception) {
                    _uiState.update { it.copy(isFetching = false) }
                    onComplete(false)
                }
            } else {
                _uiState.update { it.copy(isFetching = false) }
                onComplete(false)
            }
        }
    }

    // Persistence

    override fun saveConfiguration() {
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

        // Save per-workflow values for the currently selected workflow
        saveWorkflowValues()
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
        updateParams(save = false) { it.copy(positivePrompt = positivePrompt) }

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

        // Load workflow values without saving (to avoid circular save)
        if (workflowToLoad != null) {
            loadWorkflowValues(workflowToLoad.id, workflowToLoad.name)
        }
    }

    private fun saveLastGeneratedImage(bitmap: Bitmap) {
        // Store in memory (or disk if disk-first mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtiPreview, bitmap, applicationContext)
    }

    private fun restoreLastGeneratedImage() {
        // Results strip of the last job (disk-first mode only)
        if (_uiState.value.generationOutputs.isEmpty()) {
            MediaStateHolder.loadGenerationOutputs(applicationContext, OWNER_ID)?.let { (outputs, index) ->
                _uiState.update { it.copy(generationOutputs = outputs, currentResultIndex = index) }
            }
        }
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtiPreview, applicationContext)
        if (bitmap != null) {
            // Never over an image already shown
            _uiState.update { if (it.previewBitmap == null) it.copy(previewBitmap = bitmap) else it }
        }
    }

    override fun onStoredMediaAvailable() = restoreLastGeneratedImage()

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
