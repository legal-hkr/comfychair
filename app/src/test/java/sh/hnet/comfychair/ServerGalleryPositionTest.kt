package sh.hnet.comfychair

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*

class ServerGalleryPositionTest {
    private val images = List(150) { ServerImage("$it.png", "", "input") }
    @Test fun successfulDeletionBeforeAnchorMovesIndexNotImageOrPixels() {
        val removed = images.take(20).toSet()
        val anchor = ServerGalleryAnchorPolicy.capture(images, removed, 90, 37)
        val position = ServerGalleryAnchorPolicy.resolve(anchor, images.filterNot { it in removed })
        assertEquals(70, position.index); assertEquals(37, position.offset)
    }
    @Test fun deletedAnchorUsesNearestSurvivingNeighborAndFallbackClamps() {
        val removed = images.slice(89..92).toSet()
        val anchor = ServerGalleryAnchorPolicy.capture(images, removed, 90, 21)
        val after = images.filterNot { it in removed }
        assertEquals(images[88], after[ServerGalleryAnchorPolicy.resolve(anchor, after).index])
        val fallback = anchor.copy(candidates = emptyList())
        assertEquals(89, ServerGalleryAnchorPolicy.resolve(fallback, after).index)
        assertEquals(1, ServerGalleryAnchorPolicy.resolve(fallback, after.take(2)).index)
    }
    @Test fun deletingLastPageOrEverythingAndPartialFailures() {
        val success = images.drop(100).toSet()
        val anchor = ServerGalleryAnchorPolicy.capture(images, success, 145, 50)
        assertEquals(99, ServerGalleryAnchorPolicy.resolve(anchor, images.take(100)).index)
        assertEquals(ServerGalleryAnchorPolicy.Position(0, 0), ServerGalleryAnchorPolicy.resolve(anchor, emptyList()))
        val partial = setOf(images[1], images[3]) // failed item 2 stays
        val after = images.filterNot { it in partial }
        val p = ServerGalleryAnchorPolicy.resolve(ServerGalleryAnchorPolicy.capture(images, partial, 2, 17), after)
        assertEquals(images[2], after[p.index]); assertEquals(17, p.offset)
    }
    @Test fun deleteRetainsLoadedPagesCursorQueryAndSessionWithoutReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val owner = ServerImageIdentity("s", Any()); var requests = 0
            val picker = ServerImagePicker<Unit>(scope, { owner }, { true }, { _, _, _ -> error("legacy") }, { _, _ -> }, { _, _, _ -> },
                browse = { _, _, q -> requests++; val start = q.cursor?.toInt() ?: 0
                    ServerImagePage(images.drop(start).take(50), nextCursor = if (start < 100) "${start + 50}" else null, assets = true, totalAssets = 150) })
            picker.open(1, ServerImageSource.INPUT); picker.loadMore(); picker.loadMore()
            val old = picker.state.value
            picker.removeDeleted(listOf(images[2], images[90]), old.session)
            val state = picker.state.value
            assertEquals(148, state.images.size); assertEquals(old.session, state.session)
            assertEquals(old.query, state.query); assertEquals(old.sort, state.sort)
            assertEquals(old.nextCursor, state.nextCursor); assertEquals(3, requests)
            assertTrue(images[91] in state.images)
            picker.removeDeleted(images, old.session - 1); assertEquals(148, picker.state.value.images.size)
            picker.open(1, ServerImageSource.OUTPUT)
            val fresh = picker.state.value
            picker.removeDeleted(images, old.session); assertEquals(fresh, picker.state.value)
        } finally { scope.cancel() }
    }
    @Test fun deletionRejectsLateNonCooperativeRefreshAndPagination() = runBlocking {
        for (refresh in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val owner = ServerImageIdentity("s", Any()); var blocked = false
                val gate = CompletableDeferred<Unit>()
                val picker = ServerImagePicker<Unit>(scope, { owner }, { true }, { _, _, _ -> error("legacy") }, { _, _ -> }, { _, _, _ -> },
                    browse = { _, _, q ->
                        if (blocked) withContext(NonCancellable) { gate.await() }
                        ServerImagePage(if (q.cursor == null) images.take(50) else images.drop(50).take(50), nextCursor = "50", assets = true) })
                picker.open(1, ServerImageSource.INPUT)
                blocked = true
                if (refresh) picker.refresh() else picker.loadMore()
                val session = picker.state.value.session
                picker.removeDeleted(listOf(images[3]), session)
                gate.complete(Unit); yield()
                assertEquals(49, picker.state.value.images.size)
                assertFalse(images[3] in picker.state.value.images)
                assertEquals("50", picker.state.value.nextCursor)
                assertEquals(session, picker.state.value.session)
            } finally { scope.cancel() }
        }
    }
    @Test fun legacyInputDeletionKeepsLoadedWindowAndContinuesWithoutSkipping() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val owner = ServerImageIdentity("s", Any()); var requests = 0
            val picker = ServerImagePicker<Unit>(scope, { owner }, { true }, { _, _, _ -> requests++; ServerImagePage(images) }, { _, _ -> }, { _, _, _ -> })
            picker.open(1, ServerImageSource.INPUT); picker.loadMore()
            val session = picker.state.value.session
            picker.removeDeleted(images.take(10), session)
            assertEquals(90, picker.state.value.images.size)
            picker.loadMore()
            assertEquals(images.drop(10), picker.state.value.images)
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }
    @Test fun ownerChangesRejectDeletionAndQueryChangesResetSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var owner = ServerImageIdentity("s", Any())
            val picker = ServerImagePicker<Unit>(scope, { owner }, { true }, { _, _, _ -> ServerImagePage(images) }, { _, _ -> }, { _, _, _ -> })
            picker.open(1, ServerImageSource.INPUT)
            val old = picker.state.value
            owner = ServerImageIdentity("other", Any())
            picker.removeDeleted(images, old.session)
            assertEquals(old, picker.state.value)
            picker.open(1, ServerImageSource.INPUT)
            val session = picker.state.value.session
            picker.changeQuery("cat", ServerImageSort.NAME)
            assertNotEquals(session, picker.state.value.session)
            assertEquals("cat", picker.state.value.query)
            assertTrue(picker.state.value.images.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun descriptorAnchorSurvivesPreviewRoundtripAndAppendWithoutOldIndexJump() {
        val anchor = ServerGalleryAnchorPolicy.capture(images, emptySet(), 94, 43)
        // Preview state changes never mutate browsing data. Appended pages preserve descriptor anchor.
        val roundtrip = ServerGalleryAnchorPolicy.resolve(anchor, images + images.map { it.copy(subfolder = "next") })
        assertEquals(ServerGalleryAnchorPolicy.Position(94, 43), roundtrip)
        val reordered = listOf(images[149]) + images.dropLast(1)
        val restored = ServerGalleryAnchorPolicy.resolve(anchor, reordered)
        assertEquals(images[94], reordered[restored.index]); assertEquals(43, restored.offset)
    }
    @Test fun previewReturnRetainsCompositionAndNeverOpensOrRefreshesPicker() {
        val screen = File("src/main/java/sh/hnet/comfychair/ui/screens/ServerGalleryTabContent.kt").readText()
        assertTrue(screen.indexOf("ServerImagePickerPanel(") < screen.indexOf("if (full != null)"))
        assertTrue(screen.contains("browsingVisible = !loading && !error"))
        val vm = File("src/main/java/sh/hnet/comfychair/viewmodel/ServerGalleryViewModel.kt").readText()
        val close = vm.substringAfter("fun closeViewer()").substringBefore("fun stop()")
        assertFalse(close.contains("picker."))
        val view = vm.substringAfter("fun view(image:").substringBefore("suspend fun preview")
        assertFalse(view.contains("picker.open")); assertFalse(view.contains("picker.refresh"))
        val panel = File("src/main/java/sh/hnet/comfychair/ui/components/ServerImagePickerPanel.kt").readText()
        val delete = panel.substringAfter("ServerDeletionControls(").substringBefore("if (!deletion.active")
        assertFalse(delete.contains("onRefresh()")); assertTrue(delete.contains("ServerGalleryAnchorPolicy.capture"))
        assertTrue(panel.contains("LaunchedEffect(state.session) { gridState.scrollToItem(0) }"))
    }
}
