package sh.hnet.comfychair.workflow

/**
 * Manages workflow node groups.
 *
 * Provides functions for creating, querying, and modifying groups.
 * Groups track explicit membership via [WorkflowGroup.memberNodeIds].
 *
 * Enforces one-node-one-group constraint: a node can only belong to one group at a time.
 */
object GroupManager {

    /**
     * Create a new group containing the specified nodes.
     *
     * Enforces one-node-one-group: if any nodes are already in other groups,
     * they are removed from those groups first.
     *
     * @param graph The mutable graph to add the group to
     * @param nodeIds IDs of nodes to include in the group (must be >= 2)
     * @param title Group title (default: "Group")
     * @param idGenerator Function to generate a unique group ID
     * @return The created group, or null if fewer than 2 nodes specified
     */
    fun createGroup(
        graph: MutableWorkflowGraph,
        nodeIds: Set<String>,
        title: String = "Group",
        idGenerator: () -> Int
    ): WorkflowGroup? {
        if (nodeIds.size < 2) return null

        val nodes = graph.nodes.filter { it.id in nodeIds }
        if (nodes.size < 2) return null

        // CRITICAL: Remove nodes from any existing groups first (one-node-one-group)
        removeNodesFromGroups(graph, nodeIds)

        val newGroup = WorkflowGroup(
            id = idGenerator(),
            title = title,
            memberNodeIds = nodeIds
        )

        graph.groups.add(newGroup)
        return newGroup
    }

    /**
     * Find the group containing a specific node.
     *
     * @param graph The graph to search
     * @param nodeId ID of the node to find
     * @return The group containing the node, or null if not in any group
     */
    fun findGroupContainingNode(
        graph: WorkflowGraph,
        nodeId: String
    ): WorkflowGroup? {
        return graph.groups.find { nodeId in it.memberNodeIds }
    }

    /**
     * Find the group containing a specific node in a mutable graph.
     *
     * @param graph The mutable graph to search
     * @param nodeId ID of the node to find
     * @return The group containing the node, or null if not in any group
     */
    fun findGroupContainingNode(
        graph: MutableWorkflowGraph,
        nodeId: String
    ): WorkflowGroup? {
        return graph.groups.find { nodeId in it.memberNodeIds }
    }

    /**
     * Check if any of the specified nodes are in a group.
     *
     * @param graph The graph to check
     * @param nodeIds IDs of nodes to check
     * @return true if any node is in a group
     */
    fun isAnyNodeInGroup(graph: WorkflowGraph, nodeIds: Set<String>): Boolean {
        if (nodeIds.isEmpty()) return false
        return graph.groups.any { group ->
            nodeIds.any { it in group.memberNodeIds }
        }
    }

    /**
     * Check if any of the specified nodes are in a group (mutable graph version).
     *
     * @param graph The mutable graph to check
     * @param nodeIds IDs of nodes to check
     * @return true if any node is in a group
     */
    fun isAnyNodeInGroup(graph: MutableWorkflowGraph, nodeIds: Set<String>): Boolean {
        if (nodeIds.isEmpty()) return false
        return graph.groups.any { group ->
            nodeIds.any { it in group.memberNodeIds }
        }
    }

    /**
     * Remove nodes from their groups.
     *
     * If a group has fewer than 2 members remaining, it is dissolved entirely.
     *
     * @param graph The mutable graph to modify
     * @param nodeIds IDs of nodes to remove from groups
     * @return true if any group was modified or dissolved
     */
    fun removeNodesFromGroups(
        graph: MutableWorkflowGraph,
        nodeIds: Set<String>
    ): Boolean {
        if (nodeIds.isEmpty()) return false

        var modified = false
        val groupsToRemove = mutableListOf<Int>()

        for ((index, group) in graph.groups.withIndex()) {
            // Check if any selected node is in this group
            val hasSelectedNode = nodeIds.any { it in group.memberNodeIds }

            if (hasSelectedNode) {
                val newMemberIds = group.memberNodeIds - nodeIds

                if (newMemberIds.size < 2) {
                    // Mark for removal (dissolve group)
                    groupsToRemove.add(index)
                } else {
                    // Update group with reduced membership
                    graph.groups[index] = group.copy(memberNodeIds = newMemberIds)
                }
                modified = true
            }
        }

        // Remove dissolved groups (in reverse order to preserve indices)
        groupsToRemove.sortedDescending().forEach { index ->
            graph.groups.removeAt(index)
        }

        return modified
    }

    /**
     * Rename a group.
     *
     * @param graph The mutable graph containing the group
     * @param groupId ID of the group to rename
     * @param newTitle New title for the group
     * @return true if the group was found and renamed
     */
    fun renameGroup(
        graph: MutableWorkflowGraph,
        groupId: Int,
        newTitle: String
    ): Boolean {
        val index = graph.groups.indexOfFirst { it.id == groupId }
        if (index < 0) return false

        graph.groups[index] = graph.groups[index].copy(title = newTitle)
        return true
    }

    /**
     * Generate a unique group ID based on existing groups.
     *
     * @param graph The graph to check for existing group IDs
     * @return A new unique group ID
     */
    fun generateUniqueGroupId(graph: WorkflowGraph): Int {
        return (graph.groups.maxOfOrNull { it.id } ?: 0) + 1
    }

    /**
     * Generate a unique group ID based on a mutable graph.
     *
     * @param graph The mutable graph to check for existing group IDs
     * @return A new unique group ID
     */
    fun generateUniqueGroupId(graph: MutableWorkflowGraph): Int {
        return (graph.groups.maxOfOrNull { it.id } ?: 0) + 1
    }
}
