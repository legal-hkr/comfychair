package sh.hnet.comfychair.workflow

import java.text.Normalizer

/**
 * The node browser's content (spec §22), without the UI: the node types as a tree of their categories, split into
 * ComfyUI's own (Built-in) and extension packs (Extensions), and search results ranked best first.
 */
object NodeBrowserTree {

    /** Where a top-level folder belongs. */
    enum class Section { BUILT_IN, EXTENSIONS }

    /** What a folder stands for: a category, an extension pack (top level of Extensions), the API nodes, the rest. */
    enum class Kind { CATEGORY, PACK, API, OTHER }

    /**
     * A category folder: [key] unique in the tree (section and path), [name] the category's own segment as written,
     * its subfolders and nodes by name (deprecated and experimental ones last), and how many nodes it holds in all.
     */
    class Folder(
        val key: String,
        val name: String,
        val kind: Kind,
        val folders: List<Folder>,
        val nodes: List<NodeTypeDefinition>
    ) {
        val count: Int = nodes.size + folders.sumOf { it.count }
    }

    /** The top level: Built-in's folders and the Extensions' ones, each by name (API nodes and Other last in Built-in). */
    class Tree(val builtIn: List<Folder>, val extensions: List<Folder>) {
        val count: Int = builtIn.sumOf { it.count } + extensions.sumOf { it.count }
        val isEmpty: Boolean get() = builtIn.isEmpty() && extensions.isEmpty()
    }

    // ComfyUI's top-level category of its API ("partner") nodes, shown as one folder
    private const val API_CATEGORY = "partner"

    private fun segmentsOf(category: String): List<String> = category.split("/", "\\").filter { it.isNotEmpty() }

    /** Nodes in a folder: the usual ones by name, then deprecated and experimental ones. */
    private val nodeOrder = compareBy<NodeTypeDefinition>({ it.deprecated || it.experimental }, { displayNameOf(it).lowercase() })

    fun displayNameOf(node: NodeTypeDefinition): String = node.displayName?.takeIf { it.isNotBlank() } ?: node.classType

    /** The tree of [nodes] (already filtered, e.g. to a held socket's type). */
    fun build(nodes: List<NodeTypeDefinition>): Tree {
        fun folderOf(key: String, name: String, kind: Kind, members: List<Pair<List<String>, NodeTypeDefinition>>): Folder {
            val (here, deeper) = members.partition { it.first.isEmpty() }
            val folders = deeper.groupBy { it.first.first() }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (segment, inside) ->
                folderOf("$key/$segment", segment, Kind.CATEGORY, inside.map { it.first.drop(1) to it.second })
            }
            return Folder(key, name, kind, folders, here.map { it.second }.sortedWith(nodeOrder))
        }
        fun topLevel(section: Section, members: List<NodeTypeDefinition>): List<Folder> {
            val bySegment = members.groupBy { segmentsOf(it.category).firstOrNull().orEmpty() }
            return bySegment.entries.map { (top, inside) ->
                val kind = when {
                    top.isEmpty() -> Kind.OTHER
                    section == Section.BUILT_IN && top == API_CATEGORY -> Kind.API
                    section == Section.EXTENSIONS -> Kind.PACK
                    else -> Kind.CATEGORY
                }
                folderOf("${section.name}/$top", top, kind, inside.map { segmentsOf(it.category).drop(1) to it })
            }.sortedWith(compareBy({ it.kind == Kind.API || it.kind == Kind.OTHER }, { it.kind == Kind.OTHER }, { it.name.lowercase() }))
        }
        val (extensions, builtIn) = nodes.partition { it.isCustom }
        return Tree(topLevel(Section.BUILT_IN, builtIn), topLevel(Section.EXTENSIONS, extensions))
    }

    /**
     * The folders shown open: [openKeys] lists one key per level at most (an accordion), so opening a folder keeps its
     * ancestors open and closes its siblings with what they held.
     */
    fun open(openKeys: List<String>, folder: Folder): List<String> =
        openKeys.filter { key -> folder.key.startsWith("$key/") } + folder.key

    /** [openKeys] with [folder] closed (and whatever was open inside it). */
    fun close(openKeys: List<String>, folder: Folder): List<String> =
        openKeys.filterNot { it == folder.key || it.startsWith("${folder.key}/") }

    // Combining marks, which normalizing to NFD leaves after the letters they decorated
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")
    private val WORD_BREAKS = Regex("[^\\p{L}\\p{N}]+")

    /** The text as compared: lower case, without diacritics. */
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    /** A node's texts as compared, worked out once per node (a sort or a search goes through thousands). */
    class Entry(val node: NodeTypeDefinition) {
        val name = normalize(displayNameOf(node))
        val classType = normalize(node.classType)
        val category = normalize(node.category)
        val description = normalize(node.description.orEmpty())
        val all = "$name $classType $category $description"
        val nameWords = name.split(WORD_BREAKS)
    }

    /** [nodes] ready to sort and search. */
    fun index(nodes: List<NodeTypeDefinition>): List<Entry> = nodes.map(::Entry)

    /** Every node of [index] by name. */
    fun byName(index: List<Entry>): List<Entry> = index.sortedBy { it.name }

    /**
     * The nodes matching [query], best first: every word of it found somewhere (name, class, category, description),
     * ranked by where the whole query is found: the name starts with it, a word of the name does, the name holds it,
     * the class does, the category, the description (or only its words); then usual nodes before deprecated and
     * experimental ones, then by name.
     */
    @JvmName("searchIndex")
    fun search(index: List<Entry>, query: String): List<NodeTypeDefinition> {
        val whole = normalize(query.trim())
        if (whole.isEmpty()) return emptyList()
        val words = whole.split(SPACES).filter { it.isNotEmpty() }
        return index.mapNotNull { entry ->
            if (words.any { it !in entry.all }) return@mapNotNull null
            val rank = when {
                entry.name.startsWith(whole) -> 0
                entry.nameWords.any { it.startsWith(whole) } -> 1
                whole in entry.name -> 2
                whole in entry.classType -> 3
                whole in entry.category -> 4
                else -> 5
            }
            rank to entry
        }.sortedWith(compareBy({ it.first }, { it.second.node.deprecated || it.second.node.experimental }, { it.second.name }))
            .map { it.second.node }
    }

    /** [search] on nodes not indexed yet. */
    fun search(nodes: List<NodeTypeDefinition>, query: String): List<NodeTypeDefinition> = search(index(nodes), query)
}
