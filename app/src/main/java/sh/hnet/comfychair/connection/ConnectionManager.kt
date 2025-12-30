package sh.hnet.comfychair.connection

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import java.util.UUID

/**
 * Connection state representing whether the app is connected to a ComfyUI server.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connected(
        val serverId: String,
        val hostname: String,
        val port: Int,
        val protocol: String,
        val clientId: String
    ) : ConnectionState()
}

/**
 * Central manager for ComfyUI server connection.
 *
 * This singleton provides:
 * - Single source of truth for connection state
 * - Shared ComfyUIClient instance for all components
 * - Centralized WebSocket lifecycle management with event broadcasting
 * - Automatic cache invalidation on disconnect
 *
 * WebSocket Architecture:
 * - ConnectionManager owns the WebSocket listener and broadcasts parsed events via SharedFlow
 * - Multiple GenerationViewModels can subscribe without conflict
 * - Handles keepalive pings and automatic reconnection
 *
 * Usage:
 * 1. Call [connect] after successful connection test in LoginScreen
 * 2. Call [openWebSocket] to establish WebSocket connection
 * 3. Subscribe to [webSocketMessages] for parsed events
 * 4. Subscribe to [webSocketState] for connection status
 * 5. Call [disconnect] on logout or connection change
 */
object ConnectionManager {
    private const val TAG = "Connection"
    private const val KEEPALIVE_INTERVAL_MS = 30000L
    private const val MAX_RECONNECT_ATTEMPTS = 5

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // WebSocket state - observable by subscribers
    private val _webSocketState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val webSocketState: StateFlow<WebSocketState> = _webSocketState.asStateFlow()

    // WebSocket messages - parsed and broadcast to all subscribers
    private val _webSocketMessages = MutableSharedFlow<WebSocketMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val webSocketMessages: SharedFlow<WebSocketMessage> = _webSocketMessages.asSharedFlow()

    private var _client: ComfyUIClient? = null
    private var _clientId: String? = null

    // Shared node type registry (populated from /object_info)
    val nodeTypeRegistry = NodeTypeRegistry()

    // Shared model cache (populated from /object_info)
    private val _modelCache = MutableStateFlow(ModelCache())
    val modelCache: StateFlow<ModelCache> = _modelCache.asStateFlow()

    // WebSocket management
    private var reconnectAttempts = 0
    private var keepaliveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Flag indicating that the user explicitly logged out.
     * Used to prevent auto-connect on the login screen after logout.
     */
    var isUserInitiatedLogout: Boolean = false
        private set

    /**
     * Clear the logout flag. Called after login screen checks it.
     */
    fun clearLogoutFlag() {
        isUserInitiatedLogout = false
    }

    /**
     * Get the shared ComfyUIClient instance.
     * @throws IllegalStateException if not connected
     */
    val client: ComfyUIClient
        get() = _client ?: throw IllegalStateException("ConnectionManager: Not connected to server")

    /**
     * Get the shared ComfyUIClient instance, or null if not connected.
     */
    val clientOrNull: ComfyUIClient?
        get() = _client

    /**
     * Current hostname, or empty string if not connected.
     */
    val hostname: String
        get() = (connectionState.value as? ConnectionState.Connected)?.hostname ?: ""

    /**
     * Current port, or 8188 if not connected.
     */
    val port: Int
        get() = (connectionState.value as? ConnectionState.Connected)?.port ?: 8188

    /**
     * Current protocol ("http" or "https"), or "http" if not connected.
     */
    val protocol: String
        get() = (connectionState.value as? ConnectionState.Connected)?.protocol ?: "http"

    /**
     * Current server ID, or null if not connected.
     */
    val currentServerId: String?
        get() = (connectionState.value as? ConnectionState.Connected)?.serverId

    /**
     * Current client ID for WebSocket communication.
     */
    val clientId: String
        get() = _clientId ?: ""

    /**
     * Whether currently connected to a server.
     */
    val isConnected: Boolean
        get() = connectionState.value is ConnectionState.Connected

    /**
     * Whether WebSocket is currently connected.
     */
    val isWebSocketConnected: Boolean
        get() = webSocketState.value is WebSocketState.Connected

    /**
     * Establish connection to a ComfyUI server.
     *
     * This should be called after a successful connection test in LoginScreen.
     * If already connected to the same server, this is a no-op.
     * If connected to a different server, caches are invalidated first.
     *
     * @param context Application context
     * @param serverId Server UUID for per-server storage scoping
     * @param hostname Server hostname
     * @param port Server port
     * @param protocol Detected protocol ("http" or "https")
     */
    @Synchronized
    fun connect(context: Context, serverId: String, hostname: String, port: Int, protocol: String) {
        DebugLogger.i(TAG, "Connecting to ${Obfuscator.hostname(hostname)} (protocol: $protocol, serverId: $serverId)")
        val current = connectionState.value

        // Check if already connected to the same server
        if (current is ConnectionState.Connected &&
            current.serverId == serverId) {
            DebugLogger.d(TAG, "Already connected to same server, skipping")
            return
        }

        // Disconnect from previous server if any
        if (current is ConnectionState.Connected) {
            DebugLogger.d(TAG, "Disconnecting from previous server")
            closeWebSocketInternal()
            invalidateAll()
            _client?.shutdown()
        }

        // Generate unique client ID for this session
        _clientId = "comfychair_android_${UUID.randomUUID()}"

        // Create shared client with detected protocol and shared client ID
        _client = ComfyUIClient(context.applicationContext, hostname, port).apply {
            setWorkingProtocol(protocol)
            setClientId(_clientId!!)
        }

        _connectionState.value = ConnectionState.Connected(
            serverId = serverId,
            hostname = hostname,
            port = port,
            protocol = protocol,
            clientId = _clientId!!
        )
        DebugLogger.i(TAG, "Connected successfully")

        // Fetch server data (node types and model lists) after connection
        fetchServerData()
    }

    /**
     * Disconnect from the current server and clear all caches.
     * Called on logout or when connection needs to be reset.
     */
    @Synchronized
    fun disconnect() {
        if (connectionState.value is ConnectionState.Disconnected) {
            return
        }

        DebugLogger.i(TAG, "Disconnecting")
        closeWebSocketInternal()
        invalidateAll()
        _client?.shutdown()
        _client = null
        _clientId = null
        _connectionState.value = ConnectionState.Disconnected
        DebugLogger.i(TAG, "Disconnected")
    }

    /**
     * Called when backup restore changes connection settings.
     * Same as disconnect - clears everything and returns to login.
     */
    fun invalidateForRestore() {
        DebugLogger.i(TAG, "Invalidating for restore")
        disconnect()
    }

    /**
     * Logout from the server. Sets the logout flag to prevent auto-reconnect.
     * Called when user explicitly logs out via the UI.
     */
    fun logout() {
        DebugLogger.i(TAG, "User initiated logout")
        isUserInitiatedLogout = true
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS  // Prevent reconnection attempts
        disconnect()
    }

    /**
     * Clear all connection-dependent caches.
     */
    private fun invalidateAll() {
        GalleryRepository.getInstance().reset()
        MediaStateHolder.clearAll()
        MediaCache.reset()
        JobRegistry.clear()
        // Clear model cache and node registry
        _modelCache.value = ModelCache()
        nodeTypeRegistry.clear()
    }

    // WebSocket management

    /**
     * Open WebSocket connection with centralized event handling.
     * Events are parsed and broadcast via [webSocketMessages] SharedFlow.
     * @return true if connection attempt started
     */
    fun openWebSocket(): Boolean {
        val client = _client ?: run {
            DebugLogger.w(TAG, "Cannot open WebSocket: client is null")
            return false
        }
        val id = _clientId ?: run {
            DebugLogger.w(TAG, "Cannot open WebSocket: clientId is null")
            return false
        }

        // Already connected or connecting
        if (_webSocketState.value is WebSocketState.Connected ||
            _webSocketState.value is WebSocketState.Connecting) {
            DebugLogger.d(TAG, "WebSocket already connected/connecting")
            return true
        }

        DebugLogger.i(TAG, "Opening WebSocket connection")
        _webSocketState.value = WebSocketState.Connecting

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogger.i(TAG, "WebSocket connected")
                reconnectAttempts = 0
                _webSocketState.value = WebSocketState.Connected
                startKeepalive()
                GalleryRepository.getInstance().startBackgroundPreload()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmitMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parseAndEmitBinaryMessage(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                DebugLogger.e(TAG, "WebSocket connection failed: ${t.message}")
                stopKeepalive()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLogger.i(TAG, "WebSocket closed (code: $code)")
                stopKeepalive()
                if (code != 1000) {
                    scheduleReconnect()
                } else {
                    _webSocketState.value = WebSocketState.Disconnected
                }
            }
        }

        return client.openWebSocket(id, listener)
    }

    /**
     * Close the active WebSocket connection.
     */
    fun closeWebSocket() {
        DebugLogger.i(TAG, "Closing WebSocket connection")
        closeWebSocketInternal()
    }

    /**
     * Internal close without logging (used during disconnect)
     */
    private fun closeWebSocketInternal() {
        stopKeepalive()
        _client?.closeWebSocket()
        _webSocketState.value = WebSocketState.Disconnected
    }

    /**
     * Parse text WebSocket message and emit typed event
     */
    private fun parseAndEmitMessage(text: String) {
        try {
            val message = JSONObject(text)
            val messageType = message.optString("type")

            val wsMessage: WebSocketMessage? = when (messageType) {
                "executing" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id")
                    val node = data?.optString("node")
                    val isComplete = data?.isNull("node") == true

                    if (isComplete && !promptId.isNullOrEmpty()) {
                        DebugLogger.i(TAG, "WS: executing complete (promptId: ${Obfuscator.promptId(promptId)})")
                        // Notify JobRegistry of completion (JobRegistry handles gallery refresh)
                        JobRegistry.markCompleted(promptId)
                        WebSocketMessage.ExecutionComplete(promptId)
                    } else {
                        DebugLogger.d(TAG, "WS: executing node $node")
                        WebSocketMessage.Executing(promptId, node)
                    }
                }
                "progress" -> {
                    val data = message.optJSONObject("data")
                    val value = data?.optInt("value", 0) ?: 0
                    val max = data?.optInt("max", 0) ?: 0
                    if (max > 0) {
                        DebugLogger.d(TAG, "WS: progress $value/$max")
                        WebSocketMessage.Progress(value, max)
                    } else null
                }
                "execution_start" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id") ?: ""
                    DebugLogger.i(TAG, "WS: execution_start (promptId: ${Obfuscator.promptId(promptId)})")
                    // Notify JobRegistry that this job is now executing
                    if (promptId.isNotEmpty()) {
                        JobRegistry.markExecuting(promptId)
                    }
                    WebSocketMessage.ExecutionStart(promptId)
                }
                "execution_error" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id")
                    val errorMsg = data?.optString("exception_message", "Unknown error") ?: "Unknown error"
                    DebugLogger.e(TAG, "WS: execution_error - $errorMsg")
                    // Notify JobRegistry of the failure
                    if (!promptId.isNullOrEmpty()) {
                        JobRegistry.markFailed(promptId)
                    }
                    WebSocketMessage.ExecutionError(promptId, errorMsg)
                }
                "execution_success" -> {
                    val data = message.optJSONObject("data")
                    val promptId = data?.optString("prompt_id") ?: ""
                    DebugLogger.i(TAG, "WS: execution_success (promptId: ${Obfuscator.promptId(promptId)})")
                    // Also notify JobRegistry - execution_success is another completion signal
                    if (promptId.isNotEmpty()) {
                        JobRegistry.markCompleted(promptId)
                    }
                    WebSocketMessage.ExecutionSuccess(promptId)
                }
                "execution_cached" -> {
                    val data = message.optJSONObject("data")
                    val nodes = data?.optJSONArray("nodes")?.length() ?: 0
                    DebugLogger.d(TAG, "WS: execution_cached ($nodes nodes)")
                    WebSocketMessage.ExecutionCached(nodes)
                }
                "status" -> {
                    val data = message.optJSONObject("data")
                    val status = data?.optJSONObject("status")
                    val execInfo = status?.optJSONObject("exec_info")
                    val queueRemaining = execInfo?.optInt("queue_remaining") ?: 0
                    DebugLogger.d(TAG, "WS: status (queue: $queueRemaining)")
                    // Update JobRegistry with queue size
                    JobRegistry.updateFromStatus(queueRemaining)
                    WebSocketMessage.Status(queueRemaining)
                }
                "previewing", "executed" -> {
                    DebugLogger.d(TAG, "WS: $messageType")
                    null  // Don't emit for these types
                }
                else -> {
                    if (messageType.isNotEmpty()) {
                        DebugLogger.d(TAG, "WS: $messageType")
                        WebSocketMessage.Unknown(messageType)
                    } else null
                }
            }

            wsMessage?.let {
                scope.launch { _webSocketMessages.emit(it) }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "WS: failed to parse message: ${e.message}")
        }
    }

    /**
     * Parse binary WebSocket message (preview images) and emit
     */
    private fun parseAndEmitBinaryMessage(bytes: ByteString) {
        if (bytes.size > 8) {
            try {
                val pngBytes = bytes.substring(8).toByteArray()
                val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                if (bitmap != null) {
                    DebugLogger.d(TAG, "WS: preview image received (${bitmap.width}x${bitmap.height})")
                    scope.launch { _webSocketMessages.emit(WebSocketMessage.PreviewImage(bitmap)) }
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "WS: failed to decode preview: ${e.message}")
            }
        }
    }

    /**
     * Start keepalive ping mechanism
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive && _webSocketState.value is WebSocketState.Connected) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (_webSocketState.value is WebSocketState.Connected) {
                    _client?.sendWebSocketMessage("{\"type\":\"ping\"}")
                }
            }
        }
    }

    /**
     * Stop keepalive ping mechanism
     */
    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /**
     * Schedule WebSocket reconnection with exponential backoff
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            DebugLogger.w(TAG, "Max reconnect attempts reached")
            _webSocketState.value = WebSocketState.Failed("Max reconnect attempts reached")
            return
        }

        reconnectAttempts++
        _webSocketState.value = WebSocketState.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)

        val delayMs = (Math.pow(2.0, (reconnectAttempts - 1).toDouble()) * 1000).toLong()
        DebugLogger.d(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delayMs}ms")

        scope.launch {
            delay(delayMs)
            reconnectWebSocket()
        }
    }

    /**
     * Attempt to reconnect WebSocket
     */
    private fun reconnectWebSocket() {
        val client = _client ?: return

        client.closeWebSocket()

        client.testConnection { success, _, _ ->
            if (success) {
                openWebSocket()
            } else {
                scheduleReconnect()
            }
        }
    }

    /**
     * Poll the server queue and update JobRegistry.
     * Used to validate jobs on app restart and sync state.
     */
    fun pollQueueStatus() {
        val client = _client ?: return
        client.fetchQueue { queueJson ->
            if (queueJson != null) {
                val running = queueJson.optJSONArray("queue_running")
                val pending = queueJson.optJSONArray("queue_pending")
                JobRegistry.updateFromServerQueue(running, pending)
            }
        }
    }

    // Model cache and node registry management

    /**
     * Fetch server data (node types and model lists) from /object_info.
     * Called automatically on connection and can be triggered manually via [refreshServerData].
     */
    private fun fetchServerData() {
        val client = _client ?: run {
            DebugLogger.w(TAG, "fetchServerData: Client not available")
            return
        }

        DebugLogger.i(TAG, "Fetching server data from /object_info")
        _modelCache.value = _modelCache.value.copy(isLoading = true, lastError = null)

        client.fetchFullObjectInfo { objectInfo ->
            if (objectInfo != null) {
                DebugLogger.d(TAG, "Parsing object_info response")
                // Parse node definitions for workflow editor
                nodeTypeRegistry.parseObjectInfo(objectInfo)

                // Extract model lists for generation screens
                val models = extractModelLists(objectInfo)
                _modelCache.value = models.copy(isLoaded = true, isLoading = false)
                DebugLogger.i(TAG, "Server data loaded: ${models.checkpoints.size} checkpoints, " +
                        "${models.unets.size} unets, ${models.vaes.size} vaes, " +
                        "${models.clips.size} clips, ${models.loras.size} loras")
            } else {
                DebugLogger.w(TAG, "Failed to fetch server data")
                _modelCache.value = _modelCache.value.copy(
                    isLoading = false,
                    lastError = "Failed to fetch server data"
                )
            }
        }
    }

    /**
     * Refresh server data (node types and model lists).
     * Can be called by the user to update model lists without reconnecting.
     */
    fun refreshServerData() {
        if (_client == null) {
            DebugLogger.w(TAG, "refreshServerData: Not connected")
            return
        }
        DebugLogger.i(TAG, "Refreshing server data")
        fetchServerData()
    }

    /**
     * Extract model lists from /object_info response.
     * Each model type is extracted from its corresponding loader node.
     */
    private fun extractModelLists(objectInfo: JSONObject): ModelCache {
        return ModelCache(
            checkpoints = extractModelList(objectInfo, "CheckpointLoaderSimple", "ckpt_name"),
            unets = extractModelList(objectInfo, "UNETLoader", "unet_name"),
            vaes = extractModelList(objectInfo, "VAELoader", "vae_name"),
            clips = extractModelList(objectInfo, "CLIPLoader", "clip_name"),
            loras = extractModelList(objectInfo, "LoraLoaderModelOnly", "lora_name")
        )
    }

    /**
     * Extract a single model list from /object_info.
     * Navigates: nodeType -> input -> required -> inputName -> [0] (options array)
     */
    private fun extractModelList(objectInfo: JSONObject, nodeType: String, inputName: String): List<String> {
        return try {
            val nodeInfo = objectInfo.optJSONObject(nodeType) ?: return emptyList()
            val input = nodeInfo.optJSONObject("input") ?: return emptyList()
            val required = input.optJSONObject("required") ?: return emptyList()
            val inputSpec = required.optJSONArray(inputName) ?: return emptyList()
            val options = inputSpec.optJSONArray(0) ?: return emptyList()

            (0 until options.length()).map { options.getString(it) }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to extract model list for $nodeType/$inputName: ${e.message}")
            emptyList()
        }
    }
}
