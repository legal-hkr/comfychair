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
import sh.hnet.comfychair.queue.JobRegistry
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

    // Per-owner buffered events (keyed by ownerId)
    // This prevents events from one owner overwriting another's when jobs overlap
    private val pendingCompletions = mutableMapOf<String, GenerationEvent>()  // ImageGenerated or VideoGenerated
    private val bufferedPreviews = mutableMapOf<String, Bitmap>()  // Preview when no handler registered
    private val pendingClearPreviews = mutableSetOf<String>()  // ClearPreviewForResume when no handler

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

        // Restore JobRegistry state from persistence
        JobRegistry.restoreState(context)

        // Restore generation state
        restoreGenerationState(context)

        // Subscribe to ConnectionManager's WebSocket flows
        subscribeToWebSocketMessages()
        subscribeToWebSocketState()

        // Open WebSocket connection via ConnectionManager
        if (ConnectionManager.isConnected) {
            ConnectionManager.openWebSocket()
            // Poll queue to validate restored jobs against server
            ConnectionManager.pollQueueStatus()
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
            // When a job starts executing, update generation state to track it
            is WebSocketMessage.ExecutionStart -> {
                val promptId = message.promptId
                if (promptId.isNotEmpty()) {
                    // Look up job info from JobRegistry
                    val owner = JobRegistry.findOwner(promptId)
                    val contentType = JobRegistry.findContentType(promptId)

                    if (owner != null && contentType != null) {
                        val previousOwner = generationOwnerId
                        val ownerChanged = previousOwner != null && previousOwner != owner

                        // This is one of our jobs - update state to track it
                        DebugLogger.d(TAG, "Tracking execution start for our job (promptId: ${Obfuscator.promptId(promptId)}, owner: $owner)")
                        if (ownerChanged) {
                            DebugLogger.i(TAG, "OWNER CHANGE: $previousOwner -> $owner, currentHandler=$activeEventHandlerOwnerId")
                        }

                        generationOwnerId = owner
                        generationContentType = contentType
                        _generationState.value = GenerationState(
                            isGenerating = true,
                            promptId = promptId,
                            progress = 0,
                            maxProgress = 100,
                            ownerId = owner,
                            contentType = contentType
                        )

                        // If handler doesn't match new owner, clear it so correct screen can register
                        if (activeEventHandlerOwnerId != null && activeEventHandlerOwnerId != owner) {
                            DebugLogger.w(TAG, "Handler mismatch after ExecutionStart: handler=$activeEventHandlerOwnerId, newOwner=$owner - clearing handler")
                            activeEventHandler = null
                            activeEventHandlerOwnerId = null
                        }
                    }
                }
            }
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
        val state = _generationState.value
        val executingOwner = state.ownerId

        // Check if this owner has pending buffered events that need delivery
        val hasPendingEvents = pendingCompletions.containsKey(ownerId) ||
                               bufferedPreviews.containsKey(ownerId) ||
                               pendingClearPreviews.contains(ownerId)

        DebugLogger.d(TAG, "registerEventHandler: ownerId=$ownerId, executingOwner=$executingOwner, " +
                "generationOwnerId=$generationOwnerId, currentHandler=$activeEventHandlerOwnerId, hasPending=$hasPendingEvents")

        // Allow registration if:
        // 1. This owner matches the executing job, OR
        // 2. No generation is active, OR
        // 3. This owner has pending buffered events that need delivery
        if (ownerId == executingOwner || executingOwner == null || hasPendingEvents) {
            DebugLogger.d(TAG, "Handler registration ACCEPTED for $ownerId (reason: ${
                when {
                    ownerId == executingOwner -> "matches executing"
                    executingOwner == null -> "no active generation"
                    hasPendingEvents -> "has pending events"
                    else -> "unknown"
                }
            })")
            activeEventHandler = handler
            activeEventHandlerOwnerId = ownerId

            // Deliver pending ClearPreviewForResume if exists for THIS owner (must be before preview delivery)
            if (pendingClearPreviews.remove(ownerId)) {
                DebugLogger.d(TAG, "Delivering buffered ClearPreviewForResume to $ownerId")
                viewModelScope.launch { handler(GenerationEvent.ClearPreviewForResume) }
            }

            // Deliver buffered preview if exists for THIS owner
            bufferedPreviews.remove(ownerId)?.let { bitmap ->
                DebugLogger.d(TAG, "Delivering buffered PreviewImage to $ownerId")
                viewModelScope.launch { handler(GenerationEvent.PreviewImage(bitmap)) }
            }

            // Deliver pending completion if exists for THIS owner
            pendingCompletions.remove(ownerId)?.let { completion ->
                DebugLogger.d(TAG, "Delivering buffered ${completion::class.simpleName} to $ownerId")
                viewModelScope.launch { handler(completion) }
            }

            // If generation is active but completion wasn't detected yet,
            // trigger a server check in case completion happened before handler registered
            if (state.isGenerating && !pendingCompletions.containsKey(ownerId) && ownerId == state.ownerId) {
                DebugLogger.d(TAG, "Handler registered during active generation, verifying status")
                checkServerForCompletion()
            }
        } else {
            DebugLogger.d(TAG, "Handler registration REJECTED for $ownerId (executing owner is $executingOwner)")
        }
    }

    /**
     * Unregister the event handler for a specific owner.
     * Only clears the handler if this owner actually registered it.
     * If the owner started generation that's still running, keep handler active for pending completion.
     */
    fun unregisterEventHandler(ownerId: String) {
        val state = _generationState.value
        val executingOwner = state.ownerId

        DebugLogger.d(TAG, "unregisterEventHandler: ownerId=$ownerId, executingOwner=$executingOwner, " +
                "currentHandler=$activeEventHandlerOwnerId, isGenerating=${state.isGenerating}")

        // Only clear handler if THIS owner registered it
        if (activeEventHandlerOwnerId != ownerId) {
            DebugLogger.d(TAG, "Unregister SKIPPED: another owner ($activeEventHandlerOwnerId) registered the handler")
            return  // Another screen registered the handler, don't clear it
        }

        // Don't clear handler if this owner's job is currently executing
        if (executingOwner == ownerId && state.isGenerating) {
            DebugLogger.d(TAG, "Unregister SKIPPED: $ownerId's job is still executing")
            return
        }

        DebugLogger.d(TAG, "Unregister ACCEPTED: clearing handler for $ownerId")
        activeEventHandler = null
        activeEventHandlerOwnerId = null
    }

    /**
     * Dispatch event to active handler, falling back to SharedFlow.
     * If no handler is active OR handler doesn't match executing owner, buffer events.
     * Handler is invoked on the main thread to ensure proper UI state updates.
     */
    private fun dispatchEvent(event: GenerationEvent) {
        val handler = activeEventHandler
        val state = _generationState.value
        val eventName = event::class.simpleName
        val executingOwner = state.ownerId

        // Check if handler matches executing job owner
        val handlerMatchesExecuting = activeEventHandlerOwnerId == executingOwner

        // Only log non-preview events to avoid spam
        if (event !is GenerationEvent.PreviewImage) {
            DebugLogger.d(TAG, "dispatchEvent: $eventName, executingOwner=$executingOwner, " +
                    "handlerOwner=$activeEventHandlerOwnerId, hasHandler=${handler != null}, matches=$handlerMatchesExecuting")
        }

        // Only dispatch to handler if it matches the executing job's owner
        if (handler != null && handlerMatchesExecuting) {
            viewModelScope.launch { handler(event) }
            if (event is GenerationEvent.PreviewImage && executingOwner != null) {
                bufferedPreviews.remove(executingOwner)
            }
        } else {
            // No matching handler - buffer for later delivery to the correct owner
            // Use executingOwner as the key so this owner can retrieve it later
            if (executingOwner != null) {
                if (event !is GenerationEvent.PreviewImage) {
                    if (handler != null && !handlerMatchesExecuting) {
                        DebugLogger.d(TAG, "Handler mismatch, buffering $eventName for $executingOwner (current handler: $activeEventHandlerOwnerId)")
                    } else {
                        DebugLogger.d(TAG, "No handler, buffering $eventName for $executingOwner")
                    }
                }
                when (event) {
                    is GenerationEvent.PreviewImage -> bufferedPreviews[executingOwner] = event.bitmap
                    is GenerationEvent.ImageGenerated, is GenerationEvent.VideoGenerated -> pendingCompletions[executingOwner] = event
                    is GenerationEvent.ClearPreviewForResume -> pendingClearPreviews.add(executingOwner)
                    else -> {}
                }
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
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        DebugLogger.i(TAG, "Starting generation (owner: $ownerId, type: $contentType${if (front) ", front" else ""})")
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
                    submitWorkflow(workflowJson, front, onResult)
                } else {
                    DebugLogger.e(TAG, "WebSocket connection failed")
                    onResult(false, null, applicationContext?.getString(R.string.error_websocket_not_connected) ?: "WebSocket not connected")
                }
            }
        } else {
            submitWorkflow(workflowJson, front, onResult)
        }
    }

    /**
     * Submit workflow to server
     */
    private fun submitWorkflow(
        workflowJson: String,
        front: Boolean = false,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        val client = comfyUIClient ?: return

        DebugLogger.d(TAG, "Submitting workflow to server")
        val mainHandler = Handler(Looper.getMainLooper())
        client.submitPrompt(workflowJson, front) { success, promptId, errorMessage ->
            if (success && promptId != null) {
                DebugLogger.i(TAG, "Workflow submitted successfully (promptId: ${Obfuscator.promptId(promptId)})")

                // Register job with JobRegistry for queue tracking
                generationOwnerId?.let { ownerId ->
                    JobRegistry.registerJob(promptId, ownerId, generationContentType)
                    // Save JobRegistry state for persistence
                    applicationContext?.let { ctx -> JobRegistry.saveState(ctx) }
                }

                // Determine if we should set up GenerationState now
                // There's a race condition: ExecutionStart might fire before we register the job.
                // Check both:
                // 1. Nothing executing (first job or queue was empty)
                // 2. This job is already executing (race: ExecutionStart fired before registration)
                val queueState = JobRegistry.queueState.value
                val isThisJobExecuting = queueState.executingPromptId == promptId
                val shouldSetState = isThisJobExecuting || !queueState.isExecuting

                if (shouldSetState) {
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
                }
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
     * @param promptId Optional promptId to verify - only resets if matches current generation.
     *                 This prevents late completion calls from clearing state for a different job.
     */
    fun completeGeneration(promptId: String? = null) {
        val currentPromptId = _generationState.value.promptId

        // If promptId specified, only reset if it matches current generation
        // This prevents race condition where a late completion clears a new job's state
        if (promptId != null && promptId != currentPromptId) {
            DebugLogger.d(TAG, "Ignoring completion for old job (${Obfuscator.promptId(promptId)}) - current is (${Obfuscator.promptId(currentPromptId ?: "")})")
            return
        }

        DebugLogger.i(TAG, "Generation completed")
        resetGenerationState()
    }

    /**
     * Reset generation state
     * Clears transient buffers (previews, clear flags) but preserves pending completions
     * since they need to survive until the handler registers to receive them
     */
    private fun resetGenerationState() {
        stopCompletionPolling()

        // Clear transient buffers (previews can be discarded, completion events cannot)
        val currentOwner = generationOwnerId
        if (currentOwner != null) {
            // DON'T clear pendingCompletions - they must survive until handler retrieves them
            bufferedPreviews.remove(currentOwner)
            pendingClearPreviews.remove(currentOwner)
        }

        _generationState.value = GenerationState()
        generationOwnerId = null
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
                completeGeneration(promptId)
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
        completeGeneration(promptId)  // Pass promptId to prevent clearing state for a different job
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
        // Clear JobRegistry persisted state
        applicationContext?.let { JobRegistry.clearPersistedState(it) }

        // Logout from server (sets flag to prevent auto-reconnect, clears all caches including JobRegistry)
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
