package sh.hnet.comfychair.util

import androidx.compose.ui.geometry.Offset
import sh.hnet.comfychair.workflow.GroupManager
import sh.hnet.comfychair.workflow.MutableWorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowNode

/**
 * Result of a node duplication operation.
 *
 * @property newNodeIds Set of IDs for the newly created nodes
 * @property idMapping Map from original node IDs to their duplicate IDs
 */
data class DuplicationResult(
    val newNodeIds: Set<String>,
    val idMapping: Map<String, String>
)

/**
 * Utility for graph structure modifications.
 *
 * Provides pure functions for node duplication, ID generation, and other
 * structural changes to workflow graphs.
 */
object GraphMutationUtils {

    /**
     * Duplicate a set of nodes within the graph.
     * Internal edges between the selected nodes are also duplicated.
     *
     * Note: Duplicated nodes are NOT added to any group, even if the original
     * nodes were grouped. This is intentional - duplicates are independent copies.
     *
     * @param graph The mutable graph to modify
     * @param nodeIds IDs of nodes to duplicate
     * @param offset Position offset for duplicated nodes (temporary, for layout)
     * @param idGenerator Function to generate unique node IDs
     * @return DuplicationResult with new node IDs and ID mapping
     */
    fun duplicateNodes(
        graph: MutableWorkflowGraph,
        nodeIds: Set<String>,
        offset: Offset = Offset(50f, 50f),
        idGenerator: () -> String
    ): DuplicationResult {
        val nodesToDuplicate = graph.nodes.filter { it.id in nodeIds }
        if (nodesToDuplicate.isEmpty()) {
            return DuplicationResult(emptySet(), emptyMap())
        }

        // Create ID mapping from old to new
        val idMapping = mutableMapOf<String, String>()

        // Duplicate nodes with offset position
        val duplicatedNodes = nodesToDuplicate.map { node ->
            val newId = idGenerator()
            idMapping[node.id] = newId
            node.copy(
                id = newId,
                x = node.x + offset.x,
                y = node.y + offset.y
            )
        }

        // Duplicate internal edges (edges between selected nodes)
        val internalEdges = graph.edges.filter { edge ->
            edge.sourceNodeId in nodeIds && edge.targetNodeId in nodeIds
        }
        val duplicatedEdges = internalEdges.map { edge ->
            edge.copy(
                sourceNodeId = idMapping[edge.sourceNodeId] ?: edge.sourceNodeId,
                targetNodeId = idMapping[edge.targetNodeId] ?: edge.targetNodeId
            )
        }

        // Add duplicated nodes and edges to graph
        graph.nodes.addAll(duplicatedNodes)
        graph.edges.addAll(duplicatedEdges)

        return DuplicationResult(
            newNodeIds = duplicatedNodes.map { it.id }.toSet(),
            idMapping = idMapping
        )
    }

    /**
     * Generate a unique node ID based on existing nodes in the graph.
     * Finds the maximum numeric ID and returns the next one.
     *
     * @param graph The graph to check for existing IDs
     * @return A new unique string ID
     */
    fun generateUniqueNodeId(graph: WorkflowGraph): String {
        val maxId = graph.nodes
            .mapNotNull { it.id.toIntOrNull() }
            .maxOrNull() ?: 0
        return (maxId + 1).toString()
    }

    /**
     * Generate a unique node ID based on a mutable graph.
     *
     * @param graph The mutable graph to check for existing IDs
     * @return A new unique string ID
     */
    fun generateUniqueNodeId(graph: MutableWorkflowGraph): String {
        val maxId = graph.nodes
            .mapNotNull { it.id.toIntOrNull() }
            .maxOrNull() ?: 0
        return (maxId + 1).toString()
    }

    /**
     * Delete nodes and their connected edges from the graph.
     * Also removes nodes from any groups they belong to.
     *
     * @param graph The mutable graph to modify
     * @param nodeIds IDs of nodes to delete
     * @return true if any nodes were deleted
     */
    fun deleteNodes(
        graph: MutableWorkflowGraph,
        nodeIds: Set<String>
    ): Boolean {
        if (nodeIds.isEmpty()) return false

        // IMPORTANT: Remove nodes from groups BEFORE deleting them
        // This ensures groups don't reference non-existent nodes
        // Groups with < 2 members after removal will be dissolved
        GroupManager.removeNodesFromGroups(graph, nodeIds)

        val nodesRemoved = graph.nodes.removeAll { it.id in nodeIds }

        // Remove edges connected to deleted nodes
        graph.edges.removeAll { edge ->
            edge.sourceNodeId in nodeIds || edge.targetNodeId in nodeIds
        }

        return nodesRemoved
    }
}
