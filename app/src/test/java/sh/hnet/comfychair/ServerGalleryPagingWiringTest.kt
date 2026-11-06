package sh.hnet.comfychair

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The tab's grid must keep paginating, independently of thumbnail loading. */
class ServerGalleryPagingWiringTest {
    private fun source(path: String) = File("src/main/java/sh/hnet/comfychair/$path").readText()

    @Test fun paginationTriggersNearTheEndAndIgnoresPreviewState() {
        val grid = source("ui/components/ServerGalleryGrid.kt")
        // Reads the latest state, not the one captured when the effect started
        assertTrue(grid.contains("val latestState by rememberUpdatedState(state)"))
        assertTrue(grid.contains("val latestLoadMore by rememberUpdatedState(onLoadMore)"))
        // Near-the-end trigger on the grid's own totals
        assertTrue(grid.contains("if (lastVisible >= total - 7) latestLoadMore()"))
        // Thumbnail state must not gate pagination
        assertFalse(grid.contains("bitmap != null && "))
        assertTrue(grid.contains("if (current.nextCursor == null && current.nextOffset == null) return@collect"))
    }
}
