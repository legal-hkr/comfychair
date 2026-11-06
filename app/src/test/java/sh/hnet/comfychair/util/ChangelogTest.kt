package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.util.Changelog.Block.Item
import sh.hnet.comfychair.util.Changelog.Block.Paragraph
import java.io.File

class ChangelogTest {

    private val release = """
        v0.9.0

        New design:
        - Material 3 Expressive throughout
        - Image, Video, Gallery and Settings tabs

        Workflow editor:
        - Graph in theme colors, with an overview when zoomed out

        Bug fixes:
        - Queue jobs lost when moved or cleared
    """.trimIndent()

    @Test
    fun `release notes give the version, titled sections and items`() {
        val changelog = Changelog.parse(release)
        assertEquals("0.9.0", changelog.version)
        assertEquals(listOf("New design", "Workflow editor", "Bug fixes"), changelog.sections.map { it.title })
        assertEquals(
            listOf(Item("Material 3 Expressive throughout"), Item("Image, Video, Gallery and Settings tabs")),
            changelog.sections[0].blocks
        )
        assertEquals(4, changelog.itemCount)
    }

    @Test
    fun `a file without a version line has no version`() {
        val changelog = Changelog.parse("Fixes:\n- One")
        assertNull(changelog.version)
        assertEquals(1, changelog.itemCount)
    }

    @Test
    fun `items before the first heading go into an untitled section`() {
        val changelog = Changelog.parse("v1.2.3\n- Early\n\nLater:\n- Late")
        assertEquals(listOf(null, "Later"), changelog.sections.map { it.title })
        assertEquals(listOf(Item("Early")), changelog.sections[0].blocks)
    }

    @Test
    fun `windows line endings and other bullets are read`() {
        val changelog = Changelog.parse("v1.0\r\nNew:\r\n* Star\r\n• Dot\r\n")
        assertEquals("1.0", changelog.version)
        assertEquals(listOf(Item("Star"), Item("Dot")), changelog.sections.single().blocks)
    }

    @Test
    fun `an indented line continues the item above, other lines are paragraphs`() {
        val changelog = Changelog.parse("v1.0\nNotes:\n- A long item\n  that wraps\nPlain text\nmore text\n\nAfter a blank\nv2.0")
        assertEquals(
            listOf(Item("A long item that wraps"), Paragraph("Plain text more text"), Paragraph("After a blank v2.0")),
            changelog.sections.single().blocks
        )
    }

    @Test
    fun `an empty file has no sections and empty sections are dropped`() {
        assertTrue(Changelog.parse("").sections.isEmpty())
        assertTrue(Changelog.parse("v1.0\n\nNothing here:\n\n").sections.isEmpty())
    }

    @Test
    fun `this version's changelog exists and names this version`() {
        val file = File("../metadata/en-US/changelogs/${BuildConfig.VERSION_CODE}.txt")
        assertTrue("missing ${file.path}", file.isFile)
        val changelog = Changelog.parse(file.readText())
        assertEquals(BuildConfig.VERSION_NAME.removePrefix("v"), changelog.version)
        assertTrue("F-Droid allows at most 500 characters", file.readText().length <= 500)
        assertTrue(changelog.itemCount > 0)
    }
}
