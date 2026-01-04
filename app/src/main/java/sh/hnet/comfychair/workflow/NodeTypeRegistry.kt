package sh.hnet.comfychair.workflow

import androidx.compose.runtime.Stable
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger

/**
 * Full definition of a node input from /object_info
 */
@Stable
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
    val forceInput: Boolean = false,    // If true, must be connected (not editable)
    val tooltip: String? = null,        // Help text for the input
    val round: Number? = null,          // Rounding precision for FLOAT
    val multiselect: Boolean = false    // For COMBO: allow multiple selections
)

/**
 * Get the effective default value for this input definition.
 * Returns the server-provided default if available, otherwise returns
 * a type-appropriate fallback value.
 */
fun InputDefinition.getEffectiveDefault(): Any {
    return default ?: when (type) {
        "INT" -> 0
        "FLOAT" -> 0.0
        "STRING" -> ""
        "BOOLEAN" -> false
        "ENUM" -> options?.firstOrNull() ?: ""
        else -> ""
    }
}

/**
 * Full definition of a node type from /object_info
 */
@Stable
data class NodeTypeDefinition(
    val classType: String,
    val category: String,
    val inputs: List<InputDefinition>,
    val outputs: List<String>,
    val displayName: String? = null,    // Human-readable name
    val description: String? = null,    // Node documentation
    val deprecated: Boolean = false,    // Whether node is deprecated
    val experimental: Boolean = false   // Whether node is experimental
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

    companion object {
        private const val TAG = "NodeTypeRegistry"
    }

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

            // Parse node-level metadata
            val category = nodeInfo.optString("category", "")
            val displayName = (nodeInfo.opt("display_name") as? String)?.takeIf { it.isNotEmpty() }
            val description = (nodeInfo.opt("description") as? String)?.takeIf { it.isNotEmpty() }
            val deprecated = nodeInfo.optBoolean("deprecated", false)
            val experimental = nodeInfo.optBoolean("experimental", false)

            nodeDefinitions[classType] = NodeTypeDefinition(
                classType = classType,
                category = category,
                inputs = inputDefinitions,
                outputs = outputTypes,
                displayName = displayName,
                description = description,
                deprecated = deprecated,
                experimental = experimental
            )
        }

        DebugLogger.i(TAG, "NodeTypeRegistry populated: ${nodeDefinitions.size} node types parsed")
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
                    // Check for new COMBO format: ["COMBO", {"options": [...], "multiselect": bool}]
                    if (firstElement == "COMBO" && optionsObj != null) {
                        val optionsArray = optionsObj.optJSONArray("options")
                        if (optionsArray != null) {
                            val options = mutableListOf<String>()
                            for (i in 0 until optionsArray.length()) {
                                val opt = optionsArray.opt(i)
                                if (opt is String) {
                                    options.add(opt)
                                }
                            }
                            val default = optionsObj.opt("default")
                            val forceInput = optionsObj.optBoolean("forceInput", false)
                            val tooltip = (optionsObj.opt("tooltip") as? String)?.takeIf { it.isNotEmpty() }
                            val multiselect = optionsObj.optBoolean("multiselect", false)

                            result.add(
                                InputDefinition(
                                    name = inputName,
                                    type = "ENUM",
                                    isRequired = isRequired,
                                    default = default,
                                    options = options,
                                    forceInput = forceInput,
                                    tooltip = tooltip,
                                    multiselect = multiselect
                                )
                            )
                            continue
                        }
                    }

                    // Type-based input: ["INT", {...}] or ["FLOAT", {...}] etc.
                    val inputDef = parseTypedInput(inputName, firstElement, optionsObj, isRequired)
                    result.add(inputDef)
                }
                is JSONArray -> {
                    // Enum/dropdown (old format): [["option1", "option2"], {...}]
                    val options = mutableListOf<String>()
                    for (i in 0 until firstElement.length()) {
                        val opt = firstElement.opt(i)
                        if (opt is String) {
                            options.add(opt)
                        }
                    }
                    val default = optionsObj?.opt("default")
                    val forceInput = optionsObj?.optBoolean("forceInput", false) ?: false
                    val tooltip = (optionsObj?.opt("tooltip") as? String)?.takeIf { it.isNotEmpty() }

                    result.add(
                        InputDefinition(
                            name = inputName,
                            type = "ENUM",
                            isRequired = isRequired,
                            default = default,
                            options = options,
                            forceInput = forceInput,
                            tooltip = tooltip
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
        val tooltip = (options?.opt("tooltip") as? String)?.takeIf { it.isNotEmpty() }
        val round = options?.opt("round") as? Number

        return InputDefinition(
            name = name,
            type = type,
            isRequired = isRequired,
            default = default,
            min = min,
            max = max,
            step = step,
            multiline = multiline,
            forceInput = forceInput,
            tooltip = tooltip,
            round = round
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
     * Get the input definition for a specific input on a node type.
     * Convenience method combining getNodeDefinition and finding the input.
     */
    fun getInputDefinition(classType: String, inputName: String): InputDefinition? {
        val definition = nodeDefinitions[classType]
        if (definition == null) {
            DebugLogger.d(TAG, "getInputDefinition: Node type '$classType' not found in registry")
            return null
        }
        val inputDef = definition.inputs.find { it.name == inputName }
        if (inputDef == null) {
            DebugLogger.d(TAG, "getInputDefinition: Input '$inputName' not found on node '$classType'")
        } else {
            DebugLogger.d(TAG, "getInputDefinition: $classType.$inputName -> " +
                    "min=${inputDef.min}, max=${inputDef.max}, step=${inputDef.step}")
        }
        return inputDef
    }

    /**
     * Check if the registry has been populated.
     */
    fun isPopulated(): Boolean = nodeTypes.isNotEmpty()

    /**
     * Get all available node type definitions.
     */
    fun getAllNodeTypes(): List<NodeTypeDefinition> {
        return nodeDefinitions.values.toList()
    }

    /**
     * Get all unique options for a given input field name across all node types.
     * This enables dynamic discovery of model options from any loader node
     * (standard, GGUF, future plugins).
     */
    fun getOptionsForField(fieldName: String): List<String> {
        val allOptions = mutableSetOf<String>()
        nodeDefinitions.values.forEach { nodeDef ->
            nodeDef.inputs
                .filter { it.name == fieldName && it.options != null }
                .forEach { input -> allOptions.addAll(input.options!!) }
        }
        return allOptions.sorted()
    }

    /**
     * Get all unique options for fields matching a prefix (e.g., "clip_name" matches
     * clip_name, clip_name1, clip_name2, etc.). Useful for nodes with multiple
     * similar inputs like DualCLIPLoaderGGUF.
     */
    fun getOptionsForFieldPrefix(prefix: String): List<String> {
        val allOptions = mutableSetOf<String>()
        nodeDefinitions.values.forEach { nodeDef ->
            nodeDef.inputs
                .filter { it.name.startsWith(prefix) && it.options != null }
                .forEach { input -> allOptions.addAll(input.options!!) }
        }
        return allOptions.sorted()
    }

    /**
     * Get node types grouped by category.
     */
    fun getNodeTypesByCategory(): Map<String, List<NodeTypeDefinition>> {
        return nodeDefinitions.values.groupBy { it.category.ifEmpty { "Other" } }
    }

    /**
     * Clear the registry.
     */
    fun clear() {
        nodeTypes.clear()
        nodeDefinitions.clear()
    }
}
