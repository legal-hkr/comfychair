package sh.hnet.comfychair.util

/**
 * The licence's text, read from the FSF's standalone HTML page (kept in the assets as it is): headings, paragraphs,
 * list items and preformatted examples, in order, with whitespace joined and entities decoded. Only what that page
 * uses; pure Kotlin, so it's tested without Android.
 */
object LicenseText {

    sealed interface Block {
        /** [level] 1 the title, 2 a part (Preamble, Terms and Conditions…), 3 a numbered section */
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Item(val text: String) : Block
        /** An example notice, its lines as they are */
        data class Preformatted(val text: String) : Block
    }

    private val BLOCK = Regex("<(h3|h4|h5|p|li|pre)(?:\\s[^>]*)?>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG = Regex("<[^>]+>")
    private val SPACE = Regex("\\s+")
    private val ENTITIES = mapOf(
        "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&copy;" to "©", "&nbsp;" to " ",
        "&ldquo;" to "“", "&rdquo;" to "”", "&lsquo;" to "‘", "&rsquo;" to "’", "&ndash;" to "–", "&mdash;" to "—"
    )

    fun parse(html: String): List<Block> {
        val body = html.substringAfter("<body", html)
        return BLOCK.findAll(body).mapNotNull { match ->
            val tag = match.groupValues[1].lowercase()
            val raw = match.groupValues[2]
            if (tag == "pre") {
                return@mapNotNull decode(raw.replace(TAG, "")).trimEnd().takeIf { it.isNotBlank() }?.let { Block.Preformatted(it.trimIndent()) }
            }
            val text = decode(raw.replace(TAG, "")).replace(SPACE, " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            when (tag) {
                "h3" -> Block.Heading(1, text)
                "h4" -> Block.Heading(2, text)
                "h5" -> Block.Heading(3, text)
                "li" -> Block.Item(text)
                else -> Block.Paragraph(text)
            }
        }.toList()
    }

    // &amp; last, so "&amp;lt;" stays "&lt;"
    private fun decode(text: String): String =
        ENTITIES.entries.fold(text) { acc, (entity, char) -> acc.replace(entity, char) }.replace("&amp;", "&")
}
