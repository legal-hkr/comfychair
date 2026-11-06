package sh.hnet.comfychair

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*

/** Executable state/HTTP/cache tests, not rendered gesture or Android Bitmap tests. */
class ServerImagePreviewTest {
    private val image = ServerImage("a.png", "", "input")
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var owner = ServerImageIdentity("server", Any())
        var slots = 4
        var calls = 0
        val images = (0..120).map { ServerImage("$it.png", "", "input") }
        val picker = ServerImagePicker(scope, { owner }, { it in 1..slots },
            { _, _, _ -> calls++; ServerImagePage(images + images) },
            { _, _ -> "original" }, { _, _, _ -> })
    }

    @Test fun inputChoicesAreDedupedAndRevealedInBoundedPagesWithoutRefetch() {
        val f = Fixture()
        f.picker.open(1, ServerImageSource.INPUT)
        assertEquals(50, f.picker.state.value.images.size)
        f.picker.loadMore()
        assertEquals(100, f.picker.state.value.images.size)
        f.picker.loadMore()
        assertEquals(121, f.picker.state.value.images.size)
        assertNull(f.picker.state.value.nextOffset)
        assertEquals(1, f.calls)
        f.picker.close(); f.picker.loadMore()
        assertEquals(1, f.calls)
        f.scope.cancel()
    }

    @Test fun previewConcurrencyAndDismissCancelActiveAndQueuedWork() = runBlocking {
        val f = Fixture(); f.picker.open(1, ServerImageSource.INPUT)
        var started = 0; var stopped = 0
        val tasks = (0..9).map { launch(Dispatchers.Unconfined) {
            f.picker.preview(f.images[it]) {
                started++
                try { awaitCancellation() } finally { stopped++ }
            }
        } }
        assertEquals(3, started)
        f.picker.close()
        withTimeout(500) { tasks.joinAll() }
        assertEquals(3, stopped); assertEquals(3, started)
        assertTrue(tasks.all { it.isCancelled })
        assertNull(f.picker.state.value.slot)
        f.scope.cancel()
    }

    @Test fun tileDisposalCancelsOnlyItsRequestAndFreesPermit() = runBlocking {
        val f = Fixture(); f.picker.open(1, ServerImageSource.INPUT)
        var cancelled = false
        val task = launch(Dispatchers.Unconfined) {
            f.picker.preview(f.images.first()) { try { awaitCancellation() } finally { cancelled = true } }
        }
        task.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals("ok", f.picker.preview(f.images.first()) { "ok" })
        f.picker.close(); f.scope.cancel()
    }

    @Test fun previewErrorCanRetryAndCannotPoisonSiblingOrCache() = runBlocking {
        val f = Fixture(); f.picker.open(1, ServerImageSource.INPUT)
        assertTrue(runCatching { f.picker.preview(f.images.first()) { error("HTTP503") } }.isFailure)
        assertEquals("retry", f.picker.preview(f.images.first()) { "retry" })
        f.picker.close(); f.scope.cancel()
    }

    @Test fun slotServerClientAndReopenCancelPreviewLifetime() = runBlocking {
        for (change in 0..3) {
            val f = Fixture(); f.picker.open(4, ServerImageSource.INPUT)
            var stopped = false
            val task = launch(Dispatchers.Unconfined) {
                f.picker.preview(f.images.first()) { try { awaitCancellation() } finally { stopped = true } }
            }
            when (change) {
                0 -> f.owner = ServerImageIdentity("other", Any())
                1 -> f.owner = ServerImageIdentity("server", Any())
                2 -> f.slots = 1
                3 -> f.picker.open(1, ServerImageSource.INPUT)
            }
            f.picker.connectionChanged()
            withTimeout(500) { task.join() }
            assertTrue(stopped); assertTrue(task.isCancelled)
            f.picker.close(); f.scope.cancel()
        }
    }

    @Test fun cacheEnforcesByteEntryLruAndOwnerBoundaries() {
        val cache = ServerImagePreviewCache<Pair<ServerImageIdentity, ServerImage>, String>(10, 2)
        val a = ServerImageIdentity("a", Any()); val b = ServerImageIdentity("b", Any())
        cache.put(a to image, "a", 4); cache.put(b to image, "b", 4)
        assertEquals("a", cache.get(a to image))
        val c = ServerImageIdentity("a", Any())
        cache.put(c to image, "c", 4)
        assertNull(cache.get(b to image)); assertEquals(8, cache.bytes)
        cache.put(a to image, "oversized", 11)
        assertNull(cache.get(a to image)); assertEquals(4, cache.bytes)
        cache.put(b to image, "b", 7)
        assertNull(cache.get(c to image)); assertTrue(cache.bytes <= 10); assertTrue(cache.size <= 2)
    }

    @Test fun authenticatedPreviewUsesEncodedDescriptorAndRejectsOversizedResponse() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port,
                credentials = AuthCredentials.Bearer("fixture")).apply { setWorkingProtocol("http") }
            server.enqueue(MockResponse().setBody("preview"))
            assertEquals("preview", client.downloadSourcePreview(image).toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("Bearer fixture", request.getHeader("Authorization"))
            assertEquals("webp;60", request.requestUrl!!.queryParameter("preview"))
            assertEquals("input", request.requestUrl!!.queryParameter("type"))
            server.enqueue(MockResponse().setHeader("Content-Length", 4 * 1024 * 1024 + 1))
            assertTrue(runCatching { client.downloadSourcePreview(image) }.isFailure)
        }
    }

    @Test fun dismissActuallyCancelsPreviewHttp() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port).apply { setWorkingProtocol("http") }
            val f = Fixture(); f.picker.open(1, ServerImageSource.INPUT)
            server.enqueue(MockResponse().setBody("preview").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
            val task = launch(Dispatchers.Unconfined) { f.picker.preview(f.images.first()) { client.downloadSourcePreview(image) } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            f.picker.close()
            withTimeout(500) { task.join() }
            assertTrue(task.isCancelled)
            f.scope.cancel()
        }
    }
}
