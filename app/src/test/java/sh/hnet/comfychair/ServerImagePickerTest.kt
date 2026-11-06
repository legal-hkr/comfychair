package sh.hnet.comfychair

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*
import java.util.concurrent.TimeUnit

class ServerImagePickerTest {
    private val input = """{"LoadImage":{"input":{"required":{"image":[["中文 + #?.png","folder/a.jpg","../bad.png","clip.mp4","folder/a.jpg"],{"image_upload":true}]}}}}"""
    private val image = ServerImage("a.png", "", "input")

    @Test fun inputFiltersAndPreservesLiteralNames() {
        val files = ServerImageDiscovery.input(JSONObject(input))
        assertEquals(2, files.size)
        assertTrue(files.any { it.path == "中文 + #?.png" })
        assertTrue(files.any { it == ServerImage("a.jpg", "folder", "input") })
    }

    @Test fun historySortsDeduplicatesAndExcludesTempAndVideo() {
        val json = JSONObject("""{
            "old":{"prompt":[1],"outputs":{"7":{"images":[{"filename":"old.png","type":"output"}]}}},
            "new":{"prompt":[9],"outputs":{"8":{"images":[
                {"filename":"new.png","subfolder":"dir","type":"output"},
                {"filename":"old.png","type":"output"},
                {"filename":"preview.png","type":"temp"},{"filename":"video.mp4","type":"output"}]}}}}
        """)
        assertEquals(listOf("dir/new.png", "old.png"), ServerImageDiscovery.output(json).map { it.path })
        assertTrue(ServerImageDiscovery.output(JSONObject()).isEmpty())
    }

    @Test fun descriptorsRejectTraversalAndWrongTypes() {
        listOf("/a.png", "../a.png", "a/../b.png", "a//b.png", "a\\b.png", "a.txt").forEach {
            assertNull(ServerImageDiscovery.descriptor(it, "input"))
        }
        assertNull(ServerImageDiscovery.descriptor("a.png", "temp"))
    }

    @Test fun authenticatedApiUsesRealDiscoveryAndEncodedView() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port,
                credentials = AuthCredentials.Bearer("fixture-token"))
            client.setWorkingProtocol("http")
            server.enqueue(MockResponse().setBody(input))
            assertEquals(2, client.listSourceImages(ServerImageSource.INPUT).size)
            val request = server.takeRequest()
            assertEquals("/object_info/LoadImage", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(client.listSourceImages(ServerImageSource.OUTPUT).isEmpty())
            assertEquals("/history?max_items=1&offset=0", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("fixture bytes"))
            val selected = ServerImage("中文 + #?.png", "子目录 +", "output")
            assertEquals("fixture bytes", client.downloadSourceImage(selected).toString(Charsets.UTF_8))
            val view = server.takeRequest()
            assertEquals("GET", view.method)
            assertEquals("/view", view.requestUrl!!.encodedPath)
            assertEquals(selected.filename, view.requestUrl!!.queryParameter("filename"))
            assertEquals(selected.subfolder, view.requestUrl!!.queryParameter("subfolder"))
            assertEquals("output", view.requestUrl!!.queryParameter("type"))
            assertEquals("Bearer fixture-token", view.getHeader("Authorization"))
        }
    }

    @Test fun httpAndMalformedResponsesAreErrorsNotEmptyLists() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port).apply { setWorkingProtocol("http") }
            for (response in listOf(MockResponse().setResponseCode(401), MockResponse().setResponseCode(404),
                MockResponse().setBody("<html>"), MockResponse().setBody("{}"))) {
                server.enqueue(response)
                assertTrue(runCatching { client.listSourceImages(ServerImageSource.INPUT) }.isFailure)
            }
        }
    }

    @Test fun cancellationAbortsSlowNetworkRead() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port).apply { setWorkingProtocol("http") }
            server.enqueue(MockResponse().setBody(input).setBodyDelay(2, TimeUnit.SECONDS))
            val task = launch(Dispatchers.IO) { client.listSourceImages(ServerImageSource.INPUT) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(500) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var owner: ServerImageIdentity? = ServerImageIdentity("a", Any())
        var slots = 4
        var listing: suspend () -> List<ServerImage> = { listOf(ServerImage("a.png", "", "input")) }
        var downloading: suspend () -> String = { "bitmap" }
        val accepted = mutableListOf<Pair<Int, String>>()
        val picker = ServerImagePicker(scope, { owner }, { it in 1..slots },
            { _, _, _ -> ServerImagePage(listing()) }, { _, _ -> downloading() }, { _, slot, value -> accepted += slot to value })
    }

    @Test fun routesToCapturedSlotAndResetsOnSuccess() {
        val f = Fixture()
        for (slot in 1..4) {
            f.picker.open(slot, ServerImageSource.INPUT)
            f.picker.select(image)
        }
        assertEquals(listOf(1, 2, 3, 4), f.accepted.map { it.first })
        assertNull(f.picker.state.value.slot)
    }

    @Test fun guardsServerAndClientThroughDownloadCompletion() = runBlocking {
        for (sameServer in listOf(false, true)) {
            val f = Fixture()
            val pending = CompletableDeferred<String>()
            f.downloading = { pending.await() }
            f.picker.open(2, ServerImageSource.INPUT)
            f.picker.select(image)
            assertTrue(f.picker.state.value.downloading)
            f.owner = ServerImageIdentity(if (sameServer) "a" else "b", Any())
            pending.complete("stale") // Even without connection observer, final acceptance rejects it.
            assertTrue(f.accepted.isEmpty())
            assertNull(f.picker.state.value.slot)
        }
    }

    @Test fun closeAndSourceSwitchCancelStaleWork() {
        val f = Fixture()
        val pending = CompletableDeferred<List<ServerImage>>()
        f.listing = { pending.await() }
        f.picker.open(1, ServerImageSource.INPUT)
        assertTrue(f.picker.state.value.loading)
        f.listing = { emptyList() }
        f.picker.open(3, ServerImageSource.OUTPUT)
        pending.complete(listOf(image))
        assertEquals(3, f.picker.state.value.slot)
        assertTrue(f.picker.state.value.images.isEmpty())
        f.picker.close()
        assertNull(f.picker.state.value.slot)
    }

    @Test fun errorsAreRetryableAndEmptyIsDistinct() {
        val f = Fixture()
        f.listing = { error("HTTP 403") }
        f.picker.open(1, ServerImageSource.INPUT)
        assertTrue(f.picker.state.value.error)
        assertFalse(f.picker.state.value.loading)
        f.listing = { emptyList() }
        f.picker.open(1, ServerImageSource.INPUT)
        assertFalse(f.picker.state.value.error)
        assertTrue(f.picker.state.value.images.isEmpty())
        f.owner = null
        f.picker.open(1, ServerImageSource.INPUT)
        assertTrue(f.picker.state.value.error)
    }

    @Test fun downloadFailureAndRemovedSlotNeverMutateSources() {
        val f = Fixture()
        f.downloading = { error("bad image") }
        f.picker.open(4, ServerImageSource.INPUT)
        f.picker.select(image)
        assertTrue(f.picker.state.value.error)
        assertFalse(f.picker.state.value.downloading)
        assertTrue(f.accepted.isEmpty())
        f.slots = 1
        f.picker.select(image)
        assertTrue(f.accepted.isEmpty())
        assertNull(f.picker.state.value.slot)
    }

    @Test fun oversizedDownloadIsRejectedBeforeAllocation() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port).apply { setWorkingProtocol("http") }
            server.enqueue(MockResponse().setHeader("Content-Length", 33L * 1024 * 1024))
            assertTrue(runCatching { client.downloadSourceImage(image) }.isFailure)
        }
    }

    @Test fun closingDuringDownloadPreventsLateAcceptance() {
        val f = Fixture()
        val pending = CompletableDeferred<String>()
        f.downloading = { pending.await() }
        f.picker.open(3, ServerImageSource.INPUT)
        f.picker.select(image)
        f.picker.close()
        pending.complete("stale")
        assertTrue(f.accepted.isEmpty())
        assertNull(f.picker.state.value.slot)
    }

    @Test fun disconnectClosesAndCancelsDownload() {
        val f = Fixture()
        val pending = CompletableDeferred<String>()
        f.downloading = { pending.await() }
        f.picker.open(1, ServerImageSource.INPUT)
        f.picker.select(image)
        f.owner = null
        f.picker.connectionChanged()
        pending.complete("stale")
        assertNull(f.picker.state.value.slot)
        assertTrue(f.accepted.isEmpty())
    }
}
