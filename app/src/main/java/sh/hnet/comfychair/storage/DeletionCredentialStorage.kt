package sh.hnet.comfychair.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Unlike legacy CredentialStorage, fail CLOSED if Keystore is unavailable. No plaintext fallback. */
class DeletionCredentialStorage(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context, "MediaDeletionCredentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun get(server: String): String = prefs.getString(server, "").orEmpty()
    // Scope includes exact configured origin, so editing a server address never reuses LAN consent/key.
    fun lan(server: String): Boolean = prefs.getBoolean("lan:" + server, false)
    fun save(server: String, value: String, allowLan: Boolean = false) {
        require(value.isEmpty() || value.length >= 32)
        check(prefs.edit().putString(server, value).putBoolean("lan:" + server, allowLan).commit())
    }
}
