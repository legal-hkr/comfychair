package sh.hnet.comfychair.connection

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import sh.hnet.comfychair.R
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.ObjectInfoCache
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
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
    /** Offline mode - using cached data without actual server connection */
    data class OfflineConnected(
        val serverId: String
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
    private const val MAX_RECONNECT_ATTEMPTS = 3

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

    // Connection alert dialog state - single source of truth
    private val _connectionAlertState = MutableStateFlow<ConnectionAlertState?>(null)
    val connectionAlertState: StateFlow<ConnectionAlertState?> = _connectionAlertState.asStateFlow()

    // Connection check in progress (for "Connecting..." button state)
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    // Reconnection in progress (for "Reconnecting..." dialog button state)
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private var _client: ComfyUIClient? = null
    private var _clientId: String? = null
    private var _applicationContext: Context? = null

    // Shared node type registry (populated from /object_info)
    val nodeTypeRegistry = NodeTypeRegistry()

    // Shared model cache (populated from /object_info)
    private val _modelCache = MutableStateFlow(ModelCache())
    val modelCache: StateFlow<ModelCache> = _modelCache.asStateFlow()

    // WebSocket management
    private var reconnectAttempts = 0
    private var keepaliveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Reconnection coordination - prevents race conditions between multiple reconnection paths
    private var reconnectionSessionId = 0L
    private var pendingReconnectJob: Job? = null

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
     * Current server ID, or null if not connected (includes offline mode).
     */
    val currentServerId: String?
        get() = when (val state = connectionState.value) {
            is ConnectionState.Connected -> state.serverId
            is ConnectionState.OfflineConnected -> state.serverId
            else -> null
        }

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
     * @param credentials Authentication credentials for the server
     */
    @Synchronized
    fun connect(
        context: Context,
        serverId: String,
        hostname: String,
        port: Int,
        protocol: String,
        credentials: AuthCredentials = AuthCredentials.None
    ) {
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

        // Store application context for offline cache operations
        _applicationContext = context.applicationContext

        // Create shared client with detected protocol, shared client ID, and credentials
        _client = ComfyUIClient(context.applicationContext, hostname, port, credentials).apply {
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
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
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
     * Clears everything, reloads workflows, and returns to login.
     */
    fun invalidateForRestore() {
        DebugLogger.i(TAG, "Invalidating for restore")
        // Reload workflows to pick up restored user workflows
        WorkflowManager.reloadWorkflows()
        disconnect()
    }

    /**
     * Logout from the server. Sets the logout flag to prevent auto-reconnect.
     * Called when user explicitly logs out via the UI.
     */
    fun logout() {
        DebugLogger.i(TAG, "User initiated logout")
        isUserInitiatedLogout = true
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS  // Prevent reconnection attempts
        disconnect()
    }

    /**
     * Reset reconnection attempt counter and state.
     * Called when user chooses to retry connection from the connection alert dialog.
     */
    fun resetReconnectAttempts() {
        DebugLogger.i(TAG, "Resetting reconnect attempts")
        reconnectAttempts = 0
        _webSocketState.value = WebSocketState.Disconnected
    }

    /**
     * Start a new reconnection session.
     * Cancels any pending auto-reconnect and returns a new session ID.
     * Call at the start of any reconnection entry point (manual retry, silent reconnect, or ensureConnection).
     */
    private fun startNewReconnectionSession(): Long {
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null
        return ++reconnectionSessionId
    }

    /**
     * Check if a reconnection session is still valid.
     * Used in callbacks to detect and ignore stale results.
     */
    private fun isSessionValid(sessionId: Long): Boolean {
        return sessionId == reconnectionSessionId
    }

    /**
     * Show the connection alert dialog.
     * This is the single entry point for showing connection dialogs from anywhere in the app.
     *
     * @param context Context for checking offline cache availability
     * @param failureType The type of connection failure
     */
    fun showConnectionAlert(context: Context, failureType: ConnectionFailure) {
        val hasCache = when {
            failureType == ConnectionFailure.STALLED -> false
            failureType == ConnectionFailure.AUTHENTICATION -> false
            AppSettings.isMemoryFirstCache(context) -> false
            else -> hasOfflineCache(context, currentServerId ?: "")
        }
        DebugLogger.d(TAG, "showConnectionAlert: failureType=$failureType, hasCache=$hasCache")
        _connectionAlertState.value = ConnectionAlertState(failureType, hasCache)
    }

    /**
     * Clear the connection alert dialog.
     * Called when user dismisses the dialog or takes an action (retry, go offline, etc.).
     */
    fun clearConnectionAlert() {
        DebugLogger.d(TAG, "clearConnectionAlert")
        _connectionAlertState.value = null
    }

    /**
     * Ensure connection is ready before an operation.
     * Always verifies with HTTP test - don't trust cached WebSocket state.
     * Sets isConnecting state for UI feedback (e.g., "Connecting..." button).
     *
     * @param context Context for dialog
     * @param onResult Callback with success (true) or failure (false).
     *                 On failure, dialog is already shown via showConnectionAlert.
     */
    fun ensureConnection(context: Context, onResult: (success: Boolean) -> Unit) {
        DebugLogger.d(TAG, "ensureConnection: verifying connection")
        _isConnecting.value = true

        val client = _client
        if (client == null) {
            DebugLogger.w(TAG, "ensureConnection: no client")
            _isConnecting.value = false
            showConnectionAlert(context, ConnectionFailure.NETWORK)
            onResult(false)
            return
        }

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        // Always verify with HTTP test - don't trust cached state
        client.testConnection { success, _, _, failureType ->
            scope.launch {
                // Ignore stale callback if another reconnection started
                if (!isSessionValid(sessionId)) {
                    DebugLogger.d(TAG, "ensureConnection: ignoring stale callback (session $sessionId)")
                    _isConnecting.value = false
                    return@launch
                }

                if (success) {
                    DebugLogger.d(TAG, "ensureConnection: server reachable")
                    // Server reachable - ensure WebSocket is connected
                    if (isWebSocketConnected) {
                        _isConnecting.value = false
                        onResult(true)
                    } else {
                        DebugLogger.i(TAG, "ensureConnection: opening WebSocket")
                        openWebSocket()
                        val finalState = withTimeoutOrNull(10000L) {
                            _webSocketState.first { it is WebSocketState.Connected || it is WebSocketState.Failed }
                        }
                        _isConnecting.value = false
                        when (finalState) {
                            is WebSocketState.Connected -> {
                                DebugLogger.i(TAG, "ensureConnection: WebSocket connected")
                                onResult(true)
                            }
                            else -> {
                                DebugLogger.w(TAG, "ensureConnection: WebSocket failed")
                                showConnectionAlert(context, ConnectionFailure.NETWORK)
                                onResult(false)
                            }
                        }
                    }
                } else {
                    _isConnecting.value = false
                    DebugLogger.w(TAG, "ensureConnection: server unreachable, failureType=$failureType")
                    showConnectionAlert(context, failureType)
                    onResult(false)
                }
            }
        }
    }

    /**
     * Retry connection with a single attempt, triggered by user tapping "Reconnect" in the dialog.
     * Sets isReconnecting state for UI feedback (spinner on button).
     * Shows Toast and keeps dialog open on failure.
     * Closes dialog on success.
     */
    fun retrySingleAttempt(context: Context) {
        DebugLogger.d(TAG, "retrySingleAttempt: called, _client=${if (_client != null) "exists" else "NULL"}")
        _isReconnecting.value = true

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        val client = _client ?: run {
            DebugLogger.w(TAG, "retrySingleAttempt: aborting - client is null")
            _isReconnecting.value = false
            Toast.makeText(context, R.string.toast_reconnect_failed, Toast.LENGTH_SHORT).show()
            return
        }

        DebugLogger.i(TAG, "User initiated single retry attempt (session $sessionId)")
        _webSocketState.value = WebSocketState.Disconnected

        client.closeWebSocket()
        DebugLogger.d(TAG, "retrySingleAttempt: closed WebSocket, calling testConnection")
        client.testConnection { success, errorMessage, _, failureType ->
            scope.launch {
                // Ignore stale callback if session has changed
                if (!isSessionValid(sessionId)) {
                    DebugLogger.d(TAG, "retrySingleAttempt: ignoring stale callback (session $sessionId)")
                    return@launch
                }

                DebugLogger.d(TAG, "retrySingleAttempt: testConnection callback - success=$success, failureType=$failureType")
                if (success) {
                    DebugLogger.i(TAG, "Single retry succeeded, opening WebSocket")
                    reconnectAttempts = 0
                    openWebSocket()

                    // Wait for WebSocket to actually connect before clearing dialog
                    val finalState = withTimeoutOrNull(10000L) {
                        _webSocketState.first { it is WebSocketState.Connected || it is WebSocketState.Failed }
                    }

                    _isReconnecting.value = false
                    if (finalState is WebSocketState.Connected) {
                        DebugLogger.i(TAG, "WebSocket connected, clearing dialog")
                        clearConnectionAlert()
                    } else {
                        DebugLogger.w(TAG, "WebSocket failed to connect after successful HTTP test")
                        Toast.makeText(context, R.string.toast_reconnect_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    _isReconnecting.value = false
                    DebugLogger.w(TAG, "Single retry failed: $failureType")
                    _webSocketState.value = WebSocketState.Failed(
                        reason = errorMessage,
                        failureType = failureType
                    )
                    Toast.makeText(context, R.string.toast_reconnect_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Attempt a silent reconnection when app returns from background.
     * Unlike retrySingleAttempt(), this is designed to recover gracefully:
     * - If already connected, does nothing
     * - If reconnect succeeds, app continues normally
     * - If reconnect fails, sets Failed state (dialog will appear via normal ViewModel flow)
     */
    fun attemptSilentReconnect() {
        // Already connected or connecting - don't interfere
        if (_webSocketState.value is WebSocketState.Connected ||
            _webSocketState.value is WebSocketState.Connecting) {
            DebugLogger.d(TAG, "attemptSilentReconnect: state=${_webSocketState.value}, skipping")
            return
        }

        val client = _client ?: run {
            DebugLogger.w(TAG, "attemptSilentReconnect: client is null")
            return
        }

        val sessionId = startNewReconnectionSession()  // Cancel any pending auto-reconnect

        DebugLogger.i(TAG, "Attempting silent reconnect on resume")
        // Set to Disconnected so openWebSocket() will work and StateFlow will emit on failure
        _webSocketState.value = WebSocketState.Disconnected

        client.closeWebSocket()
        client.testConnection { success, errorMessage, _, failureType ->
            // Ignore stale callback if another reconnection started
            if (!isSessionValid(sessionId)) {
                DebugLogger.d(TAG, "attemptSilentReconnect: ignoring stale callback (session $sessionId)")
                return@testConnection
            }

            DebugLogger.d(TAG, "attemptSilentReconnect: testConnection result - success=$success, failureType=$failureType")
            if (success) {
                DebugLogger.i(TAG, "Silent reconnect succeeded, opening WebSocket")
                reconnectAttempts = 0
                openWebSocket()
            } else {
                DebugLogger.w(TAG, "Silent reconnect failed: $failureType")
                _webSocketState.value = WebSocketState.Failed(
                    reason = errorMessage,
                    failureType = failureType
                )
                // Dialog will appear via existing GenerationViewModel observation
            }
        }
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
        DebugLogger.d(TAG, "openWebSocket: called, currentState=${_webSocketState.value}")
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
            DebugLogger.d(TAG, "WebSocket already connected/connecting - skipping open")
            return true
        }

        DebugLogger.i(TAG, "Opening WebSocket connection")
        _webSocketState.value = WebSocketState.Connecting

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLogger.i(TAG, "WebSocket connected")
                reconnectAttempts = 0
                _webSocketState.value = WebSocketState.Connected

                // Clear any pending connection dialog and reset flags
                // This handles the case where auto-reconnect succeeds while dialog is open
                if (_connectionAlertState.value != null) {
                    DebugLogger.i(TAG, "WebSocket connected, clearing dialog")
                    _connectionAlertState.value = null
                }
                _isReconnecting.value = false
                _isConnecting.value = false

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
                DebugLogger.e(TAG, "WebSocket connection failed: ${t::class.simpleName}")
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
                        WebSocketMessage.Executing(promptId, node)
                    }
                }
                "progress" -> {
                    val data = message.optJSONObject("data")
                    val value = data?.optInt("value", 0) ?: 0
                    val max = data?.optInt("max", 0) ?: 0
                    if (max > 0) WebSocketMessage.Progress(value, max) else null
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
                    DebugLogger.e(TAG, "WS: execution_error (promptId: ${Obfuscator.promptId(promptId)})")
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
                    WebSocketMessage.ExecutionCached(nodes)
                }
                "status" -> {
                    val data = message.optJSONObject("data")
                    val status = data?.optJSONObject("status")
                    val execInfo = status?.optJSONObject("exec_info")
                    val queueRemaining = execInfo?.optInt("queue_remaining") ?: 0
                    // Update JobRegistry with queue size
                    JobRegistry.updateFromStatus(queueRemaining)
                    WebSocketMessage.Status(queueRemaining)
                }
                "previewing", "executed" -> null  // Don't emit for these types
                else -> {
                    if (messageType.isNotEmpty()) WebSocketMessage.Unknown(messageType) else null
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
        // Don't auto-reconnect if manual retry or ensureConnection is in progress
        if (_isReconnecting.value || _isConnecting.value) {
            DebugLogger.d(TAG, "scheduleReconnect: skipped (manual reconnection in progress)")
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            DebugLogger.w(TAG, "Max reconnect attempts reached")
            _webSocketState.value = WebSocketState.Failed(
                reason = "Max reconnect attempts reached",
                failureType = ConnectionFailure.NETWORK
            )
            return
        }

        reconnectAttempts++
        _webSocketState.value = WebSocketState.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)

        // Linear delay: 2s, 4s, 6s for attempts 1, 2, 3
        val delayMs = (reconnectAttempts * 2000).toLong()
        val sessionId = reconnectionSessionId  // Capture current session

        pendingReconnectJob = scope.launch {
            delay(delayMs)
            // Only proceed if session still valid (wasn't cancelled by manual retry)
            if (isSessionValid(sessionId)) {
                reconnectWebSocket(sessionId)
            } else {
                DebugLogger.d(TAG, "scheduleReconnect: cancelled (session invalidated)")
            }
        }
    }

    /**
     * Attempt to reconnect WebSocket
     * @param sessionId The reconnection session ID for stale callback detection
     */
    private fun reconnectWebSocket(sessionId: Long) {
        val client = _client ?: return

        client.closeWebSocket()

        client.testConnection { success, errorMessage, _, failureType ->
            // Ignore stale callback if session has changed
            if (!isSessionValid(sessionId)) {
                DebugLogger.d(TAG, "reconnectWebSocket: ignoring stale callback (session $sessionId)")
                return@testConnection
            }

            if (success) {
                openWebSocket()
            } else if (failureType == ConnectionFailure.AUTHENTICATION) {
                // Auth failure - don't retry, notify UI immediately
                DebugLogger.w(TAG, "Authentication failed during reconnection")
                reconnectAttempts = MAX_RECONNECT_ATTEMPTS  // Prevent further retries
                _webSocketState.value = WebSocketState.Failed(
                    reason = errorMessage,
                    failureType = ConnectionFailure.AUTHENTICATION
                )
            } else {
                // Network error - schedule retry
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
                // Parse node definitions for workflow editor
                nodeTypeRegistry.parseObjectInfo(objectInfo)

                // Cache object_info to disk for offline mode
                val ctx = _applicationContext
                val serverId = currentServerId
                if (ctx != null && serverId != null) {
                    ObjectInfoCache.saveObjectInfo(ctx, serverId, objectInfo)
                }

                // Extract model lists for generation screens (field-name-based discovery)
                val models = extractModelLists()
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
     * Load server data from offline cache.
     * Used when offline mode is enabled to populate node registry and model lists.
     *
     * @param context Application context for cache access
     * @param serverId Server ID to load cache for
     * @return true if cache was loaded successfully, false otherwise
     */
    fun loadFromOfflineCache(context: Context, serverId: String): Boolean {
        DebugLogger.i(TAG, "Loading server data from offline cache for server: $serverId")

        if (!ObjectInfoCache.hasCache(context, serverId)) {
            DebugLogger.w(TAG, "No offline cache available for server: $serverId")
            return false
        }

        val objectInfo = ObjectInfoCache.loadObjectInfo(context, serverId)
        if (objectInfo == null) {
            DebugLogger.e(TAG, "Failed to load offline cache for server: $serverId")
            return false
        }

        // Store application context for cache operations
        _applicationContext = context.applicationContext

        // Set offline connected state so currentServerId is available
        _connectionState.value = ConnectionState.OfflineConnected(serverId = serverId)

        // Parse node definitions for workflow editor
        nodeTypeRegistry.parseObjectInfo(objectInfo)

        // Extract model lists for generation screens
        val models = extractModelLists()
        _modelCache.value = models.copy(isLoaded = true, isLoading = false)

        DebugLogger.i(TAG, "Offline cache loaded: ${models.checkpoints.size} checkpoints, " +
                "${models.unets.size} unets, ${models.vaes.size} vaes, " +
                "${models.clips.size} clips, ${models.loras.size} loras")

        // Initialize MediaCache with context and set cache mode from user preferences
        // This must happen BEFORE gallery preload so images can be fetched from disk cache
        val isMemoryFirst = AppSettings.isMemoryFirstCache(context)
        MediaCache.ensureInitialized(context)
        MediaCache.setMemoryFirstMode(isMemoryFirst)
        DebugLogger.d(TAG, "MediaCache initialized for offline mode (memoryFirst=$isMemoryFirst)")

        // Trigger gallery preload from cache
        GalleryRepository.getInstance().apply {
            initialize(context)
            startBackgroundPreload()
        }

        return true
    }

    /**
     * Check if offline cache is available for a server.
     *
     * @param context Application context for cache access
     * @param serverId Server ID to check
     * @return true if cache exists, false otherwise
     */
    fun hasOfflineCache(context: Context, serverId: String): Boolean {
        return ObjectInfoCache.hasCache(context, serverId)
    }

    /**
     * Extract model lists from NodeTypeRegistry by field name.
     * Automatically discovers models from any loader node (standard, GGUF, future plugins).
     *
     * Uses TemplateKeyRegistry to translate placeholder names to actual ComfyUI field names.
     * For example, "text_encoder_name" placeholder maps to "text_encoder" field in LTXAVTextEncoderLoader.
     */
    private fun extractModelLists(): ModelCache {
        return ModelCache(
            checkpoints = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("ckpt_name")
            ),
            unets = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("unet_name")
            ),
            vaes = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("vae_name")
            ),
            clips = nodeTypeRegistry.getOptionsForFieldPrefix("clip_name"),
            loras = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("lora_name")
            ),
            upscaleMethods = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("upscale_method")
            ),
            textEncoders = nodeTypeRegistry.getOptionsForField(
                TemplateKeyRegistry.getJsonKeyForPlaceholder("text_encoder_name")
            ),
            // Use node-specific extraction for latent upscale models because "model_name"
            // is a generic field used by multiple unrelated nodes (UpscaleModelLoader, KlingOmniPro*, etc.)
            latentUpscaleModels = nodeTypeRegistry.getOptionsForNodeInput(
                "LatentUpscaleModelLoader",
                TemplateKeyRegistry.getJsonKeyForPlaceholder("latent_upscale_model")
            )
        )
    }
}
