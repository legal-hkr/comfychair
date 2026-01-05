package sh.hnet.comfychair.workflow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/**
 * Parses markdown text and returns an AnnotatedString with appropriate styles.
 * Designed for use with TextMeasurer in Canvas drawing.
 * Base text color and fontSize are provided via TextStyle in TextMeasurer.measure().
 */
object MarkdownParser {

    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(listOf(StrikethroughExtension.create()))
            .build()
    }

    /**
     * Parse markdown content into an AnnotatedString.
     *
     * @param markdown The markdown text to parse
     * @param codeBackgroundColor Background color for inline code blocks
     * @param baseFontSize Base font size for proportional heading sizes
     * @return AnnotatedString with styles applied
     */
    fun parse(
        markdown: String,
        codeBackgroundColor: Color,
        baseFontSize: TextUnit
    ): AnnotatedString {
        val document = parser.parse(markdown)

        return buildAnnotatedString {
            val context = RenderContext(
                codeBackgroundColor = codeBackgroundColor,
                baseFontSize = baseFontSize
            )
            renderNode(document, context, emptyList())
        }
    }

    private data class RenderContext(
        val codeBackgroundColor: Color,
        val baseFontSize: TextUnit
    )

    private fun AnnotatedString.Builder.renderNode(
        node: Node,
        context: RenderContext,
        styleStack: List<SpanStyle>
    ) {
        when (node) {
            is Document -> renderChildren(node, context, styleStack)
            is Paragraph -> {
                renderChildren(node, context, styleStack)
                // Add newline after paragraph (except at end)
                if (node.next != null) append("\n")
            }
            is Heading -> {
                val headingStyle = getHeadingStyle(node.level, context)
                pushStyle(headingStyle)
                renderChildren(node, context, styleStack + headingStyle)
                pop()
                if (node.next != null) append("\n")
            }
            is Text -> {
                append(node.literal)
            }
            is SoftLineBreak -> {
                append(" ")
            }
            is HardLineBreak -> {
                append("\n")
            }
            is Emphasis -> {
                val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
                pushStyle(italicStyle)
                renderChildren(node, context, styleStack + italicStyle)
                pop()
            }
            is StrongEmphasis -> {
                val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
                pushStyle(boldStyle)
                renderChildren(node, context, styleStack + boldStyle)
                pop()
            }
            is Strikethrough -> {
                val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
                pushStyle(strikeStyle)
                renderChildren(node, context, styleStack + strikeStyle)
                pop()
            }
            is Code -> {
                val codeStyle = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = context.codeBackgroundColor
                )
                pushStyle(codeStyle)
                append(node.literal)
                pop()
            }
            is BulletList -> {
                renderListItems(node, context, styleStack, numbered = false)
            }
            is OrderedList -> {
                @Suppress("DEPRECATION")
                renderListItems(node, context, styleStack, numbered = true, startNumber = node.startNumber)
            }
            is ListItem -> {
                // Handled by renderListItems
                renderChildren(node, context, styleStack)
            }
            is FencedCodeBlock -> {
                val codeStyle = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = context.codeBackgroundColor
                )
                pushStyle(codeStyle)
                append(node.literal.trimEnd())
                pop()
                if (node.next != null) append("\n")
            }
            is IndentedCodeBlock -> {
                val codeStyle = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = context.codeBackgroundColor
                )
                pushStyle(codeStyle)
                append(node.literal.trimEnd())
                pop()
                if (node.next != null) append("\n")
            }
            is BlockQuote -> {
                // Render with italic style to indicate quote
                val quoteStyle = SpanStyle(fontStyle = FontStyle.Italic)
                pushStyle(quoteStyle)
                renderChildren(node, context, styleStack + quoteStyle)
                pop()
            }
            is ThematicBreak -> {
                append("———\n")
            }
            is Link -> {
                // Just render link text (no clickable links in canvas)
                renderChildren(node, context, styleStack)
            }
            is Image -> {
                // Show alt text for images
                append("[${node.title ?: "image"}]")
            }
            else -> {
                // For unknown nodes, try to render children
                renderChildren(node, context, styleStack)
            }
        }
    }

    private fun AnnotatedString.Builder.renderChildren(
        parent: Node,
        context: RenderContext,
        styleStack: List<SpanStyle>
    ) {
        var child = parent.firstChild
        while (child != null) {
            renderNode(child, context, styleStack)
            child = child.next
        }
    }

    private fun AnnotatedString.Builder.renderListItems(
        list: Node,
        context: RenderContext,
        styleStack: List<SpanStyle>,
        numbered: Boolean,
        startNumber: Int = 1
    ) {
        var itemNumber = startNumber
        var child = list.firstChild
        while (child != null) {
            if (child is ListItem) {
                // Add bullet or number prefix
                val prefix = if (numbered) {
                    "${itemNumber}. "
                } else {
                    "• "
                }
                append(prefix)

                // Render item content (without extra newline from paragraph)
                var itemChild = child.firstChild
                while (itemChild != null) {
                    when (itemChild) {
                        is Paragraph -> {
                            // Render paragraph content without trailing newline
                            renderChildren(itemChild, context, styleStack)
                        }
                        else -> {
                            renderNode(itemChild, context, styleStack)
                        }
                    }
                    itemChild = itemChild.next
                }

                // Add newline after item (except for last item)
                if (child.next != null) append("\n")

                itemNumber++
            }
            child = child.next
        }
        // Add newline after list if there's more content
        if (list.next != null) append("\n")
    }

    private fun getHeadingStyle(level: Int, context: RenderContext): SpanStyle {
        val fontSize = when (level) {
            1 -> context.baseFontSize * 1.5f   // H1: 150%
            2 -> context.baseFontSize * 1.3f   // H2: 130%
            3 -> context.baseFontSize * 1.15f  // H3: 115%
            else -> context.baseFontSize
        }
        return SpanStyle(fontWeight = FontWeight.Bold, fontSize = fontSize)
    }
}
