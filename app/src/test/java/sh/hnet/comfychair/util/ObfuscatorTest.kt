package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException

class ObfuscatorTest {

    @Test
    fun `an exception is logged by its type, never its message`() {
        assertEquals("<UnknownHostException>", Obfuscator.error(UnknownHostException("Unable to resolve host \"my-server\"")))
        assertEquals("<error>", Obfuscator.error(null))
    }

    @Test
    fun `a title is logged by its length`() {
        assertEquals("<title:10chars>", Obfuscator.title("My private"))
        assertEquals("<untitled>", Obfuscator.title(" "))
    }
}
