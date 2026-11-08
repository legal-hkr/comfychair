package sh.hnet.comfychair.storage

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretCodecTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `a sealed value opens under its name`() {
        val key = newKey()
        val codec = SecretCodec { key }
        val sealed = codec.seal("password_a", "zażółć gęślą jaźń")!!
        assertFalse(sealed.contains("zażółć"))
        assertEquals("zażółć gęślą jaźń", codec.open("password_a", sealed))
    }

    @Test
    fun `each seal uses its own IV`() {
        val key = newKey()
        val codec = SecretCodec { key }
        assertNotEquals(codec.seal("token_a", "same"), codec.seal("token_a", "same"))
    }

    @Test
    fun `a value moved to another name doesn't open`() {
        val key = newKey()
        val codec = SecretCodec { key }
        val sealed = codec.seal("password_a", "secret")!!
        assertNull(codec.open("password_b", sealed))
    }

    @Test
    fun `another key, damage or no key give nothing`() {
        val key = newKey()
        val sealed = SecretCodec { key }.seal("token_a", "secret")!!
        assertNull(SecretCodec { newKey() }.open("token_a", sealed))
        val bytes = java.util.Base64.getDecoder().decode(sealed).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(SecretCodec { key }.open("token_a", java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull(SecretCodec { key }.open("token_a", "not base64 !"))
        assertNull(SecretCodec { null }.seal("token_a", "secret"))
        assertNull(SecretCodec { null }.open("token_a", sealed))
    }
}
