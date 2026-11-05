package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/** Path rejection is performed before any network request. */
class ServerWorkflowApiTest {
    private val client = ComfyUIClient(hostname = "localhost", port = 8188)

    @Test fun acceptsRelativeJsonPaths() {
        listOf("example.json", "folder/中文 + name.json", "a/b/workflow.JSON").forEach {
            assertTrue(it, client.isValidSavedWorkflowPath(it))
        }
    }

    @Test fun refusesTraversalAndAmbiguousPaths() {
        listOf("", "/absolute.json", "../secret.json", "a/../secret.json", "a/./file.json",
            "a//file.json", "a\\file.json", "C:/file.json", "a%2Fb.json", "a?x.json",
            "a#x.json", "notes.txt").forEach {
            assertFalse(it, client.isValidSavedWorkflowPath(it))
        }
    }

    @Test fun listFiltersFilesAndUsesOnlyGet() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"storage":"server","migrated":true}"""))
            server.enqueue(MockResponse().setBody("""[{"path":"子目录/a+b.json","size":42,"modified":123},
                {"path":"notes.txt","size":5},{"path":"../private.json","size":1}]"""))
            val client = ComfyUIClient(hostname = server.hostName, port = server.port)
            client.setWorkingProtocol("http")
            val latch = CountDownLatch(1)
            var result: ComfyUIClient.SavedWorkflowResult<List<ComfyUIClient.SavedWorkflow>>? = null
            client.listSavedWorkflows { result = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val files = (result as ComfyUIClient.SavedWorkflowResult.Success<List<ComfyUIClient.SavedWorkflow>>).value
            assertEquals(listOf("子目录/a+b.json"), files.map { it.path })
            assertEquals(42L, files[0].size)
            assertEquals("/users", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/userdata?dir=workflows&recurse=true&full_info=true", request.path)
        }
    }

    @Test fun downloadEncodesEntireRelativePathAsOneSegment() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"storage":"server","migrated":true}"""))
            server.enqueue(MockResponse().setBody("""{"nodes":[{"id":1}],"links":[]}"""))
            val client = ComfyUIClient(hostname = server.hostName, port = server.port)
            client.setWorkingProtocol("http")
            val latch = CountDownLatch(1)
            var result: ComfyUIClient.SavedWorkflowResult<String>? = null
            client.fetchSavedWorkflow("folder/中文 +.json") { result = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(result is ComfyUIClient.SavedWorkflowResult.Success)
            assertEquals("/users", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path.orEmpty().startsWith("/userdata/workflows%2Ffolder%2F"))
            assertTrue(request.path.orEmpty().contains("%2B"))
            assertTrue(request.path.orEmpty().contains("%E4%B8%AD"))
        }
    }

    @Test fun httpErrorsAndNonJsonAreRejected() {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port)
            client.setWorkingProtocol("http")
            for ((code, expected) in listOf(
                403 to ComfyUIClient.SavedWorkflowError.UNAUTHORIZED,
                404 to ComfyUIClient.SavedWorkflowError.NOT_FOUND
            )) {
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(code))
                val latch = CountDownLatch(1)
                var result: ComfyUIClient.SavedWorkflowResult<String>? = null
                client.fetchSavedWorkflow("file.json") { result = it; latch.countDown() }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertEquals(expected, (result as ComfyUIClient.SavedWorkflowResult.Failure).error)
            }
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("<html>not json</html>"))
            val latch = CountDownLatch(1)
            var result: ComfyUIClient.SavedWorkflowResult<String>? = null
            client.fetchSavedWorkflow("file.json") { result = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(ComfyUIClient.SavedWorkflowError.INVALID_RESPONSE,
                (result as ComfyUIClient.SavedWorkflowResult.Failure).error)
        }
    }

    @Test fun rejectsOversizedResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("x".repeat(8 * 1024 * 1024 + 1)))
            val client = ComfyUIClient(hostname = server.hostName, port = server.port)
            client.setWorkingProtocol("http")
            val latch = CountDownLatch(1)
            var result: ComfyUIClient.SavedWorkflowResult<String>? = null
            client.fetchSavedWorkflow("large.json") { result = it; latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(ComfyUIClient.SavedWorkflowError.TOO_LARGE,
                (result as ComfyUIClient.SavedWorkflowResult.Failure).error)
        }
    }

    @Test fun forwardsConnectionAuthentication() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("[]"))
            val client = ComfyUIClient(hostname = server.hostName, port = server.port,
                credentials = sh.hnet.comfychair.model.AuthCredentials.Bearer("test-token"))
            client.setWorkingProtocol("http")
            val latch = CountDownLatch(1)
            client.listSavedWorkflows { latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun multiuserNeverRequestsUserdata() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"storage":"server","users":{"alice":"Alice"}}"""))
            server.enqueue(MockResponse().setBody("""{"storage":"server","users":{"alice":"Alice"}}"""))
            val client = ComfyUIClient(hostname = server.hostName, port = server.port)
            client.setWorkingProtocol("http")
            val listed = CountDownLatch(1)
            var listResult: ComfyUIClient.SavedWorkflowResult<List<ComfyUIClient.SavedWorkflow>>? = null
            client.listSavedWorkflows { listResult = it; listed.countDown() }
            assertTrue(listed.await(5, TimeUnit.SECONDS))
            assertEquals(ComfyUIClient.SavedWorkflowError.MULTIUSER_UNSUPPORTED,
                (listResult as ComfyUIClient.SavedWorkflowResult.Failure).error)
            val fetched = CountDownLatch(1)
            var fetchResult: ComfyUIClient.SavedWorkflowResult<String>? = null
            client.fetchSavedWorkflow("test.json") { fetchResult = it; fetched.countDown() }
            assertTrue(fetched.await(5, TimeUnit.SECONDS))
            assertEquals(ComfyUIClient.SavedWorkflowError.MULTIUSER_UNSUPPORTED,
                (fetchResult as ComfyUIClient.SavedWorkflowResult.Failure).error)
            assertEquals("/users", server.takeRequest().path)
            assertEquals("/users", server.takeRequest().path)
            assertEquals(0, server.requestCount - 2)
        }
    }

    @Test fun invalidPathFailsWithoutConnection() {
        var result: ComfyUIClient.SavedWorkflowResult<String>? = null
        client.fetchSavedWorkflow("../secret.json") { result = it }
        assertTrue(result is ComfyUIClient.SavedWorkflowResult.Failure)
        assertTrue((result as ComfyUIClient.SavedWorkflowResult.Failure).error ==
            ComfyUIClient.SavedWorkflowError.INVALID_PATH)
    }
}
