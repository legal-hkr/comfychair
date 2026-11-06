package sh.hnet.comfychair.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionValidatorTest {
    @Test
    fun valuesTakeLinksOfTheirOwnType() {
        assertTrue(ConnectionValidator.acceptsValueLink("INT", "INT"))
        assertTrue(ConnectionValidator.acceptsValueLink("string", "STRING"))
        assertTrue(ConnectionValidator.acceptsValueLink("*", "FLOAT"))
    }

    @Test
    fun valuesRefuseOtherTypes() {
        // INT and FLOAT don't mix, as in ComfyUI
        assertFalse(ConnectionValidator.acceptsValueLink("FLOAT", "INT"))
        assertFalse(ConnectionValidator.acceptsValueLink("INT", "FLOAT"))
        assertFalse(ConnectionValidator.acceptsValueLink("IMAGE", "STRING"))
        // An unknown output isn't a target for values (it is for connection inputs)
        assertFalse(ConnectionValidator.acceptsValueLink(null, "INT"))
    }
}
