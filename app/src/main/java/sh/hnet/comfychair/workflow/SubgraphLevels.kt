package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * The inside of a subgraph as a level of the editor (spec §19.2): a definition's nodes, groups and notes as a workflow
 * the editor shows and edits like the top level, with its inputs and outputs as two port nodes (the Inputs and Outputs
 * strips). The subgraph's inputs are the Inputs node's outputs, its outputs the Outputs node's inputs; a value an input
 * feeds inside is linked from the Inputs node, its own value kept aside and put back when the level is written.
 *
 * Port nodes have the IDs ComfyUI gives them ([INPUTS_ID], [OUTPUTS_ID]) and class types naming their definition, so
 * the registry can give them types ([portTypes]). They're never written into a definition.
 */
object SubgraphLevels {
    const val INPUTS_ID = "-10"
    const val OUTPUTS_ID = "-20"
    private const val INPUTS_TYPE = "ComfyChair.SubgraphInputs:"
    private const val OUTPUTS_TYPE = "ComfyChair.SubgraphOutputs:"
    private const val MAX_DEPTH = 32

    fun isPortNode(node: WorkflowNode): Boolean = isPortType(node.classType)
    fun isPortType(classType: String): Boolean = classType.startsWith(INPUTS_TYPE) || classType.startsWith(OUTPUTS_TYPE)
    fun isInputsNode(node: WorkflowNode): Boolean = node.classType.startsWith(INPUTS_TYPE)
    fun isOutputsNode(node: WorkflowNode): Boolean = node.classType.startsWith(OUTPUTS_TYPE)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun targetsOf(port: JSONObject): List<Pair<String, String>> {
        val targets = port.optJSONArray("targets") ?: return emptyList()
        return (0 until targets.length()).mapNotNull { i ->
            targets.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { it.optString(0) to it.optString(1) }
        }
    }

    private fun isLink(value: Any?): Boolean =
        value is JSONArray && value.length() == 2 && value.opt(0) is String && value.opt(1) is Number

    /** The keys of the Outputs node's inputs, by output: the outputs' names, a repeated one with _1, _2. */
    fun outputKeys(definition: JSONObject): List<String> {
        val used = mutableSetOf<String>()
        return definition.optJSONArray("outputs").objects().mapIndexed { index, port ->
            val base = port.optString("name").ifEmpty { "output_${index + 1}" }
            var key = base
            var n = 1
            while (!used.add(key)) key = "${base}_${n++}"
            key
        }
    }

    /** A level being shown: its workflow (with port nodes and the definitions) and the values put aside for links. */
    class Level(val root: JSONObject, val kept: Map<Pair<String, String>, Any>)

    /** The inside of definition [definitionId] as a workflow (null for an unknown one). */
    fun levelOf(definitions: JSONObject, definitionId: String): Level? {
        val definition = definitions.optJSONObject(definitionId) ?: return null
        val source = definition.optJSONObject("nodes") ?: JSONObject()
        val nodes = JSONObject()
        val kept = mutableMapOf<Pair<String, String>, Any>()
        // The port nodes first: drawn behind, found first
        nodes.put(INPUTS_ID, JSONObject().put("class_type", INPUTS_TYPE + definitionId).put("inputs", JSONObject()))
        val outputs = JSONObject()
        val keys = outputKeys(definition)
        definition.optJSONArray("outputs").objects().forEachIndexed { index, port ->
            val link = when {
                port.optJSONArray("source") != null -> port.getJSONArray("source").let { JSONArray().put(it.optString(0)).put(it.optInt(1)) }
                port.has("input") -> JSONArray().put(INPUTS_ID).put(port.optInt("input"))
                else -> null
            }
            if (link != null) outputs.put(keys[index], link)
        }
        nodes.put(OUTPUTS_ID, JSONObject().put("class_type", OUTPUTS_TYPE + definitionId).put("inputs", outputs))
        for (id in source.keys()) nodes.put(id, JSONObject(source.getJSONObject(id).toString()))
        // Values an input feeds: linked from the Inputs node, their own values put aside
        definition.optJSONArray("inputs").objects().forEachIndexed { index, port ->
            targetsOf(port).forEach { (nodeId, input) ->
                val node = nodes.optJSONObject(nodeId) ?: return@forEach
                val inputs = node.optJSONObject("inputs") ?: JSONObject().also { node.put("inputs", it) }
                inputs.opt(input)?.takeIf { it != JSONObject.NULL && !isLink(it) }?.let { kept[nodeId to input] = it }
                inputs.put(input, JSONArray().put(INPUTS_ID).put(index))
            }
        }
        val root = JSONObject()
        root.put("name", definition.optString("name"))
        root.put("nodes", nodes)
        definition.optJSONArray("groups")?.let { root.put("groups", JSONArray(it.toString())) }
        definition.optJSONArray("notes")?.let { root.put("notes", JSONArray(it.toString())) }
        root.put(Subgraphs.KEY, JSONObject(definitions.toString()))
        return Level(root, kept)
    }

    /**
     * Write a level back into its definition: [level] is the level as the serializer writes it (nodes as the editor
     * shows them, nested instances' values exposed; groups and notes; no definitions). Returns the definitions with
     * [definitionId] replaced, nested instances' values taken into theirs; the definitions as they were for an unknown
     * one.
     */
    fun write(definitions: JSONObject, definitionId: String, level: JSONObject, kept: Map<Pair<String, String>, Any>): JSONObject {
        val result = JSONObject(definitions.toString())
        val definition = result.optJSONObject(definitionId) ?: return result
        val levelNodes = JSONObject(level.optJSONObject("nodes")?.toString() ?: "{}")
        val outputsNode = levelNodes.optJSONObject(OUTPUTS_ID)
        levelNodes.remove(INPUTS_ID)
        levelNodes.remove(OUTPUTS_ID)

        // The inputs: what each feeds now; values no longer fed get their own back
        val inputPorts = definition.optJSONArray("inputs").objects()
        val fed = inputPorts.map { mutableListOf<Pair<String, String>>() }
        for (id in levelNodes.keys()) {
            val inputs = levelNodes.getJSONObject(id).optJSONObject("inputs") ?: continue
            for (key in inputs.keys().asSequence().toList()) {
                val value = inputs.opt(key)
                if (!isLink(value) || (value as JSONArray).optString(0) != INPUTS_ID) continue
                fed.getOrNull(value.optInt(1))?.add(id to key)
                val own = kept[id to key]
                if (own != null) inputs.put(key, own) else inputs.remove(key)
            }
        }
        // Targets kept in their order, new ones after them
        inputPorts.forEachIndexed { index, port ->
            val before = targetsOf(port)
            val now = fed[index].sortedBy { target -> before.indexOf(target).let { if (it < 0) Int.MAX_VALUE else it } }
            port.put("targets", JSONArray().also { array -> now.forEach { (id, key) -> array.put(JSONArray().put(id).put(key)) } })
        }

        // The outputs: what feeds each now
        val keys = outputKeys(definition)
        definition.optJSONArray("outputs").objects().forEachIndexed { index, port ->
            val link = outputsNode?.optJSONObject("inputs")?.opt(keys[index]) as? JSONArray
            port.remove("source")
            port.remove("input")
            when {
                link == null || !isLink(link) -> Unit
                link.optString(0) == INPUTS_ID -> { port.put("input", link.optInt(1)); port.remove("value") }
                else -> { port.put("source", JSONArray().put(link.optString(0)).put(link.optInt(1))); port.remove("value") }
            }
        }

        // Nested instances' values into their definitions (the definitions all stay: other levels use them)
        val scratch = JSONObject().put("nodes", levelNodes).put(Subgraphs.KEY, result)
        Subgraphs.absorb(scratch, prune = false)
        definition.put("nodes", levelNodes)
        listOf("groups", "notes").forEach { key ->
            val items = level.optJSONArray(key)
            if (items != null && items.length() > 0) definition.put(key, JSONArray(items.toString())) else definition.remove(key)
        }
        return result
    }

    /**
     * The types of a level's port nodes among [classTypes]: the Inputs node's outputs are the subgraph's inputs, the
     * Outputs node's inputs its outputs (sockets only).
     */
    fun portTypes(definitions: JSONObject, classTypes: Collection<String>): Map<String, NodeTypeDefinition> =
        classTypes.filter(::isPortType).distinct().mapNotNull { type ->
            val inputs = type.startsWith(INPUTS_TYPE)
            val definitionId = type.removePrefix(if (inputs) INPUTS_TYPE else OUTPUTS_TYPE)
            val definition = definitions.optJSONObject(definitionId) ?: return@mapNotNull null
            type to if (inputs) {
                NodeTypeDefinition(
                    classType = type, category = Subgraphs.SUBGRAPH_CATEGORY, inputs = emptyList(),
                    outputs = Subgraphs.portsOf(definitions, definitionId, "inputs").map { (name, slotType) -> OutputSlot(slotType, name) }
                )
            } else {
                val keys = outputKeys(definition)
                NodeTypeDefinition(
                    classType = type, category = Subgraphs.SUBGRAPH_CATEGORY,
                    inputs = Subgraphs.portsOf(definitions, definitionId, "outputs").mapIndexed { index, (_, slotType) ->
                        InputDefinition(name = keys[index], type = slotType, isRequired = false, forceInput = true)
                    },
                    outputs = emptyList()
                )
            }
        }.toMap()

    /** The nodes inside definition [definitionId], those inside nested subgraphs included (instances not counted). */
    fun nodeCount(definitions: JSONObject, definitionId: String, depth: Int = 0): Int {
        if (depth > MAX_DEPTH) return 0
        val nodes = definitions.optJSONObject(definitionId)?.optJSONObject("nodes") ?: return 0
        return nodes.keys().asSequence().sumOf { id ->
            val type = nodes.optJSONObject(id)?.optString("class_type").orEmpty()
            if (definitions.has(type)) nodeCount(definitions, type, depth + 1) else 1
        }
    }

    /** How many instances of [definitionId] the workflow has: among [topLevelTypes] and inside its definitions. */
    fun usageCount(definitions: JSONObject, topLevelTypes: Collection<String>, definitionId: String): Int =
        topLevelTypes.count { it == definitionId } + definitions.keys().asSequence().sumOf { id ->
            val nodes = definitions.optJSONObject(id)?.optJSONObject("nodes") ?: return@sumOf 0
            nodes.keys().asSequence().count { nodes.optJSONObject(it)?.optString("class_type") == definitionId }
        }

    /** A definition's name ([definitionId] when it has none). */
    fun nameOf(definitions: JSONObject, definitionId: String): String =
        definitions.optJSONObject(definitionId)?.optString("name")?.takeIf { it.isNotEmpty() } ?: definitionId

    /**
     * How the values an instance shows from inside are labelled (decided by the owner over spec §19.1): a primitive
     * node's value by that node's title, any other by the input's name; labels that repeat get " – " and the node's
     * title. [keys] are the instance's input keys; only paths get a label.
     */
    fun promotedLabels(definitions: JSONObject, definitionId: String, keys: Collection<String>): Map<String, String> {
        data class Inner(val key: String, val label: String, val nodeTitle: String)
        val inner = keys.filter(Subgraphs::isPromotedKey).mapNotNull { key ->
            var currentDefinition = definitionId
            var rest = key
            var depth = 0
            while (depth++ < MAX_DEPTH) {
                val nodeId = rest.substringBefore('/')
                val node = definitions.optJSONObject(currentDefinition)?.optJSONObject("nodes")?.optJSONObject(nodeId) ?: return@mapNotNull null
                val type = node.optString("class_type")
                val after = rest.substringAfter('/')
                if (definitions.has(type) && Subgraphs.isPromotedKey(after)) {
                    currentDefinition = type
                    rest = after
                    continue
                }
                val title = node.optJSONObject("_meta")?.optString("title")?.takeIf { it.isNotEmpty() }
                    ?: (if (definitions.has(type)) nameOf(definitions, type) else type)
                val primitive = type.startsWith("Primitive") && !definitions.has(type)
                return@mapNotNull Inner(key, if (primitive) title else after, title)
            }
            null
        }
        val repeated = inner.groupBy { it.label }.filterValues { it.size > 1 }.keys
        return inner.associate { it.key to if (it.label in repeated) "${it.label} – ${it.nodeTitle}" else it.label }
    }
}
