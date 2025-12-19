package sh.hnet.comfychair.connection

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.WebSocketListener
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.repository.GalleryRepository
import java.util.UUID

/**
 * Connection state representing whether the app is connected to a ComfyUI server.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connected(
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
 * - WebSocket lifecycle management
 * - Automatic cache invalidation on disconnect
 *
 * Usage:
 * 1. Call [connect] after successful connection test in LoginScreen
 * 2. Access [client] from any component needing server communication
 * 3. Call [disconnect] on logout or connection change
 */
object ConnectionManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var _client: ComfyUIClient? = null
    private var _clientId: String? = null

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
     * Establish connection to a ComfyUI server.
     *
     * This should be called after a successful connection test in LoginScreen.
     * If already connected to the same server, this is a no-op.
     * If connected to a different server, caches are invalidated first.
     *
     * @param context Application context
     * @param hostname Server hostname
     * @param port Server port
     * @param protocol Detected protocol ("http" or "https")
     */
    @Synchronized
    fun connect(context: Context, hostname: String, port: Int, protocol: String) {
        val current = connectionState.value

        // Check if already connected to the same server
        if (current is ConnectionState.Connected &&
            current.hostname == hostname &&
            current.port == port) {
            return
        }

        // Disconnect from previous server if any
        if (current is ConnectionState.Connected) {
            invalidateAll()
            _client?.shutdown()
        }

        // Generate unique client ID for this session
        // Use same format as original ComfyUIClient: comfychair_android_UUID
        _clientId = "comfychair_android_${UUID.randomUUID()}"

        // Create shared client with detected protocol and shared client ID
        _client = ComfyUIClient(context.applicationContext, hostname, port).apply {
            setWorkingProtocol(protocol)
            setClientId(_clientId!!)
        }

        _connectionState.value = ConnectionState.Connected(
            hostname = hostname,
            port = port,
            protocol = protocol,
            clientId = _clientId!!
        )
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

        invalidateAll()
        _client?.shutdown()
        _client = null
        _clientId = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Called when backup restore changes connection settings.
     * Same as disconnect - clears everything and returns to login.
     */
    fun invalidateForRestore() {
        disconnect()
    }

    /**
     * Clear all connection-dependent caches.
     */
    private fun invalidateAll() {
        GalleryRepository.getInstance().reset()
        MediaStateHolder.clearAll()
        MediaCache.reset()
    }

    // WebSocket delegation methods

    /**
     * Open WebSocket connection to the server.
     * @param listener WebSocket event listener
     * @return true if connection attempt started
     */
    fun openWebSocket(listener: WebSocketListener): Boolean {
        val client = _client ?: return false
        val id = _clientId ?: return false
        return client.openWebSocket(id, listener)
    }

    /**
     * Close the active WebSocket connection.
     */
    fun closeWebSocket() {
        _client?.closeWebSocket()
    }

    /**
     * Check if WebSocket is currently connected.
     */
    fun isWebSocketConnected(): Boolean {
        return _client?.isWebSocketConnected() ?: false
    }
}
