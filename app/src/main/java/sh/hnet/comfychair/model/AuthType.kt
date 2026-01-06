package sh.hnet.comfychair.model

/**
 * Authentication types supported for ComfyUI server connections.
 */
enum class AuthType {
    /** No authentication required */
    NONE,
    /** HTTP Basic authentication (username + password) */
    BASIC,
    /** API key or bearer token authentication */
    BEARER
}
