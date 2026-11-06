package sh.hnet.comfychair

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Deletion outcomes must be reported per file, not as a bare title. */
class ServerDeletionResultsTest {
    private val screen = File("src/main/java/sh/hnet/comfychair/ui/screens/ServerGalleryTabContent.kt").readText()

    @Test fun outcomesAreListedPerFileWithLocalizedStatus() {
        assertTrue(screen.contains("deleteResults = captured.mapIndexed"))
        assertTrue(screen.contains("outcomes.firstOrNull { it.index == position }?.status"))
        assertTrue(screen.contains("title = { Text(stringResource(R.string.server_delete_results)) }"))
        // Each status has its own label
        for (status in listOf("success", "not_found", "conflict", "busy", "invalid")) {
            assertTrue(status, screen.contains("\"$status\" -> R.string.server_delete_"))
        }
        // The bare title toast is gone
        assertFalse(screen.contains("Toast.makeText(context, R.string.server_delete_results"))
        // The label is a resource id, so it must be resolved, never interpolated as a number
        assertTrue(screen.contains("+ stringResource(label)"))
        // Deleting updates the hoisted selection so the top bar's count follows
        assertTrue(screen.contains("onSelectionChange(\n                            selected.isNotEmpty(), selected.size,"))
    }
}
