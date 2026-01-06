package sh.hnet.comfychair.connection

/**
 * Types of connection failures that can occur when connecting to ComfyUI server.
 * Used to distinguish recoverable failures (network issues) from non-recoverable ones (auth).
 */
enum class ConnectionFailure {
    /** No failure - connection succeeded */
    NONE,
    /** Authentication failed (401/403) - credentials are invalid */
    AUTHENTICATION,
    /** Network or other recoverable error - retry may succeed */
    NETWORK,
    /** Server is not a valid ComfyUI server */
    INVALID_SERVER
}
