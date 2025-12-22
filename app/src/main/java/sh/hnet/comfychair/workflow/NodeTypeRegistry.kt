package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Information about a node type from /object_info
 */
data class NodeTypeInfo(
    val classType: String,
    val inputTypes: Map<String, String>,    // inputName -> type (e.g., "model" -> "MODEL")
    val outputTypes: List<String>           // output types by index (e.g., ["MODEL", "CLIP", "VAE"])
)

/**
 * Registry of node type information fetched from ComfyUI server.
 * Used to resolve slot types for edge coloring.
 */
class NodeTypeRegistry {
    private val nodeTypes = mutableMapOf<String, NodeTypeInfo>()

    /**
     * Parse /object_info response and populate the registry.
     */
    fun parseObjectInfo(objectInfoJson: JSONObject) {
        nodeTypes.clear()

        val keys = objectInfoJson.keys()
        while (keys.hasNext()) {
            val classType = keys.next()
            val nodeInfo = objectInfoJson.optJSONObject(classType) ?: continue

            // Parse input types
            val inputTypes = mutableMapOf<String, String>()
            val inputJson = nodeInfo.optJSONObject("input")

            // Parse required inputs
            val requiredJson = inputJson?.optJSONObject("required")
            parseInputTypes(requiredJson, inputTypes)

            // Parse optional inputs
            val optionalJson = inputJson?.optJSONObject("optional")
            parseInputTypes(optionalJson, inputTypes)

            // Parse output types
            val outputArray = nodeInfo.optJSONArray("output")
            val outputTypes = mutableListOf<String>()
            if (outputArray != null) {
                for (i in 0 until outputArray.length()) {
                    outputTypes.add(outputArray.optString(i, "UNKNOWN"))
                }
            }

            nodeTypes[classType] = NodeTypeInfo(
                classType = classType,
                inputTypes = inputTypes,
                outputTypes = outputTypes
            )
        }
    }

    private fun parseInputTypes(inputsJson: JSONObject?, result: MutableMap<String, String>) {
        if (inputsJson == null) return

        val keys = inputsJson.keys()
        while (keys.hasNext()) {
            val inputName = keys.next()
            val inputSpec = inputsJson.opt(inputName)

            // Input format can be:
            // - ["TYPE"] for simple types
            // - [["option1", "option2"], {}] for enums/dropdowns (no type info)
            // - ["TYPE", {"default": ...}] for types with options
            when (inputSpec) {
                is JSONArray -> {
                    if (inputSpec.length() > 0) {
                        val firstElement = inputSpec.opt(0)
                        if (firstElement is String) {
                            // Simple type: ["MODEL"] or ["MODEL", {...}]
                            result[inputName] = firstElement
                        }
                        // If firstElement is JSONArray, it's an enum/dropdown - no type info
                    }
                }
            }
        }
    }

    /**
     * Get the output type for a node at a specific output index.
     */
    fun getOutputType(classType: String, outputIndex: Int): String? {
        val info = nodeTypes[classType] ?: return null
        return info.outputTypes.getOrNull(outputIndex)
    }

    /**
     * Get the input type for a specific input on a node.
     */
    fun getInputType(classType: String, inputName: String): String? {
        val info = nodeTypes[classType] ?: return null
        return info.inputTypes[inputName]
    }

    /**
     * Check if the registry has been populated.
     */
    fun isPopulated(): Boolean = nodeTypes.isNotEmpty()

    /**
     * Clear the registry.
     */
    fun clear() {
        nodeTypes.clear()
    }
}
