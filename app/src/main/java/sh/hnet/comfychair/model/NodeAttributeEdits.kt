package sh.hnet.comfychair.model

import org.json.JSONObject

/**
 * Container for user edits to node attributes within a workflow.
 * Stored as a nested map: nodeId -> (inputName -> value)
 */
data class NodeAttributeEdits(
    val edits: Map<String, Map<String, Any>> = emptyMap()
) {
    /**
     * Get edits for a specific node.
     */
    fun getEditsForNode(nodeId: String): Map<String, Any> {
        return edits[nodeId] ?: emptyMap()
    }

    /**
     * Get a specific edit value.
     */
    fun getEdit(nodeId: String, inputName: String): Any? {
        return edits[nodeId]?.get(inputName)
    }

    /**
     * Create a new instance with an updated edit.
     */
    fun withEdit(nodeId: String, inputName: String, value: Any): NodeAttributeEdits {
        val nodeEdits = edits[nodeId]?.toMutableMap() ?: mutableMapOf()
        nodeEdits[inputName] = value
        val newEdits = edits.toMutableMap()
        newEdits[nodeId] = nodeEdits
        return NodeAttributeEdits(newEdits)
    }

    /**
     * Create a new instance with an edit removed.
     */
    fun withoutEdit(nodeId: String, inputName: String): NodeAttributeEdits {
        val nodeEdits = edits[nodeId]?.toMutableMap() ?: return this
        nodeEdits.remove(inputName)
        val newEdits = edits.toMutableMap()
        if (nodeEdits.isEmpty()) {
            newEdits.remove(nodeId)
        } else {
            newEdits[nodeId] = nodeEdits
        }
        return NodeAttributeEdits(newEdits)
    }

    /**
     * Check if there are any edits.
     */
    fun isEmpty(): Boolean = edits.isEmpty()

    /**
     * Serialize to JSON string for storage.
     */
    fun toJson(): String {
        val root = JSONObject()
        edits.forEach { (nodeId, nodeEdits) ->
            val nodeJson = JSONObject()
            nodeEdits.forEach { (inputName, value) ->
                when (value) {
                    is String -> nodeJson.put(inputName, value)
                    is Int -> nodeJson.put(inputName, value)
                    is Long -> nodeJson.put(inputName, value)
                    is Float -> nodeJson.put(inputName, value.toDouble())
                    is Double -> nodeJson.put(inputName, value)
                    is Boolean -> nodeJson.put(inputName, value)
                    else -> nodeJson.put(inputName, value.toString())
                }
            }
            root.put(nodeId, nodeJson)
        }
        return root.toString()
    }

    companion object {
        /**
         * Parse from JSON string.
         */
        fun fromJson(jsonString: String?): NodeAttributeEdits {
            if (jsonString.isNullOrBlank()) return NodeAttributeEdits()

            return try {
                val root = JSONObject(jsonString)
                val edits = mutableMapOf<String, Map<String, Any>>()

                val nodeIds = root.keys()
                while (nodeIds.hasNext()) {
                    val nodeId = nodeIds.next()
                    val nodeJson = root.optJSONObject(nodeId) ?: continue
                    val nodeEdits = mutableMapOf<String, Any>()

                    val inputNames = nodeJson.keys()
                    while (inputNames.hasNext()) {
                        val inputName = inputNames.next()
                        val value = nodeJson.opt(inputName)
                        if (value != null && value != JSONObject.NULL) {
                            nodeEdits[inputName] = value
                        }
                    }

                    if (nodeEdits.isNotEmpty()) {
                        edits[nodeId] = nodeEdits
                    }
                }

                NodeAttributeEdits(edits)
            } catch (e: Exception) {
                NodeAttributeEdits()
            }
        }
    }
}
