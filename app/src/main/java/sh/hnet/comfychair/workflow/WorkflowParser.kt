package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses ComfyUI workflow JSON into a WorkflowGraph
 */
class WorkflowParser {

    /**
     * Parse workflow JSON content into a WorkflowGraph
     */
    fun parse(jsonContent: String, workflowName: String = "", workflowDescription: String = ""): WorkflowGraph {
        val json = JSONObject(jsonContent)

        val nodes = mutableListOf<WorkflowNode>()
        val edges = mutableListOf<WorkflowEdge>()
        val allTemplateVars = mutableSetOf<String>()

        // Determine the structure: our custom format has "nodes" object, ComfyUI API format has nodes at top level
        val nodesJson = if (json.has("nodes") && json.get("nodes") is JSONObject) {
            // Our custom workflow format: { "name": "...", "description": "...", "nodes": { ... } }
            json.getJSONObject("nodes")
        } else {
            // Standard ComfyUI API format: nodes at top level
            json
        }

        // Parse each node - workflow JSON has node IDs as keys
        val nodeIds = nodesJson.keys()
        for (nodeId in nodeIds) {
            val nodeValue = nodesJson.get(nodeId)
            if (nodeValue !is JSONObject) continue

            val nodeJson = nodeValue
            val classType = nodeJson.optString("class_type", "Unknown")
            val meta = nodeJson.optJSONObject("_meta")
            val title = meta?.optString("title")?.takeIf { it.isNotEmpty() } ?: classType

            val inputsJson = nodeJson.optJSONObject("inputs") ?: JSONObject()
            val inputs = parseInputs(inputsJson)

            // Find template input keys in this node (keys that are known template targets)
            val templateKeys = inputs.keys.filter { key ->
                TemplateKeyRegistry.isTemplateKey(key)
            }.toSet()

            if (templateKeys.isNotEmpty()) {
                allTemplateVars.addAll(templateKeys)
            }

            nodes.add(
                WorkflowNode(
                    id = nodeId,
                    classType = classType,
                    title = title,
                    category = categorizeNode(classType),
                    inputs = inputs,
                    templateInputKeys = templateKeys
                )
            )

            // Extract edges from connection inputs
            inputs.forEach { (inputName, value) ->
                if (value is InputValue.Connection) {
                    edges.add(
                        WorkflowEdge(
                            sourceNodeId = value.sourceNodeId,
                            sourceOutputIndex = value.outputIndex,
                            targetNodeId = nodeId,
                            targetInputName = inputName
                        )
                    )
                }
            }
        }

        return WorkflowGraph(
            name = workflowName,
            description = workflowDescription,
            nodes = nodes,
            edges = edges,
            templateVariables = allTemplateVars
        )
    }

    /**
     * Parse inputs from a node's inputs JSON object
     */
    private fun parseInputs(inputsJson: JSONObject): Map<String, InputValue> {
        val result = mutableMapOf<String, InputValue>()

        for (key in inputsJson.keys()) {
            val value = inputsJson.get(key)
            result[key] = when {
                // Connection format: ["nodeId", outputIndex]
                value is JSONArray && value.length() == 2 -> {
                    try {
                        InputValue.Connection(
                            sourceNodeId = value.getString(0),
                            outputIndex = value.getInt(1)
                        )
                    } catch (e: Exception) {
                        // If parsing fails, treat as literal
                        InputValue.Literal(value.toString())
                    }
                }
                else -> InputValue.Literal(formatLiteralValue(value))
            }
        }

        return result
    }

    /**
     * Format a literal value for display
     */
    private fun formatLiteralValue(value: Any): Any {
        return when (value) {
            is String -> {
                // Truncate long strings
                if (value.length > 30) {
                    value.take(27) + "..."
                } else {
                    value
                }
            }
            is Number -> value
            is Boolean -> value
            else -> value.toString()
        }
    }

    /**
     * Categorize a node by its class type
     */
    private fun categorizeNode(classType: String): NodeCategory {
        return when {
            // Loaders
            classType.contains("Loader", ignoreCase = true) -> NodeCategory.LOADER

            // Text encoders
            classType.contains("CLIPTextEncode", ignoreCase = true) ||
            classType.contains("TextEncode", ignoreCase = true) -> NodeCategory.ENCODER

            // Samplers
            classType.contains("Sampler", ignoreCase = true) -> NodeCategory.SAMPLER

            // Latent image creation
            classType.contains("EmptyLatent", ignoreCase = true) ||
            classType.contains("Empty") && classType.contains("Latent", ignoreCase = true) -> NodeCategory.LATENT

            // Input nodes
            classType.contains("LoadImage", ignoreCase = true) -> NodeCategory.INPUT

            // Output nodes
            classType.contains("Save", ignoreCase = true) ||
            classType.contains("Preview", ignoreCase = true) -> NodeCategory.OUTPUT

            // Processing nodes
            classType.contains("Decode", ignoreCase = true) ||
            classType.contains("Encode", ignoreCase = true) ||
            classType.contains("Scale", ignoreCase = true) ||
            classType.contains("Create", ignoreCase = true) ||
            classType.contains("Sampling", ignoreCase = true) ||
            classType.contains("ModelMerge", ignoreCase = true) -> NodeCategory.PROCESS

            else -> NodeCategory.OTHER
        }
    }
}
