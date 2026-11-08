package sh.hnet.comfychair.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import sh.hnet.comfychair.workflow.MarkdownParser

/**
 * A note's content laid out for the canvas: its Markdown blocks one below another (text, code blocks on their own
 * background, tables), with the height they take. Laid out once per content, width and colours ([NoteLayouts]), not
 * every frame.
 */
internal class NoteLayout(val width: Float, val height: Float, val parts: List<Part>) {
    sealed interface Part { val top: Float }
    class TextPart(override val top: Float, val text: LaidText) : Part
    class CodePart(override val top: Float, val height: Float, val layout: TextLayoutResult) : Part
    class TablePart(
        override val top: Float,
        val width: Float,
        val columnLefts: List<Float>,
        val rowTops: List<Float>,
        val rowHeights: List<Float>,
        // Rows of cells, the header first
        val cells: List<List<LaidText>>
    ) : Part
}

/** Laid-out text and the areas of its inline code, for their rounded background (from the text's own origin). */
internal class LaidText(val layout: TextLayoutResult) {
    val codeAreas: List<Rect> = codeAreasOf(layout)
}

// Inline code's background: a little beyond its characters, a little inside its line's height, rounded
private const val CODE_AREA_PADDING = 3f
private const val CODE_AREA_INSET = 1f
private const val CODE_AREA_CORNER = 5f

private fun codeAreasOf(layout: TextLayoutResult): List<Rect> {
    val text = layout.layoutInput.text
    return text.getStringAnnotations(MarkdownParser.CODE_TAG, 0, text.length).flatMap { range ->
        if (range.end <= range.start) return@flatMap emptyList()
        // One area per line the code runs over
        (layout.getLineForOffset(range.start)..layout.getLineForOffset(range.end - 1)).mapNotNull { line ->
            val start = maxOf(range.start, layout.getLineStart(line))
            val end = minOf(range.end, layout.getLineEnd(line, visibleEnd = true))
            if (end <= start) return@mapNotNull null
            val first = layout.getBoundingBox(start)
            val last = layout.getBoundingBox(end - 1)
            Rect(
                minOf(first.left, last.left) - CODE_AREA_PADDING,
                layout.getLineTop(line) + CODE_AREA_INSET,
                maxOf(first.right, last.right) + CODE_AREA_PADDING,
                layout.getLineBottom(line) - CODE_AREA_INSET
            )
        }
    }
}

private fun DrawScope.drawLaidText(text: LaidText, origin: Offset, codeBackground: Color, alpha: Float) {
    text.codeAreas.forEach { area ->
        drawRoundRect(
            color = codeBackground,
            topLeft = origin + area.topLeft,
            size = area.size,
            cornerRadius = CornerRadius(CODE_AREA_CORNER),
            alpha = alpha
        )
    }
    drawText(text.layout, topLeft = origin, alpha = alpha)
}

/** The colours a note's content is laid out and drawn in: theme roles, and two tones between the note and its text. */
internal data class NoteColors(val text: Color, val codeBackground: Color, val rule: Color)

/** Laid-out notes by what decides their layout; the canvas keeps one. */
internal class NoteLayouts {
    private data class Key(val content: String, val width: Float, val colors: NoteColors, val fontSize: TextUnit, val lineHeight: TextUnit)
    private val cache = HashMap<Key, NoteLayout>()

    fun layout(
        content: String,
        width: Float,
        colors: NoteColors,
        fontSize: TextUnit,
        lineHeight: TextUnit,
        textMeasurer: TextMeasurer,
        density: Density
    ): NoteLayout {
        val key = Key(content, width, colors, fontSize, lineHeight)
        cache[key]?.let { return it }
        // Old layouts (edited notes, other themes) don't pile up
        if (cache.size >= MAX_CACHED) cache.clear()
        return measureNote(content, width, colors, fontSize, lineHeight, textMeasurer, density).also { cache[key] = it }
    }

    private companion object {
        const val MAX_CACHED = 64
    }
}

private const val BLOCK_GAP = 12f
// Room above a heading (more than between other blocks) and below it, before its text
private const val HEADING_GAP_ABOVE = 20f
private const val HEADING_GAP_BELOW = 4f
private const val CODE_PADDING = 12f
private const val CODE_CORNER = 8f
// A code block's text shrinks so its longest line fits, down to this; longer lines wrap
private const val CODE_MIN_SCALE = 0.6f
private const val CELL_PADDING_H = 8f
private const val CELL_PADDING_V = 6f
private const val HEADER_RULE = 2f
private const val ROW_RULE = 1f

private fun measureNote(
    content: String,
    width: Float,
    colors: NoteColors,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    textMeasurer: TextMeasurer,
    density: Density
): NoteLayout {
    val style = TextStyle(color = colors.text, fontSize = fontSize, lineHeight = lineHeight)
    val blocks = MarkdownParser.parseBlocks(content, fontSize)
    val parts = mutableListOf<NoteLayout.Part>()
    var y = 0f
    var afterHeading = false
    for (block in blocks) {
        if (parts.isNotEmpty()) y += when {
            block is MarkdownParser.Block.Heading -> HEADING_GAP_ABOVE
            afterHeading -> HEADING_GAP_BELOW
            else -> BLOCK_GAP
        }
        afterHeading = block is MarkdownParser.Block.Heading
        when (block) {
            is MarkdownParser.Block.Heading -> {
                val layout = textMeasurer.measure(
                    block.text,
                    style.copy(fontSize = fontSize * block.scale, lineHeight = lineHeight * block.scale, fontWeight = FontWeight.Bold),
                    constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1))
                )
                parts.add(NoteLayout.TextPart(y, LaidText(layout)))
                y += layout.size.height
            }
            is MarkdownParser.Block.Text -> {
                val layout = textMeasurer.measure(block.text, style, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)))
                parts.add(NoteLayout.TextPart(y, LaidText(layout)))
                y += layout.size.height
            }
            is MarkdownParser.Block.Code -> {
                val inner = (width - 2 * CODE_PADDING).coerceAtLeast(1f)
                val mono = style.copy(fontFamily = FontFamily.Monospace)
                val longest = textMeasurer.measure(block.text, mono, softWrap = false).size.width.toFloat()
                val scale = if (longest <= inner) 1f else (inner / longest).coerceAtLeast(CODE_MIN_SCALE)
                val layout = textMeasurer.measure(
                    block.text,
                    mono.copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale),
                    constraints = Constraints(maxWidth = inner.toInt())
                )
                val height = layout.size.height + 2 * CODE_PADDING
                parts.add(NoteLayout.CodePart(y, height, layout))
                y += height
            }
            is MarkdownParser.Block.Table -> {
                val table = measureTable(block, width, style, textMeasurer)
                parts.add(table.copyAt(y))
                y += table.rowTops.last() + table.rowHeights.last()
            }
        }
    }
    return NoteLayout(width, y, parts)
}

/** A table: columns as wide as their content when it fits, else sharing the width by it; text wraps in its cell. */
private fun measureTable(
    table: MarkdownParser.Block.Table,
    width: Float,
    style: TextStyle,
    textMeasurer: TextMeasurer
): NoteLayout.TablePart {
    val headerStyle = style.copy(fontWeight = FontWeight.Bold)
    val rows = listOf(table.header) + table.rows
    val columns = table.header.size
    // Each column's widest cell on one line
    val natural = (0 until columns).map { column ->
        rows.withIndex().maxOf { (index, row) ->
            textMeasurer.measure(row[column], if (index == 0) headerStyle else style, softWrap = false).size.width.toFloat()
        } + 2 * CELL_PADDING_H
    }
    val total = natural.sum()
    val widths = if (total <= width) natural else {
        // Shared by content, each at least a fair part of what's left (a short column isn't squeezed to nothing)
        val floor = width / columns * 0.5f
        val fixed = natural.map { minOf(it, floor) }
        val rest = width - fixed.sum()
        val extra = natural.zip(fixed).map { (n, f) -> n - f }
        val extraTotal = extra.sum().coerceAtLeast(1f)
        fixed.zip(extra).map { (f, e) -> f + rest * e / extraTotal }
    }
    val lefts = widths.runningFold(0f) { acc, w -> acc + w }.dropLast(1)
    val cells = rows.mapIndexed { index, row ->
        row.mapIndexed { column, text ->
            val align = when (table.alignments.getOrNull(column)) {
                MarkdownParser.Alignment.CENTER -> TextAlign.Center
                MarkdownParser.Alignment.END -> TextAlign.End
                else -> TextAlign.Start
            }
            val inner = (widths[column] - 2 * CELL_PADDING_H).toInt().coerceAtLeast(1)
            LaidText(
                textMeasurer.measure(
                    text,
                    (if (index == 0) headerStyle else style).copy(textAlign = align),
                    constraints = Constraints.fixedWidth(inner)
                )
            )
        }
    }
    val heights = cells.map { row -> row.maxOf { it.layout.size.height }.toFloat() + 2 * CELL_PADDING_V }
    val tops = heights.runningFold(0f) { acc, h -> acc + h }.dropLast(1)
    return NoteLayout.TablePart(0f, widths.sum(), lefts, tops, heights, cells)
}

private fun NoteLayout.TablePart.copyAt(top: Float) =
    NoteLayout.TablePart(top, width, columnLefts, rowTops, rowHeights, cells)

/** Draw [layout] with its top start at [left], [top]. */
internal fun DrawScope.drawNoteContent(layout: NoteLayout, left: Float, top: Float, colors: NoteColors, alpha: Float) {
    for (part in layout.parts) {
        val y = top + part.top
        when (part) {
            is NoteLayout.TextPart -> drawLaidText(part.text, Offset(left, y), colors.codeBackground, alpha)
            is NoteLayout.CodePart -> {
                drawRoundRect(
                    color = colors.codeBackground,
                    topLeft = Offset(left, y),
                    // As wide as the note's content
                    size = Size(layout.width, part.height),
                    cornerRadius = CornerRadius(CODE_CORNER),
                    alpha = alpha
                )
                drawText(part.layout, topLeft = Offset(left + CODE_PADDING, y + CODE_PADDING), alpha = alpha)
            }
            is NoteLayout.TablePart -> {
                part.cells.forEachIndexed { row, cells ->
                    val rowTop = y + part.rowTops[row]
                    cells.forEachIndexed { column, cell ->
                        drawLaidText(
                            cell,
                            Offset(left + part.columnLefts[column] + CELL_PADDING_H, rowTop + CELL_PADDING_V),
                            colors.codeBackground,
                            alpha
                        )
                    }
                    // A rule under the header, thinner ones between rows
                    if (row < part.cells.size - 1) {
                        val ruleY = rowTop + part.rowHeights[row]
                        val stroke = if (row == 0) HEADER_RULE else ROW_RULE
                        drawLine(
                            color = colors.rule,
                            start = Offset(left, ruleY),
                            end = Offset(left + part.width, ruleY),
                            strokeWidth = stroke,
                            alpha = alpha
                        )
                    }
                }
            }
        }
    }
}
