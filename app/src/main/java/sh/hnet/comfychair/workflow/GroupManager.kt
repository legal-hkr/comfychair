package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.util.DebugLogger

/**
 * Manages workflow node groups.
 *
 * Provides functions for creating, querying, and modifying groups.
 * Groups track explicit membership via [WorkflowGroup.memberNodeIds].
 *
 * Enforces one-node-one-group constraint: a node can only belong to one group at a time.
 */
object GroupManager {

    private const val TAG = "GroupManager"

    /**
     * Create a new group containing the specified nodes and/or notes.
     *
     * Enforces one-member-one-group: if any members are already in other groups,
     * they are removed from those groups first.
     *
     * @param graph The mutable graph to add the group to
     * @param memberIds IDs of members to include (node IDs or "note:X" IDs, must be >= 1)
     * @param title Group title (default: "Group")
     * @param idGenerator Function to generate a unique group ID
     * @return The created group, or null if no valid members specified
     */
    fun createGroup(
        graph: MutableWorkflowGraph,
        memberIds: Set<String>,
        title: String = "Group",
        idGenerator: () -> Int
    ): WorkflowGroup? {
        DebugLogger.d(TAG, "createGroup: requested with ${memberIds.size} members: $memberIds, title='$title'")

        if (memberIds.isEmpty()) {
            DebugLogger.w(TAG, "createGroup: rejected - need at least 1 member")
            return null
        }

        // Count valid members (nodes and notes that actually exist in the graph)
        val validNodeIds = memberIds.filter { memberId ->
            !WorkflowNote.isNoteMemberId(memberId) && graph.nodes.any { it.id == memberId }
        }
        val validNoteIds = memberIds.filter { memberId ->
            WorkflowNote.isNoteMemberId(memberId) &&
            graph.notes.any { it.id == WorkflowNote.memberIdToNoteId(memberId) }
        }
        val validMemberCount = validNodeIds.size + validNoteIds.size

        if (validMemberCount < 1) {
            DebugLogger.w(TAG, "createGroup: rejected - none of ${memberIds.size} requested members exist in graph")
            return null
        }

        // CRITICAL: Remove members from any existing groups first (one-member-one-group)
        removeNodesFromGroups(graph, memberIds)

        val newGroup = WorkflowGroup(
            id = idGenerator(),
            title = title,
            memberNodeIds = memberIds
        )

        graph.groups.add(newGroup)
        DebugLogger.i(TAG, "createGroup: created group id=${newGroup.id} '$title' with ${memberIds.size} members: $memberIds")
        DebugLogger.d(TAG, "createGroup: graph now has ${graph.groups.size} groups")
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
     * If a group has no members remaining, it is dissolved entirely.
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

        DebugLogger.d(TAG, "removeNodesFromGroups: removing nodes $nodeIds from groups")

        var modified = false
        val groupsToRemove = mutableListOf<Int>()

        for ((index, group) in graph.groups.withIndex()) {
            // Check if any selected node is in this group
            val hasSelectedNode = nodeIds.any { it in group.memberNodeIds }

            if (hasSelectedNode) {
                val newMemberIds = group.memberNodeIds - nodeIds
                val removedNodes = group.memberNodeIds.intersect(nodeIds)

                if (newMemberIds.isEmpty()) {
                    // Mark for removal (dissolve group)
                    groupsToRemove.add(index)
                    DebugLogger.d(TAG, "removeNodesFromGroups: group ${group.id} '${group.title}' will be dissolved (removed $removedNodes, no members left)")
                } else {
                    // Update group with reduced membership
                    graph.groups[index] = group.copy(memberNodeIds = newMemberIds)
                    DebugLogger.d(TAG, "removeNodesFromGroups: group ${group.id} '${group.title}' updated - removed $removedNodes, ${newMemberIds.size} members remain")
                }
                modified = true
            }
        }

        // Remove dissolved groups (in reverse order to preserve indices)
        groupsToRemove.sortedDescending().forEach { index ->
            val dissolved = graph.groups[index]
            DebugLogger.i(TAG, "removeNodesFromGroups: dissolved group ${dissolved.id} '${dissolved.title}'")
            graph.groups.removeAt(index)
        }

        if (modified) {
            DebugLogger.d(TAG, "removeNodesFromGroups: graph now has ${graph.groups.size} groups")
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
        DebugLogger.d(TAG, "renameGroup: renaming group $groupId to '$newTitle'")

        val index = graph.groups.indexOfFirst { it.id == groupId }
        if (index < 0) {
            DebugLogger.w(TAG, "renameGroup: group $groupId not found")
            return false
        }

        val oldTitle = graph.groups[index].title
        graph.groups[index] = graph.groups[index].copy(title = newTitle)
        DebugLogger.i(TAG, "renameGroup: group $groupId renamed from '$oldTitle' to '$newTitle'")
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
