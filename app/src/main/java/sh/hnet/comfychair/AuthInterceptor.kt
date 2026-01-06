package sh.hnet.comfychair

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import sh.hnet.comfychair.model.AuthCredentials

/**
 * OkHttp interceptor that adds Authorization headers to requests.
 * Supports HTTP Basic Auth and Bearer token authentication.
 */
class AuthInterceptor(
    credentials: AuthCredentials = AuthCredentials.None
) : Interceptor {

    @Volatile
    private var currentCredentials: AuthCredentials = credentials

    /**
     * Update the credentials used for authentication.
     * Thread-safe - can be called from any thread.
     */
    fun setCredentials(newCredentials: AuthCredentials) {
        currentCredentials = newCredentials
    }

    /**
     * Get the current credentials.
     */
    fun getCredentials(): AuthCredentials = currentCredentials

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val authHeader = when (val creds = currentCredentials) {
            is AuthCredentials.None -> null
            is AuthCredentials.Basic -> Credentials.basic(creds.username, creds.password)
            is AuthCredentials.Bearer -> "Bearer ${creds.token}"
        }

        val newRequest = if (authHeader != null) {
            originalRequest.newBuilder()
                .header("Authorization", authHeader)
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(newRequest)
    }
}
