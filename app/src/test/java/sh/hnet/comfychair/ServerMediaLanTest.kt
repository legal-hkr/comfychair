package sh.hnet.comfychair

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.connection.ServerMediaDelete
import sh.hnet.comfychair.model.ServerImage
import java.net.InetAddress
import java.io.File

class ServerMediaLanTest {
    private val key = "d".repeat(32)
    private val capability = """{"version":1,"delete":true,"max_batch":50,"auth_mode":"lan","auth_modes":["lan","proxy"]}"""

    @Test fun nonLoopbackHttpRequiresExplicitOptInBeforeAnyKeyIsSent() = runBlocking {
        MockWebServer().use { s ->
            s.start(InetAddress.getByName("0.0.0.0"), 0)
            val url = s.url("/").newBuilder().host("127.0.0.2").build()
            val denied = ServerMediaDelete(OkHttpClient(), url, key)
            assertFalse(denied.credentialTransportAllowed)
            assertTrue(runCatching { denied.available() }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, s.requestCount)
            val allowed = ServerMediaDelete(OkHttpClient(), url, key, allowLan = true)
            s.enqueue(MockResponse().setBody(capability))
            assertTrue(allowed.available())
            val request = s.takeRequest()
            assertEquals(key, request.getHeader("X-ComfyChair-Delete"))
            assertNull(request.getHeader("X-ComfyChair-Proxy"))
            assertNull(request.getHeader("X-ComfyChair-Principal"))
        }
    }
    @Test fun lanAuthErrorsRedirectsAndPrivateMissingRemainFailClosed() = runBlocking {
        MockWebServer().use { s -> MockWebServer().use { other ->
            val api = ServerMediaDelete(OkHttpClient(), s.url("/"), key, true)
            for (status in listOf(301,302,307,308,401,403)) {
                s.enqueue(MockResponse().setResponseCode(status).setHeader("Location", other.url("/steal")))
                assertTrue(runCatching { api.missing() }.isFailure)
                assertEquals(key, s.takeRequest().getHeader("X-ComfyChair-Delete"))
            }
            assertEquals(0, other.requestCount)
            s.enqueue(MockResponse().setBody("""{"items":[{"type":"input","filename":"x.png","subfolder":""}]}"""))
            assertEquals(setOf(ServerImage("x.png", "", "input")), api.missing())
        } }
    }
    @Test fun discoveryWithoutKeyNeverAuthorizesAndNeverReadsMissing() = runBlocking {
        MockWebServer().use { s ->
            val api = ServerMediaDelete(OkHttpClient(), s.url("/"), "", true)
            s.enqueue(MockResponse().setBody(capability))
            assertFalse(api.available())
            assertNull(s.takeRequest().getHeader("X-ComfyChair-Delete"))
            assertTrue(runCatching { api.missing() }.isFailure)
            assertTrue(runCatching { api.prepare(listOf(ServerImage("x.png", "", "input"))) }.isFailure)
            assertEquals(1, s.requestCount)
        }
    }
    @Test fun consentAndCredentialScopeChangesWithServerOriginAndId() {
        val a = ComfyUIClient(hostname="192.168.4.36", port=8189).apply { setWorkingProtocol("http") }
        val b = ComfyUIClient(hostname="192.168.4.37", port=8189).apply { setWorkingProtocol("http") }
        val c = ComfyUIClient(hostname="192.168.4.36", port=8190).apply { setWorkingProtocol("http") }
        assertNotEquals(a.deletionCredentialScope("id"), b.deletionCredentialScope("id"))
        assertNotEquals(a.deletionCredentialScope("id"), c.deletionCredentialScope("id"))
        assertNotEquals(a.deletionCredentialScope("id"), a.deletionCredentialScope("other"))
        val old = a.deletionCredentialScope("id")
        a.setWorkingProtocol("https")
        assertNotEquals(old, a.deletionCredentialScope("id"))
        val controls = File("src/main/java/sh/hnet/comfychair/ui/components/ServerDeletionControls.kt").readText()
        assertTrue(controls.contains("check(currentMediaIdentity() == expected)\n                    val manifest"))
        assertTrue(controls.contains("check(currentMediaIdentity() == expected)\n                    val outcomes"))
    }
}
