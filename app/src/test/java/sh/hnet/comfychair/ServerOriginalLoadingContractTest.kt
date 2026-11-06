package sh.hnet.comfychair

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import sh.hnet.comfychair.model.*
import sh.hnet.comfychair.viewmodel.*

/** Executed source wiring contracts, not rendered Compose/device evidence. */
class ServerOriginalLoadingContractTest {
    @Test fun pendingOriginalCancelFailureAndRetryKeepListSessionAndAnchor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val owner = ServerImageIdentity("server", Any())
            val images = List(100) { ServerImage("$it.png", "", "input") }
            var pending = CompletableDeferred<Unit>()
            var fail = false
            var accepted = 0
            val picker = ServerImagePicker<Unit>(scope, { owner }, { true },
                { _, _, _ -> ServerImagePage(images) },
                { _, _ -> pending.await(); if (fail) error("decode failed") },
                { _, _, _ -> accepted++ })
            picker.open(1, ServerImageSource.INPUT)
            picker.loadMore()
            val before = picker.state.value
            val anchor = ServerGalleryAnchorPolicy.capture(before.images, emptySet(), 73, 19)
            picker.select(images[73])
            assertTrue(picker.state.value.downloading)
            assertSame(before.images, picker.state.value.images)
            assertEquals(before.session, picker.state.value.session)
            picker.cancelDownload()
            assertFalse(picker.state.value.downloading)
            assertSame(before.images, picker.state.value.images)
            assertEquals(before.session, picker.state.value.session)
            pending.complete(Unit); yield(); assertEquals(0, accepted)
            pending = CompletableDeferred(); fail = true
            picker.select(images[73]); pending.complete(Unit); yield()
            assertTrue(picker.state.value.downloadError)
            assertFalse(picker.state.value.downloading)
            assertSame(before.images, picker.state.value.images)
            assertEquals(before.session, picker.state.value.session)
            assertEquals(ServerGalleryAnchorPolicy.Position(73, 19),
                ServerGalleryAnchorPolicy.resolve(anchor, picker.state.value.images))
            pending = CompletableDeferred(); fail = false
            picker.retryDownload(); assertTrue(picker.state.value.downloading)
            picker.cancelDownload(); pending.complete(Unit); yield()
            assertEquals(0, accepted)
            assertEquals(before.session, picker.state.value.session)
        } finally { scope.cancel() }
    }

    private fun source(path: String) = File("src/main/java/sh/hnet/comfychair/$path").readText()

    @Test fun reusesMediaViewerIndeterminateSpinnerWithoutTextOrFakeProgress() {
        val overlay = source("ui/components/MediaLoadingOverlay.kt")
        assertTrue(overlay.contains("ContainedLoadingIndicator()"))
        assertTrue(overlay.contains("MediaLoadingIndicator()"))
        assertFalse(overlay.contains("Text("))
        assertFalse(overlay.contains("progress ="))
        assertTrue(source("ui/screens/MediaViewerScreen.kt").contains("ContainedLoadingIndicator()"))
    }

    @Test fun sharedOverlayHasAccessibleCancellationAndBlocksUnderlyingActions() {
        val overlay = source("ui/components/MediaLoadingOverlay.kt")
        assertTrue(overlay.contains("IconButton(onClick = onCancel"))
        assertTrue(overlay.contains("Modifier.size(48.dp)"))
        assertTrue(overlay.contains("stringResource(R.string.button_cancel)"))
        assertTrue(overlay.contains(".clickable { /* Block underlying actions while loading. */ }"))
    }

    @Test fun galleryKeepsPanelAndUsesOverlayInsteadOfLoadingStatusRow() {
        val screen = source("ui/screens/ServerGalleryTabContent.kt")
        assertFalse(screen.contains("server_assets_download_wait"))
        val panel = source("ui/components/ServerImagePickerPanel.kt")
        assertTrue(panel.contains("R.string.server_image_error"))
        assertTrue(screen.contains("onRetryDownload = viewModel.picker::retryDownload"))
        assertTrue(screen.contains("ServerImagePickerPanel("))
        assertTrue(screen.contains("browsingVisible = !loading && !error"))
        assertTrue(screen.contains("viewModel.closeViewer()"))
    }

    @Test fun originalPickerHasOneOverlayNoBusyTextOrCompetingTileSpinner() {
        val panel = source("ui/components/ServerImagePickerPanel.kt")
        assertTrue(panel.contains("if (state.downloading) MediaLoadingOverlay(onCancel = onCancelDownload)"))
        assertFalse(panel.contains("server_assets_download_wait"))
        assertFalse(panel.contains("state.downloading ->"))
        assertFalse(panel.contains("if (selected) Box("))
        assertTrue(panel.contains("onRetryDownload"))
        assertTrue(panel.contains("R.string.server_image_error"))
        assertTrue(panel.contains("R.string.server_image_retry_more"))
    }

    @Test fun originalLoadingDoesNotResetBitmapSessionScrollOrDeleteAnchor() {
        val panel = source("ui/components/ServerImagePickerPanel.kt")
        assertTrue(panel.contains("var bitmap by remember(image)"))
        assertTrue(panel.contains("if (!requestAllowed || bitmap != null) return@LaunchedEffect"))
        assertTrue(panel.contains("key(state.session, image)"))
        assertTrue(panel.contains("LaunchedEffect(state.session) { gridState.scrollToItem(0) }"))
        assertTrue(panel.contains("ServerGalleryAnchorPolicy.capture"))
        assertTrue(panel.contains("ServerGalleryAnchorPolicy.resolve"))
        assertFalse(panel.contains("remember(state.downloading)"))
        assertTrue(source("ui/components/ServerDeletionControls.kt").contains("server_delete_confirm"))
    }
}
