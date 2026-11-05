package sh.hnet.comfychair.viewmodel.base

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.GenerationEvent
import sh.hnet.comfychair.viewmodel.GenerationScreenEvent
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.WorkflowItem

/** Fallback values of the video screens; a workflow's saved values and template defaults come first. */
val videoScreenDefaults = GenerationParams(width = "848", height = "480")

/**
 * Base ViewModel of the video screens (Text to Video, Image to Video): workflow list and preferences,
 * the live preview, fetching and restoring the finished video, and the LoRA chains video workflows use.
 * A video screen adds only what is its own (Image to Video: the source image) and prepares its workflow.
 */
abstract class BaseVideoGenerationViewModel<UiState> : BaseGenerationViewModel<UiState>() {

    protected abstract val tag: String
    /** Owner ID under which this screen receives generation events */
    protected abstract val ownerId: String
    protected abstract val workflowType: WorkflowType
    /** SharedPreferences file of the screen (global prompt and selected workflow, per server) */
    protected abstract val prefsName: String
    /** File name prefix of the screen's stored videos */
    protected abstract val videoFilePrefix: String
    protected abstract val previewKey: MediaStateHolder.MediaKey
    protected abstract fun videoKey(promptId: String): MediaStateHolder.MediaKey
    protected abstract fun currentVideoPromptId(): String?
    protected abstract fun clearCurrentVideoPromptId()
    protected abstract fun defaultPositivePrompt(): String

    protected abstract fun UiState.video(): VideoResult
    protected abstract fun UiState.withVideo(video: VideoResult): UiState
    protected abstract fun UiState.workflows(): List<WorkflowItem>
    protected abstract fun UiState.withWorkflows(workflows: List<WorkflowItem>): UiState

    override val fallbackParams: GenerationParams get() = videoScreenDefaults

    override fun validateLength(length: String) = ValidationUtils.validateVideoLength(length, applicationContext)

    private fun updateVideo(transform: (VideoResult) -> VideoResult) {
        _uiState.update { it.withVideo(transform(it.video())) }
    }

    init {
        // Dispatched (not immediate): the subclass's initialState doesn't exist until its constructor has run
        viewModelScope.launch(Dispatchers.Main) {
            // Keep the result list for process death (disk-first mode only, see MediaStateHolder)
            _uiState
                .map { it.video().generationOutputs to it.video().currentResultIndex }
                .distinctUntilChanged()
                .drop(1)
                .collect { (outputs, index) ->
                    MediaStateHolder.saveGenerationOutputs(applicationContext, ownerId, outputs, index)
                }
        }
        viewModelScope.launch(Dispatchers.Main) {
            // Refresh the list when workflows are added, updated, deleted or switched on/off
            WorkflowManager.workflowsVersion.collect { loadWorkflows() }
        }
    }

    override fun onInitialize() {
        DebugLogger.i(tag, "Initializing")
        loadWorkflows()
        restorePreferences()
        restoreLastPreviewImage()
        loadLastGeneratedVideo()
    }

    override fun onStoredMediaAvailable() {
        restoreLastPreviewImage()
        loadLastGeneratedVideo()
    }

    override fun onReloadSavedState() {
        _uiState.update { it.withParams(videoScreenDefaults) }
        loadWorkflows()
        restorePreferences()
    }

    /** The video shown is played from where it's kept now (RAM or the device), not from where it was. */
    override fun onMediaStorageChanged() {
        val context = applicationContext ?: return
        val key = videoKey(currentVideoPromptId() ?: return)
        viewModelScope.launch {
            val uri = MediaStateHolder.getVideoUri(context, key) ?: return@launch
            updateVideo { if (it.currentVideoUri != null) it.copy(currentVideoUri = uri) else it }
        }
    }

    override fun onClearShownMedia() {
        lastClearedForPromptId = null
        updateVideo { VideoResult() }
    }

    // Workflows and preferences

    private fun loadWorkflows() {
        if (applicationContext == null) return
        val workflows = WorkflowManager.getEnabledWorkflowsByType(workflowType)
            .map { WorkflowItem(id = it.id, name = it.name) }
            .sortedBy { it.displayName }
        val current = _uiState.value.params().selectedWorkflow
        val selected = workflows.find { it.name == current } ?: workflows.firstOrNull()

        _uiState.update { it.withWorkflows(workflows) }

        // Reload workflow values to refresh capability flags from WorkflowDefaults
        // This is important after backup restore when workflowsVersion triggers this function
        if (selected != null) {
            loadWorkflowValues(selected.id, selected.name)
        } else {
            updateParams(save = false) { it.copy(selectedWorkflow = "", selectedWorkflowId = "") }
        }
    }

    private fun restorePreferences() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Global preferences with serverId prefix
        val savedWorkflowId = prefs.getString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", "") ?: ""
        val savedPositivePrompt = prefs.getString("${serverId}_$KEY_POSITIVE_PROMPT", null) ?: defaultPositivePrompt()
        updateParams(save = false) { it.copy(positivePrompt = savedPositivePrompt) }

        // Saved workflow, else the current selection (set by loadWorkflows), else the first one
        val workflows = _uiState.value.workflows()
        val workflow = workflows.find { it.id == savedWorkflowId }
            ?: workflows.find { it.name == _uiState.value.params().selectedWorkflow }
            ?: workflows.firstOrNull()
        if (workflow != null) {
            loadWorkflowValues(workflow.id, workflow.name)
        }
    }

    override fun saveConfiguration() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val params = currentParams

        // Global preferences with serverId prefix
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("${serverId}_$KEY_SELECTED_WORKFLOW_ID", params.selectedWorkflowId)
            .putString("${serverId}_$KEY_POSITIVE_PROMPT", params.positivePrompt)
            .apply()

        // Per-workflow values of the selected workflow
        saveWorkflowValues()
    }

    override fun onWorkflowChange(workflowName: String) {
        val workflow = _uiState.value.workflows().find { it.name == workflowName } ?: return
        DebugLogger.d(tag, "onWorkflowChange: ${Obfuscator.workflowName(workflowName)}")
        switchWorkflow(workflow.id, workflow.name)
    }

    /**
     * Reset prompt to seasonal default.
     * Clears saved positive prompt and reloads with seasonal default.
     */
    override fun resetPromptToDefault() {
        val context = applicationContext ?: return
        val serverId = ConnectionManager.currentServerId ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    .edit().remove("${serverId}_$KEY_POSITIVE_PROMPT").apply()
            }
            val defaultPrompt = defaultPositivePrompt()
            updateParams(save = false) { it.copy(positivePrompt = defaultPrompt) }
            _events.emit(GenerationScreenEvent.ShowToast(R.string.msg_prompt_preset_reset_success))
        }
    }

    // Preview and results

    fun onPreviewBitmapChange(bitmap: Bitmap) {
        updateVideo { it.copy(previewBitmap = bitmap) }
        // Store in cache (memory or disk based on mode)
        MediaStateHolder.putBitmap(previewKey, bitmap, applicationContext)
    }

    /**
     * Select the result shown in the preview when a job produced several files.
     */
    fun onCurrentResultChange(index: Int) {
        updateVideo { it.copy(currentResultIndex = index.coerceIn(0, (it.generationOutputs.size - 1).coerceAtLeast(0))) }
    }

    /**
     * Clear preview for a specific execution. Only clears if this is a new promptId
     * to prevent duplicate clears when navigating back to the screen.
     */
    override fun clearPreviewForExecution(promptId: String) {
        if (promptId == lastClearedForPromptId) return
        lastClearedForPromptId = promptId
        // Evict preview from cache so restoreLastPreviewImage() won't restore the old preview
        // when navigating back to this screen during generation
        MediaStateHolder.evict(previewKey)
        updateVideo { VideoResult() }
        // Clear prompt ID tracking to prevent restoration on subsequent screen navigations
        clearCurrentVideoPromptId()
    }

    fun clearPreview() {
        lastClearedForPromptId = null // Reset tracking when manually clearing
        updateVideo { VideoResult() }
        clearCurrentVideoPromptId()
    }

    private fun restoreLastPreviewImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val bitmap = MediaStateHolder.getBitmap(previewKey, applicationContext) ?: return
        // Never over a preview or video already shown
        updateVideo { if (it.previewBitmap == null && it.currentVideoUri == null) it.copy(previewBitmap = bitmap) else it }
    }

    /**
     * Load the last generated video from cache, so the screen shows it again when recreated.
     * The video URI is prepared off the main thread and applied when ready.
     */
    private fun loadLastGeneratedVideo() {
        val context = applicationContext ?: return
        // Results strip of the last job (disk-first mode only)
        if (_uiState.value.video().generationOutputs.isEmpty()) {
            MediaStateHolder.loadGenerationOutputs(context, ownerId)?.let { (outputs, index) ->
                updateVideo { it.copy(generationOutputs = outputs, currentResultIndex = index) }
            }
        }
        val promptId = currentVideoPromptId() ?: return
        val key = videoKey(promptId)
        if (MediaStateHolder.hasVideoBytes(key, context)) {
            viewModelScope.launch {
                val uri = MediaStateHolder.getVideoUri(context, key) ?: return@launch
                // Don't replace a video that arrived while the URI was being prepared
                updateVideo { if (it.currentVideoUri == null) it.copy(currentVideoUri = uri) else it }
            }
        }
    }

    // Generation events

    /**
     * Start listening for generation events from the GenerationViewModel.
     * This registers this ViewModel as the active event handler.
     */
    override fun startListening(generationViewModel: GenerationViewModel) {
        generationViewModelRef = generationViewModel

        // Retry loading video if not loaded during initialize()
        // This handles the race condition where MediaStateHolder.loadFromDisk()
        // completes after initialize() but before startListening()
        if (_uiState.value.video().currentVideoUri == null) {
            loadLastGeneratedVideo()
        }

        generationViewModel.registerEventHandler(ownerId) { event ->
            handleGenerationEvent(event)
        }
    }

    /**
     * Stop listening for generation events.
     * Note: We keep the refs if generation is still running,
     * as the handler may still be called for completion events.
     */
    override fun stopListening(generationViewModel: GenerationViewModel) {
        generationViewModel.unregisterEventHandler(ownerId)
        // Only clear refs if no generation is active (handler was actually unregistered)
        if (!generationViewModel.generationState.value.isGenerating &&
            generationViewModelRef == generationViewModel
        ) {
            generationViewModelRef = null
        }
    }

    private fun handleGenerationEvent(event: GenerationEvent) {
        when (event) {
            is GenerationEvent.PreviewImage -> onPreviewBitmapChange(event.bitmap)
            is GenerationEvent.VideoGenerated -> {
                DebugLogger.i(tag, "VideoGenerated: ${Obfuscator.promptId(event.promptId)}")
                fetchGeneratedVideo(event.promptId)
            }
            is GenerationEvent.ConnectionLostDuringGeneration -> {
                // DON'T clear state - generation may still be running on server
                viewModelScope.launch {
                    val message = applicationContext?.getString(R.string.msg_connection_lost_generation_may_continue)
                        ?: "Connection lost. Will check for completion when reconnected."
                    _events.emit(GenerationScreenEvent.ShowToastMessage(message))
                }
            }
            is GenerationEvent.Error -> {
                // DON'T complete the generation - this may just be a connection error and the
                // server might still finish it
                viewModelScope.launch {
                    _events.emit(GenerationScreenEvent.ShowToastMessage(event.message))
                }
            }
            is GenerationEvent.ClearPreviewForResume -> {
                // Keep the restored preview visible until the video loads
            }
            else -> {}
        }
    }

    /**
     * Fetch the generated video from the server and show it.
     */
    private fun fetchGeneratedVideo(promptId: String) {
        val context = applicationContext ?: return
        val client = comfyUIClient ?: return

        updateVideo { it.copy(isFetching = true) }

        VideoUtils.fetchVideoFromHistory(
            context = context,
            client = client,
            promptId = promptId,
            filePrefix = videoFilePrefix
        ) { uri, outputs ->
            updateVideo { it.copy(isFetching = false) }
            if (uri != null) {
                DebugLogger.i(tag, "Video fetch successful")
                // Clear the preview frame so the video player takes display precedence
                updateVideo { VideoResult(currentVideoUri = uri, generationOutputs = outputs) }
                // Remove the stored preview too, so it isn't restored after a restart
                viewModelScope.launch { MediaStateHolder.evictAndDeleteFromDisk(context, previewKey) }
                generationViewModelRef?.completeGeneration(promptId)
            } else {
                // Generation isn't completed, so the fetch is retried on the next return
                DebugLogger.w(tag, "Video fetch failed")
            }
        }
    }

    private companion object {
        // Global preferences (camelCase keys for BackupManager compatibility)
        const val KEY_SELECTED_WORKFLOW_ID = "selectedWorkflowId"
        const val KEY_POSITIVE_PROMPT = "positivePrompt"
    }
}
