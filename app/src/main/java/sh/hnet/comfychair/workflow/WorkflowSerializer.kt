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
        DebugLogger.d(TAG, "Serializing workflow with wrapper: name=${graph.name}, ${graph.nodes.size} nodes")
        val rootJson = JSONObject()
        rootJson.put("name", graph.name)
        rootJson.put("description", graph.description)

        val nodesJson = JSONObject()
        for (node in graph.nodes) {
            val nodeJson = serializeNode(node, includeMetadata)
            nodesJson.put(node.id, nodeJson)
        }
        rootJson.put("nodes", nodesJson)

        val result = rootJson.toString(2)
        DebugLogger.d(TAG, "Serialized workflow with wrapper: ${result.length} chars")
        return result
    }

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
