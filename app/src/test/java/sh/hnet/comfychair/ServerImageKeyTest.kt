package sh.hnet.comfychair

import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.model.ServerImage

class ServerImageKeyTest {
    @Test fun gridKeysAreSaveableStringsAndUnambiguous() {
        val a = ServerImage("a/b.png", "c", "input")
        val b = ServerImage("b.png", "c/a", "input")
        assertNotEquals(a.stableKey, b.stableKey)
        assertEquals(a.stableKey, a.copy().stableKey)
        assertNotEquals(a.stableKey, a.copy(type = "output").stableKey)
    }
}
