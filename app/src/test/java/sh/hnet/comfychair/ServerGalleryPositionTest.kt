package sh.hnet.comfychair

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The server tab keeps its composition and its picker session across viewer open/close and view switches. */
class ServerGalleryPositionTest {
    @Test fun previewReturnRetainsCompositionAndNeverOpensOrRefreshesPicker() {
        val screen = File("src/main/java/sh/hnet/comfychair/ui/screens/ServerGalleryTabContent.kt").readText()
        assertTrue(screen.indexOf("ServerGalleryGrid(") < screen.indexOf("if (full != null)"))
        assertTrue(screen.contains("onCancelDownload = viewModel::closeViewer"))
        val vm = File("src/main/java/sh/hnet/comfychair/viewmodel/ServerGalleryViewModel.kt").readText()
        val close = vm.substringAfter("fun closeViewer()").substringBefore("fun stop()")
        assertFalse(close.contains("picker."))
        val view = vm.substringAfter("fun view(image:").substringBefore("suspend fun preview")
        assertFalse(view.contains("picker.open")); assertFalse(view.contains("picker.refresh"))
        val grid = File("src/main/java/sh/hnet/comfychair/ui/components/ServerGalleryGrid.kt").readText()
        // Selection is the caller's state; the grid never refreshes on the caller's behalf while selecting
        assertTrue(grid.contains("selecting: Boolean"))
        assertTrue(grid.contains("if (latestSelecting) return@collect"))
        assertTrue(grid.contains("LaunchedEffect(state.session) { gridState.scrollToItem(0) }"))
        assertTrue(screen.contains("onSelectionChange(selected.isNotEmpty(), selected.size"))
    }
}
