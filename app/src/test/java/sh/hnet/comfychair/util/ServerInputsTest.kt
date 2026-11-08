package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerInputsTest {

    @Test
    fun listingDropsTheFolderMarkAndKeepsOrder() {
        assertEquals(
            listOf("inpaint_source_43cf.png", "pasted/image.png", "clip.mp4"),
            ServerInputs.parseListing("""["inpaint_source_43cf.png [input]", "pasted/image.png [input]", "clip.mp4 [input]"]""")
        )
        assertNull(ServerInputs.parseListing("<html>404</html>"))
    }

    @Test
    fun optionsFromPlainListAndCombo() {
        val plain = """{"LoadImage": {"input": {"required": {"image": [["a.png", "b.jpg"], {"image_upload": true}]}}}}"""
        assertEquals(listOf("a.png", "b.jpg"), ServerInputs.parseOptions(plain, "LoadImage", "image"))
        val combo = """{"LoadVideo": {"input": {"required": {"file": ["COMBO", {"options": ["x.mp4", "y.webm"]}]}}}}"""
        assertEquals(listOf("x.mp4", "y.webm"), ServerInputs.parseOptions(combo, "LoadVideo", "file"))
        assertNull(ServerInputs.parseOptions("{}", "LoadImage", "image"))
    }

    @Test
    fun fileSplitsSubfolderAndVideoNames() {
        val file = ServerInputs.File("pasted/image.png")
        assertEquals("pasted", file.subfolder)
        assertEquals("image.png", file.filename)
        assertEquals("", ServerInputs.File("a.png").subfolder)
        assertEquals(true, ServerInputs.isVideoName("clip.MP4"))
        assertEquals(false, ServerInputs.isVideoName("a.png"))
    }

    @Test
    fun workFilesAreNoSources() {
        listOf(
            "inpaint_source_43cf.png", "clipspace/clipspace-mask-1.png",
            "clipspace-painted-masked-1794149027312.png", "clipspace-paint-1794149027312.png", "pasted/clipspace-mask-2.png"
        ).forEach { assertEquals(it, true, ServerInputs.isWorkFile(it)) }
        listOf("inpainting_image_20ca.png", "Lexie.jpg", "editing_source_1f47.png", "pasted/image.png")
            .forEach { assertEquals(it, false, ServerInputs.isWorkFile(it)) }
    }
}
