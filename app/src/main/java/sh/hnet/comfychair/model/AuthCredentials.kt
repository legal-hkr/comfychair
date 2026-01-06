package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable

/**
 * Authentication credentials for a server connection.
 */
@Immutable
sealed class AuthCredentials {
    /** No authentication */
    data object None : AuthCredentials()

    /** HTTP Basic authentication credentials */
    data class Basic(
        val username: String,
        val password: String
    ) : AuthCredentials()

    /** Bearer token / API key */
    data class Bearer(
        val token: String
    ) : AuthCredentials()
}
