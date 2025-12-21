package sh.hnet.comfychair.connection

import android.graphics.Bitmap

/**
 * Typed WebSocket messages parsed from ComfyUI server.
 * ConnectionManager parses raw messages and emits these typed events via SharedFlow.
 */
sealed class WebSocketMessage {
    data class ExecutionStart(val promptId: String) : WebSocketMessage()
    data class Executing(val promptId: String?, val node: String?) : WebSocketMessage()
    data class ExecutionComplete(val promptId: String) : WebSocketMessage()
    data class Progress(val value: Int, val max: Int) : WebSocketMessage()
    data class ExecutionError(val promptId: String?, val message: String) : WebSocketMessage()
    data class ExecutionSuccess(val promptId: String) : WebSocketMessage()
    data class Status(val queueRemaining: Int?) : WebSocketMessage()
    data class ExecutionCached(val nodeCount: Int) : WebSocketMessage()
    data class PreviewImage(val bitmap: Bitmap) : WebSocketMessage()
    data class Unknown(val type: String) : WebSocketMessage()
}

/**
 * WebSocket connection state.
 * Subscribers can react to state changes (e.g., check server on reconnect).
 */
sealed class WebSocketState {
    data object Disconnected : WebSocketState()
    data object Connecting : WebSocketState()
    data object Connected : WebSocketState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : WebSocketState()
    data class Failed(val reason: String?) : WebSocketState()
}
