package sh.hnet.comfychair.workflow

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun blocks(markdown: String) = MarkdownParser.parseBlocks(markdown, 14.sp)

    @Test
    fun tableBecomesItsOwnBlock() {
        val result = blocks(
            """
            Place the `.safetensors` files.

            | LoRA | Trigger Words | Strength |
            |------|:-------------:|-----:|
            | `krea2_darkbrush` | `monochrome ink wash style` | `1.0` |
            | `krea2_dotmatrix` | **bold** | |

            After.
            """.trimIndent()
        )
        assertEquals(3, result.size)
        assertEquals("Place the .safetensors files.", (result[0] as MarkdownParser.Block.Text).text.text)
        val table = result[1] as MarkdownParser.Block.Table
        assertEquals(listOf("LoRA", "Trigger Words", "Strength"), table.header.map { it.text })
        assertEquals(
            listOf(MarkdownParser.Alignment.START, MarkdownParser.Alignment.CENTER, MarkdownParser.Alignment.END),
            table.alignments
        )
        assertEquals(listOf("krea2_darkbrush", "monochrome ink wash style", "1.0"), table.rows[0].map { it.text })
        // A missing cell is empty, so every row has the header's columns
        assertEquals(listOf("krea2_dotmatrix", "bold", ""), table.rows[1].map { it.text })
        assertEquals("After.", (result[2] as MarkdownParser.Block.Text).text.text)
    }

    @Test
    fun fencedCodeKeepsItsLines() {
        val result = blocks("Intro\n\n```\n📂 ComfyUI/\n├── 📂 models/\n│   └── a.safetensors\n```\n\n## Next")
        assertEquals(3, result.size)
        assertEquals("📂 ComfyUI/\n├── 📂 models/\n│   └── a.safetensors", (result[1] as MarkdownParser.Block.Code).text)
        // Text blocks don't end with the newline that separated them from the code
        assertEquals("Intro", (result[0] as MarkdownParser.Block.Text).text.text)
        assertEquals(MarkdownParser.Block.Heading(androidx.compose.ui.text.AnnotatedString("Next"), 1.3f), result[2])
    }

    @Test
    fun codeInsideAListStaysText() {
        val result = blocks("- item\n\n  ```\n  code\n  ```")
        assertEquals(1, result.size)
        assertTrue((result[0] as MarkdownParser.Block.Text).text.text.contains("code"))
    }
}

class NoteWidthTest {
    @Test
    fun shortNoteTakesOneColumn() {
        assertEquals(WorkflowLayoutEngine.NODE_WIDTH, WorkflowNote(1, "t", "A few words.\n\n| a | b |\n|---|---|\n| 1 | 2 |").width)
    }

    @Test
    fun longNoteOrWideTableTakesTwo() {
        assertEquals(WorkflowNote.WIDE_WIDTH, WorkflowNote(1, "t", "word ".repeat(300)).width)
        val table = "| LoRA | Trigger Words | Recommended Strength |\n|---|---|---|\n| `krea2_softwatercolor` | `art deco watercolor style` | `1.0` |"
        assertEquals(WorkflowNote.WIDE_WIDTH, WorkflowNote(1, "t", table).width)
    }
}

class MarkdownListAndCodeTest {
    private fun text(markdown: String) =
        (MarkdownParser.parseBlocks(markdown, 14.sp).single() as MarkdownParser.Block.Text).text

    @Test
    fun inlineCodeIsMarked() {
        val result = text("Put `a.safetensors` in `models/`.")
        assertEquals(
            listOf("a.safetensors", "models/"),
            result.getStringAnnotations(MarkdownParser.CODE_TAG, 0, result.length).map { result.text.substring(it.start, it.end) }
        )
    }

    @Test
    fun listItemsAreIndentedParagraphs() {
        val result = text("- one\n- two\n  - nested\n- three")
        val paragraphs = result.paragraphStyles
        assertEquals(listOf("• one", "• two", "• nested", "• three"), paragraphs.map { result.text.substring(it.start, it.end) })
        val indents = paragraphs.map { it.item.textIndent!!.firstLine.value }
        // The nested item one level deeper
        assertTrue(indents[2] > indents[0])
    }

    @Test
    fun paragraphsHaveNoNewlinesBetweenThem() {
        // A newline beside a paragraph style's boundary is an empty line: every paragraph is a style, back to back
        val result = text("**diffusion_models**\n\n- model (12 GB)\n\n**vae**\n\n- vae (242 MB)")
        assertTrue('\n' !in result.text)
        val ranges = result.paragraphStyles.map { it.start to it.end }
        assertEquals(4, ranges.size)
        assertEquals(0, ranges.first().first)
        assertEquals(result.length, ranges.last().second)
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.second == b.first })
    }

    @Test
    fun containersInListItemsDontNestParagraphs() {
        // A quote (with its own list) inside a list item: its paragraphs run on in the item's, never overlapping
        val result = text("- item\n\n  > quoted\n  >\n  > - inner\n- next")
        val ranges = result.paragraphStyles.map { it.start to it.end }.sortedBy { it.first }
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.second <= b.first })
        assertTrue(result.text.contains("quoted"))
        assertTrue(result.text.contains("inner"))
    }
}
