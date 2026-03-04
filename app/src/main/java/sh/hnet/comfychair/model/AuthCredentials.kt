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

    /** Browser-captured session cookies (e.g., from Authentik SSO) */
    data class Cookie(
        /** Raw Cookie header string for the ComfyUI server (e.g. outpost cookie). */
        val cookies: String,
        /**
         * Hostname of the SSO/auth domain (e.g. "auth.bun.cafe").
         * Empty if not yet captured or not applicable.
         */
        val authDomain: String = "",
        /**
         * Raw Cookie header string for the auth domain (e.g. Authentik session cookie).
         * Used by the OkHttp silent refresh flow to re-authenticate without UI.
         */
        val authDomainCookies: String = ""
    ) : AuthCredentials()
}
