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
    INVALID_SERVER,
    /** Transfer stalled - no data transferred for extended period */
    STALLED,
    /** Server certificate is not trusted, or differs from the one the user accepted - needs the user's decision */
    CERTIFICATE,
    /** HTTPS stopped working for a server that used it before, while HTTP responds - needs the user's consent */
    INSECURE_FALLBACK
}
