package sh.hnet.comfychair.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator

/**
 * Credentials of the servers (username and password, token, a browser sign-in's cookies), each value sealed with an
 * AES-256-GCM key of the Android Keystore ([SecretCodec]; the key never leaves the device and is hardware-backed where
 * the phone has it) and kept in plain preferences. Without a working key nothing is saved: the server then shows as
 * missing its credentials, as after a restore, and the user enters them in its form. Nothing is ever written in clear.
 *
 * Until 0.9.5 the credentials were in androidx.security's EncryptedSharedPreferences, deprecated by Google; they are
 * moved over once, on first use ([migrateLegacy]), and the old file and its master key deleted. The library stays only
 * for that read.
 */
class CredentialStorage(context: Context) {

    companion object {
        private const val TAG = "CredentialStorage"
        const val PREFS_NAME = "Credentials"
        private const val KEY_ALIAS = "comfychair_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_USERNAME_PREFIX = "username_"
        private const val KEY_PASSWORD_PREFIX = "password_"
        private const val KEY_TOKEN_PREFIX = "token_"
        private const val KEY_COOKIES_PREFIX = "cookies_"
        private val PREFIXES = listOf(KEY_USERNAME_PREFIX, KEY_PASSWORD_PREFIX, KEY_TOKEN_PREFIX, KEY_COOKIES_PREFIX)

        // The EncryptedSharedPreferences file and the library's master key, read once to move the credentials over
        const val LEGACY_PREFS_NAME = "CredentialPrefs"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        @Volatile
        private var cachedKey: SecretKey? = null

        @Volatile
        private var migrated = false

        /** The Keystore key, made on first use; null when the Keystore can't give one. */
        private fun keystoreKey(): SecretKey? {
            cachedKey?.let { return it }
            return synchronized(this) {
                cachedKey ?: runCatching {
                    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                        init(
                            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build()
                        )
                        generateKey()
                    }
                }.onFailure { DebugLogger.e(TAG, "Keystore key unavailable: ${Obfuscator.error(it)}") }
                    .getOrNull()
                    ?.also { cachedKey = it }
            }
        }

        private val codec = SecretCodec(::keystoreKey)

        /**
         * The credentials of EncryptedSharedPreferences moved into this storage, once per install: the entries the
         * library decrypts, and entries in clear its old fallback may have left in the same file (by their names; the
         * library's own names are Base64, never one of ours). Whatever can't be read is dropped: those servers ask for
         * their credentials again. The old file and the library's master key are deleted either way.
         */
        @Suppress("DEPRECATION")
        private fun migrateLegacy(context: Context, prefs: SharedPreferences) {
            if (migrated) return
            synchronized(this) {
                if (migrated) return
                val file = File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")
                if (file.exists()) {
                    val ours: (Map.Entry<String, Any?>) -> Boolean = { (key, value) -> value is String && PREFIXES.any { key.startsWith(it) } }
                    val decrypted = runCatching {
                        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                        EncryptedSharedPreferences.create(
                            context,
                            LEGACY_PREFS_NAME,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        ).all.filter(ours)
                    }.onFailure { DebugLogger.w(TAG, "Old credentials unreadable: ${Obfuscator.error(it)}") }.getOrNull().orEmpty()
                    val inClear = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).all.filter(ours)
                    val entries = inClear + decrypted
                    var moved = 0
                    val editor = prefs.edit()
                    entries.forEach { (key, value) ->
                        if (prefs.contains(key)) return@forEach
                        codec.seal(key, value as String)?.let {
                            editor.putString(key, it)
                            moved++
                        }
                    }
                    editor.commit()
                    context.deleteSharedPreferences(LEGACY_PREFS_NAME)
                    runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(LEGACY_MASTER_KEY_ALIAS) }
                    DebugLogger.i(TAG, "Moved $moved of ${entries.size} credential entries from the old storage")
                }
                migrated = true
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacy(context.applicationContext, prefs)
    }

    private fun SharedPreferences.Editor.putSecret(name: String, value: String) {
        val sealed = codec.seal(name, value)
        if (sealed != null) putString(name, sealed) else {
            remove(name)
            DebugLogger.w(TAG, "Credential not saved: no Keystore key")
        }
    }

    private fun secret(name: String): String? = prefs.getString(name, null)?.let { codec.open(name, it) }

    /**
     * Save credentials for a server.
     * @param serverId The server UUID
     * @param credentials The credentials to save
     */
    fun saveCredentials(serverId: String, credentials: AuthCredentials) {
        prefs.edit().apply {
            // Clear all existing credentials first
            remove("$KEY_USERNAME_PREFIX$serverId")
            remove("$KEY_PASSWORD_PREFIX$serverId")
            remove("$KEY_TOKEN_PREFIX$serverId")
            remove("$KEY_COOKIES_PREFIX$serverId")

            when (credentials) {
                is AuthCredentials.None -> { /* Nothing to store */ }
                is AuthCredentials.Basic -> {
                    putSecret("$KEY_USERNAME_PREFIX$serverId", credentials.username)
                    putSecret("$KEY_PASSWORD_PREFIX$serverId", credentials.password)
                }
                is AuthCredentials.Bearer -> {
                    putSecret("$KEY_TOKEN_PREFIX$serverId", credentials.token)
                }
                is AuthCredentials.Cookie -> {
                    putSecret("$KEY_COOKIES_PREFIX$serverId", credentials.cookies)
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
                val username = secret("$KEY_USERNAME_PREFIX$serverId")
                val password = secret("$KEY_PASSWORD_PREFIX$serverId")
                if (username != null && password != null) {
                    AuthCredentials.Basic(username, password)
                } else {
                    AuthCredentials.None
                }
            }
            AuthType.BEARER -> {
                val token = secret("$KEY_TOKEN_PREFIX$serverId")
                if (token != null) {
                    AuthCredentials.Bearer(token)
                } else {
                    AuthCredentials.None
                }
            }
            AuthType.BROWSER -> {
                val cookies = secret("$KEY_COOKIES_PREFIX$serverId")
                if (cookies != null) {
                    AuthCredentials.Cookie(cookies)
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
            remove("$KEY_COOKIES_PREFIX$serverId")
            apply()
        }
        DebugLogger.d(TAG, "Deleted credentials for server $serverId")
    }

    /**
     * Whether a server signs in with a username and password or a token that this device doesn't have: restored from a
     * backup or moved to a new phone, which carry no credentials. Browser sign-in isn't counted: Connect signs in.
     */
    fun isMissingCredentials(serverId: String, authType: AuthType): Boolean =
        (authType == AuthType.BASIC || authType == AuthType.BEARER) && !hasCredentials(serverId, authType)

    /**
     * Check if credentials exist for a server.
     */
    fun hasCredentials(serverId: String, authType: AuthType): Boolean {
        return when (authType) {
            AuthType.NONE -> true
            AuthType.BASIC -> {
                secret("$KEY_USERNAME_PREFIX$serverId") != null &&
                    secret("$KEY_PASSWORD_PREFIX$serverId") != null
            }
            AuthType.BEARER -> {
                secret("$KEY_TOKEN_PREFIX$serverId") != null
            }
            AuthType.BROWSER -> {
                secret("$KEY_COOKIES_PREFIX$serverId") != null
            }
        }
    }

    /** Delete the credentials of servers not in [validServerIds]; returns how many entries went. */
    fun deleteAllExcept(validServerIds: Set<String>): Int {
        val stale = prefs.all.keys.filter { key ->
            val prefix = PREFIXES.firstOrNull { key.startsWith(it) } ?: return@filter false
            key.removePrefix(prefix) !in validServerIds
        }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        return stale.size
    }
}
