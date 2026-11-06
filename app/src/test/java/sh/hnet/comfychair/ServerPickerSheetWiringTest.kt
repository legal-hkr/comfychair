package sh.hnet.comfychair

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The generation screens' server picker: both sources, a bounded sheet, and progress while the original loads. */
class ServerPickerSheetWiringTest {
    private val sheet = File("src/main/java/sh/hnet/comfychair/ui/components/ServerImagePickerSheet.kt").readText()

    @Test fun offersBothSources() {
        assertTrue(sheet.contains("ServerImageSource.entries.forEachIndexed"))
        assertTrue(sheet.contains("viewModel.open(item)"))
    }

    @Test fun contentIsBoundedRatherThanFullScreen() {
        // No second ModalBottomSheet inside the caller's sheet, and a capped grid height
        assertFalse(sheet.contains("ModalBottomSheet("))
        assertTrue(sheet.contains("heightIn(max = 520.dp)"))
    }

    @Test fun sourceToolbarOffersTheServerEntryInBothModes() {
        val tools = File("src/main/java/sh/hnet/comfychair/ui/components/generation/SourceTools.kt").readText()
        // Both the plain and the inpainting toolbar carry the server entry, so a chosen source keeps it
        assertEquals(2, tools.split("fromServer?.invoke()").size - 1)
        assertTrue(tools.contains("val fromServer: (@Composable () -> Unit)? = onPickFromServer?.let"))
        // And the screens pass it in
        for (screen in listOf("ui/screens/ImageToImageScreen.kt", "ui/screens/ImageToVideoScreen.kt")) {
            assertTrue(screen, File("src/main/java/sh/hnet/comfychair/$screen").readText()
                .contains("onPickFromServer = pickFromServer"))
        }
    }

    @Test fun pickingShowsProgressUntilTheOriginalArrives() {
        assertTrue(sheet.contains("var downloading by remember { mutableStateOf<ServerImage?>(null) }"))
        assertTrue(sheet.contains("MediaLoadingOverlay(onCancel = { downloading = null })"))
        assertTrue(sheet.contains("viewModel.loadOriginal(image)"))
        // The sheet closes only after the bitmap is handed over
        val block = sheet.substringAfter("scope.launch {").substringBefore("}")
        assertTrue(block.contains("onPick(bitmap)"))
        assertTrue(block.contains("onClose()"))
    }
}
