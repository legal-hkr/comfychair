package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewTest {

    @Test
    fun parsesTheChangelogFormat() {
        val sections = WhatsNew.parse(
            """
            v0.8.13

            Privacy and security:
            - Media kept in memory only by default
            - No silent fallback from HTTPS to HTTP

            New features:
            - On/off switch for each workflow
            """.trimIndent()
        )
        assertEquals(listOf("Privacy and security", "New features"), sections.map { it.title })
        assertEquals(2, sections[0].lines.size)
        assertEquals("No silent fallback from HTTPS to HTTP", sections[0].lines[1].text)
        assertEquals(true, sections[1].lines.single().bullet)
    }

    @Test
    fun keepsTextOutsideSections() {
        val sections = WhatsNew.parse("v1.0\n\nA quiet release.\n")
        assertNull(sections.single().title)
        assertEquals(WhatsNew.Line("A quiet release.", bullet = false), sections.single().lines.single())
    }
}
