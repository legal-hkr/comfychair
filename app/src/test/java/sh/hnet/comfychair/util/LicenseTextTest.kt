package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The GPL page in the assets read into blocks: the shape the License page shows. */
class LicenseTextTest {

    private val blocks = LicenseText.parse(File("src/main/assets/licenses/gpl-3.0.html").readText())

    @Test
    fun `the whole licence is there, in order`() {
        assertEquals(LicenseText.Block.Heading(1, "GNU GENERAL PUBLIC LICENSE"), blocks.first())
        val sections = blocks.filterIsInstance<LicenseText.Block.Heading>().filter { it.level == 3 }
        // Sections 0 to 17
        assertEquals(18, sections.size)
        assertEquals("0. Definitions.", sections.first().text)
        assertTrue(blocks.filterIsInstance<LicenseText.Block.Item>().first().text.startsWith("a) The work must carry"))
        assertEquals(2, blocks.count { it is LicenseText.Block.Preformatted })
    }

    @Test
    fun `text is joined and decoded`() {
        val copyright = blocks.filterIsInstance<LicenseText.Block.Paragraph>().first { it.text.startsWith("Copyright") }
        assertEquals("Copyright © 2007 Free Software Foundation, Inc. <https://fsf.org/>", copyright.text)
        val paragraphs = blocks.filterIsInstance<LicenseText.Block.Paragraph>().map { it.text }
        assertTrue(paragraphs.none { '\n' in it || "&ldquo;" in it || "&lt;" in it })
        val example = blocks.filterIsInstance<LicenseText.Block.Preformatted>().first()
        assertTrue(example.text.startsWith("<one line to give the program's name"))
    }
}
