package sh.hnet.comfychair.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.util.DebugLogger

/**
 * Secure storage for authentication credentials using EncryptedSharedPreferences.
 * Credentials are stored separately from server configuration for security.
 */
class CredentialStorage(context: Context) {

    companion object {
        private const val TAG = "CredentialStorage"
        private const val PREFS_NAME = "CredentialPrefs"
        private const val KEY_USERNAME_PREFIX = "username_"
        private const val KEY_PASSWORD_PREFIX = "password_"
        private const val KEY_TOKEN_PREFIX = "token_"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to create encrypted prefs, using fallback: ${e.message}")
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Save credentials for a server.
     * @param serverId The server UUID
     * @param credentials The credentials to save
     */
    fun saveCredentials(serverId: String, credentials: AuthCredentials) {
        prefs.edit().apply {
            // Clear existing credentials first
            remove("$KEY_USERNAME_PREFIX$serverId")
            remove("$KEY_PASSWORD_PREFIX$serverId")
            remove("$KEY_TOKEN_PREFIX$serverId")

            when (credentials) {
                is AuthCredentials.None -> { /* Nothing to store */ }
                is AuthCredentials.Basic -> {
                    putString("$KEY_USERNAME_PREFIX$serverId", credentials.username)
                    putString("$KEY_PASSWORD_PREFIX$serverId", credentials.password)
                }
                is AuthCredentials.Bearer -> {
                    putString("$KEY_TOKEN_PREFIX$serverId", credentials.token)
                }
            }
            apply()
        }
        DebugLogger.d(TAG, "Saved credentials for server $serverId")
    }

    /**
     * Get credentials for a server.
     * @param serverId The server UUID
     * @param authType The expected auth type (from Server model)
     * @return The credentials, or AuthCredentials.None if not found
     */
    fun getCredentials(serverId: String, authType: AuthType): AuthCredentials {
        return when (authType) {
            AuthType.NONE -> AuthCredentials.None
            AuthType.BASIC -> {
                val username = prefs.getString("$KEY_USERNAME_PREFIX$serverId", null)
                val password = prefs.getString("$KEY_PASSWORD_PREFIX$serverId", null)
                if (username != null && password != null) {
                    AuthCredentials.Basic(username, password)
                } else {
                    AuthCredentials.None
                }
            }
            AuthType.BEARER -> {
                val token = prefs.getString("$KEY_TOKEN_PREFIX$serverId", null)
                if (token != null) {
                    AuthCredentials.Bearer(token)
                } else {
                    AuthCredentials.None
                }
            }
        }
    }

    /**
     * Delete credentials for a server.
     * Should be called when a server is deleted.
     */
    fun deleteCredentials(serverId: String) {
        prefs.edit().apply {
            remove("$KEY_USERNAME_PREFIX$serverId")
            remove("$KEY_PASSWORD_PREFIX$serverId")
            remove("$KEY_TOKEN_PREFIX$serverId")
            apply()
        }
        DebugLogger.d(TAG, "Deleted credentials for server $serverId")
    }

    /**
     * Check if credentials exist for a server.
     */
    fun hasCredentials(serverId: String, authType: AuthType): Boolean {
        return when (authType) {
            AuthType.NONE -> true
            AuthType.BASIC -> {
                prefs.getString("$KEY_USERNAME_PREFIX$serverId", null) != null &&
                    prefs.getString("$KEY_PASSWORD_PREFIX$serverId", null) != null
            }
            AuthType.BEARER -> {
                prefs.getString("$KEY_TOKEN_PREFIX$serverId", null) != null
            }
        }
    }
}
