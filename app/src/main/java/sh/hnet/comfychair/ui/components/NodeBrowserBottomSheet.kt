package sh.hnet.comfychair.ui.components

import sh.hnet.comfychair.ui.components.settings.groupShapes
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.NodeBrowserPrefs
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.SearchCountLine
import sh.hnet.comfychair.ui.components.shared.SearchEmptyState
import sh.hnet.comfychair.ui.components.shared.SearchField
import sh.hnet.comfychair.ui.components.shared.SearchFilter
import sh.hnet.comfychair.ui.components.shared.SearchFilterChips
import sh.hnet.comfychair.ui.components.shared.highlighted
import sh.hnet.comfychair.util.ConnectionValidator
import sh.hnet.comfychair.workflow.GraphColors
import sh.hnet.comfychair.workflow.NodeBrowserTree
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.nodeCategoryOf

/** Literal types that are not connection types */
private val LITERAL_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

/** Check if a type is a connection type (not a literal) */
private fun isConnectionType(type: String): Boolean = type.uppercase() !in LITERAL_TYPES

// Room above the list's first row
private val LIST_TOP_PADDING = 8.dp

// Each level of the tree in from the one above, as the model picker's tree
private val TREE_INDENT = 16.dp

/** One row of the browser's list. */
private sealed interface BrowserRow {
    val key: String
    data class Section(val title: String) : BrowserRow { override val key = "section_$title" }
    data class Folder(val folder: NodeBrowserTree.Folder, val depth: Int, val open: Boolean, val index: Int, val size: Int) : BrowserRow {
        override val key = "folder_${folder.key}"
    }
    data class Node(val node: NodeTypeDefinition, val depth: Int, val index: Int, val size: Int, val group: String) : BrowserRow {
        override val key = "node_${group}_${node.classType}"
    }
}

/**
 * The node browser (spec §22), a full-height sheet: the title with the view switch (tree or list), the search field on
 * the page, the held socket's filter under it, a count line while a query or the filter is on, then the list. The list
 * view: every node by name under its first letter. The tree view, the tree of categories (open folders scrolled out of
 * sight stay pinned at the top, root first): ComfyUI's own categories (Built-in, API nodes and the rest last) and the extension packs, each folder
 * with its node count, opening in place one per level. Searching, one flat list of matches, best first, the match in
 * bold. Tapping a node adds it (wired to the held socket when it came from one).
 *
 * @param filterToOutputType Held output socket: nodes that take its type
 * @param filterToInputType Held input socket: nodes that give its type
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeBrowserBottomSheet(
    nodeTypesByCategory: Map<String, List<NodeTypeDefinition>>,
    sheetState: SheetState,
    onNodeTypeSelected: (NodeTypeDefinition) -> Unit,
    onDismiss: () -> Unit,
    filterToOutputType: String? = null,
    filterToInputType: String? = null
) {
    val context = LocalContext.current
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val slotType = filterToOutputType ?: filterToInputType
    var filterOn by remember { mutableStateOf(slotType != null) }
    // The tree of categories, or every node by name (to look through without a name in mind)
    var listView by remember { mutableStateOf(NodeBrowserPrefs.isListView(context)) }

    val allNodes = remember(nodeTypesByCategory) { nodeTypesByCategory.values.flatten().distinctBy { it.classType } }
    fun fits(node: NodeTypeDefinition): Boolean = when {
        filterToOutputType != null -> node.inputs.any { isConnectionType(it.type) && ConnectionValidator.isTypeCompatible(filterToOutputType, it.type) }
        filterToInputType != null -> node.outputs.any { ConnectionValidator.isTypeCompatible(it.type, filterToInputType) }
        else -> true
    }
    val compatible = remember(allNodes, slotType) { if (slotType == null) allNodes else allNodes.filter(::fits) }
    val scope = if (filterOn) compatible else allNodes
    val tree = remember(scope) { NodeBrowserTree.build(scope) }
    // Each node's texts normalized once: sorting and searching thousands of them must not redo it
    val index = remember(scope) { NodeBrowserTree.index(scope) }
    val results = remember(index, query) { NodeBrowserTree.search(index, query) }
    val searching = query.isNotBlank()

    // Open folders, one per level; with the filter on, the folder holding most compatible nodes opens first
    var openKeys by remember {
        mutableStateOf(
            if (slotType != null) listOfNotNull((tree.builtIn + tree.extensions).maxByOrNull { it.count }?.key) else emptyList()
        )
    }
    var scrollTo by remember { mutableStateOf<String?>(null) }

    val sectionBuiltIn = stringResource(R.string.node_section_builtin)
    val sectionExtensions = stringResource(R.string.node_section_extensions)
    val rows = remember(tree, openKeys, searching, results, listView, index) {
        buildList {
            if (searching) {
                results.forEachIndexed { i, node -> add(BrowserRow.Node(node, 0, i, results.size, "found")) }
                return@buildList
            }
            if (listView) {
                // Every node by name, under its first letter (anything not a letter under "#")
                NodeBrowserTree.byName(index)
                    .groupBy { it.name.firstOrNull()?.takeIf { c -> c.isLetter() }?.uppercaseChar() ?: '#' }
                    .entries.sortedBy { (letter, _) -> if (letter == '#') "~" else letter.toString() }
                    .forEach { (letter, entries) ->
                        add(BrowserRow.Section(letter.toString()))
                        entries.forEachIndexed { i, entry -> add(BrowserRow.Node(entry.node, 0, i, entries.size, "letter_$letter")) }
                    }
                return@buildList
            }
            for ((title, folders) in listOf(sectionBuiltIn to tree.builtIn, sectionExtensions to tree.extensions)) {
                if (folders.isEmpty()) continue
                add(BrowserRow.Section(title))
                // One segmented group per section: its folders and what open ones show
                val group = mutableListOf<BrowserRow>()
                fun addFolder(folder: NodeBrowserTree.Folder, depth: Int) {
                    val open = folder.key in openKeys
                    group += BrowserRow.Folder(folder, depth, open, 0, 0)
                    if (!open) return
                    folder.folders.forEach { addFolder(it, depth + 1) }
                    folder.nodes.forEach { group += BrowserRow.Node(it, depth + 1, 0, 0, folder.key) }
                }
                folders.forEach { addFolder(it, 0) }
                group.forEachIndexed { i, row ->
                    add(
                        when (row) {
                            is BrowserRow.Folder -> row.copy(index = i, size = group.size)
                            is BrowserRow.Node -> row.copy(index = i, size = group.size)
                            else -> row
                        }
                    )
                }
            }
        }
    }

    val listState = rememberLazyListState()
    // Results start at the top; a tree's scroll would cut off the best match
    LaunchedEffect(query) { if (query.isNotBlank()) listState.scrollToItem(0) }
    // Another view starts at its top
    LaunchedEffect(listView) { listState.scrollToItem(0) }
    // The folder just opened scrolls to the top
    // A folder row's height with its gap, as laid out (the pinned rows stack by it)
    val density = LocalDensity.current
    var rowStep by remember { mutableIntStateOf(with(density) { 50.dp.roundToPx() }) }
    val listTopPadding = with(density) { LIST_TOP_PADDING.roundToPx() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("folder_") == true }?.size }
            .collect { size -> if (size != null && size > 0) rowStep = size }
    }
    // The folder just opened scrolls up to just under its open ancestors, which stay pinned at the top
    LaunchedEffect(scrollTo, rows) {
        val key = scrollTo ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == "folder_$key" }
        val ancestors = openKeys.indexOf(key).coerceAtLeast(0)
        // The list's own top padding already lies above the item scrolled to
        val offset = if (ancestors == 0) 0 else -(ancestors * rowStep - listTopPadding)
        if (index >= 0) listState.animateScrollToItem(index, offset)
        scrollTo = null
    }
    // The open folders whose rows went up under the top: kept there, one under another, root first
    val pinned by remember(rows, openKeys, listView, searching) {
        derivedStateOf {
            if (listView || searching) return@derivedStateOf emptyList()
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = listState.firstVisibleItemIndex
            openKeys.mapIndexedNotNull { level, key ->
                val index = rows.indexOfFirst { it.key == "folder_$key" }
                if (index < 0) return@mapIndexedNotNull null
                val offset = visible.firstOrNull { it.index == index }?.offset
                val under = index < first || (offset != null && offset < level * rowStep)
                if (under) rows[index] as? BrowserRow.Folder else null
            }
        }
    }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = GraphColors.palette(dark)
    val noSwatch = MaterialTheme.colorScheme.surfaceContainerHighest
    val notConnected = slotType?.let { stringResource(R.string.node_not_connected, it) }

    fun choose(node: NodeTypeDefinition) {
        // The filter lifted: a node without a fitting socket is added on its own
        if (slotType != null && !fits(node)) Toast.makeText(context, notConnected, Toast.LENGTH_SHORT).show()
        onNodeTypeSelected(node)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.node_browser_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                ViewSwitch(listView) { list ->
                    listView = list
                    NodeBrowserPrefs.setListView(context, list)
                }
            }
            SearchField(
                state = search,
                placeholder = pluralStringResource(R.plurals.node_search_hint, scope.size, scope.size),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
            )
            if (slotType != null) {
                val label = stringResource(if (filterToOutputType != null) R.string.node_browser_accepts_input else R.string.node_browser_provides_output, slotType)
                val dot = palette.slot(slotType) ?: MaterialTheme.colorScheme.onSurfaceVariant
                SearchFilterChips(
                    filters = listOf(
                        SearchFilter(
                            key = "socket",
                            label = label,
                            selected = filterOn,
                            onToggle = { filterOn = !filterOn },
                            leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(dot)) }
                        )
                    ),
                    moreLabel = { "" },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
            }
            if (searching || (slotType != null && filterOn)) {
                SearchCountLine(
                    text = when {
                        searching && !filterOn -> stringResource(R.string.node_count, results.size)
                        searching -> stringResource(R.string.node_count_of, results.size, allNodes.size)
                        else -> stringResource(R.string.node_count_of, compatible.size, allNodes.size)
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
            }

            if (rows.isEmpty()) {
                SearchEmptyState(
                    title = if (searching) stringResource(R.string.node_browser_no_match, query.trim()) else stringResource(R.string.node_browser_no_results),
                    body = if (filterOn && slotType != null) stringResource(R.string.node_none_body) else null,
                    liftLabel = if (filterOn && slotType != null) stringResource(R.string.node_search_all) else null,
                    liftIcon = Icons.Rounded.FilterAltOff,
                    onLift = { filterOn = false }
                )
            } else {
                Box(Modifier.weight(1f)) {
                // A new list for another view or for searching: its rows' animations are for folders opening, not for
                // all rows changing at once (old and new would cross-fade over each other)
                key(listView, searching) {
                NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = LIST_TOP_PADDING, bottom = 16.dp)
                    ) {
                        items(rows, key = { it.key }) { row ->
                            Box(Modifier.animateItem()) {
                                when (row) {
                                    is BrowserRow.Section -> Text(
                                        row.title,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp)
                                    )
                                    is BrowserRow.Folder -> FolderRow(
                                        folder = row.folder,
                                        depth = row.depth,
                                        open = row.open,
                                        shapes = groupShapes(row.index, row.size),
                                        onClick = {
                                            openKeys = if (row.open) NodeBrowserTree.close(openKeys, row.folder)
                                            else NodeBrowserTree.open(openKeys, row.folder).also { scrollTo = row.folder.key }
                                        }
                                    )
                                    is BrowserRow.Node -> NodeTypeRow(
                                        nodeType = row.node,
                                        depth = row.depth,
                                        swatch = palette.categoryTint(nodeCategoryOf(row.node.classType)) ?: noSwatch,
                                        shapes = groupShapes(row.index, row.size),
                                        highlight = if (searching) query.trim() else null,
                                        onClick = { choose(row.node) }
                                    )
                                }
                            }
                            // The segmented groups' gap between rows
                            if (row !is BrowserRow.Section) Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                }
                // The open folders above the top, pinned (on the sheet's own colour, the list passing under)
                if (pinned.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(BottomSheetDefaults.ContainerColor)
                            .padding(horizontal = 16.dp)
                    ) {
                        pinned.forEachIndexed { i, row ->
                            FolderRow(
                                folder = row.folder,
                                depth = row.depth,
                                open = true,
                                shapes = groupShapes(i, pinned.size),
                                onClick = { openKeys = NodeBrowserTree.close(openKeys, row.folder) }
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                }
            }
        }
    }
}

/** Tree or list: connected toggle buttons, as the generation screens' source switch. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewSwitch(listView: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.content_description_node_view)
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier
            .selectableGroup()
            .semantics { contentDescription = label }
    ) {
        listOf(false to R.string.node_view_tree, true to R.string.node_view_list).forEachIndexed { index, (list, text) ->
            ToggleButton(
                checked = listView == list,
                onCheckedChange = { if (it) onChange(list) },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .semantics { role = Role.RadioButton }
            ) {
                Text(stringResource(text), maxLines = 1)
            }
        }
    }
}

/**
 * A category folder: its icon (an extension pack's, the API nodes', else a folder, open or not), its name, the nodes it
 * holds and the arrow; open, on the secondary container.
 */
@Composable
private fun FolderRow(folder: NodeBrowserTree.Folder, depth: Int, open: Boolean, shapes: ListItemShapes, onClick: () -> Unit) {
    val name = when (folder.kind) {
        NodeBrowserTree.Kind.API -> stringResource(R.string.node_folder_api)
        NodeBrowserTree.Kind.OTHER -> stringResource(R.string.node_folder_other)
        else -> folder.name
    }
    val icon = when {
        folder.kind == NodeBrowserTree.Kind.PACK -> Icons.Rounded.Extension
        folder.kind == NodeBrowserTree.Kind.API -> Icons.Rounded.Cloud
        open -> Icons.Rounded.FolderOpen
        else -> Icons.Rounded.Folder
    }
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        // The level's indent before the icon (the list item keeps its own padding)
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (depth > 0) Spacer(Modifier.width(TREE_INDENT * depth))
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folder.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Icon(
                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name)
    }
}

/**
 * A node type: a swatch in its category colour (the graph's colours, the one exception to theme roles), its name (the
 * search's match in bold), "category (inputs: n, outputs: n)" with "– deprecated" / "– experimental" in italics, and
 * the class name under it when it differs. Extension nodes carry the extension icon. The description is read out only.
 */
@Composable
private fun NodeTypeRow(
    nodeType: NodeTypeDefinition,
    depth: Int,
    swatch: Color,
    shapes: ListItemShapes,
    highlight: String?,
    onClick: () -> Unit
) {
    val name = NodeBrowserTree.displayNameOf(nodeType)
    val deprecated = stringResource(R.string.node_mark_deprecated)
    val experimental = stringResource(R.string.node_mark_experimental)
    val supporting = buildAnnotatedString {
        append(
            if (nodeType.category.isEmpty()) stringResource(R.string.node_row_io, nodeType.inputs.size, nodeType.outputs.size)
            else stringResource(R.string.node_row_supporting, nodeType.category, nodeType.inputs.size, nodeType.outputs.size)
        )
        // The states after one dash, together (no chain of dashes)
        val states = listOfNotNull(deprecated.takeIf { nodeType.deprecated }, experimental.takeIf { nodeType.experimental })
        if (states.isNotEmpty()) {
            append(" – ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(states.joinToString(", ")) }
        }
    }
    val description = nodeType.description
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.semantics { if (!description.isNullOrBlank()) contentDescription = "$name, $description" },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (depth > 0) Spacer(Modifier.width(TREE_INDENT * depth))
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(swatch)
                )
            }
        },
        trailingContent = if (nodeType.isCustom) {
            {
                Icon(
                    Icons.Rounded.Extension,
                    contentDescription = stringResource(R.string.content_description_custom_node),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else null,
        supportingContent = {
            Column {
                Text(supporting)
                if (name != nodeType.classType) {
                    Text(nodeType.classType, style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(highlighted(name, highlight))
    }
}
