package sh.hnet.comfychair

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import sh.hnet.comfychair.model.AuthCredentials

/**
 * OkHttp interceptor that adds Authorization headers to requests.
 * Supports HTTP Basic Auth, Bearer token, and Cookie authentication.
 *
 * For Cookie auth (browser SSO), detects session expiry via 401/403 responses
 * or redirects to auth domains, and notifies via [onSessionExpired] callback.
 */
class AuthInterceptor(
    credentials: AuthCredentials = AuthCredentials.None
) : Interceptor {

    @Volatile
    private var currentCredentials: AuthCredentials = credentials

    /** Called when a cookie-based session appears expired (401/403 or auth redirect). */
    @Volatile
    var onSessionExpired: (() -> Unit)? = null

    /** Prevent firing the callback multiple times before re-auth completes. */
    @Volatile
    private var sessionExpiredFired = false

    /**
     * Update the credentials used for authentication.
     * Thread-safe - can be called from any thread.
     */
    fun setCredentials(newCredentials: AuthCredentials) {
        currentCredentials = newCredentials
        // Reset expiry flag when new credentials are set (re-auth completed)
        sessionExpiredFired = false
    }

    /**
     * Get the current credentials.
     */
    fun getCredentials(): AuthCredentials = currentCredentials

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val newRequest = when (val creds = currentCredentials) {
            is AuthCredentials.None -> originalRequest
            is AuthCredentials.Basic -> originalRequest.newBuilder()
                .header("Authorization", Credentials.basic(creds.username, creds.password))
                .build()
            is AuthCredentials.Bearer -> originalRequest.newBuilder()
                .header("Authorization", "Bearer ${creds.token}")
                .build()
            is AuthCredentials.Cookie -> originalRequest.newBuilder()
                .header("Cookie", creds.cookies)
                .build()
        }

        val response = chain.proceed(newRequest)

        // Detect session expiry for cookie-based auth
        if (currentCredentials is AuthCredentials.Cookie && !sessionExpiredFired) {
            val expired = when {
                response.code == 401 || response.code == 403 -> true
                // Authentik/OAuth redirects: 302 to a different host (auth domain)
                response.isRedirect -> {
                    val location = response.header("Location")
                    val requestHost = originalRequest.url.host
                    location != null && !location.contains(requestHost, ignoreCase = true)
                }
                else -> false
            }
            if (expired) {
                sessionExpiredFired = true
                onSessionExpired?.invoke()
            }
        }

        return response
    }
}
