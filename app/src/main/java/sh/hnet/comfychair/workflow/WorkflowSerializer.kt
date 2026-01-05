package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger

/**
 * Serializes a WorkflowGraph back to ComfyUI API JSON format.
 */
class WorkflowSerializer {

    companion object {
        private const val TAG = "WorkflowSerializer"
    }

    /**
     * Serialize a workflow graph to JSON string.
     *
     * @param graph The workflow graph to serialize
     * @param includeMetadata Whether to include node titles in _meta
     * @return JSON string in ComfyUI API format
     */
    fun serialize(graph: WorkflowGraph, includeMetadata: Boolean = true): String {
        DebugLogger.d(TAG, "Serializing workflow: ${graph.nodes.size} nodes")
        val rootJson = JSONObject()

        for (node in graph.nodes) {
            val nodeJson = serializeNode(node, includeMetadata)
            rootJson.put(node.id, nodeJson)
        }

        val result = rootJson.toString(2)  // Pretty print with 2-space indent
        DebugLogger.d(TAG, "Serialized workflow: ${result.length} chars")
        return result
    }

    /**
     * Serialize a workflow graph with wrapper metadata (our custom format).
     *
     * @param graph The workflow graph to serialize
     * @param includeMetadata Whether to include node titles in _meta
     * @return JSON string with name, description, and nodes wrapper
     */
    fun serializeWithWrapper(graph: WorkflowGraph, includeMetadata: Boolean = true): String {
        DebugLogger.d(TAG, "Serializing workflow with wrapper: name=${graph.name}, ${graph.nodes.size} nodes, ${graph.groups.size} groups")
        val rootJson = JSONObject()
        rootJson.put("name", graph.name)
        rootJson.put("description", graph.description)

        val nodesJson = JSONObject()
        for (node in graph.nodes) {
            val nodeJson = serializeNode(node, includeMetadata)
            nodesJson.put(node.id, nodeJson)
        }
        rootJson.put("nodes", nodesJson)

        // Serialize groups if present
        if (graph.groups.isNotEmpty()) {
            rootJson.put("groups", serializeGroups(graph.groups, graph.nodes, graph.notes))
        }

        // Serialize notes if present
        if (graph.notes.isNotEmpty()) {
            rootJson.put("notes", serializeNotes(graph.notes))
        }

        val result = rootJson.toString(2)
        DebugLogger.d(TAG, "Serialized workflow with wrapper: ${result.length} chars")
        return result
    }

    /**
     * Serialize groups to JSON array.
     *
     * Core data (ComfyChair format): id, title, member_nodes
     * Computed for compatibility (ComfyUI): bounding array
     *
     * @param groups The groups to serialize
     * @param nodes All nodes in the graph (for computing bounding boxes)
     * @param notes All notes in the graph (groups can contain notes too)
     */
    private fun serializeGroups(
        groups: List<WorkflowGroup>,
        nodes: List<WorkflowNode>,
        notes: List<WorkflowNote>
    ): JSONArray {
        DebugLogger.d(TAG, "serializeGroups: serializing ${groups.size} groups")
        val nodeMap = nodes.associateBy { it.id }
        val noteMap = notes.associateBy { WorkflowNote.noteIdToMemberId(it.id) }
        val groupsArray = JSONArray()

        for (group in groups) {
            DebugLogger.d(TAG, "serializeGroups: processing group id=${group.id} '${group.title}' with ${group.memberNodeIds.size} members: ${group.memberNodeIds}")

            val groupJson = JSONObject()
            groupJson.put("id", group.id)
            groupJson.put("title", group.title)

            // Serialize member node IDs (core data)
            val memberNodesArray = JSONArray()
            group.memberNodeIds.forEach { memberNodesArray.put(it) }
            groupJson.put("member_nodes", memberNodesArray)

            // Compute and serialize bounding box for ComfyUI compatibility
            val bounds = computeGroupBounds(group.memberNodeIds, nodeMap, noteMap)
            if (bounds != null) {
                val boundingArray = JSONArray()
                boundingArray.put(bounds.x.toDouble())
                boundingArray.put(bounds.y.toDouble())
                boundingArray.put(bounds.width.toDouble())
                boundingArray.put(bounds.height.toDouble())
                groupJson.put("bounding", boundingArray)
                DebugLogger.d(TAG, "serializeGroups: group ${group.id} bounds: x=${bounds.x}, y=${bounds.y}, w=${bounds.width}, h=${bounds.height}")
            } else {
                DebugLogger.w(TAG, "serializeGroups: group ${group.id} has no valid bounds (members not found in node/note map)")
            }

            groupsArray.put(groupJson)
        }

        DebugLogger.i(TAG, "serializeGroups: serialized ${groupsArray.length()} groups to JSON")
        return groupsArray
    }

    /**
     * Serialize notes to JSON array.
     *
     * @param notes The notes to serialize
     */
    private fun serializeNotes(notes: List<WorkflowNote>): JSONArray {
        DebugLogger.d(TAG, "serializeNotes: serializing ${notes.size} notes")
        val notesArray = JSONArray()

        for (note in notes) {
            val noteJson = JSONObject()
            noteJson.put("id", note.id)
            noteJson.put("title", note.title)
            noteJson.put("content", note.content)
            notesArray.put(noteJson)
            DebugLogger.d(TAG, "serializeNotes: note id=${note.id} '${note.title}' (${note.content.length} chars)")
        }

        DebugLogger.i(TAG, "serializeNotes: serialized ${notesArray.length()} notes to JSON")
        return notesArray
    }

    /**
     * Compute bounding box for a group from its member nodes and notes.
     *
     * @param memberIds IDs of nodes/notes in the group (notes prefixed with "note:")
     * @param nodeMap Map of node ID to node
     * @param noteMap Map of member ID ("note:N") to note
     * @return Computed bounds, or null if no members found
     */
    private fun computeGroupBounds(
        memberIds: Set<String>,
        nodeMap: Map<String, WorkflowNode>,
        noteMap: Map<String, WorkflowNote>
    ): GroupBounds? {
        // Collect bounds from both nodes and notes
        val allBounds = mutableListOf<MemberBounds>()

        for (memberId in memberIds) {
            if (WorkflowNote.isNoteMemberId(memberId)) {
                noteMap[memberId]?.let { note ->
                    allBounds.add(MemberBounds(note.x, note.y, note.width, note.height))
                }
            } else {
                nodeMap[memberId]?.let { node ->
                    allBounds.add(MemberBounds(node.x, node.y, node.width, node.height))
                }
            }
        }

        if (allBounds.isEmpty()) return null

        // Padding around members within group
        val padding = 20f
        val headerHeight = 30f  // Space for group title

        val minX = allBounds.minOf { it.x } - padding
        val minY = allBounds.minOf { it.y } - padding - headerHeight
        val maxX = allBounds.maxOf { it.x + it.width } + padding
        val maxY = allBounds.maxOf { it.y + it.height } + padding

        return GroupBounds(
            x = minX,
            y = minY,
            width = maxX - minX,
            height = maxY - minY
        )
    }

    /**
     * Simple bounds container for member (node or note) bounds.
     */
    private data class MemberBounds(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    /**
     * Simple bounds container for group bounding box computation.
     */
    private data class GroupBounds(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    /**
     * Serialize a single node to JSON.
     */
    private fun serializeNode(node: WorkflowNode, includeMetadata: Boolean): JSONObject {
        val nodeJson = JSONObject()

        // Class type is required
        nodeJson.put("class_type", node.classType)

        // Add metadata if title differs from class type
        if (includeMetadata && node.title != node.classType) {
            val metaJson = JSONObject()
            metaJson.put("title", node.title)
            nodeJson.put("_meta", metaJson)
        }

        // Serialize inputs
        val inputsJson = JSONObject()
        for ((inputName, inputValue) in node.inputs) {
            when (inputValue) {
                is InputValue.Literal -> {
                    // Serialize literal value
                    inputsJson.put(inputName, serializeLiteralValue(inputValue.value))
                }
                is InputValue.Connection -> {
                    // Serialize connection as [nodeId, outputIndex]
                    val connectionArray = JSONArray()
                    connectionArray.put(inputValue.sourceNodeId)
                    connectionArray.put(inputValue.outputIndex)
                    inputsJson.put(inputName, connectionArray)
                }
                is InputValue.UnconnectedSlot -> {
                    // Skip unconnected slots - they're not serialized to JSON
                    // The slot will be connected later or left as a required input
                }
            }
        }
        nodeJson.put("inputs", inputsJson)

        // Add mode if not default (0=active, 2=muted, 4=bypassed)
        if (node.mode != 0) {
            DebugLogger.d(TAG, "Serializing node ${node.id} with mode=${node.mode} (${if (node.mode == 4) "bypassed" else if (node.mode == 2) "muted" else "unknown"})")
            nodeJson.put("mode", node.mode)
        }

        return nodeJson
    }

    /**
     * Serialize a literal value to the appropriate JSON type.
     */
    private fun serializeLiteralValue(value: Any): Any {
        return when (value) {
            is String -> value
            is Int -> value
            is Long -> value
            is Float -> value.toDouble()  // JSON uses double
            is Double -> value
            is Boolean -> value
            is Number -> value.toDouble()
            else -> value.toString()
        }
    }

    /**
     * Apply node attribute edits to a workflow before serialization.
     * This creates a new graph with edits applied.
     */
    fun applyEdits(
        graph: WorkflowGraph,
        nodeAttributeEdits: Map<String, Map<String, Any>>
    ): WorkflowGraph {
        if (nodeAttributeEdits.isEmpty()) {
            DebugLogger.d(TAG, "applyEdits: no edits to apply")
            return graph
        }

        val totalEdits = nodeAttributeEdits.values.sumOf { it.size }
        DebugLogger.d(TAG, "applyEdits: applying $totalEdits edits to ${nodeAttributeEdits.size} nodes")

        val updatedNodes = graph.nodes.map { node ->
            val edits = nodeAttributeEdits[node.id] ?: return@map node

            val updatedInputs = node.inputs.toMutableMap()
            for ((inputName, editedValue) in edits) {
                // Only update if the input exists and is a Literal
                if (updatedInputs[inputName] is InputValue.Literal) {
                    updatedInputs[inputName] = InputValue.Literal(editedValue)
                }
            }

            node.copy(inputs = updatedInputs)
        }

        return graph.copy(nodes = updatedNodes)
    }
}
