package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator

/**
 * Generation state data class
 */
data class GenerationState(
    val isGenerating: Boolean = false,
    val promptId: String? = null,
    val progress: Int = 0,
    val maxProgress: Int = 100,
    val ownerId: String? = null,
    val contentType: ContentType = ContentType.IMAGE
)

/**
 * Connection state enum
 */
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Content type for generation (determines which event to dispatch on completion)
 */
enum class ContentType {
    IMAGE,
    VIDEO
}

/**
 * One-time events emitted during generation
 */
sealed class GenerationEvent {
    data class ImageGenerated(val promptId: String) : GenerationEvent()
    data class VideoGenerated(val promptId: String) : GenerationEvent()
    data class PreviewImage(val bitmap: Bitmap) : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
    data object GenerationCancelled : GenerationEvent()
    data object ConnectionLostDuringGeneration : GenerationEvent()
    data object ClearPreviewForResume : GenerationEvent()
}

/**
 * Central ViewModel for managing generation state.
 *
 * WebSocket Architecture:
 * - ConnectionManager owns the WebSocket connection and broadcasts parsed events
 * - This ViewModel subscribes to ConnectionManager.webSocketMessages SharedFlow
 * - Multiple instances can safely coexist (e.g., MainContainer + GalleryContainer)
 * - Only the instance whose ownerId matches the generation state processes events
 */
class GenerationViewModel : ViewModel() {

    // Application context for gallery repository
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Connection state (derived from WebSocket state)
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // Generation state
    private val _generationState = MutableStateFlow(GenerationState())
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    // One-time events
    private val _events = MutableSharedFlow<GenerationEvent>()
    val events: SharedFlow<GenerationEvent> = _events.asSharedFlow()

    // Active event handler for screen-specific event delivery
    private var activeEventHandler: ((GenerationEvent) -> Unit)? = null
    private var activeEventHandlerOwnerId: String? = null  // Track who registered the handler
    private var generationOwnerId: String? = null
    private var pendingCompletion: GenerationEvent? = null  // ImageGenerated or VideoGenerated
    private var bufferedPreview: Bitmap? = null  // Buffer for preview when no handler registered
    private var pendingClearPreview: Boolean = false  // Buffer for ClearPreviewForResume when no handler

    // Polling for completion when resuming a generation
    private var completionPollingJob: Job? = null

    // Subscription jobs
    private var webSocketMessageJob: Job? = null
    private var webSocketStateJob: Job? = null

    companion object {
        private const val TAG = "Generation"
        private const val PREFS_NAME = "GenerationViewModelPrefs"
        private const val PREF_IS_GENERATING = "isGenerating"
        private const val PREF_CURRENT_PROMPT_ID = "currentPromptId"
        private const val PREF_OWNER_ID = "ownerId"
        private const val PREF_CONTENT_TYPE = "contentType"
        private const val COMPLETION_POLLING_INTERVAL_MS = 3000L
    }

    /**
     * Initialize the ViewModel with context.
     * Connection is managed by ConnectionManager.
     */
    fun initialize(context: Context) {
        if (this.applicationContext != null) {
            // Already initialized
            DebugLogger.d(TAG, "Already initialized, skipping")
            return
        }

        DebugLogger.i(TAG, "Initializing")
        this.applicationContext = context.applicationContext

        // Restore generation state
        restoreGenerationState(context)

        // Subscribe to ConnectionManager's WebSocket flows
        subscribeToWebSocketMessages()
        subscribeToWebSocketState()

        // Open WebSocket connection via ConnectionManager
        if (ConnectionManager.isConnected) {
            ConnectionManager.openWebSocket()
        } else {
            _connectionStatus.value = ConnectionStatus.FAILED
        }

        // If we restored an active generation, check completion immediately
        if (_generationState.value.isGenerating) {
            DebugLogger.d(TAG, "Restored active generation, checking completion immediately")
            checkServerForCompletion()
        }
    }

    /**
     * Subscribe to WebSocket messages from ConnectionManager
     */
    private fun subscribeToWebSocketMessages() {
        webSocketMessageJob?.cancel()
        webSocketMessageJob = viewModelScope.launch {
            ConnectionManager.webSocketMessages.collect { message ->
                handleWebSocketMessage(message)
            }
        }
    }

    /**
     * Subscribe to WebSocket state changes from ConnectionManager
     */
    private fun subscribeToWebSocketState() {
        webSocketStateJob?.cancel()
        webSocketStateJob = viewModelScope.launch {
            ConnectionManager.webSocketState.collect { state ->
                when (state) {
                    is WebSocketState.Connected -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        // Check for pending completion when reconnecting
                        if (_generationState.value.isGenerating) {
                            DebugLogger.d(TAG, "WebSocket reconnected, checking for completion")
                            checkServerForCompletion()
                        }
                    }
                    is WebSocketState.Connecting -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTING
                    }
                    is WebSocketState.Reconnecting -> {
                        _connectionStatus.value = ConnectionStatus.RECONNECTING
                        // Notify user if generation is in progress
                        if (_generationState.value.isGenerating) {
                            applicationContext?.let { saveGenerationState(it) }
                            dispatchEvent(GenerationEvent.ConnectionLostDuringGeneration)
                        }
                    }
                    is WebSocketState.Failed -> {
                        _connectionStatus.value = ConnectionStatus.FAILED
                    }
                    is WebSocketState.Disconnected -> {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    }
                }
            }
        }
    }

    /**
     * Handle parsed WebSocket message from ConnectionManager
     */
    private fun handleWebSocketMessage(message: WebSocketMessage) {
        val currentState = _generationState.value

        when (message) {
            is WebSocketMessage.ExecutionComplete -> {
                if (message.promptId == currentState.promptId && currentState.isGenerating) {
                    DebugLogger.i(TAG, "Generation complete (promptId: ${Obfuscator.promptId(message.promptId)})")
                    dispatchCompletionEvent(message.promptId, currentState.contentType)
                }
            }
            is WebSocketMessage.ExecutionSuccess -> {
                if (message.promptId == currentState.promptId && currentState.isGenerating) {
                    DebugLogger.i(TAG, "Generation complete via execution_success (promptId: ${Obfuscator.promptId(message.promptId)})")
                    dispatchCompletionEvent(message.promptId, currentState.contentType)
                }
            }
            is WebSocketMessage.Progress -> {
                if (currentState.isGenerating) {
                    _generationState.value = currentState.copy(
                        progress = message.value,
                        maxProgress = message.max
                    )
                }
            }
            is WebSocketMessage.PreviewImage -> {
                // Only process previews if we're generating and live preview is enabled
                val context = applicationContext
                if (currentState.isGenerating && context != null && AppSettings.isLivePreviewEnabled(context)) {
                    dispatchEvent(GenerationEvent.PreviewImage(message.bitmap))
                }
            }
            is WebSocketMessage.ExecutionError -> {
                if (message.promptId == currentState.promptId || message.promptId == null) {
                    resetGenerationState()
                    val errorMessage = applicationContext?.getString(R.string.error_generation_failed)
                        ?: "Generation failed"
                    dispatchEvent(GenerationEvent.Error(errorMessage))
                }
            }
            // Informational messages - no action needed
            is WebSocketMessage.ExecutionStart -> {}
            is WebSocketMessage.Executing -> {}
            is WebSocketMessage.ExecutionCached -> {}
            is WebSocketMessage.Status -> {}
            is WebSocketMessage.Unknown -> {}
        }
    }

    /**
     * Get the ComfyUI client instance
     */
    fun getClient(): ComfyUIClient? = comfyUIClient

    /**
     * Get the hostname from ConnectionManager
     */
    fun getHostname(): String = ConnectionManager.hostname

    /**
     * Get the port from ConnectionManager
     */
    fun getPort(): Int = ConnectionManager.port

    /**
     * Register an event handler for a specific owner (screen).
     * Only the owner that started generation will receive events.
     * If there's a buffered preview or pending completion, it will be delivered immediately.
     */
    fun registerEventHandler(ownerId: String, handler: (GenerationEvent) -> Unit) {
        // Only allow registration if this owner started generation OR no generation is active
        if (ownerId == generationOwnerId || generationOwnerId == null) {
            activeEventHandler = handler
            activeEventHandlerOwnerId = ownerId

            // Deliver pending ClearPreviewForResume if exists (must be before preview delivery)
            if (pendingClearPreview) {
                viewModelScope.launch { handler(GenerationEvent.ClearPreviewForResume) }
                pendingClearPreview = false
            }

            // Deliver buffered preview if exists
            bufferedPreview?.let { bitmap ->
                viewModelScope.launch { handler(GenerationEvent.PreviewImage(bitmap)) }
                bufferedPreview = null
            }

            // Deliver pending completion if exists
            pendingCompletion?.let { completion ->
                viewModelScope.launch { handler(completion) }
                pendingCompletion = null
            }

            // If generation is active but completion wasn't detected yet,
            // trigger a server check in case completion happened before handler registered
            val state = _generationState.value
            if (state.isGenerating && pendingCompletion == null && ownerId == state.ownerId) {
                DebugLogger.d(TAG, "Handler registered during active generation, verifying status")
                checkServerForCompletion()
            }
        }
    }

    /**
     * Unregister the event handler for a specific owner.
     * Only clears the handler if this owner actually registered it.
     * If the owner started generation that's still running, keep handler active for pending completion.
     */
    fun unregisterEventHandler(ownerId: String) {
        // Only clear handler if THIS owner registered it
        if (activeEventHandlerOwnerId != ownerId) {
            return  // Another screen registered the handler, don't clear it
        }

        // Don't clear handler if this owner started generation that's still running
        if (generationOwnerId == ownerId && _generationState.value.isGenerating) {
            return
        }

        activeEventHandler = null
        activeEventHandlerOwnerId = null
    }

    /**
     * Dispatch event to active handler, falling back to SharedFlow.
     * If no handler is active, buffer previews and store completion events for later.
     * Handler is invoked on the main thread to ensure proper UI state updates.
     */
    private fun dispatchEvent(event: GenerationEvent) {
        val handler = activeEventHandler

        if (handler != null) {
            viewModelScope.launch { handler(event) }
            if (event is GenerationEvent.PreviewImage) {
                bufferedPreview = null
            }
        } else {
            // No handler - buffer for later delivery
            when (event) {
                is GenerationEvent.PreviewImage -> bufferedPreview = event.bitmap
                is GenerationEvent.ImageGenerated, is GenerationEvent.VideoGenerated -> pendingCompletion = event
                is GenerationEvent.ClearPreviewForResume -> pendingClearPreview = true
                else -> {}
            }
        }
        // Also emit to SharedFlow for backwards compatibility
        viewModelScope.launch { _events.emit(event) }
    }

    // Content type for current generation (used in submitWorkflow)
    private var generationContentType: ContentType = ContentType.IMAGE

    /**
     * Start generation with the given workflow JSON
     * @param workflowJson The workflow JSON to submit
     * @param ownerId The ID of the screen/ViewModel that owns this generation (e.g., "TEXT_TO_IMAGE")
     * @param contentType The type of content being generated (IMAGE or VIDEO)
     * @param onResult Callback with result
     */
    fun startGeneration(
        workflowJson: String,
        ownerId: String,
        contentType: ContentType = ContentType.IMAGE,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        DebugLogger.i(TAG, "Starting generation (owner: $ownerId, type: $contentType)")
        comfyUIClient ?: run {
            DebugLogger.e(TAG, "Cannot start generation: client not initialized")
            onResult(false, null, applicationContext?.getString(R.string.error_client_not_initialized) ?: "Client not initialized")
            return
        }

        // Store the owner and content type before starting generation
        generationOwnerId = ownerId
        generationContentType = contentType

        if (!ConnectionManager.isWebSocketConnected) {
            DebugLogger.d(TAG, "WebSocket not connected, opening connection...")
            ConnectionManager.openWebSocket()

            viewModelScope.launch {
                delay(2000)
                if (ConnectionManager.isWebSocketConnected) {
                    submitWorkflow(workflowJson, onResult)
                } else {
                    DebugLogger.e(TAG, "WebSocket connection failed")
                    onResult(false, null, applicationContext?.getString(R.string.error_websocket_not_connected) ?: "WebSocket not connected")
                }
            }
        } else {
            submitWorkflow(workflowJson, onResult)
        }
    }

    /**
     * Submit workflow to server
     */
    private fun submitWorkflow(
        workflowJson: String,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        val client = comfyUIClient ?: return

        DebugLogger.d(TAG, "Submitting workflow to server")
        val mainHandler = Handler(Looper.getMainLooper())
        client.submitPrompt(workflowJson) { success, promptId, errorMessage ->
            if (success && promptId != null) {
                DebugLogger.i(TAG, "Workflow submitted successfully (promptId: ${Obfuscator.promptId(promptId)})")
                _generationState.value = GenerationState(
                    isGenerating = true,
                    promptId = promptId,
                    progress = 0,
                    maxProgress = 100,
                    ownerId = generationOwnerId,
                    contentType = generationContentType
                )
                // Save state immediately after starting
                applicationContext?.let { saveGenerationState(it) }
                mainHandler.post { onResult(true, promptId, null) }
            } else {
                DebugLogger.e(TAG, "Workflow submission failed: $errorMessage")
                mainHandler.post { onResult(false, null, errorMessage) }
            }
        }
    }

    /**
     * Cancel the current generation
     */
    fun cancelGeneration(onResult: (success: Boolean) -> Unit) {
        DebugLogger.i(TAG, "Cancelling generation")
        val client = comfyUIClient ?: run {
            DebugLogger.w(TAG, "Cannot cancel: client not initialized")
            onResult(false)
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        client.interruptExecution { success ->
            if (success) {
                DebugLogger.i(TAG, "Generation cancelled successfully")
            } else {
                DebugLogger.w(TAG, "Failed to cancel generation")
            }
            resetGenerationState()
            dispatchEvent(GenerationEvent.GenerationCancelled)
            mainHandler.post { onResult(success) }
        }
    }

    /**
     * Complete generation (called after image is fetched)
     */
    fun completeGeneration() {
        DebugLogger.i(TAG, "Generation completed")
        resetGenerationState()
    }

    /**
     * Reset generation state
     */
    private fun resetGenerationState() {
        stopCompletionPolling()
        _generationState.value = GenerationState()
        generationOwnerId = null
        pendingCompletion = null
        bufferedPreview = null
        pendingClearPreview = false
        // Clear persisted state to prevent stale "generating" state on app restart
        applicationContext?.let { saveGenerationState(it) }
    }

    /**
     * Start polling for generation completion.
     * Used when resuming a generation after app restart, since WebSocket
     * won't receive progress updates for jobs submitted with a different client ID.
     */
    private fun startCompletionPolling(promptId: String, contentType: ContentType) {
        if (completionPollingJob?.isActive == true) return

        completionPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(COMPLETION_POLLING_INTERVAL_MS)
                if (!isActive) break
                pollForCompletion(promptId, contentType)
            }
        }
    }

    /**
     * Stop polling for generation completion
     */
    private fun stopCompletionPolling() {
        completionPollingJob?.cancel()
        completionPollingJob = null
    }

    /**
     * Poll the server to check if generation has completed
     */
    private fun pollForCompletion(promptId: String, contentType: ContentType) {
        val client = comfyUIClient ?: return

        client.fetchHistory(promptId) { historyJson ->
            if (historyJson == null) return@fetchHistory

            val outputs = historyJson.optJSONObject(promptId)?.optJSONObject("outputs")
            if (outputs != null && outputs.length() > 0) {
                val event = if (contentType == ContentType.VIDEO) {
                    GenerationEvent.VideoGenerated(promptId)
                } else {
                    GenerationEvent.ImageGenerated(promptId)
                }
                dispatchEvent(event)
                completeGeneration()
                GalleryRepository.getInstance().refresh()
            }
        }
    }

    /**
     * Save generation state to SharedPreferences
     */
    fun saveGenerationState(context: Context) {
        val state = _generationState.value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(PREF_IS_GENERATING, state.isGenerating)
            putString(PREF_CURRENT_PROMPT_ID, state.promptId)
            putString(PREF_OWNER_ID, state.ownerId)
            putString(PREF_CONTENT_TYPE, state.contentType.name)
            apply()
        }
    }

    /**
     * Check server for generation completion.
     * Called when returning from background or reconnecting after connection loss.
     * Queries the server's history API to see if the generation completed while we were away.
     * If not in history, checks the queue to determine if still running or truly stale.
     */
    fun checkServerForCompletion(onResult: (completed: Boolean, promptId: String?) -> Unit = { _, _ -> }) {
        val state = _generationState.value
        if (!state.isGenerating || state.promptId == null) {
            onResult(false, null)
            return
        }

        val client = comfyUIClient ?: run {
            onResult(false, null)
            return
        }

        client.fetchHistory(state.promptId) { historyJson ->
            if (historyJson == null) {
                // History fetch failed - check queue to detect stale state
                checkIfPromptInQueue(client, state.promptId) { inQueue ->
                    if (inQueue) {
                        dispatchEvent(GenerationEvent.ClearPreviewForResume)
                        startCompletionPolling(state.promptId, state.contentType)
                        onResult(false, state.promptId)
                    } else {
                        // Not in queue - retry history check in case it just completed
                        client.fetchHistory(state.promptId) { retryHistoryJson ->
                            val retryOutputs = retryHistoryJson?.optJSONObject(state.promptId)?.optJSONObject("outputs")
                            if (retryOutputs != null && retryOutputs.length() > 0) {
                                dispatchCompletionEvent(state.promptId, state.contentType)
                                onResult(true, state.promptId)
                            } else {
                                resetGenerationState()
                                onResult(false, null)
                            }
                        }
                    }
                }
                return@fetchHistory
            }

            val outputs = historyJson.optJSONObject(state.promptId)?.optJSONObject("outputs")
            if (outputs != null && outputs.length() > 0) {
                dispatchCompletionEvent(state.promptId, state.contentType)
                onResult(true, state.promptId)
            } else {
                // Not in history with outputs - check if still in queue
                checkIfPromptInQueue(client, state.promptId) { inQueue ->
                    if (inQueue) {
                        dispatchEvent(GenerationEvent.ClearPreviewForResume)
                        startCompletionPolling(state.promptId, state.contentType)
                        onResult(false, state.promptId)
                    } else {
                        resetGenerationState()
                        onResult(false, null)
                    }
                }
            }
        }
    }

    /**
     * Helper to dispatch completion event and clean up state
     */
    private fun dispatchCompletionEvent(promptId: String, contentType: ContentType) {
        val event = if (contentType == ContentType.VIDEO) {
            GenerationEvent.VideoGenerated(promptId)
        } else {
            GenerationEvent.ImageGenerated(promptId)
        }
        dispatchEvent(event)
        completeGeneration()
        GalleryRepository.getInstance().refresh()
    }

    /**
     * Check if a prompt is in the ComfyUI queue (either running or pending)
     */
    private fun checkIfPromptInQueue(
        client: ComfyUIClient,
        promptId: String,
        callback: (inQueue: Boolean) -> Unit
    ) {
        client.fetchQueue { queueJson ->
            if (queueJson == null) {
                // Couldn't fetch queue, assume still running to be safe
                callback(true)
                return@fetchQueue
            }

            // Check queue_running array: [[number, prompt_id, {...}], ...]
            val queueRunning = queueJson.optJSONArray("queue_running")
            if (queueRunning != null) {
                for (i in 0 until queueRunning.length()) {
                    val entry = queueRunning.optJSONArray(i)
                    if (entry != null && entry.length() > 1 && entry.optString(1) == promptId) {
                        callback(true)
                        return@fetchQueue
                    }
                }
            }

            // Check queue_pending array: [[number, prompt_id, {...}], ...]
            val queuePending = queueJson.optJSONArray("queue_pending")
            if (queuePending != null) {
                for (i in 0 until queuePending.length()) {
                    val entry = queuePending.optJSONArray(i)
                    if (entry != null && entry.length() > 1 && entry.optString(1) == promptId) {
                        callback(true)
                        return@fetchQueue
                    }
                }
            }

            callback(false)
        }
    }

    /**
     * Restore generation state from SharedPreferences
     */
    private fun restoreGenerationState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isGenerating = prefs.getBoolean(PREF_IS_GENERATING, false)
        val promptId = prefs.getString(PREF_CURRENT_PROMPT_ID, null)
        val ownerId = prefs.getString(PREF_OWNER_ID, null)
        val contentTypeName = prefs.getString(PREF_CONTENT_TYPE, null)
        val contentType = try {
            contentTypeName?.let { ContentType.valueOf(it) } ?: ContentType.IMAGE
        } catch (e: IllegalArgumentException) {
            ContentType.IMAGE
        }

        if (isGenerating && promptId != null) {
            generationOwnerId = ownerId
            generationContentType = contentType
            _generationState.value = GenerationState(
                isGenerating = true,
                promptId = promptId,
                ownerId = ownerId,
                contentType = contentType
            )
        }
    }

    /**
     * Logout - close connections and reset state
     */
    fun logout() {
        // Logout from server (sets flag to prevent auto-reconnect, clears all caches)
        ConnectionManager.logout()

        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        resetGenerationState()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketMessageJob?.cancel()
        webSocketStateJob?.cancel()
        // WebSocket is managed by ConnectionManager, don't close it here
        // as other ViewModels may still need it
    }
}
