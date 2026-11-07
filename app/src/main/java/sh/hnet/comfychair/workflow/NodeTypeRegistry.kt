package sh.hnet.comfychair.workflow

import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.Reader

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
    val multiselect: Boolean = false,   // For COMBO: allow multiple selections
    /**
     * For a dynamic combo (COMFY_DYNAMICCOMBO_V3): each option's own inputs (required, then optional), which the
     * frontend shows right after the combo as "combo.input" while that option is chosen
     */
    val dynamicOptions: Map<String, List<InputDefinition>>? = null,
    /** A widget without a socket (an image comparer, a colour, a bounding box editor…): drawn by the frontend only */
    val socketless: Boolean = false,
    /** Followed by the frontend's "control after generate" widget (fixed, increment, decrement, randomize) */
    val controlAfterGenerate: Boolean = false,
    /** Has the frontend's upload button (image, video or file), which may save a value of its own after it */
    val upload: Boolean = false
)

/**
 * Whether ComfyUI's frontend shows this input as a widget, whose value is saved in a node's widgets_values: the value
 * types and combos. Anything else is a socket (MODEL, IMAGE, AUDIO, VIDEO, an extension's type), as are the dynamic
 * sockets (COMFY_MATCHTYPE_V3, COMFY_AUTOGROW_V3, COMFY_DYNAMICGROUP_V3) and inputs forced to be connected.
 */
val InputDefinition.isWidget: Boolean
    get() = !forceInput && type in WIDGET_TYPES

private val WIDGET_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM", "COMBO", DYNAMIC_COMBO_TYPE)

const val DYNAMIC_COMBO_TYPE = "COMFY_DYNAMICCOMBO_V3"

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
        // A dynamic combo: its first option, as ComfyUI's own combo widget starts
        DYNAMIC_COMBO_TYPE -> dynamicOptions?.keys?.firstOrNull() ?: ""
        else -> ""
    }
}

/**
 * Definition of a node output slot from /object_info.
 * Type is used for wire coloring, name is displayed in the UI.
 */
@Immutable
data class OutputSlot(
    val type: String,  // e.g., "CONDITIONING" - used for slot colors
    val name: String   // e.g., "positive" - displayed in UI
)

/**
 * Full definition of a node type from /object_info
 */
@Stable
data class NodeTypeDefinition(
    val classType: String,
    val category: String,
    val inputs: List<InputDefinition>,
    val outputs: List<OutputSlot>,
    val displayName: String? = null,    // Human-readable name
    val description: String? = null,    // Node documentation
    val deprecated: Boolean = false,    // Whether node is deprecated
    val experimental: Boolean = false,  // Whether node is experimental
    val isCustom: Boolean = false       // From an extension (custom_nodes), not ComfyUI itself
)

/**
 * Information about a node type from /object_info
 */
data class NodeTypeInfo(
    val classType: String,
    val inputTypes: Map<String, String>,    // inputName -> type (e.g., "model" -> "MODEL")
    val outputTypes: List<String>,          // output types by index (e.g., ["MODEL", "CLIP", "VAE"])
    val outputSlots: List<OutputSlot> = emptyList()  // full output info with names
)

/**
 * Registry of node type information fetched from ComfyUI server.
 * Used to resolve slot types for edge coloring and input definitions for editing.
 */
class NodeTypeRegistry {
    // Replaced as a whole after a successful parse, so readers never see a partially populated registry
    @Volatile
    private var nodeTypes: Map<String, NodeTypeInfo> = emptyMap()
    @Volatile
    private var nodeDefinitions: Map<String, NodeTypeDefinition> = emptyMap()

    companion object {
        private const val TAG = "NodeTypeRegistry"
    }

    /**
     * Parse a streamed /object_info response and populate the registry.
     *
     * The response can reach tens of MB on servers with many custom nodes or model files, so it is
     * never materialized as a whole: only one node definition at a time is turned into a JSONObject.
     * The registry is replaced only if the entire stream parses successfully; otherwise the
     * exception propagates and the previous contents are kept.
     */
    fun parseObjectInfo(objectInfoReader: Reader) {
        val newNodeTypes = mutableMapOf<String, NodeTypeInfo>()
        val newNodeDefinitions = mutableMapOf<String, NodeTypeDefinition>()
        // Model dropdowns repeat the same file list in every loader node (e.g. a LoRA list appears
        // ~150 times), so identical option lists are shared instead of being kept once per input
        val optionListPool = HashMap<List<String>, List<String>>()

        val reader = JsonReader(objectInfoReader)
        // Python's json module emits NaN/Infinity literals, which org.json used to tolerate
        reader.isLenient = true
        reader.beginObject()
        while (reader.hasNext()) {
            val classType = reader.nextName()
            val nodeInfo = readJsonValue(reader) as? JSONObject ?: continue
            parseNode(classType, nodeInfo, newNodeTypes, newNodeDefinitions, optionListPool)
        }
        reader.endObject()

        nodeTypes = newNodeTypes
        nodeDefinitions = newNodeDefinitions

        DebugLogger.i(TAG, "NodeTypeRegistry populated: ${newNodeDefinitions.size} node types parsed")
    }

    /**
     * Read the next value from [reader] into the same representation org.json would produce.
     */
    private fun readJsonValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
            reader.beginObject()
            while (reader.hasNext()) {
                obj.put(reader.nextName(), readJsonValue(reader))
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            reader.beginArray()
            while (reader.hasNext()) {
                array.put(readJsonValue(reader))
            }
            reader.endArray()
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }
        JsonToken.NUMBER -> {
            // Same narrowing as org.json: Int, then Long, then Double; non-finite values stay strings
            val literal = reader.nextString()
            literal.toIntOrNull()
                ?: literal.toLongOrNull()
                ?: literal.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: literal
        }
        else -> reader.nextString()
    }

    /**
     * Parse a single node entry of /object_info into the given maps.
     */
    private fun parseNode(
        classType: String,
        nodeInfo: JSONObject,
        nodeTypes: MutableMap<String, NodeTypeInfo>,
        nodeDefinitions: MutableMap<String, NodeTypeDefinition>,
        optionListPool: MutableMap<List<String>, List<String>>
    ) {
        // Parse input types (for edge coloring)
        val inputTypes = mutableMapOf<String, String>()
        val inputJson = nodeInfo.optJSONObject("input")

        // Parse required inputs
        val requiredJson = inputJson?.optJSONObject("required")
        parseInputTypes(requiredJson, inputTypes)

        // Parse optional inputs
        val optionalJson = inputJson?.optJSONObject("optional")
        parseInputTypes(optionalJson, inputTypes)

        // Parse output types and names
        val outputArray = nodeInfo.optJSONArray("output")
        val outputNameArray = nodeInfo.optJSONArray("output_name")
        val outputTypes = mutableListOf<String>()
        val outputSlots = mutableListOf<OutputSlot>()
        if (outputArray != null) {
            for (i in 0 until outputArray.length()) {
                val type = outputArray.optString(i, "UNKNOWN")
                val name = outputNameArray?.optString(i)?.takeIf { it.isNotEmpty() } ?: type
                outputTypes.add(type)
                outputSlots.add(OutputSlot(type = type, name = name))
            }
        }

        nodeTypes[classType] = NodeTypeInfo(
            classType = classType,
            inputTypes = inputTypes,
            outputTypes = outputTypes,
            outputSlots = outputSlots
        )

        // Parse full input definitions (for attribute editing)
        val inputDefinitions = mutableListOf<InputDefinition>()
        parseFullInputDefinitions(requiredJson, isRequired = true, inputDefinitions)
        parseFullInputDefinitions(optionalJson, isRequired = false, inputDefinitions)
        val sharedInputDefinitions = inputDefinitions.map { definition ->
            val options = definition.options ?: return@map definition
            definition.copy(options = optionListPool.getOrPut(options) { options })
        }

        // Parse node-level metadata
        val category = nodeInfo.optString("category", "")
        val displayName = (nodeInfo.opt("display_name") as? String)?.takeIf { it.isNotEmpty() }
        val description = (nodeInfo.opt("description") as? String)?.takeIf { it.isNotEmpty() }
        val deprecated = nodeInfo.optBoolean("deprecated", false)
        val experimental = nodeInfo.optBoolean("experimental", false)
        // "nodes" and "comfy_extras.…" are ComfyUI's own; extensions live in "custom_nodes.…"
        val isCustom = nodeInfo.optString("python_module", "").startsWith("custom_nodes")

        nodeDefinitions[classType] = NodeTypeDefinition(
            classType = classType,
            category = category,
            inputs = sharedInputDefinitions,
            outputs = outputSlots,
            displayName = displayName,
            description = description,
            deprecated = deprecated,
            experimental = experimental,
            isCustom = isCustom
        )
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
                                    multiselect = multiselect,
                                    socketless = optionsObj.optBoolean("socketless", false),
                                    controlAfterGenerate = optionsObj.optBoolean("control_after_generate", false),
                                    upload = optionsObj.hasUploadFlag()
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
                            tooltip = tooltip,
                            socketless = optionsObj?.optBoolean("socketless", false) ?: false,
                            controlAfterGenerate = optionsObj?.optBoolean("control_after_generate", false) ?: false,
                            upload = optionsObj?.hasUploadFlag() ?: false
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
        // A dynamic combo: its options, each with the inputs it adds
        val dynamicOptions = if (type == DYNAMIC_COMBO_TYPE) {
            options?.optJSONArray("options")?.let { array ->
                val result = LinkedHashMap<String, List<InputDefinition>>()
                for (i in 0 until array.length()) {
                    val option = array.optJSONObject(i) ?: continue
                    val key = option.opt("key") as? String ?: continue
                    val optionInputs = option.optJSONObject("inputs")
                    val defs = mutableListOf<InputDefinition>()
                    parseFullInputDefinitions(optionInputs?.optJSONObject("required"), isRequired = true, defs)
                    parseFullInputDefinitions(optionInputs?.optJSONObject("optional"), isRequired = false, defs)
                    result[key] = defs
                }
                result
            }
        } else null

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
            round = round,
            dynamicOptions = dynamicOptions,
            socketless = options?.optBoolean("socketless", false) ?: false,
            controlAfterGenerate = options?.optBoolean("control_after_generate", false) ?: false,
            upload = options?.hasUploadFlag() ?: false
        )
    }

    /** Whether an input's options ask the frontend for an upload button (image_upload, video_upload, file_upload…). */
    private fun JSONObject.hasUploadFlag(): Boolean =
        keys().asSequence().any { it.endsWith("_upload") && optBoolean(it, false) }

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
     * Get options for a specific input on a specific node type.
     * Use this when a generic field name like "model_name" is used by multiple
     * unrelated node types and you need options from a specific one.
     */
    fun getOptionsForNodeInput(classType: String, inputName: String): List<String> {
        return getInputDefinition(classType, inputName)?.options ?: emptyList()
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
        nodeTypes = emptyMap()
        nodeDefinitions = emptyMap()
    }
}
