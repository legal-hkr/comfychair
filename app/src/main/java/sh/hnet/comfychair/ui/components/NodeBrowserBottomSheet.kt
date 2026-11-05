package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.segmentedItemShape
import sh.hnet.comfychair.util.ConnectionValidator
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.SlotColors
import sh.hnet.comfychair.workflow.nodeCategoryOf

/** Split category into hierarchy levels (handles both "/" and "\" separators) */
private fun parseCategoryLevels(category: String): List<String> {
    return category.split("/", "\\").filter { it.isNotEmpty() }
}

/** Normalize category to use "/" separator consistently */
private fun normalizeCategory(category: String): String {
    return category.replace("\\", "/")
}

/** Literal types that are not connection types */
private val LITERAL_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

/** Check if a type is a connection type (not a literal) */
private fun isConnectionType(type: String): Boolean = type.uppercase() !in LITERAL_TYPES

/**
 * The node browser, a full-height sheet: search, then chips (from a held socket first "Takes LATENT ×", then the
 * categories, most nodes first, drilling down one level per chip), and the nodes as segmented rows with their
 * category colour. Tapping a node adds it (wired to the held socket when it came from one).
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
    var searchQuery by remember { mutableStateOf("") }
    // Selected category levels ("sampling", "custom_sampling", …)
    var path by remember { mutableStateOf(emptyList<String>()) }
    val slotType = filterToOutputType ?: filterToInputType
    var compatibleOnly by remember { mutableStateOf(slotType != null) }

    val allNodes = remember(nodeTypesByCategory) { nodeTypesByCategory.values.flatten() }
    val compatibleNodes = remember(allNodes, compatibleOnly) {
        when {
            !compatibleOnly -> allNodes
            filterToOutputType != null -> allNodes.filter { node ->
                node.inputs.any { isConnectionType(it.type) && ConnectionValidator.isTypeCompatible(filterToOutputType, it.type) }
            }
            filterToInputType != null -> allNodes.filter { node ->
                node.outputs.any { ConnectionValidator.isTypeCompatible(it.type, filterToInputType) }
            }
            else -> allNodes
        }
    }
    val prefix = path.joinToString("/")
    val inCategory = remember(compatibleNodes, prefix) {
        if (prefix.isEmpty()) compatibleNodes
        else compatibleNodes.filter { normalizeCategory(it.category).let { c -> c == prefix || c.startsWith("$prefix/") } }
    }
    // The next level's categories, most nodes first
    val subcategories = remember(inCategory, path) {
        inCategory.mapNotNull { parseCategoryLevels(normalizeCategory(it.category)).getOrNull(path.size) }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
    }
    val shown = remember(inCategory, searchQuery) {
        val query = searchQuery.trim().lowercase()
        inCategory.filter { node ->
            query.isEmpty() || node.classType.lowercase().contains(query) || node.category.lowercase().contains(query) ||
                node.displayName?.lowercase()?.contains(query) == true || node.description?.lowercase()?.contains(query) == true
        }.sortedBy { (it.displayName ?: it.classType).lowercase() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.node_browser_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = { Text(stringResource(R.string.placeholder_node_browser_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.button_clear_search))
                        }
                    }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .clip(SearchBarDefaults.inputFieldShape)
            )

            // Compatibility, the chosen categories, then the next level's
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                if (slotType != null && compatibleOnly) {
                    item(key = "compatible") {
                        InputChip(
                            selected = true,
                            onClick = {
                                compatibleOnly = false
                                path = emptyList()
                            },
                            label = {
                                Text(
                                    stringResource(
                                        if (filterToOutputType != null) R.string.node_browser_accepts_input else R.string.node_browser_provides_output,
                                        slotType
                                    )
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.content_description_remove_filter),
                                    modifier = Modifier.size(InputChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                }
                path.forEachIndexed { index, level ->
                    item(key = "path_$index") {
                        FilterChip(
                            selected = true,
                            onClick = { path = path.take(index) },
                            label = { Text(level) },
                            leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        )
                    }
                }
                items(subcategories, key = { "sub_$it" }) { category ->
                    FilterChip(selected = false, onClick = { path = path + category }, label = { Text(category) })
                }
            }

            if (slotType != null && compatibleOnly) {
                Text(
                    pluralStringResource(R.plurals.node_browser_compatible_count, shown.size, shown.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                )
            }

            if (shown.isEmpty()) {
                Text(
                    text = if (searchQuery.isNotBlank()) stringResource(R.string.node_browser_no_match, searchQuery.trim())
                        else stringResource(R.string.node_browser_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                val dark = isSystemInDarkTheme()
                NoOverscrollContainer(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(shown, key = { _, node -> "node_${node.classType}" }) { index, nodeType ->
                            NodeTypeRow(
                                nodeType = nodeType,
                                swatch = SlotColors.getCategoryColor(nodeCategoryOf(nodeType.classType), dark).header,
                                shape = segmentedItemShape(index, shown.size),
                                onClick = { onNodeTypeSelected(nodeType) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A node type: a swatch in its category colour (the graph's colours, the one exception to theme roles), its name, and
 * "category – n inputs – n outputs"; the class name under it when it differs. Extension nodes, deprecated and
 * experimental ones are marked with an icon.
 */
@Composable
private fun NodeTypeRow(nodeType: NodeTypeDefinition, swatch: Color, shape: Shape, onClick: () -> Unit) {
    val io = listOf(
        nodeType.category,
        pluralStringResource(R.plurals.node_browser_inputs_count, nodeType.inputs.size, nodeType.inputs.size),
        pluralStringResource(R.plurals.node_browser_outputs_count, nodeType.outputs.size, nodeType.outputs.size)
    ).filter { it.isNotEmpty() }.joinToString(" – ")
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(swatch)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(nodeType.displayName ?: nodeType.classType, style = MaterialTheme.typography.bodyLarge)
                Text(io, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nodeType.displayName != null && nodeType.displayName != nodeType.classType) {
                    Text(nodeType.classType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (nodeType.deprecated) {
                Icon(
                    Icons.Rounded.Warning,
                    contentDescription = stringResource(R.string.node_browser_deprecated),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp)
                )
            }
            if (nodeType.experimental) {
                Icon(
                    Icons.Rounded.Science,
                    contentDescription = stringResource(R.string.node_browser_experimental),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp)
                )
            }
            if (nodeType.isCustom) {
                Icon(
                    Icons.Rounded.Extension,
                    contentDescription = stringResource(R.string.content_description_custom_node),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp)
                )
            }
        }
    }
}
