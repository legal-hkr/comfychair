package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository

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
 * Central ViewModel for managing WebSocket connection and generation state.
 * Replaces the state management logic previously in MainContainerActivity.
 */
class GenerationViewModel : ViewModel() {

    // Application context for gallery repository
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Connection state
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private var isWebSocketConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

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

    companion object {
        private const val PREFS_NAME = "GenerationViewModelPrefs"
        private const val PREF_IS_GENERATING = "isGenerating"
        private const val PREF_CURRENT_PROMPT_ID = "currentPromptId"
        private const val PREF_OWNER_ID = "ownerId"
        private const val PREF_CONTENT_TYPE = "contentType"
        private const val KEEPALIVE_INTERVAL_MS = 30000L
        private const val COMPLETION_POLLING_INTERVAL_MS = 3000L
    }

    /**
     * Initialize the ViewModel with context.
     * Connection is managed by ConnectionManager.
     */
    fun initialize(context: Context) {
        if (this.applicationContext != null) {
            // Already initialized
            return
        }

        this.applicationContext = context.applicationContext

        // Restore generation state
        restoreGenerationState(context)

        // Connect WebSocket
        connectToServer()
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
        }
    }

    /**
     * Unregister the event handler for a specific owner.
     * Only clears the handler if this owner actually registered it.
     * If the owner started generation that's still running, keep handler active for pending completion.
     */
    fun unregisterEventHandler(ownerId: String) {
        // Only clear handler if THIS owner registered it
        // This prevents race conditions where a new screen's handler gets cleared
        // by the old screen's onDispose
        if (activeEventHandlerOwnerId != ownerId) {
            return  // Another screen registered the handler, don't clear it
        }

        // Don't clear handler if this owner started generation that's still running
        // This ensures completion events aren't lost when user navigates away
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
        val eventType = event::class.simpleName

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

    /**
     * Connect to the ComfyUI server.
     * Connection is already established via ConnectionManager in LoginScreen.
     */
    private fun connectToServer() {
        if (!ConnectionManager.isConnected) {
            _connectionStatus.value = ConnectionStatus.FAILED
            return
        }

        _connectionStatus.value = ConnectionStatus.CONNECTING
        openWebSocketConnection()
    }

    /**
     * Open WebSocket connection via ConnectionManager
     */
    private fun openWebSocketConnection() {
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isWebSocketConnected = true
                reconnectAttempts = 0
                _connectionStatus.value = ConnectionStatus.CONNECTED

                startKeepalive()
                GalleryRepository.getInstance().startBackgroundPreload()

                // Check if there's a pending generation that completed while we were disconnected
                if (_generationState.value.isGenerating) {
                    checkServerForCompletion()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleWebSocketBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWebSocketConnected = false

                if (_generationState.value.isGenerating) {
                    applicationContext?.let { saveGenerationState(it) }
                    dispatchEvent(GenerationEvent.ConnectionLostDuringGeneration)
                }

                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWebSocketConnected = false

                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        }

        ConnectionManager.openWebSocket(webSocketListener)
    }

    /**
     * Handle text WebSocket messages
     */
    private fun handleWebSocketMessage(text: String) {
        try {
            val message = JSONObject(text)
            val messageType = message.optString("type")

            when (messageType) {
                "executing" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id")
                    val isComplete = data?.isNull("node") == true
                    val currentState = _generationState.value

                    if (isComplete && promptId == currentState.promptId && promptId != null) {
                        // Use dispatchCompletionEvent to ensure consistent behavior with server polling
                        // This dispatches the event, calls completeGeneration(), and refreshes gallery
                        dispatchCompletionEvent(promptId, currentState.contentType)
                    }
                }
                "progress" -> {
                    val data = message.optJSONObject("data")
                    val value = data?.optInt("value", 0) ?: 0
                    val max = data?.optInt("max", 0) ?: 0

                    if (max > 0) {
                        _generationState.value = _generationState.value.copy(
                            progress = value,
                            maxProgress = max
                        )
                    }
                }
                "execution_error" -> {
                    resetGenerationState()
                    val errorMessage = applicationContext?.getString(R.string.error_generation_failed)
                        ?: "Generation failed"
                    dispatchEvent(GenerationEvent.Error(errorMessage))
                }
                "status", "previewing", "execution_cached", "execution_start",
                "execution_success", "progress_state", "executed" -> {
                    // Known message types - no action needed
                }
                else -> {
                    // Unknown message type - ignore
                }
            }
        } catch (_: Exception) {
            // Ignore malformed WebSocket messages
        }
    }

    /**
     * Handle binary WebSocket messages (preview images)
     */
    private fun handleWebSocketBinaryMessage(bytes: ByteString) {
        if (bytes.size > 8 && _generationState.value.isGenerating) {
            try {
                val pngBytes = bytes.substring(8).toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                if (bitmap != null) {
                    dispatchEvent(GenerationEvent.PreviewImage(bitmap))
                }
            } catch (_: Exception) {
                // Ignore malformed preview images
            }
        }
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
        comfyUIClient ?: run {
            onResult(false, null, applicationContext?.getString(R.string.error_client_not_initialized) ?: "Client not initialized")
            return
        }

        // Store the owner and content type before starting generation
        generationOwnerId = ownerId
        generationContentType = contentType

        if (!isWebSocketConnected) {
            reconnectWebSocket()

            viewModelScope.launch {
                delay(2000)
                if (isWebSocketConnected) {
                    submitWorkflow(workflowJson, onResult)
                } else {
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

        client.submitPrompt(workflowJson) { success, promptId, errorMessage ->
            if (success && promptId != null) {
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
                onResult(true, promptId, null)
            } else {
                onResult(false, null, errorMessage)
            }
        }
    }

    /**
     * Cancel the current generation
     */
    fun cancelGeneration(onResult: (success: Boolean) -> Unit) {
        val client = comfyUIClient ?: run {
            onResult(false)
            return
        }

        client.interruptExecution { success ->
            resetGenerationState()
            dispatchEvent(GenerationEvent.GenerationCancelled)
            onResult(success)
        }
    }

    /**
     * Complete generation (called after image is fetched)
     */
    fun completeGeneration() {
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
     * Start keepalive ping mechanism
     */
    private fun startKeepalive() {
        viewModelScope.launch {
            while (isWebSocketConnected) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (isWebSocketConnected) {
                    comfyUIClient?.sendWebSocketMessage("{\"type\":\"ping\"}")
                }
            }
        }
    }

    /**
     * Schedule WebSocket reconnection
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            _connectionStatus.value = ConnectionStatus.FAILED
            return
        }

        _connectionStatus.value = ConnectionStatus.RECONNECTING

        val delayMs = (Math.pow(2.0, reconnectAttempts.toDouble()) * 1000).toLong()
        reconnectAttempts++

        viewModelScope.launch {
            delay(delayMs)
            reconnectWebSocket()
        }
    }

    /**
     * Reconnect WebSocket
     */
    private fun reconnectWebSocket() {
        comfyUIClient?.closeWebSocket()

        comfyUIClient?.testConnection { success, _, _ ->
            if (success) {
                openWebSocketConnection()
            } else {
                scheduleReconnect()
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
        // Disconnect from server (clears all caches including GalleryRepository)
        ConnectionManager.disconnect()

        isWebSocketConnected = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        resetGenerationState()
    }

    override fun onCleared() {
        super.onCleared()
        // WebSocket is managed by ConnectionManager, don't close it here
        // as other ViewModels may still need it
    }
}
