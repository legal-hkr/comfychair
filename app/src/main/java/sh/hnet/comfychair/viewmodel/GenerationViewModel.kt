package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
import sh.hnet.comfychair.repository.GalleryRepository

/**
 * Generation state data class
 */
data class GenerationState(
    val isGenerating: Boolean = false,
    val promptId: String? = null,
    val progress: Int = 0,
    val maxProgress: Int = 100
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
 * One-time events emitted during generation
 */
sealed class GenerationEvent {
    data class ImageGenerated(val promptId: String) : GenerationEvent()
    data class VideoGenerated(val promptId: String) : GenerationEvent()
    data class PreviewImage(val bitmap: Bitmap) : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
    data object GenerationCancelled : GenerationEvent()
}

/**
 * Central ViewModel for managing WebSocket connection and generation state.
 * Replaces the state management logic previously in MainContainerActivity.
 */
class GenerationViewModel : ViewModel() {

    // ComfyUI client
    private var comfyUIClient: ComfyUIClient? = null

    // Application context for gallery repository
    private var applicationContext: Context? = null

    // Connection parameters
    private var hostname: String = ""
    private var port: Int = 8188

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

    // Pending image (generated while no collector was active)
    private var pendingImagePromptId: String? = null

    companion object {
        private const val PREFS_NAME = "GenerationViewModelPrefs"
        private const val PREF_IS_GENERATING = "isGenerating"
        private const val PREF_CURRENT_PROMPT_ID = "currentPromptId"
        private const val KEEPALIVE_INTERVAL_MS = 30000L
    }

    /**
     * Initialize the ViewModel with connection parameters and context
     */
    fun initialize(context: Context, hostname: String, port: Int) {
        if (this.comfyUIClient != null) {
            // Already initialized
            return
        }

        this.applicationContext = context.applicationContext
        this.hostname = hostname
        this.port = port
        this.comfyUIClient = ComfyUIClient(hostname, port)

        // Initialize gallery repository with client
        GalleryRepository.getInstance().initialize(context.applicationContext, this.comfyUIClient!!)

        // Restore generation state
        restoreGenerationState(context)

        // Connect to server
        connectToServer()
    }

    /**
     * Get the ComfyUI client instance
     */
    fun getClient(): ComfyUIClient? = comfyUIClient

    /**
     * Get the hostname
     */
    fun getHostname(): String = hostname

    /**
     * Get the port
     */
    fun getPort(): Int = port

    /**
     * Connect to the ComfyUI server
     */
    private fun connectToServer() {
        val client = comfyUIClient ?: return

        _connectionStatus.value = ConnectionStatus.CONNECTING

        client.testConnection { success, errorMessage, _ ->
            if (success) {
                println("GenerationViewModel: Connection successful!")
                openWebSocketConnection()
            } else {
                println("GenerationViewModel: Failed to connect: $errorMessage")
                _connectionStatus.value = ConnectionStatus.FAILED
                viewModelScope.launch {
                    _events.emit(GenerationEvent.Error("Failed to connect: $errorMessage"))
                }
            }
        }
    }

    /**
     * Open WebSocket connection
     */
    private fun openWebSocketConnection() {
        val client = comfyUIClient ?: return

        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("GenerationViewModel: WebSocket connected")
                isWebSocketConnected = true
                reconnectAttempts = 0
                _connectionStatus.value = ConnectionStatus.CONNECTED

                // Start keepalive
                startKeepalive()

                // Start background gallery preloading
                GalleryRepository.getInstance().startBackgroundPreload()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleWebSocketBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("GenerationViewModel: WebSocket failed: ${t.message}")
                isWebSocketConnected = false

                if (_generationState.value.isGenerating) {
                    resetGenerationState()
                    viewModelScope.launch {
                        _events.emit(GenerationEvent.Error("Connection lost during generation"))
                    }
                }

                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("GenerationViewModel: WebSocket closed: $code - $reason")
                isWebSocketConnected = false

                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        }

        client.openWebSocket(webSocketListener)
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
                        println("GenerationViewModel: Generation complete for prompt: $promptId")
                        viewModelScope.launch {
                            _events.emit(GenerationEvent.ImageGenerated(promptId))
                        }
                        // Trigger gallery refresh to include the new item
                        GalleryRepository.getInstance().refresh()
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
                    println("GenerationViewModel: Execution error: $text")
                    resetGenerationState()
                    viewModelScope.launch {
                        _events.emit(GenerationEvent.Error("Generation failed"))
                    }
                }
                "status", "previewing", "execution_cached", "execution_start",
                "execution_success", "progress_state", "executed" -> {
                    // Known message types - no action needed
                }
                else -> {
                    println("GenerationViewModel: Unknown message type: $messageType")
                }
            }
        } catch (e: Exception) {
            println("GenerationViewModel: Failed to parse WebSocket message: ${e.message}")
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
                    viewModelScope.launch {
                        _events.emit(GenerationEvent.PreviewImage(bitmap))
                    }
                }
            } catch (e: Exception) {
                println("GenerationViewModel: Failed to decode preview image: ${e.message}")
            }
        }
    }

    /**
     * Start generation with the given workflow JSON
     */
    fun startGeneration(
        workflowJson: String,
        onResult: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        val client = comfyUIClient ?: run {
            onResult(false, null, "Client not initialized")
            return
        }

        if (!isWebSocketConnected) {
            println("GenerationViewModel: WebSocket not connected, attempting reconnect...")
            reconnectWebSocket()

            viewModelScope.launch {
                delay(2000)
                if (isWebSocketConnected) {
                    submitWorkflow(workflowJson, onResult)
                } else {
                    onResult(false, null, "WebSocket not connected")
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
                println("GenerationViewModel: Workflow submitted. Prompt ID: $promptId")
                _generationState.value = GenerationState(
                    isGenerating = true,
                    promptId = promptId,
                    progress = 0,
                    maxProgress = 100
                )
                onResult(true, promptId, null)
            } else {
                println("GenerationViewModel: Failed to submit workflow: $errorMessage")
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
            if (success) {
                println("GenerationViewModel: Generation cancelled")
            }
            resetGenerationState()
            viewModelScope.launch {
                _events.emit(GenerationEvent.GenerationCancelled)
            }
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
        _generationState.value = GenerationState()
        pendingImagePromptId = null
    }

    /**
     * Start keepalive ping mechanism
     */
    private fun startKeepalive() {
        viewModelScope.launch {
            while (isWebSocketConnected) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (isWebSocketConnected) {
                    val success = comfyUIClient?.sendWebSocketMessage("{\"type\":\"ping\"}") ?: false
                    if (!success) {
                        println("GenerationViewModel: Failed to send keepalive ping")
                    }
                }
            }
        }
    }

    /**
     * Schedule WebSocket reconnection
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            println("GenerationViewModel: Max reconnection attempts reached")
            _connectionStatus.value = ConnectionStatus.FAILED
            return
        }

        _connectionStatus.value = ConnectionStatus.RECONNECTING

        val delayMs = (Math.pow(2.0, reconnectAttempts.toDouble()) * 1000).toLong()
        reconnectAttempts++

        println("GenerationViewModel: Scheduling reconnect attempt $reconnectAttempts in ${delayMs}ms")

        viewModelScope.launch {
            delay(delayMs)
            reconnectWebSocket()
        }
    }

    /**
     * Reconnect WebSocket
     */
    private fun reconnectWebSocket() {
        println("GenerationViewModel: Attempting to reconnect...")
        comfyUIClient?.closeWebSocket()

        comfyUIClient?.testConnection { success, errorMessage, _ ->
            if (success) {
                openWebSocketConnection()
                println("GenerationViewModel: Reconnected successfully")
            } else {
                println("GenerationViewModel: Reconnection failed: $errorMessage")
                scheduleReconnect()
            }
        }
    }

    /**
     * Save generation state to SharedPreferences
     */
    fun saveGenerationState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = _generationState.value
        prefs.edit().apply {
            putBoolean(PREF_IS_GENERATING, state.isGenerating)
            putString(PREF_CURRENT_PROMPT_ID, state.promptId)
            apply()
        }
    }

    /**
     * Restore generation state from SharedPreferences
     */
    private fun restoreGenerationState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isGenerating = prefs.getBoolean(PREF_IS_GENERATING, false)
        val promptId = prefs.getString(PREF_CURRENT_PROMPT_ID, null)

        if (isGenerating && promptId != null) {
            _generationState.value = GenerationState(
                isGenerating = true,
                promptId = promptId
            )
        }
        println("GenerationViewModel: Restored state: isGenerating=$isGenerating, promptId=$promptId")
    }

    /**
     * Logout - close connections and reset state
     */
    fun logout() {
        // Stop gallery background tasks
        GalleryRepository.getInstance().reset()

        comfyUIClient?.closeWebSocket()
        comfyUIClient?.shutdown()
        isWebSocketConnected = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        resetGenerationState()
    }

    override fun onCleared() {
        super.onCleared()
        comfyUIClient?.closeWebSocket()
        comfyUIClient?.shutdown()
    }
}
