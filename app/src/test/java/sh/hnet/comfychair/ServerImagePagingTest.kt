package sh.hnet.comfychair

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*

class ServerImagePagingTest {
    private val a = ServerImage("a.png", "", "output")
    private val b = ServerImage("b.png", "", "output")

    @Test fun oldestBasedServerPagesBeyond200ByRecordsNotImages() = runBlocking {
        MockWebServer().use { server ->
            val offsets = mutableListOf<Pair<Int, Int>>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer fixture", request.getHeader("Authorization"))
                    val count = request.requestUrl!!.queryParameter("max_items")!!.toInt()
                    val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                    synchronized(offsets) { offsets += offset to count }
                    val result = JSONObject()
                    for (i in offset until minOf(offset + count, 237)) {
                        // Half the records have no images; some have multiple/duplicate images.
                        result.put("p$i", JSONObject("""{"prompt":[$i],"outputs":{}}""").apply {
                            if (i % 2 == 0) put("outputs", JSONObject("""{"7":{"images":[{"filename":"$i.png","type":"output"},{"filename":"shared.png","type":"output"}]}}"""))
                        })
                    }
                    return MockResponse().setBody(result.toString())
                }
            }
            val client = ComfyUIClient(hostname = server.hostName, port = server.port,
                credentials = AuthCredentials.Bearer("fixture")).apply { setWorkingProtocol("http") }
            var page = client.listSourceImagePage(ServerImageSource.OUTPUT)
            assertEquals(187, page.nextOffset)
            assertEquals("236.png", page.images.first().filename)
            val all = page.images.toMutableList()
            while (page.nextOffset != null) {
                page = client.listSourceImagePage(ServerImageSource.OUTPUT, page.nextOffset)
                all += page.images
            }
            assertEquals(120, all.distinct().size)
            assertTrue(all.any { it.filename == "0.png" })
            assertTrue(offsets.contains(187 to 50))
            assertTrue(offsets.contains(137 to 50))
            assertTrue(offsets.contains(0 to 37))
            assertTrue(offsets.all { it.first >= 0 && it.second <= 50 })
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var owner = ServerImageIdentity("a", Any())
        var slots = 4
        var listing: suspend (Int?) -> ServerImagePage = { ServerImagePage(emptyList()) }
        var download: suspend () -> String = { "bitmap" }
        val accepted = mutableListOf<Int>()
        val picker = ServerImagePicker(scope, { owner }, { it in 1..slots },
            { _, _, offset -> listing(offset) }, { _, _ -> download() }, { _, slot, _ -> accepted += slot })
    }

    @Test fun dedupEmptyImagePageRetryAndEndKeepRecordCursor() {
        val f = Fixture()
        val offsets = mutableListOf<Int?>()
        var fail = true
        f.listing = { offset ->
            offsets += offset
            when (offset) {
                null -> ServerImagePage(listOf(a), 150)
                150 -> ServerImagePage(emptyList(), 100)
                100 -> if (fail) error("HTTP 503") else ServerImagePage(listOf(a, b), 50)
                else -> ServerImagePage(listOf(b))
            }
        }
        f.picker.open(2, ServerImageSource.OUTPUT)
        f.picker.loadMore()
        assertEquals(100, f.picker.state.value.nextOffset)
        f.picker.loadMore()
        assertTrue(f.picker.state.value.moreError)
        assertEquals(listOf(a), f.picker.state.value.images)
        f.picker.loadMore() // No automatic retry storm.
        assertEquals(3, offsets.size)
        fail = false
        f.picker.loadMore(retry = true)
        assertEquals(listOf(a, b), f.picker.state.value.images)
        f.picker.loadMore()
        assertNull(f.picker.state.value.nextOffset)
        f.picker.loadMore()
        assertEquals(listOf(null, 150, 100, 100, 50), offsets)
    }

    @Test fun overlappingBottomTriggersAreCoalescedAndSelectionCancelsPage() = runBlocking {
        val f = Fixture()
        val pending = CompletableDeferred<ServerImagePage>()
        var calls = 0
        f.listing = { offset -> calls++; if (offset == null) ServerImagePage(listOf(a), 50) else pending.await() }
        f.picker.open(3, ServerImageSource.OUTPUT)
        f.picker.loadMore()
        f.picker.loadMore()
        assertTrue(f.picker.state.value.loadingMore)
        assertEquals(2, calls)
        f.picker.select(a)
        pending.complete(ServerImagePage(listOf(b)))
        assertEquals(listOf(3), f.accepted)
        assertNull(f.picker.state.value.slot)
    }

    @Test fun nonCooperativeLatePagesCannotOverwriteRefreshSlotServerOrClient() {
        for (change in 0..4) {
            val f = Fixture()
            var resume: kotlin.coroutines.Continuation<ServerImagePage>? = null
            f.listing = { offset ->
                if (offset == null) ServerImagePage(listOf(a), 50)
                else kotlin.coroutines.suspendCoroutine { resume = it }
            }
            f.picker.open(4, ServerImageSource.OUTPUT)
            f.picker.loadMore()
            when (change) {
                0 -> f.picker.open(1, ServerImageSource.INPUT)
                1 -> f.picker.close()
                2 -> f.owner = ServerImageIdentity("b", Any())
                3 -> f.owner = ServerImageIdentity("a", Any())
                4 -> f.slots = 1
            }
            resume!!.resumeWith(Result.success(ServerImagePage(listOf(b))))
            assertFalse(f.picker.state.value.images.contains(b))
            if (change == 0) assertEquals(1, f.picker.state.value.slot) else assertNull(f.picker.state.value.slot)
            assertTrue(f.accepted.isEmpty())
        }
    }

    @Test fun repeatedOrInvalidCursorBecomesRetryableError() {
        val f = Fixture()
        f.listing = { offset -> ServerImagePage(listOf(a), offset ?: 50) }
        f.picker.open(1, ServerImageSource.OUTPUT)
        f.picker.loadMore()
        assertTrue(f.picker.state.value.moreError)
        assertFalse(f.picker.state.value.loadingMore)
        assertEquals(50, f.picker.state.value.nextOffset)
    }

    @Test fun clearedHistoryEndsAndNegativeCursorIsRejected() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port).apply { setWorkingProtocol("http") }
            server.enqueue(MockResponse().setBody("{}"))
            val page = client.listSourceImagePage(ServerImageSource.OUTPUT, 120)
            assertTrue(page.images.isEmpty())
            assertNull(page.nextOffset)
            assertEquals("/history?max_items=50&offset=70", server.takeRequest().path)
            assertTrue(runCatching { client.listSourceImagePage(ServerImageSource.OUTPUT, -50) }.isFailure)
        }
    }

    @Test fun cancellingPaginationAbortsHttpAndKeepsNoLateState() = runBlocking {
        MockWebServer().use { server ->
            val client = ComfyUIClient(hostname = server.hostName, port = server.port).apply { setWorkingProtocol("http") }
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
            val task = launch(Dispatchers.IO) { client.listSourceImagePage(ServerImageSource.OUTPUT, 75) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(500) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        }
    }

    @Test fun inputNeverPaginatesAndRefreshResetsOutputCursor() {
        val f = Fixture()
        var calls = 0
        f.listing = { calls++; ServerImagePage(listOf(a), 50) }
        f.picker.open(1, ServerImageSource.INPUT)
        f.picker.loadMore()
        assertEquals(1, calls)
        f.picker.open(2, ServerImageSource.OUTPUT)
        f.picker.open(2, ServerImageSource.OUTPUT)
        assertEquals(3, calls)
        assertFalse(f.picker.state.value.moreError)
    }
}
