package sh.hnet.comfychair.storage

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values sealed with AES-256-GCM under [key]: the stored text is Base64 of the IV followed by the ciphertext with its
 * tag. The entry's name is the associated data, so a value moved to another name (another server's password) doesn't
 * open. Anything that fails (no key, a changed key, a damaged value) gives null: nothing is ever stored or returned in
 * clear.
 */
class SecretCodec(private val key: () -> SecretKey?) {

    fun seal(name: String, value: String): String? = runCatching {
        val secretKey = key() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The IV comes from the cipher: the Android Keystore requires keys to pick their own
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        if (iv.size != IV_SIZE) return null
        Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    fun open(name: String, stored: String): String? = runCatching {
        val secretKey = key() ?: return null
        val bytes = Base64.getDecoder().decode(stored)
        if (bytes.size <= IV_SIZE) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
