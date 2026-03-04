package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A model tree node — either a folder containing children or a leaf model.
 */
data class ModelTreeNode(
    val name: String,
    val fullPath: String = "",
    val children: MutableMap<String, ModelTreeNode> = mutableMapOf(),
    val isFolder: Boolean = false
)

/**
 * Build a tree from a flat list of model paths.
 * Paths like "subfolder/model.safetensors" become folder nodes with leaf children.
 */
fun buildModelTree(options: List<String>): ModelTreeNode {
    val root = ModelTreeNode(name = "", isFolder = true)
    for (path in options) {
        val parts = path.replace('\\', '/').split('/')
        var current = root
        for (i in parts.indices) {
            val part = parts[i]
            if (i == parts.lastIndex) {
                current.children[part] = ModelTreeNode(
                    name = part,
                    fullPath = path,
                    isFolder = false
                )
            } else {
                current.children.getOrPut(part) {
                    ModelTreeNode(name = part, isFolder = true)
                }
                current = current.children[part]!!
            }
        }
    }
    return root
}

/**
 * Check if a tree has any folder nodes (i.e. needs hierarchical display).
 */
fun modelTreeHasFolders(tree: ModelTreeNode): Boolean =
    tree.children.values.any { it.isFolder }

/**
 * Get the folder path of a model path.
 * e.g. "illustrious/sub/model.safetensors" -> "illustrious/sub"
 * Returns "" for root-level models.
 */
fun folderPathOf(modelPath: String): String {
    val normalized = modelPath.replace('\\', '/')
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash > 0) normalized.substring(0, lastSlash) else ""
}

/**
 * Render hierarchical tree items inside a dropdown menu.
 * Call this inside an ExposedDropdownMenu or DropdownMenu block.
 *
 * @param node The tree node to render children of
 * @param currentPath The path prefix for this level (empty for root)
 * @param expandedPath The currently expanded folder path
 * @param selectedValue The currently selected model's full path
 * @param depth The nesting depth (for indentation)
 * @param onExpandFolder Called when a folder is clicked
 * @param onSelectModel Called when a model is selected
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HierarchicalTreeItems(
    node: ModelTreeNode,
    currentPath: String,
    expandedPath: String,
    selectedValue: String,
    depth: Int,
    onExpandFolder: (String) -> Unit,
    onSelectModel: (String) -> Unit
) {
    val sortedChildren = remember(node.children) {
        node.children.values.sortedWith(
            compareByDescending<ModelTreeNode> { it.isFolder }
                .thenBy { it.name.lowercase() }
        )
    }

    for (child in sortedChildren) {
        val childPath = if (currentPath.isEmpty()) child.name else "$currentPath/${child.name}"

        if (child.isFolder) {
            val isExpanded = expandedPath == childPath || expandedPath.startsWith("$childPath/")

            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.FolderOpen
                            else Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = child.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (isExpanded)
                                Icons.Default.KeyboardArrowDown
                            else
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = {
                    if (isExpanded) {
                        onExpandFolder(if (currentPath.isEmpty()) "" else currentPath)
                    } else {
                        onExpandFolder(childPath)
                    }
                },
                modifier = Modifier.padding(start = (depth * 16).dp),
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            )

            if (isExpanded) {
                HierarchicalTreeItems(
                    node = child,
                    currentPath = childPath,
                    expandedPath = expandedPath,
                    selectedValue = selectedValue,
                    depth = depth + 1,
                    onExpandFolder = onExpandFolder,
                    onSelectModel = onSelectModel
                )
            }
        } else {
            DropdownMenuItem(
                text = {
                    Text(
                        text = child.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (child.fullPath == selectedValue)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = { onSelectModel(child.fullPath) },
                modifier = Modifier.padding(start = (depth * 16).dp),
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            )
        }
    }
}
