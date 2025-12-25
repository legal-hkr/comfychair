package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Full definition of a node input from /object_info
 */
data class InputDefinition(
    val name: String,
    val type: String,               // "INT", "FLOAT", "STRING", "BOOLEAN", or connection type
    val isRequired: Boolean,
    val default: Any? = null,
    val min: Number? = null,        // For INT/FLOAT
    val max: Number? = null,        // For INT/FLOAT
    val step: Number? = null,       // For FLOAT sliders
    val options: List<String>? = null,  // For enum dropdowns
    val multiline: Boolean = false,     // For STRING
    val forceInput: Boolean = false     // If true, must be connected (not editable)
)

/**
 * Full definition of a node type from /object_info
 */
data class NodeTypeDefinition(
    val classType: String,
    val category: String,
    val inputs: List<InputDefinition>,
    val outputs: List<String>
)

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
 * Used to resolve slot types for edge coloring and input definitions for editing.
 */
class NodeTypeRegistry {
    private val nodeTypes = mutableMapOf<String, NodeTypeInfo>()
    private val nodeDefinitions = mutableMapOf<String, NodeTypeDefinition>()

    /**
     * Parse /object_info response and populate the registry.
     */
    fun parseObjectInfo(objectInfoJson: JSONObject) {
        nodeTypes.clear()
        nodeDefinitions.clear()

        val keys = objectInfoJson.keys()
        while (keys.hasNext()) {
            val classType = keys.next()
            val nodeInfo = objectInfoJson.optJSONObject(classType) ?: continue

            // Parse input types (for edge coloring)
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

            // Parse full input definitions (for attribute editing)
            val inputDefinitions = mutableListOf<InputDefinition>()
            parseFullInputDefinitions(requiredJson, isRequired = true, inputDefinitions)
            parseFullInputDefinitions(optionalJson, isRequired = false, inputDefinitions)

            // Parse category
            val category = nodeInfo.optString("category", "")

            nodeDefinitions[classType] = NodeTypeDefinition(
                classType = classType,
                category = category,
                inputs = inputDefinitions,
                outputs = outputTypes
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
     * Parse full input definitions with constraints for attribute editing.
     */
    private fun parseFullInputDefinitions(
        inputsJson: JSONObject?,
        isRequired: Boolean,
        result: MutableList<InputDefinition>
    ) {
        if (inputsJson == null) return

        val keys = inputsJson.keys()
        while (keys.hasNext()) {
            val inputName = keys.next()
            val inputSpec = inputsJson.opt(inputName)

            if (inputSpec !is JSONArray || inputSpec.length() == 0) continue

            val firstElement = inputSpec.opt(0)
            val optionsObj = if (inputSpec.length() > 1) inputSpec.optJSONObject(1) else null

            when (firstElement) {
                is String -> {
                    // Type-based input: ["INT", {...}] or ["FLOAT", {...}] etc.
                    val inputDef = parseTypedInput(inputName, firstElement, optionsObj, isRequired)
                    result.add(inputDef)
                }
                is JSONArray -> {
                    // Enum/dropdown: [["option1", "option2"], {...}]
                    val options = mutableListOf<String>()
                    for (i in 0 until firstElement.length()) {
                        val opt = firstElement.opt(i)
                        if (opt is String) {
                            options.add(opt)
                        }
                    }
                    val default = optionsObj?.opt("default")
                    val forceInput = optionsObj?.optBoolean("forceInput", false) ?: false

                    result.add(
                        InputDefinition(
                            name = inputName,
                            type = "ENUM",
                            isRequired = isRequired,
                            default = default,
                            options = options,
                            forceInput = forceInput
                        )
                    )
                }
            }
        }
    }

    /**
     * Parse a typed input (INT, FLOAT, STRING, BOOLEAN, or connection type).
     */
    private fun parseTypedInput(
        name: String,
        type: String,
        options: JSONObject?,
        isRequired: Boolean
    ): InputDefinition {
        val default = options?.opt("default")
        val min = options?.opt("min") as? Number
        val max = options?.opt("max") as? Number
        val step = options?.opt("step") as? Number
        val multiline = options?.optBoolean("multiline", false) ?: false
        val forceInput = options?.optBoolean("forceInput", false) ?: false

        return InputDefinition(
            name = name,
            type = type,
            isRequired = isRequired,
            default = default,
            min = min,
            max = max,
            step = step,
            multiline = multiline,
            forceInput = forceInput
        )
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
     * Get the full node definition for a class type.
     */
    fun getNodeDefinition(classType: String): NodeTypeDefinition? {
        return nodeDefinitions[classType]
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
        nodeDefinitions.clear()
    }
}
