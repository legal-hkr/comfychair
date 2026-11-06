package sh.hnet.comfychair.ui.components.shared

import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.getValue

/**
 * Immutable tree representation of a flat model path list.
 *
 * Paths like "illustrious/v3/model.safetensors" become:
 *   Folder("illustrious", "illustrious") ->
 *     Folder("v3", "illustrious/v3") ->
 *       Leaf("model.safetensors", "illustrious/v3/model.safetensors")
 */
sealed class ModelTreeNode {
    data class Folder(
        val name: String,
        val fullPath: String,
        val children: List<ModelTreeNode>
    ) : ModelTreeNode()

    data class Leaf(
        val name: String,
        val fullPath: String
    ) : ModelTreeNode()
}

/**
 * Build an immutable tree from a flat list of model paths.
 * Within each level, folders are sorted before leaves, then both sorted alphabetically.
 * Backslashes are normalized to forward slashes.
 */
fun buildModelTree(paths: List<String>): List<ModelTreeNode> =
    buildTreeLevel(
        entries = paths.map { it.replace('\\', '/') to it },
        pathPrefix = ""
    )

private fun buildTreeLevel(
    entries: List<Pair<String, String>>,
    pathPrefix: String
): List<ModelTreeNode> {
    val folderEntries = mutableMapOf<String, MutableList<Pair<String, String>>>()
    val leaves = mutableListOf<ModelTreeNode.Leaf>()

    for ((normalized, original) in entries) {
        val slashIndex = normalized.indexOf('/')
        if (slashIndex < 0) {
            leaves.add(ModelTreeNode.Leaf(name = normalized, fullPath = original))
        } else {
            val folderName = normalized.substring(0, slashIndex)
            val remainder = normalized.substring(slashIndex + 1)
            folderEntries.getOrPut(folderName) { mutableListOf() }.add(remainder to original)
        }
    }

    val folders = folderEntries
        .map { (name, children) ->
            val fullPath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
            ModelTreeNode.Folder(
                name = name,
                fullPath = fullPath,
                children = buildTreeLevel(children, fullPath)
            )
        }
        .sortedBy { it.name.lowercase() }

    return folders + leaves.sortedBy { it.name.lowercase() }
}

/** Returns true if the top-level nodes contain at least one folder, triggering tree display. */
fun modelTreeHasFolders(nodes: List<ModelTreeNode>): Boolean =
    nodes.any { it is ModelTreeNode.Folder }

/**
 * Returns the folder portion of a model path, e.g. "illustrious/v3/model.safetensors" → "illustrious/v3".
 * Returns "" for root-level paths (no slash).
 */
fun folderPathOf(modelPath: String): String {
    val normalized = modelPath.replace('\\', '/')
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash >= 0) normalized.substring(0, lastSlash) else ""
}
