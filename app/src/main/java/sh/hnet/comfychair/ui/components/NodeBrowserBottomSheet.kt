package sh.hnet.comfychair.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.util.ConnectionValidator
import sh.hnet.comfychair.workflow.NodeTypeDefinition

/** Split category into hierarchy levels (handles both "/" and "\" separators) */
private fun parseCategoryLevels(category: String): List<String> {
    return category.split("/", "\\").filter { it.isNotEmpty() }
}

/** Normalize category to use "/" separator consistently */
private fun normalizeCategory(category: String): String {
    return category.replace("\\", "/")
}

/** Get unique values at a specific level, optionally filtered by parent prefix */
private fun getOptionsAtLevel(
    categories: Set<String>,
    level: Int,
    parentPrefix: String?
): List<String> {
    return categories
        .map { normalizeCategory(it) }
        .filter { cat ->
            if (parentPrefix == null) true
            else cat.startsWith("$parentPrefix/") || cat == parentPrefix
        }
        .mapNotNull { cat ->
            parseCategoryLevels(cat).getOrNull(level)
        }
        .distinct()
        .sorted()
}

/** Build category prefix from selections */
private fun buildCategoryPrefix(
    level1: String?,
    level2: String?,
    level3: String?
): String? {
    return listOfNotNull(level1, level2, level3)
        .takeIf { it.isNotEmpty() }
        ?.joinToString("/")
}

/** Literal types that are not connection types */
private val LITERAL_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

/** Check if a type is a connection type (not a literal) */
private fun isConnectionType(type: String): Boolean = type.uppercase() !in LITERAL_TYPES

/**
 * Bottom sheet for browsing and selecting node types to add to the workflow.
 *
 * @param nodeTypesByCategory All available node types grouped by category
 * @param sheetState The modal sheet state
 * @param onNodeTypeSelected Callback when user selects a node type
 * @param onDismiss Callback when sheet is dismissed
 * @param filterToOutputType If set, only show nodes with compatible inputs for this output type
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeBrowserBottomSheet(
    nodeTypesByCategory: Map<String, List<NodeTypeDefinition>>,
    sheetState: SheetState,
    onNodeTypeSelected: (NodeTypeDefinition) -> Unit,
    onDismiss: () -> Unit,
    filterToOutputType: String? = null
) {
    var searchQuery by remember { mutableStateOf("") }

    // Category filter state
    var level1Selection by remember { mutableStateOf<String?>(null) }
    var level2Selection by remember { mutableStateOf<String?>(null) }
    var level3Selection by remember { mutableStateOf<String?>(null) }

    // Expansion state for each level
    var level1Expanded by remember { mutableStateOf(false) }
    var level2Expanded by remember { mutableStateOf(false) }
    var level3Expanded by remember { mutableStateOf(false) }

    // Compute available options for each level
    val allCategories = remember(nodeTypesByCategory) {
        nodeTypesByCategory.keys
    }

    val level1Options = remember(allCategories) {
        getOptionsAtLevel(allCategories, 0, null)
    }

    val level2Options = remember(allCategories, level1Selection) {
        if (level1Selection == null) emptyList()
        else getOptionsAtLevel(allCategories, 1, level1Selection)
    }

    val level3Options = remember(allCategories, level1Selection, level2Selection) {
        val prefix = buildCategoryPrefix(level1Selection, level2Selection, null)
        if (prefix == null || level2Selection == null) emptyList()
        else getOptionsAtLevel(allCategories, 2, prefix)
    }

    val categoryPrefix = buildCategoryPrefix(level1Selection, level2Selection, level3Selection)

    // Filter nodes by compatibility, search query, and category filter
    val filteredCategories = remember(nodeTypesByCategory, searchQuery, categoryPrefix, filterToOutputType) {
        var result = nodeTypesByCategory

        // Apply compatibility filter FIRST if set (for long-press connection mode)
        if (filterToOutputType != null) {
            result = result.mapValues { (_, nodes) ->
                nodes.filter { nodeType ->
                    // Node is compatible if it has any connection-type input that matches output type
                    nodeType.inputs.any { input ->
                        isConnectionType(input.type) &&
                        ConnectionValidator.isTypeCompatible(filterToOutputType, input.type)
                    }
                }
            }.filterValues { it.isNotEmpty() }
        }

        // Apply category filter (normalize both sides for cross-platform matching)
        if (categoryPrefix != null) {
            result = result.filterKeys { category ->
                val normalizedCategory = normalizeCategory(category)
                normalizedCategory.startsWith(categoryPrefix) || normalizedCategory == categoryPrefix
            }
        }

        // Apply search filter
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            result = result.mapValues { (_, nodes) ->
                nodes.filter { node ->
                    node.classType.lowercase().contains(query) ||
                    node.category.lowercase().contains(query) ||
                    node.displayName?.lowercase()?.contains(query) == true ||
                    node.description?.lowercase()?.contains(query) == true
                }
            }.filterValues { it.isNotEmpty() }
        }

        result
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.node_browser_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Show filter chip when filtering by output type
            if (filterToOutputType != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { /* Filter is read-only in this mode */ },
                    label = {
                        Text(stringResource(R.string.node_browser_compatible_with, filterToOutputType))
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search field with M3 Expressive style
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { },
                        expanded = false,
                        onExpandedChange = { },
                        placeholder = { Text(stringResource(R.string.node_browser_search_placeholder)) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.clear_search)
                                    )
                                }
                            }
                        } else null
                    )
                },
                expanded = false,
                onExpandedChange = { },
                modifier = Modifier.fillMaxWidth()
            ) { }

            Spacer(modifier = Modifier.height(12.dp))

            // Category filters section with animated size changes
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                // Level 1 filter chips
                // Collapse to only selected chip when level 2 has options (user drilled down)
                val level1DisplayOptions = remember(level1Selection, level2Options, level1Options) {
                    if (level2Options.isNotEmpty() && level1Selection != null) {
                        listOfNotNull(level1Selection)
                    } else {
                        level1Options
                    }
                }
                if (level1DisplayOptions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.node_browser_filter_category),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpandableFilterChipRow(
                        options = level1DisplayOptions,
                        selectedOption = level1Selection,
                        onOptionSelected = { selection ->
                            level1Selection = selection
                            level2Selection = null  // Clear deeper levels
                            level3Selection = null
                            level2Expanded = false
                            level3Expanded = false
                        },
                        expanded = level1Expanded,
                        onExpandedChange = { level1Expanded = it }
                    )
                }

                // Level 2 filter chips (only show if level 1 selected and options exist)
                // Collapse to only selected chip when level 3 has options (user drilled down)
                val level2DisplayOptions = remember(level2Selection, level3Options, level2Options) {
                    if (level3Options.isNotEmpty() && level2Selection != null) {
                        listOfNotNull(level2Selection)
                    } else {
                        level2Options
                    }
                }
                if (level2DisplayOptions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.node_browser_filter_subcategory),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpandableFilterChipRow(
                        options = level2DisplayOptions,
                        selectedOption = level2Selection,
                        onOptionSelected = { selection ->
                            level2Selection = selection
                            level3Selection = null  // Clear deeper level
                            level3Expanded = false
                        },
                        expanded = level2Expanded,
                        onExpandedChange = { level2Expanded = it }
                    )
                }

                // Level 3 filter chips (only show if level 2 selected and options exist)
                if (level3Options.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.node_browser_filter_group),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpandableFilterChipRow(
                        options = level3Options,
                        selectedOption = level3Selection,
                        onOptionSelected = { selection ->
                            level3Selection = selection
                        },
                        expanded = level3Expanded,
                        onExpandedChange = { level3Expanded = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Flatten and sort all nodes alphabetically by display name (or classType)
            val sortedNodes = remember(filteredCategories) {
                filteredCategories.values.flatten().sortedBy {
                    (it.displayName ?: it.classType).lowercase()
                }
            }

            if (sortedNodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.node_browser_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                NoOverscrollContainer(modifier = Modifier.weight(1f, fill = false)) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = sortedNodes,
                            key = { "node_${it.classType}" }
                        ) { nodeType ->
                            NodeTypeRow(
                                nodeType = nodeType,
                                onClick = { onNodeTypeSelected(nodeType) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeTypeRow(
    nodeType: NodeTypeDefinition,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (nodeType.category.isNotEmpty()) {
                Text(
                    text = nodeType.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = nodeType.displayName ?: nodeType.classType,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Show deprecated badge (error color - adapts to light/dark)
                if (nodeType.deprecated) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(R.string.node_browser_deprecated),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                // Show experimental badge (tertiary color - adapts to light/dark)
                if (nodeType.experimental) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = stringResource(R.string.node_browser_experimental),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            // Show classType if different from displayName
            if (nodeType.displayName != null && nodeType.displayName != nodeType.classType) {
                Text(
                    text = nodeType.classType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            if (nodeType.inputs.isNotEmpty() || nodeType.outputs.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.node_browser_io_info,
                        nodeType.inputs.size,
                        nodeType.outputs.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandableFilterChipRow(
    options: List<String>,
    selectedOption: String?,
    onOptionSelected: (String?) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return

    // Only show expand/collapse if there are enough options
    val canExpand = options.size > 3

    if (expanded) {
        // Expanded mode: wrapping flow layout with collapse button first
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Collapse button first (FilledTonalIconButton for visual distinction)
            if (canExpand) {
                FilledTonalIconButton(
                    onClick = { onExpandedChange(false) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = null
                    )
                }
            }
            options.forEach { option ->
                key(option) {
                    val isSelected = option == selectedOption
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onOptionSelected(if (isSelected) null else option)
                        },
                        label = { Text(option) },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else null
                    )
                }
            }
        }
    } else {
        // Collapsed mode: horizontal scrolling with expand button first
        NoOverscrollContainer(modifier = modifier.fillMaxWidth()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Expand button first (FilledTonalIconButton for visual distinction)
                if (canExpand) {
                    item(key = "expand_chip") {
                        FilledTonalIconButton(
                            onClick = { onExpandedChange(true) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                    }
                }
                items(
                    items = options,
                    key = { "chip_$it" }
                ) { option ->
                    val isSelected = option == selectedOption
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onOptionSelected(if (isSelected) null else option)
                        },
                        label = { Text(option) },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else null
                    )
                }
            }
        }
    }
}
