package sh.hnet.comfychair.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerImportOriginTest {
    @Test fun switchingServerOrClientInvalidatesOpenDialogAndInflightConfirmation() {
        val originalClient = Any()
        val anotherClient = Any()
        val origin = ServerImportOrigin(originalClient, "server-a")
        assertTrue(origin.matches(originalClient, "server-a"))
        assertFalse(origin.matches(originalClient, "server-b"))
        assertFalse(origin.matches(anotherClient, "server-a"))
        assertFalse(origin.matches(null, null))
        // A callback captured before a connection switch must re-check the origin.
        val inFlightCheck = { origin.matches(anotherClient, "server-b") }
        assertFalse(inFlightCheck())
    }
}
