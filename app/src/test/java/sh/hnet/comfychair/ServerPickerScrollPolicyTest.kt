package sh.hnet.comfychair

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/** Executes the same policy used by snapshotFlow; not a rendered Compose gesture test. */
class ServerPickerScrollPolicyTest {
    private val owner = ServerImageIdentity("server", Any())
    private fun image(i: Int) = ServerImage("$i.png", "", "output", "id$i")
    private fun eligible(s: ServerImagePickerState) = ServerPickerScrollPolicy.shouldLoad(s, s.images.size - 1, s.images.size)

    @Test fun normalScrollingPastFivePagesDoesNotDrainUnseenPages() {
        for (source in ServerImageSource.entries) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var calls = 0
            val picker = ServerImagePicker(scope, { owner }, { true }, { _, _, _ -> error("legacy") },
                { _, _ -> "source" }, { _, _, _ -> }, browse = { _, _, q ->
                    val start = q.cursor?.toInt() ?: 0
                    calls++
                    ServerImagePage((start until start + 50).map(::image), nextCursor = "${start + 50}", assets = true)
                })
            picker.open(1, source)
            repeat(12) {
                val old = picker.state.value
                assertTrue(eligible(old))
                picker.loadMore()
                val next = picker.state.value
                assertEquals(0, next.sparsePages)
                assertFalse("Old layout must not chain another page", ServerPickerScrollPolicy.shouldLoad(next, old.images.size - 1, old.images.size))
                assertFalse("New layout at old scroll position is not near bottom", ServerPickerScrollPolicy.shouldLoad(next, old.images.size - 1, next.images.size))
            }
            assertEquals(13, calls)
            assertEquals(650, picker.state.value.images.size)
            picker.close(); scope.cancel()
        }
    }

    @Test fun unchangedViewportWithNewCursorAdvancesSparsePagesButHasManualRecovery() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cursor = 0
        var duplicate = false
        val picker = ServerImagePicker(scope, { owner }, { true }, { _, _, _ -> error("legacy") },
            { _, _ -> "x" }, { _, _, _ -> }, browse = { _, _, _ ->
                ServerImagePage(if (cursor++ == 0 || duplicate) listOf(image(1)) else emptyList(), nextCursor = "$cursor", assets = true)
            })
        picker.open(1, ServerImageSource.OUTPUT)
        repeat(5) {
            assertTrue(eligible(picker.state.value))
            picker.loadMore()
        }
        assertEquals(5, picker.state.value.sparsePages)
        assertFalse(eligible(picker.state.value))
        picker.loadMore(retry = true) // explicit footer action renews sparse budget
        assertEquals(1, picker.state.value.sparsePages)
        assertTrue(eligible(picker.state.value))
        duplicate = true
        picker.loadMore()
        assertEquals(2, picker.state.value.sparsePages) // duplicate images also make no progress
        picker.close(); scope.cancel()
    }

    @Test fun lifecycleErrorAndCursorGuards() {
        val base = ServerImagePickerState(slot = 1, images = listOf(image(1)), nextCursor = "next")
        assertTrue(eligible(base))
        for (blocked in listOf(base.copy(slot = null), base.copy(loading = true), base.copy(loadingMore = true),
            base.copy(downloading = true), base.copy(error = true), base.copy(moreError = true),
            base.copy(nextCursor = null), base.copy(sparsePages = 5))) assertFalse(eligible(blocked))
        assertTrue(eligible(base.copy(nextCursor = null, nextOffset = 50)))
        assertFalse(ServerPickerScrollPolicy.shouldLoad(base, -1, 1))
        assertTrue(eligible(base.copy(images = emptyList())))
    }

    @Test fun thumbnailFailureNeverBlocksPaginationOrRetriesAutomatically() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val picker = ServerImagePicker(scope, { owner }, { true }, { _, _, _ -> error("legacy") },
            { _, _ -> "x" }, { _, _, _ -> }, browse = { _, _, q ->
                calls++; ServerImagePage(listOf(image(calls)), nextCursor = if (q.cursor == null) "next" else null, assets = true)
            })
        picker.open(1, ServerImageSource.OUTPUT)
        assertTrue(runCatching { picker.preview(image(1)) { error("preview HTTP503") } }.isFailure)
        assertTrue(eligible(picker.state.value))
        picker.loadMore()
        assertEquals(2, calls)
        assertFalse(eligible(picker.state.value))
        assertEquals("retry", picker.preview(image(1)) { "retry" })
        picker.close(); scope.cancel()
    }

    @Test fun repeatedTriggersCoalesceErrorRequiresRetryAndNoCursorStops() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<ServerImagePage>()
        var calls = 0
        var fail = true
        val picker = ServerImagePicker(scope, { owner }, { true }, { _, _, _ -> error("legacy") },
            { _, _ -> "x" }, { _, _, _ -> }, browse = { _, _, q ->
                calls++
                if (q.cursor == null) ServerImagePage(listOf(image(1)), nextCursor = "next", assets = true)
                else if (fail) { pending.await(); error("HTTP503") }
                else ServerImagePage(listOf(image(2)), assets = true)
            })
        picker.open(1, ServerImageSource.OUTPUT)
        picker.loadMore(); picker.loadMore()
        assertEquals(2, calls); assertFalse(eligible(picker.state.value))
        pending.complete(ServerImagePage(emptyList()))
        assertTrue(picker.state.value.moreError)
        picker.loadMore(); assertEquals(2, calls)
        fail = false; picker.loadMore(retry = true)
        assertFalse(picker.state.value.moreError); assertNull(picker.state.value.nextCursor)
        picker.loadMore(); assertEquals(3, calls)
        picker.close(); scope.cancel()
    }

    @Test fun nonCooperativeOldPageCannotOverwriteRefreshEvenWithSamePopupSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var late: Continuation<ServerImagePage>? = null
        var calls = 0
        val picker = ServerImagePicker(scope, { owner }, { true }, { _, _, _ -> error("legacy") },
            { _, _ -> "x" }, { _, _, _ -> }, browse = { _, _, q ->
                calls++
                if (q.cursor == null) ServerImagePage(listOf(image(calls)), nextCursor = "cursor$calls", assets = true)
                else suspendCoroutine { late = it }
            })
        picker.open(1, ServerImageSource.OUTPUT)
        val session = picker.state.value.session
        picker.loadMore(); picker.refresh()
        assertEquals(session, picker.state.value.session)
        assertEquals(listOf(image(3)), picker.state.value.images)
        late!!.resumeWith(Result.success(ServerImagePage(listOf(image(99)), nextCursor = "stale", assets = true)))
        assertEquals(listOf(image(3)), picker.state.value.images)
        assertEquals("cursor3", picker.state.value.nextCursor)
        assertFalse(picker.state.value.loadingMore)
        picker.close(); scope.cancel()
    }
}
