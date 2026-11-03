package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
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
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.viewmodel.base.GenerationParamsState
import sh.hnet.comfychair.viewmodel.base.ParamsGenerationViewModel

/**
 * Represents a workflow item with display name
 */
data class TtvWorkflowItem(
    val id: String,             // Workflow ID for editor
    override val name: String,           // User-friendly workflow name
    override val displayName: String,    // Display name
    val type: WorkflowType      // TTV
) : WorkflowItemBase

/** Fallback values of the video screens; a workflow's saved values and template defaults come first. */
private val textToVideoDefaults = GenerationParams(width = "848", height = "480")

/**
 * UI state for the Text-to-Video screen
 */
data class TextToVideoUiState(
    val params: GenerationParams = textToVideoDefaults,
    // Preview state
    val previewBitmap: android.graphics.Bitmap? = null,
    val currentVideoUri: android.net.Uri? = null,
    // All result files of the last job, in server order (the first video is the one shown)
    val generationOutputs: List<GenerationOutput> = emptyList(),
    // Result shown in the preview (index into generationOutputs)
    val currentResultIndex: Int = 0,

    val availableWorkflows: List<TtvWorkflowItem> = emptyList(),

    // Fetch state
    val isFetching: Boolean = false
) : GenerationParamsState by params

/**
 * One-time events for Text-to-Video screen
 */
sealed class TextToVideoEvent {
    data class ShowToast(val messageResId: Int) : TextToVideoEvent()
    data class ShowToastMessage(val message: String) : TextToVideoEvent()
}

/**
 * ViewModel for the Text-to-Video screen
 */
class TextToVideoViewModel : ParamsGenerationViewModel<TextToVideoUiState, TextToVideoEvent>() {

    override val initialState = TextToVideoUiState()

    override fun TextToVideoUiState.params() = params
    override fun TextToVideoUiState.withParams(params: GenerationParams) = copy(params = params)
    override val fallbackParams get() = textToVideoDefaults

    override fun validateLength(length: String) = ValidationUtils.validateVideoLength(length, applicationContext)

    // Constants
    companion object {
        private const val TAG = "TextToVideo"
        const val OWNER_ID = "TEXT_TO_VIDEO"
        private const val PREFS_NAME = "TextToVideoFragmentPrefs"

        // Global preferences (camelCase keys for BackupManager compatibility)
        private const val KEY_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        private const val KEY_POSITIVE_PROMPT = "positivePrompt"
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
        restoreLastPreviewImage()
        loadLastGeneratedVideo()
        // Models are now loaded automatically via ConnectionManager
    }

    /**
     * Load the last generated video from cache.
     * This restores the video preview when the screen is recreated.
     * The video URI is prepared off the main thread and applied when ready.
     */
    private fun loadLastGeneratedVideo() {
        val context = applicationContext ?: return
        // Results strip of the last job (disk-first mode only)
        if (_uiState.value.generationOutputs.isEmpty()) {
            MediaStateHolder.loadGenerationOutputs(applicationContext, OWNER_ID)?.let { (outputs, index) ->
                _uiState.update { it.copy(generationOutputs = outputs, currentResultIndex = index) }
            }
        }
        val promptId = MediaStateHolder.getCurrentTtvPromptId() ?: return

        val key = MediaStateHolder.MediaKey.TtvVideo(promptId)
        if (MediaStateHolder.hasVideoBytes(key, context)) {
            viewModelScope.launch {
                val uri = MediaStateHolder.getVideoUri(context, key) ?: return@launch
                // Don't replace a video that arrived while the URI was being prepared
                _uiState.update { if (it.currentVideoUri == null) it.copy(currentVideoUri = uri) else it }
            }
        }
    }

    private fun loadWorkflows() {
        val ctx = applicationContext ?: return

        val workflows = WorkflowManager.getEnabledWorkflowsByType(WorkflowType.TTV)

        val unifiedWorkflows = workflows.map { workflow ->
            TtvWorkflowItem(
                id = workflow.id,
                name = workflow.name,
                displayName = workflow.name,
                type = WorkflowType.TTV
            )
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

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load global preferences with serverId prefix
        val savedWorkflowId = prefs.getString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", "") ?: ""
        val defaultPositivePrompt = SeasonalPrompts.getTextToVideoPrompt()
        val savedPositivePrompt = prefs.getString("${serverId}_$KEY_POSITIVE_PROMPT", null) ?: defaultPositivePrompt

        // Update positive prompt first
        updateParams(save = false) { it.copy(positivePrompt = savedPositivePrompt) }

        // Find workflow by ID
        val workflowItem = if (savedWorkflowId.isNotEmpty()) {
            _uiState.value.availableWorkflows.find { it.id == savedWorkflowId }
        } else {
            _uiState.value.availableWorkflows.find { it.name == _uiState.value.selectedWorkflow }
                ?: _uiState.value.availableWorkflows.firstOrNull()
        }

        // Load workflow values (single source of truth)
        if (workflowItem != null) {
            loadWorkflowValues(workflowItem.id, workflowItem.name)
        }
    }

    override fun saveConfiguration() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val state = _uiState.value

        // Save global preferences with serverId prefix
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", state.selectedWorkflowId)
            .putString("${serverId}_$KEY_POSITIVE_PROMPT", state.positivePrompt)
            .apply()

        // Save per-workflow values of the selected workflow
        saveWorkflowValues()
    }


    fun onWorkflowChange(workflow: String) {
        val workflowItem = _uiState.value.availableWorkflows.find { it.name == workflow } ?: return
        DebugLogger.d(TAG, "onWorkflowChange: ${Obfuscator.workflowName(workflow)}")
        switchWorkflow(workflowItem.id, workflowItem.name)
    }

    /**
     * Reset prompt to seasonal default.
     * Clears saved positive prompt and reloads with seasonal default.
     */
    fun resetPromptToDefault() {
        val ctx = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Clear saved positive prompt
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove("${serverId}_$KEY_POSITIVE_PROMPT").apply()
            }

            // Set prompt to seasonal default
            val defaultPrompt = SeasonalPrompts.getTextToVideoPrompt()
            updateParams(save = false) { it.copy(positivePrompt = defaultPrompt) }

            // Emit toast event
            _events.emit(TextToVideoEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }


    fun onPreviewBitmapChange(bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
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
    fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) {
            return
        }
        lastClearedForPromptId = promptId
        // Evict preview from cache so restoreLastPreviewImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(MediaStateHolder.MediaKey.TtvPreview)
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null, generationOutputs = emptyList(), currentResultIndex = 0)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentTtvPromptId()
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        _uiState.value = _uiState.value.copy(previewBitmap = null, currentVideoUri = null, generationOutputs = emptyList(), currentResultIndex = 0)
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        MediaStateHolder.clearCurrentTtvPromptId()
    }


    fun prepareWorkflow(): String? {
        val state = _uiState.value

        // Validate all fields
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        val width = state.width.toIntOrNull() ?: return null
        val height = state.height.toIntOrNull() ?: return null
        val length = state.length.toIntOrNull() ?: return null
        val fps = state.fps.toIntOrNull() ?: return null

        val baseWorkflow = WorkflowManager.prepareVideoWorkflowById(
            workflowId = state.selectedWorkflowId,
            positivePrompt = state.positivePrompt,
            negativePrompt = state.negativePrompt,
            // Single-model patterns (e.g., LTX 2.0)
            checkpoint = state.selectedCheckpoint,
            unet = state.selectedUnet,
            lora = state.selectedLoraName.takeIf { it.isNotEmpty() },
            // Dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = state.selectedHighnoiseUnet,
            lownoiseUnet = state.selectedLownoiseUnet,
            highnoiseLora = state.selectedHighnoiseLora,
            lownoiseLora = state.selectedLownoiseLora,
            // Common models
            vae = state.selectedVae,
            clip = state.selectedClip,
            clip1 = state.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = state.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = state.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = state.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoder = state.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = state.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            width = width,
            height = height,
            length = length,
            fps = fps,
            // Generation parameters
            steps = state.steps.toIntOrNull(),
            cfg = state.cfg.toFloatOrNull(),
            samplerName = state.sampler,
            scheduler = state.scheduler,
            denoise = state.denoise.toFloatOrNull(),
            megapixels = state.megapixels.toFloatOrNull(),
            seed = state.seed.toLongOrNull(),
            randomSeed = state.randomSeed,
            upscaleMethod = state.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = state.scaleBy.toFloatOrNull(),
            stopAtClipLayer = state.stopAtClipLayer.toIntOrNull()
        ) ?: return null

        // Inject LoRAs if configured
        var workflow = baseWorkflow

        // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
        if (state.loraChain.isNotEmpty()) {
            workflow = WorkflowManager.injectLoraChain(workflow, state.loraChain, sh.hnet.comfychair.WorkflowType.TTV)
        }

        // Dual-model LoRA chains for workflows like Wan 2.2
        if (state.highnoiseLoraChain.isNotEmpty()) {
            workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.highnoiseLoraChain, isHighNoise = true)
        }
        if (state.lownoiseLoraChain.isNotEmpty()) {
            workflow = WorkflowManager.injectAdditionalVideoLoras(workflow, state.lownoiseLoraChain, isHighNoise = false)
        }
        return workflow
    }

    override fun hasValidConfiguration(): Boolean =
        isReadyToGenerate(_uiState.value.params)

    // Event listener management

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     * @param generationViewModel The shared GenerationViewModel
     */
    fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel

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
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(TextToVideoEvent.ShowToastMessage(message))
                }
                // DON'T clear state - generation may still be running on server
            }
            is GenerationEvent.Error -> {
                viewModelScope.launch {
                    _events.emit(TextToVideoEvent.ShowToastMessage(event.message))
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

        _uiState.update { it.copy(isFetching = true) }

        VideoUtils.fetchVideoFromHistory(
            context = context,
            client = client,
            promptId = promptId,
            filePrefix = VideoUtils.FilePrefix.TEXT_TO_VIDEO
        ) { uri, outputs ->
            _uiState.update { it.copy(isFetching = false) }
            if (uri != null) {
                DebugLogger.i(TAG, "Video fetch successful")
                // Clear preview bitmap so video player takes display precedence
                _uiState.value = _uiState.value.copy(currentVideoUri = uri, previewBitmap = null, generationOutputs = outputs, currentResultIndex = 0)
                deleteLastPreviewImage()
                generationViewModelRef?.completeGeneration(promptId)
            } else {
                DebugLogger.w(TAG, "Video fetch failed")
            }
            // If uri is null, don't complete generation - will retry on next return
        }
    }

    private fun saveLastPreviewImage(bitmap: Bitmap) {
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.TtvPreview, bitmap, applicationContext)
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.TtvPreview, applicationContext)
        if (bitmap != null) {
            _uiState.value = _uiState.value.copy(previewBitmap = bitmap)
        }
    }

    private fun deleteLastPreviewImage() {
        // Remove from in-memory cache AND delete from disk
        // This prevents stale preview from being restored on app restart
        val context = applicationContext ?: return
        viewModelScope.launch {
            MediaStateHolder.evictAndDeleteFromDisk(context, MediaStateHolder.MediaKey.TtvPreview)
        }
    }
}
