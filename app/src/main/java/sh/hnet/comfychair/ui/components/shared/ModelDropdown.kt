package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * A dropdown component for selecting models (checkpoints, UNETs, VAEs, CLIPs, etc.)
 *
 * Automatically switches between flat and hierarchical (folder tree) display based on
 * whether any option contains path separators.
 *
 * Hierarchical behavior:
 * - Folders shown first, then root-level models, both sorted alphabetically
 * - Clicking a folder expands it (accordion: collapses siblings at the same level)
 * - Re-opening auto-expands to the currently selected model's folder
 * - Selected model highlighted in primary color
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val tree = remember(options) { buildModelTree(options) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(selectedValue) { mutableStateOf(folderPathOf(selectedValue)) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it) expandedPath = folderPathOf(selectedValue)
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ModelMenuItems(tree, hasFolders, options, selectedValue, expandedPath, { expandedPath = it }) {
                onValueChange(it)
                expanded = false
            }
        }
    }
}

/**
 * A choice of the options sheet as a list row (models, sampler, scheduler…): the value as headline with its role
 * under it, so choices read differently from fields typed by hand. Tapping it opens the same picker as
 * [ModelDropdown] (folders as a tree).
 *
 * @param isFile The value is a file path: the headline shows the file name without extension, the folder goes under it
 * @param shape The row's shape within its group (rounded more at the group's ends)
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val tree = remember(options) { buildModelTree(options) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(selectedValue) { mutableStateOf(folderPathOf(selectedValue)) }
    val folder = if (isFile) folderPathOf(selectedValue) else ""
    val headline = if (isFile) {
        selectedValue.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
    } else {
        selectedValue
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            expanded = it
            if (it) expandedPath = folderPathOf(selectedValue)
        },
        modifier = modifier
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = headline.ifEmpty { role },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (headline.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                )
            },
            supportingContent = {
                // The role, and for files the folder they are in
                Text(
                    text = if (folder.isNotEmpty() && headline.isNotEmpty()) "$role – $folder/" else role,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .clip(shape)
                .semantics { contentDescription = "$role, $selectedValue" }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ModelMenuItems(tree, hasFolders, options, selectedValue, expandedPath, { expandedPath = it }) {
                onValueChange(it)
                expanded = false
            }
        }
    }
}

/** The picker's items: a flat list, or a folder tree when any option is in a folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelMenuItems(
    tree: List<ModelTreeNode>,
    hasFolders: Boolean,
    options: List<String>,
    selectedValue: String,
    expandedPath: String,
    onExpandFolder: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    if (!hasFolders) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { ModelPathText(option) },
                onClick = { onSelect(option) },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            )
        }
    } else {
        HierarchicalTreeItems(
            nodes = tree,
            expandedPath = expandedPath,
            selectedValue = selectedValue,
            depth = 0,
            onExpandFolder = onExpandFolder,
            onSelectModel = onSelect
        )
    }
}
