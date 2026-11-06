package sh.hnet.comfychair.util

/**
 * Release notes as written for a release (the F-Droid changelog, bundled with the app), read into sections for the
 * What's new page. No Android dependencies, so the rules are unit-tested.
 *
 * @param version The first version line, without its leading "v"; null when the file has none
 */
data class Changelog(val version: String?, val sections: List<Section>) {
    data class Section(val title: String?, val blocks: List<Block>)

    sealed interface Block {
        data class Item(val text: String) : Block
        data class Paragraph(val text: String) : Block
    }

    /** All items across the sections (the "N changes" of the About row). */
    val itemCount: Int get() = sections.sumOf { section -> section.blocks.count { it is Block.Item } }

    companion object {
        private val VERSION = Regex("""^v?(\d+(\.\d+){1,3}[\w.-]*)$""", RegexOption.IGNORE_CASE)
        private val BULLETS = listOf("- ", "* ", "• ")

        /**
         * Line by line: the first version line ("v0.9.0") is the version; a line ending in a colon starts a section
         * titled without it; "- ", "* " or "• " lines are items, and lines indented by 2+ spaces under an item continue
         * it; other lines are paragraphs, consecutive ones joined; blank lines only separate. Text before the first
         * heading goes into an untitled section; empty sections are dropped.
         */
        fun parse(text: String): Changelog {
            var version: String? = null
            val sections = mutableListOf<Section>()
            var title: String? = null
            var blocks = mutableListOf<Block>()
            // What a following line may join: the last item (indented continuation) or the open paragraph
            var openItem = false
            var openParagraph = false

            fun closeSection() {
                if (blocks.isNotEmpty()) sections += Section(title, blocks)
                blocks = mutableListOf()
                openItem = false
                openParagraph = false
            }

            for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val line = raw.trimEnd()
                val trimmed = line.trimStart()
                val bullet = BULLETS.firstOrNull { trimmed.startsWith(it) }
                when {
                    trimmed.isEmpty() -> {
                        openItem = false
                        openParagraph = false
                    }
                    version == null && VERSION.matches(trimmed) -> {
                        version = VERSION.matchEntire(trimmed)!!.groupValues[1]
                        openItem = false
                        openParagraph = false
                    }
                    bullet != null -> {
                        blocks += Block.Item(trimmed.removePrefix(bullet).trim())
                        openItem = true
                        openParagraph = false
                    }
                    openItem && line.startsWith("  ") -> {
                        val last = blocks.removeAt(blocks.lastIndex) as Block.Item
                        blocks += Block.Item("${last.text} $trimmed")
                    }
                    trimmed.endsWith(":") -> {
                        closeSection()
                        title = trimmed.dropLast(1).trimEnd()
                    }
                    openParagraph -> {
                        val last = blocks.removeAt(blocks.lastIndex) as Block.Paragraph
                        blocks += Block.Paragraph("${last.text} $trimmed")
                    }
                    else -> {
                        blocks += Block.Paragraph(trimmed)
                        openItem = false
                        openParagraph = true
                    }
                }
            }
            closeSection()
            return Changelog(version, sections)
        }
    }
}
