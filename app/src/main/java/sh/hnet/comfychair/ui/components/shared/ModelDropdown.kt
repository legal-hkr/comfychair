@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R


/**
 * A choice of the options sheet as a list row (models, sampler, scheduler…): the value as headline with its role
 * under it, so choices read differently from fields typed by hand. Tapping it opens [ChoiceMenu] (folders as a tree).
 *
 * @param isFile The value is a file path: the headline shows the file name without extension, the folder goes under it
 * @param shape The row's shape within its group (rounded more at the group's ends)
 */
@Composable
fun ChoiceRow(
    role: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    isFile: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val folder = if (isFile) folderPathOf(selectedValue) else ""
    val headline = if (isFile) {
        selectedValue.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
    } else {
        selectedValue
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "choice arrow"
    )

    // The menu is as wide as the row: model names are long, and the menu's own width follows its items (at most 280 dp)
    val density = LocalDensity.current
    var rowWidth by remember { mutableStateOf<Dp?>(null) }
    Box(modifier.onSizeChanged { rowWidth = with(density) { it.width.toDp() } }) {
        SegmentedListItem(
            onClick = { expanded = true },
            shapes = ListItemDefaults.shapes(shape = shape),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            supportingContent = {
                // The role, and for files the folder they are in
                Text(
                    text = if (folder.isNotEmpty() && headline.isNotEmpty()) "$role – $folder/" else role,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.rotate(arrowRotation)) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$role, $selectedValue" }
        ) {
            Text(
                text = headline.ifEmpty { role },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (headline.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
        }
        ChoiceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = options,
            selectedValue = selectedValue,
            onSelect = {
                expanded = false
                onValueChange(it)
            },
            width = rowWidth
        )
    }
}

/**
 * The options of a choice in the app's menu style (M3 Expressive): one group, the chosen option checked, in the
 * options' own order. Options in folders (models) form a tree: folders first, then files, alphabetically; a folder opens in place (one folder path
 * open at a time) and the chosen option's folder is open when the menu opens.
 *
 * @param width The menu's width (its anchor's), wider than the menu's own limit for long names
 */
@Composable
fun ChoiceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<String>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    width: Dp? = null
) {
    // The items carry the width (the group has no side padding of its own); the menu fits around them
    val sized = if (width != null) Modifier.width(width) else Modifier
    val tree = remember(options) { buildModelTree(options) }
    var expandedPath by remember(selectedValue, expanded) { mutableStateOf(folderPathOf(selectedValue)) }
    // Options without folders keep their own order (a sampler list is ordered on purpose); only a tree is sorted
    val rows = remember(tree, options, expandedPath) {
        if (modelTreeHasFolders(tree)) visibleTreeRows(tree, expandedPath)
        else options.map { ModelTreeNode.Leaf(name = it, fullPath = it) to 0 }
    }

    val expandedLabel = stringResource(R.string.state_expanded)
    val collapsedLabel = stringResource(R.string.state_collapsed)
    // The Expressive menu doesn't scroll by itself (the plain one did): long model lists need it
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss, modifier = rememberMenuScrollModifier()) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            rows.forEachIndexed { index, (node, depth) ->
                val indent = Modifier.padding(start = (depth * 16).dp)
                when (node) {
                    is ModelTreeNode.Folder -> {
                        val open = expandedPath == node.fullPath || expandedPath.startsWith("${node.fullPath}/")
                        DropdownMenuItem(
                            text = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = MenuDefaults.itemShape(index, rows.size).shape,
                            leadingIcon = {
                                Icon(if (open) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, contentDescription = null, modifier = indent)
                            },
                            trailingIcon = {
                                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                            },
                            // Open closes back to its parent
                            onClick = { expandedPath = if (open) node.fullPath.substringBeforeLast('/', "") else node.fullPath },
                            modifier = sized.semantics { stateDescription = if (open) expandedLabel else collapsedLabel }
                        )
                    }
                    is ModelTreeNode.Leaf -> {
                        val selected = node.fullPath == selectedValue
                        DropdownMenuItem(
                            text = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = MenuDefaults.itemShape(index, rows.size).shape,
                            leadingIcon = {
                                if (selected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = indent)
                                else Spacer(indent.size(24.dp))
                            },
                            onClick = { onSelect(node.fullPath) },
                            modifier = sized.semantics {
                                role = Role.RadioButton
                                this.selected = selected
                            }
                        )
                    }
                }
            }
        }
    }
}

/** The tree's rows as shown: each with its depth, a folder's children only while it's open. */
private fun visibleTreeRows(nodes: List<ModelTreeNode>, expandedPath: String, depth: Int = 0): List<Pair<ModelTreeNode, Int>> =
    nodes.flatMap { node ->
        val row = listOf(node to depth)
        if (node is ModelTreeNode.Folder && (expandedPath == node.fullPath || expandedPath.startsWith("${node.fullPath}/"))) {
            row + visibleTreeRows(node.children, expandedPath, depth + 1)
        } else row
    }
