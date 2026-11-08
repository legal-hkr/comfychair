package sh.hnet.comfychair.workflow

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/**
 * A note's Markdown as blocks for drawing on the canvas: runs of styled text, code blocks (kept line by line, drawn
 * on their own background) and GitHub tables (drawn as tables). Base text colour and size come from the TextStyle
 * the text is measured with.
 */
object MarkdownParser {

    /** One block of a note, drawn below the previous one. */
    sealed interface Block {
        data class Text(val text: AnnotatedString) : Block
        /** A heading: its text and how much larger than the base size it is drawn (bold), with room around it. */
        data class Heading(val text: AnnotatedString, val scale: Float) : Block
        data class Code(val text: String) : Block
        data class Table(
            val alignments: List<Alignment>,
            val header: List<AnnotatedString>,
            val rows: List<List<AnnotatedString>>
        ) : Block
    }

    enum class Alignment { START, CENTER, END }

    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
            .build()
    }

    /** Inline code's annotation: monospaced, its background drawn by the canvas (rounded, which a span can't be). */
    const val CODE_TAG = "code"

    /**
     * Parse [markdown] into blocks; inline code is marked with [CODE_TAG], headings sized from [baseFontSize]. Code
     * blocks and tables nested in lists or quotes stay text.
     */
    fun parseBlocks(markdown: String, baseFontSize: TextUnit): List<Block> {
        val context = RenderContext(baseFontSize)
        val blocks = mutableListOf<Block>()
        var text = AnnotatedString.Builder()
        fun flush() {
            val done = text.toAnnotatedString()
            val end = done.text.trimEnd('\n').length
            if (end > 0) blocks.add(Block.Text(done.subSequence(0, end)))
            text = AnnotatedString.Builder()
        }
        var node = parser.parse(markdown).firstChild
        while (node != null) {
            when (node) {
                is FencedCodeBlock -> { flush(); blocks.add(Block.Code(node.literal.trimEnd())) }
                is IndentedCodeBlock -> { flush(); blocks.add(Block.Code(node.literal.trimEnd())) }
                is TableBlock -> { flush(); tableOf(node, context)?.let(blocks::add) }
                is Heading -> {
                    flush()
                    blocks.add(Block.Heading(buildAnnotatedString { renderChildren(node, context) }, headingScale(node.level)))
                }
                else -> text.renderNode(node, context)
            }
            node = node.next
        }
        flush()
        return blocks
    }

    private class RenderContext(val baseFontSize: TextUnit) {
        // Inside a paragraph already (a quote or a table in a list item): blocks run on in it, as paragraph styles
        // must not overlap
        var inParagraph = false
    }

    private fun tableOf(table: TableBlock, context: RenderContext): Block.Table? {
        var alignments = emptyList<Alignment>()
        var header = emptyList<AnnotatedString>()
        val rows = mutableListOf<List<AnnotatedString>>()
        fun cellsOf(row: Node): List<Pair<TableCell, AnnotatedString>> = generateSequence(row.firstChild) { it.next }
            .filterIsInstance<TableCell>()
            .map { cell -> cell to buildAnnotatedString { renderChildren(cell, context) } }
            .toList()
        generateSequence(table.firstChild) { it.next }.forEach { section ->
            generateSequence(section.firstChild) { it.next }.filterIsInstance<TableRow>().forEach { row ->
                val cells = cellsOf(row)
                when (section) {
                    is TableHead -> {
                        header = cells.map { it.second }
                        alignments = cells.map { (cell, _) ->
                            when (cell.alignment) {
                                TableCell.Alignment.CENTER -> Alignment.CENTER
                                TableCell.Alignment.RIGHT -> Alignment.END
                                else -> Alignment.START
                            }
                        }
                    }
                    is TableBody -> rows.add(cells.map { it.second })
                }
            }
        }
        if (header.isEmpty()) return null
        return Block.Table(alignments, header, rows.map { row -> List(header.size) { row.getOrNull(it) ?: AnnotatedString("") } })
    }

    private fun AnnotatedString.Builder.renderNode(node: Node, context: RenderContext) {
        when (node) {
            is Document -> renderChildren(node, context)
            is Paragraph -> paragraph(context) { renderChildren(node, context) }
            is Heading -> paragraph(context) { styled(headingStyle(node.level, context)) { renderChildren(node, context) } }
            is Text -> append(node.literal)
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            is Emphasis -> styled(SpanStyle(fontStyle = FontStyle.Italic)) { renderChildren(node, context) }
            is StrongEmphasis -> styled(SpanStyle(fontWeight = FontWeight.Bold)) { renderChildren(node, context) }
            is Strikethrough -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough)) { renderChildren(node, context) }
            is Code -> {
                pushStringAnnotation(CODE_TAG, "")
                styled(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
                pop()
            }
            // Each item ends with its newline (see renderList)
            is BulletList, is OrderedList -> renderList(node, context, depth = 0)
            is ListItem -> renderChildren(node, context)
            // Nested in a list or a quote: monospaced text
            is FencedCodeBlock, is IndentedCodeBlock -> {
                val literal = if (node is FencedCodeBlock) node.literal else (node as IndentedCodeBlock).literal
                paragraph(context) { styled(SpanStyle(fontFamily = FontFamily.Monospace)) { append(literal.trimEnd()) } }
            }
            is BlockQuote -> styled(SpanStyle(fontStyle = FontStyle.Italic)) { renderChildren(node, context) }
            is ThematicBreak -> paragraph(context) { append("———") }
            // No clickable links on the canvas: the link's text
            is Link -> renderChildren(node, context)
            is Image -> append("[${node.title ?: "image"}]")
            else -> renderChildren(node, context)
        }
    }

    /**
     * Every paragraph is its own paragraph style and none ends with a newline: a style's boundary already breaks the
     * line, so a newline beside one is an empty line (a list item's indent is a paragraph style, which left blank
     * lines around items).
     */
    private inline fun AnnotatedString.Builder.paragraph(
        context: RenderContext,
        style: ParagraphStyle = ParagraphStyle(),
        block: AnnotatedString.Builder.() -> Unit
    ) {
        // Within a paragraph, a block runs on after a space instead
        if (context.inParagraph) {
            append(" ")
            block()
            return
        }
        pushStyle(style)
        block()
        pop()
    }

    private inline fun AnnotatedString.Builder.styled(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
        pushStyle(style)
        block()
        pop()
    }

    private fun AnnotatedString.Builder.renderChildren(parent: Node, context: RenderContext) {
        var child = parent.firstChild
        while (child != null) {
            renderNode(child, context)
            child = child.next
        }
    }

    /**
     * A list's items, each paragraph of an item its own paragraph indented by the list's [depth], the marker hanging
     * before the first one's text (wrapped lines start under the text); nested lists one level deeper.
     */
    private fun AnnotatedString.Builder.renderList(list: Node, context: RenderContext, depth: Int) {
        val numbered = list is OrderedList
        @Suppress("DEPRECATION")
        var number = (list as? OrderedList)?.startNumber ?: 1
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                var first = true
                var child = item.firstChild
                while (child != null) {
                    if ((child is BulletList || child is OrderedList) && !context.inParagraph) {
                        renderList(child, context, depth + 1)
                    } else {
                        val marker = if (!first) "" else if (numbered) "$number. " else "• "
                        val hang = if (numbered) NUMBER_HANG else BULLET_HANG
                        val indent = LIST_INDENT + depth * LIST_LEVEL
                        paragraph(context, ParagraphStyle(textIndent = TextIndent(firstLine = indent.em, restLine = (indent + hang).em))) {
                            append(marker)
                            // What the item holds stays in its paragraph
                            val outer = context.inParagraph
                            context.inParagraph = true
                            when (child) {
                                is Paragraph -> renderChildren(child, context)
                                is FencedCodeBlock -> styled(SpanStyle(fontFamily = FontFamily.Monospace)) { append(child.literal.trimEnd()) }
                                is IndentedCodeBlock -> styled(SpanStyle(fontFamily = FontFamily.Monospace)) { append(child.literal.trimEnd()) }
                                else -> renderChildren(child, context)
                            }
                            context.inParagraph = outer
                        }
                        first = false
                    }
                    child = child.next
                }
                number++
            }
            item = item.next
        }
    }

    // Lists' indents in em: the first level, each deeper one, and the marker's hang ("• ", "10. ")
    private const val LIST_INDENT = 0.8f
    private const val LIST_LEVEL = 1.2f
    private const val BULLET_HANG = 0.7f
    private const val NUMBER_HANG = 1.3f

    /** H1 150 %, H2 130 %, H3 115 %, smaller ones the base size (all bold). */
    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.5f
        2 -> 1.3f
        3 -> 1.15f
        else -> 1f
    }

    // A heading nested in a list or a quote: bold, larger, within the text
    private fun headingStyle(level: Int, context: RenderContext): SpanStyle =
        SpanStyle(fontWeight = FontWeight.Bold, fontSize = context.baseFontSize * headingScale(level))
}
