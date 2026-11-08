package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.Subgraphs
import sh.hnet.comfychair.workflow.objects
import sh.hnet.comfychair.workflow.getEffectiveDefault
import sh.hnet.comfychair.workflow.isWidget

/**
 * Converts a workflow in ComfyUI's UI format (what its "Save" and "Export" write) to ComfyChair's format: nodes keyed
 * by ID with `class_type` and named `inputs` (values, or `[sourceNodeId, outputIndex]` for links), groups with their
 * member nodes and notes, subgraphs as definitions used by instance nodes ([Subgraphs]).
 *
 * What runs is what ComfyUI would send to the server for the same file:
 * - the frontend's own nodes leave: notes become notes, reroutes pass their link straight through, a primitive node
 *   puts its value into every widget it feeds;
 * - subgraphs (definitions.subgraphs, nested to any depth) stay subgraphs: a definition's links from its input node
 *   become the values each input feeds, its output node's links the sources of its outputs; an instance's inputs are
 *   matched to the subgraph's by name; the values of the subgraph's inputs the file gives for it (`proxyWidgets` entries
 *   "-1", or in older files the values of the subgraph's value inputs) are its own, the values it promotes from inside
 *   are kept as its promoted list, the values the file gives for them go into the definition;
 * - widget values are assigned to inputs in the frontend's widget order (see [mapWidgetValues]).
 *
 * The format is described in `comfyui-format-research.md` (project notes); nothing here is taken from ComfyUI's code.
 */
class LiteGraphConverter(
    private val getNodeDefinition: (classType: String) -> NodeTypeDefinition?
) {

    data class ConversionResult(
        val jsonContent: String,
        val warnings: List<String>
    )

    /** A link of a graph level; the top level saves them as arrays, subgraphs as objects. */
    private data class Link(
        val id: Int,
        val originId: Int,
        val originSlot: Int,
        val targetId: Int,
        val targetSlot: Int
    )

    /**
     * A subgraph's input or output port: [name] as the file has it (instances match their inputs by it), [key] as
     * ComfyChair names it (the same, but never empty: a port the file leaves unnamed gets its label or "input N").
     */
    private data class Port(val name: String, val type: String, val key: String = name)

    /** One level of the workflow: the top level, or a subgraph definition. */
    private class Graph(
        val nodes: List<JSONObject>,
        val links: Map<Int, Link>,
        val inputs: List<Port> = emptyList(),
        val outputs: List<Port> = emptyList(),
        val inputNodeId: Int = SUBGRAPH_INPUT_NODE_ID,
        val outputNodeId: Int = SUBGRAPH_OUTPUT_NODE_ID,
        val name: String = "",
        val isSubgraph: Boolean = false,
        val groups: JSONArray? = null
    ) {
        val nodeById: Map<Int, JSONObject> = nodes.associateBy { it.optInt("id", Int.MIN_VALUE) }
    }

    /** Where a link's value comes from within its level, once the frontend's nodes are gone. */
    private sealed interface Source {
        data class Output(val nodeId: String, val slot: Int) : Source
        data class Value(val value: Any) : Source
        /** One of the subgraph's inputs (by index): the instance decides */
        data class SubgraphInput(val index: Int) : Source
    }

    /**
     * Values an instance gives for its subgraph: by input name its own (kept on the instance), by promoted path the
     * definition's (written into it).
     */
    private data class InstanceValues(val instance: JSONObject, val definitionId: String, val values: List<Pair<String, Any>>)

    private lateinit var subgraphs: Map<String, Graph>
    // Each instance converted, with the UI node it came from, for its promoted list once all definitions are converted
    private val instances = mutableListOf<Pair<JSONObject, JSONObject>>()
    private val instanceValues = mutableListOf<InstanceValues>()

    companion object {
        private const val TAG = "LiteGraphConverter"

        private const val SUBGRAPH_INPUT_NODE_ID = -10
        private const val SUBGRAPH_OUTPUT_NODE_ID = -20

        // A promoted entry naming the subgraph's own input instead of a node inside
        private const val PROMOTED_SUBGRAPH_INPUT = -1

        // The frontend's notes, extracted to the notes list
        private val NOTE_NODE_TYPES = setOf("Note", "MarkdownNote", "workflow/note")

        // The frontend's reroutes: a link passes straight through
        private val REROUTE_NODE_TYPES = setOf("Reroute", "ReroutePrimitive")

        // The frontend's primitive node: one value for every widget its output feeds
        private const val PRIMITIVE_NODE_TYPE = "PrimitiveNode"

        // Values of the frontend's "control after generate" widget, saved after the widget it controls
        private val CONTROL_WIDGET_VALUES = setOf("fixed", "increment", "decrement", "randomize")

        // Inputs followed by that widget in files saved before servers flagged it
        private val SEED_INPUT_NAMES = setOf("seed", "noise_seed")

        // Values an upload button may save after its widget
        private val UPLOAD_WIDGET_VALUES = setOf("image", "video", "audio", "file", "upload")

        // Promoted entries that aren't values (an instance's image preview)
        private const val PROMOTED_PREVIEW_PREFIX = "$$"

        // Link chains through reroutes longer than this are taken as a loop in a broken file
        private const val MAX_RESOLVE_DEPTH = 256

        // Nested subgraphs deeper than this are taken as a definition containing itself
        private const val MAX_NESTING = 32

        // The subgraph types used by widgets promoted from a subgraph without a list of them (older files)
        private val VALUE_PORT_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO", "ENUM")
    }

    /**
     * Convert a workflow in ComfyUI's UI format to ComfyChair's format.
     */
    fun convert(liteGraphJson: JSONObject): ConversionResult {
        val warnings = mutableListOf<String>()
        instances.clear()
        instanceValues.clear()
        val topNodes = liteGraphJson.optJSONArray("nodes").objects()
        DebugLogger.i(TAG, "Converting UI workflow: ${topNodes.size} nodes")

        subgraphs = parseSubgraphs(liteGraphJson)
        if (subgraphs.isNotEmpty()) DebugLogger.i(TAG, "Found ${subgraphs.size} subgraph definitions")

        val definitions = JSONObject()
        subgraphs.forEach { (id, graph) -> definitions.put(id, convertDefinition(graph, warnings)) }

        val top = Graph(topNodes, parseLinks(liteGraphJson.optJSONArray("links")))
        val apiNodes = JSONObject()
        val notes = JSONArray()
        emitLevel(top, apiNodes, notes, targets = null, warnings)

        // Values instances give: their inputs' their own, promoted ones into their definitions; then each instance
        // gets the list of values it shows
        instanceValues.forEach { (instance, definitionId, values) ->
            values.forEach { (key, value) ->
                if (Subgraphs.isPromotedKey(key)) Subgraphs.setValueAt(definitions, definitionId, key, value)
                else instance.optJSONObject("inputs")?.let { inputs -> if (!inputs.has(key)) inputs.put(key, value) }
            }
        }
        instances.forEach { (instance, uiNode) -> setPromoted(instance, uiNode, definitions) }

        val groups = convertGroups(liteGraphJson.optJSONArray("groups"), positionsOf(top))
        val result = JSONObject().apply {
            put("nodes", apiNodes)
            if (groups.length() > 0) put("groups", groups)
            if (notes.length() > 0) put("notes", notes)
            if (definitions.length() > 0) put(Subgraphs.KEY, definitions)
        }
        // Definitions no instance uses are left out
        Subgraphs.absorb(result)
        DebugLogger.i(TAG, "Conversion complete: ${apiNodes.length()} nodes, ${groups.length()} groups, ${notes.length()} notes, " +
            "${result.optJSONObject(Subgraphs.KEY)?.length() ?: 0} subgraphs")
        if (warnings.isNotEmpty()) DebugLogger.w(TAG, "Conversion warnings: ${warnings.size}")
        return ConversionResult(result.toString(2), warnings)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------------------------


    /** Links of a level: arrays `[id, origin, originSlot, target, targetSlot, type]` or objects with those names. */
    private fun parseLinks(array: JSONArray?): Map<Int, Link> {
        val links = mutableMapOf<Int, Link>()
        if (array == null) return links
        for (i in 0 until array.length()) {
            val link = when (val entry = array.opt(i)) {
                is JSONArray -> if (entry.length() >= 5) Link(
                    entry.optInt(0, -1), entry.optInt(1), entry.optInt(2), entry.optInt(3), entry.optInt(4)
                ) else null
                is JSONObject -> Link(
                    entry.optInt("id", -1), entry.optInt("origin_id"), entry.optInt("origin_slot"),
                    entry.optInt("target_id"), entry.optInt("target_slot")
                )
                else -> null
            }
            if (link != null && link.id != -1) links[link.id] = link
        }
        return links
    }

    private fun parseSubgraphs(json: JSONObject): Map<String, Graph> {
        val definitions = json.optJSONObject("definitions")?.optJSONArray("subgraphs").objects()
        return definitions.mapNotNull { definition ->
            val id = definition.optString("id", "").ifEmpty { return@mapNotNull null }
            fun ports(key: String): List<Port> {
                val entries = definition.optJSONArray(key).objects()
                val taken = entries.map { it.optString("name", "") }.toMutableSet()
                return entries.mapIndexed { i, port ->
                    val name = port.optString("name", "")
                    val ownKey = name.ifEmpty {
                        port.optString("label", "").takeIf { it.isNotEmpty() && it !in taken }
                            ?: generateSequence(i + 1) { it + 1 }.map { n -> "${key.removeSuffix("s")}_$n" }.first { it !in taken }
                    }
                    taken.add(ownKey)
                    Port(name, port.optString("type", "*"), ownKey)
                }
            }
            val graph = Graph(
                nodes = definition.optJSONArray("nodes").objects(),
                links = parseLinks(definition.optJSONArray("links")),
                inputs = ports("inputs"),
                outputs = ports("outputs"),
                inputNodeId = definition.optJSONObject("inputNode")?.optInt("id", SUBGRAPH_INPUT_NODE_ID) ?: SUBGRAPH_INPUT_NODE_ID,
                outputNodeId = definition.optJSONObject("outputNode")?.optInt("id", SUBGRAPH_OUTPUT_NODE_ID) ?: SUBGRAPH_OUTPUT_NODE_ID,
                name = definition.optString("name", "Subgraph"),
                isSubgraph = true,
                groups = definition.optJSONArray("groups")
            )
            DebugLogger.d(TAG, "Parsed subgraph ${Obfuscator.title(graph.name)}: ${graph.nodes.size} nodes, ${graph.links.size} links")
            id to graph
        }.toMap()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Levels
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Where output [slot] of node [nodeId] in [graph] really comes from: through reroutes, to a primitive node's value,
     * to one of the subgraph's inputs. Null when nothing feeds it.
     */
    private fun resolve(graph: Graph, nodeId: Int, slot: Int, depth: Int = 0): Source? {
        if (depth > MAX_RESOLVE_DEPTH) return null
        if (graph.isSubgraph && nodeId == graph.inputNodeId) {
            return Source.SubgraphInput(slot).takeIf { slot in graph.inputs.indices }
        }
        val node = graph.nodeById[nodeId] ?: return null
        return when (node.optString("type", "")) {
            in REROUTE_NODE_TYPES -> {
                val link = node.optJSONArray("inputs").objects().firstOrNull()?.linkId()?.let { graph.links[it] }
                link?.let { resolve(graph, it.originId, it.originSlot, depth + 1) }
            }
            PRIMITIVE_NODE_TYPE ->
                node.optJSONArray("widgets_values")?.opt(0)?.takeIf { it != JSONObject.NULL }?.let { Source.Value(it) }
            else -> Source.Output(nodeId.toString(), slot)
        }
    }

    private fun JSONObject.linkId(): Int? = if (isNull("link")) null else optInt("link", -1).takeIf { it >= 0 }

    /** A subgraph definition in ComfyChair's format. */
    private fun convertDefinition(graph: Graph, warnings: MutableList<String>): JSONObject {
        val nodes = JSONObject()
        val notes = JSONArray()
        val targets = List(graph.inputs.size) { JSONArray() }
        emitLevel(graph, nodes, notes, targets, warnings)

        val outputs = JSONArray()
        graph.outputs.forEachIndexed { index, port ->
            val link = graph.links.values.find { it.targetId == graph.outputNodeId && it.targetSlot == index }
            outputs.put(JSONObject().apply {
                put("name", port.key)
                put("type", port.type)
                when (val source = link?.let { resolve(graph, it.originId, it.originSlot) }) {
                    is Source.Output -> put("source", JSONArray().put(source.nodeId).put(source.slot))
                    is Source.SubgraphInput -> put("input", source.index)
                    is Source.Value -> put("value", source.value)
                    null -> Unit
                }
            })
        }
        val groups = convertGroups(graph.groups, positionsOf(graph))
        return JSONObject().apply {
            put("name", graph.name)
            put("inputs", JSONArray().apply {
                graph.inputs.forEachIndexed { index, port ->
                    put(JSONObject().put("name", port.key).put("type", port.type).put("targets", targets[index]))
                }
            })
            put("outputs", outputs)
            put("nodes", nodes)
            if (groups.length() > 0) put("groups", groups)
            if (notes.length() > 0) put("notes", notes)
        }
    }

    /** Every node of a level; in a definition, the values its inputs feed are added to [targets] (by input index). */
    private fun emitLevel(
        graph: Graph,
        apiNodes: JSONObject,
        notes: JSONArray,
        targets: List<JSONArray>?,
        warnings: MutableList<String>
    ) {
        for (node in graph.nodes) emitNode(graph, node, apiNodes, notes, targets, warnings)
    }

    /** Positions of a level's nodes that run and of its subgraph instances, for the groups drawn around them. */
    private fun positionsOf(graph: Graph): Map<String, FloatArray> = graph.nodes.mapNotNull { node ->
        val type = node.optString("type", "")
        if (type in NOTE_NODE_TYPES || type in REROUTE_NODE_TYPES || type == PRIMITIVE_NODE_TYPE) return@mapNotNull null
        val id = node.optInt("id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return@mapNotNull null
        val pos = node.optJSONArray("pos") ?: return@mapNotNull null
        id.toString() to floatArrayOf(pos.optDouble(0, 0.0).toFloat(), pos.optDouble(1, 0.0).toFloat())
    }.toMap()

    /** One node of a level: a note, nothing (the frontend's reroutes and primitives), an instance or a node that runs. */
    private fun emitNode(
        graph: Graph,
        node: JSONObject,
        apiNodes: JSONObject,
        notes: JSONArray,
        targets: List<JSONArray>?,
        warnings: MutableList<String>
    ) {
        val nodeId = node.optInt("id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return
        val type = node.optString("type", "").ifEmpty { return }
        when (type) {
            in NOTE_NODE_TYPES -> {
                notes.put(extractNote(node, nodeId))
                return
            }
            in REROUTE_NODE_TYPES, PRIMITIVE_NODE_TYPE -> return
        }
        val id = nodeId.toString()
        val inputs = JSONObject()
        val subgraph = subgraphs[type]

        // 1. Widget values (an instance's values are placed once all definitions are converted, see [instanceValuesOf])
        if (subgraph == null) {
            val definition = getNodeDefinition(type)
            if (definition != null) {
                mapWidgetValues(node, definition.inputs, inputs)
            } else if (node.has("widgets_values")) {
                DebugLogger.w(TAG, "Node #$nodeId ($type): no definition found, cannot map widget values")
                warnings.add("Node \"$type\" (#$nodeId): inputs could not be fully mapped")
            }
        }

        // 2. Links, resolved to the node or value that really feeds them (a widget linked to nothing keeps its value);
        // a subgraph's input decides at each instance
        val linkedValues = mutableListOf<Pair<String, Any>>()
        for (input in node.optJSONArray("inputs").objects()) {
            val fileName = input.optString("name", "")
            // An instance's input by the subgraph's name for it (an unnamed port has one of ComfyChair's)
            val name = subgraph?.inputs?.find { it.name == fileName }?.key ?: fileName.ifEmpty { continue }
            val link = input.linkId()?.let { graph.links[it] } ?: continue
            when (val source = resolve(graph, link.originId, link.originSlot)) {
                is Source.Output -> inputs.put(name, JSONArray().put(source.nodeId).put(source.slot))
                is Source.Value -> if (subgraph != null) linkedValues.add(name to source.value) else inputs.put(name, source.value)
                // The node keeps its own value for an instance whose input gets nothing
                is Source.SubgraphInput -> targets?.getOrNull(source.index)?.put(JSONArray().put(id).put(name))
                null -> Unit
            }
        }

        val converted = JSONObject().apply {
            put("class_type", type)
            put("inputs", inputs)
            // 0 runs, 2 is muted, 4 bypassed (an instance's mode reaches its nodes when it runs)
            val mode = node.optInt("mode", 0)
            if (mode != 0) put("mode", mode)
            val title = node.optString("title", "").ifEmpty { subgraph?.name ?: "" }
            if (title.isNotEmpty() && title != type) put("_meta", JSONObject().put("title", title))
        }
        apiNodes.put(id, converted)

        if (subgraph != null) {
            instances.add(converted to node)
            val values = instanceValuesOf(node, subgraph) + linkedValues
            if (values.isNotEmpty()) instanceValues.add(InstanceValues(converted, type, values))
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Subgraph instances
    // ---------------------------------------------------------------------------------------------------------------

    /** A promoted entry: (node inside, widget name), the node [PROMOTED_SUBGRAPH_INPUT] for the subgraph's own input. */
    private fun promotedEntries(instance: JSONObject): List<Pair<Int, String>>? {
        val proxies = instance.optJSONObject("properties")?.optJSONArray("proxyWidgets") ?: return null
        return (0 until proxies.length()).mapNotNull { i ->
            val proxy = proxies.optJSONArray(i) ?: return@mapNotNull null
            val nodeId = proxy.optString(0).toIntOrNull() ?: return@mapNotNull null
            nodeId to proxy.optString(1, "")
        }
    }

    /**
     * Values an instance gives for its subgraph: listed with its promoted entries (`proxyWidgets`, in the order of
     * `widgets_values`; files saved by recent frontends leave the list empty, the values being inside), or in older
     * files the values of the subgraph's value inputs in order. Inputs linked from outside get no value.
     */
    private fun instanceValuesOf(instance: JSONObject, subgraph: Graph): List<Pair<String, Any>> {
        val values = instance.optJSONArray("widgets_values") ?: return emptyList()
        val linked = instance.optJSONArray("inputs").objects().filter { it.linkId() != null }
            .map { input -> subgraph.inputs.find { it.name == input.optString("name") }?.key ?: input.optString("name") }.toSet()
        val result = mutableListOf<Pair<String, Any>>()
        val entries = promotedEntries(instance)
        if (entries != null) {
            entries.forEachIndexed { i, (nodeId, widget) ->
                if (widget.isEmpty() || widget.startsWith(PROMOTED_PREVIEW_PREFIX)) return@forEachIndexed
                val value = values.opt(i)?.takeIf { it != JSONObject.NULL } ?: return@forEachIndexed
                val key = promotedKey(subgraph, nodeId, widget) ?: return@forEachIndexed
                if (key !in linked) result.add(key to value)
            }
        } else {
            subgraph.inputs.filter { it.type.uppercase() in VALUE_PORT_TYPES }.forEachIndexed { i, port ->
                val value = values.opt(i)?.takeIf { it != JSONObject.NULL } ?: return@forEachIndexed
                if (port.key !in linked) result.add(port.key to value)
            }
        }
        return result
    }

    /**
     * The path of a promoted widget in ComfyChair's format: "node/input" for a node inside, through a nested instance to
     * the input or promoted value of that name; the subgraph's own input by its name. Null when it names nothing.
     */
    private fun promotedKey(subgraph: Graph, nodeId: Int, widget: String, depth: Int = 0): String? {
        if (depth > MAX_NESTING || widget.isEmpty()) return null
        if (nodeId == PROMOTED_SUBGRAPH_INPUT) return subgraph.inputs.find { it.name == widget }?.key
        val node = subgraph.nodeById[nodeId] ?: return null
        val nested = subgraphs[node.optString("type", "")] ?: return "$nodeId/$widget"
        nested.inputs.find { it.name == widget }?.let { return "$nodeId/${it.key}" }
        val inner = promotedEntries(node)?.firstNotNullOfOrNull { (innerId, innerWidget) ->
            if (innerWidget == widget) promotedKey(nested, innerId, innerWidget, depth + 1) else null
        } ?: return null
        return "$nodeId/$inner"
    }

    /**
     * An instance's promoted list: the values it shows from inside, that are values once the definitions are converted
     * (a control widget or a link isn't). The subgraph's own inputs are shown anyway.
     */
    private fun setPromoted(instance: JSONObject, uiNode: JSONObject, definitions: JSONObject) {
        val definitionId = instance.optString("class_type")
        val subgraph = subgraphs[definitionId] ?: return
        val keys = promotedEntries(uiNode).orEmpty()
            .filter { (nodeId, widget) -> nodeId != PROMOTED_SUBGRAPH_INPUT && !widget.startsWith(PROMOTED_PREVIEW_PREFIX) }
            .mapNotNull { (nodeId, widget) -> promotedKey(subgraph, nodeId, widget) }
            .filter { Subgraphs.isPromotedKey(it) && Subgraphs.valueAt(definitions, definitionId, it) != null }
            .distinct()
        if (keys.isNotEmpty()) instance.put(Subgraphs.PROMOTED_KEY, JSONArray(keys))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Widget values
    // ---------------------------------------------------------------------------------------------------------------

    /** Inputs the frontend saves a value for: widgets, and widgets without a socket (drawn by the frontend only). */
    private val InputDefinition.savesValue: Boolean get() = isWidget || socketless

    /**
     * Assign a node's widget values to its inputs, as ComfyUI's frontend lays out its widgets.
     *
     * The frontend's `widgets_values_named` (when it has as many entries as the list) names the list's positions;
     * `widgets_values` as an object (as some extensions save them) is taken by name. A list follows the widget order: the node's inputs that save a value, in definition order, a
     * dynamic combo's chosen option's widgets right after it; the frontend's "control after generate" value after the
     * widget it controls, an upload button's value after its widget. Values the frontend adds for its own widgets
     * (buttons, previews) are passed over where a number or a switch is due; a widget the file has no value for gets
     * the node's default, as in ComfyUI.
     */
    private fun mapWidgetValues(node: JSONObject, definitions: List<InputDefinition>, inputs: JSONObject) {
        // The frontend's record by name lists its widgets in their order, also those of types only it draws (boxes,
        // colours…), but it isn't always up to date: the list is what ComfyUI loads, so the names only lay it out
        val named = node.optJSONObject("widgets_values_named")
        val list = node.optJSONArray("widgets_values")
        if (named != null && (list == null || list.length() == named.length())) {
            val laidOut = if (list == null) named else JSONObject().also { byName ->
                named.keys().asSequence().forEachIndexed { i, key -> byName.put(key, list.opt(i)) }
            }
            mapNamedWidgetValues(laidOut, definitions, inputs, prefix = "", anyInput = true)
            return
        }
        // An extension's object by name may hold other keys (sockets, previews): only the definition's widgets count
        node.optJSONObject("widgets_values")?.let { named ->
            mapNamedWidgetValues(named, definitions, inputs, prefix = "", anyInput = false)
            return
        }
        val values = node.optJSONArray("widgets_values") ?: JSONArray()
        var index = 0
        // Values beyond the widgets the definition has: the frontend's own, which may be skipped
        var spare = (values.length() - widgetSlotCount(definitions)).coerceAtLeast(0)

        fun mapWidgets(defs: List<InputDefinition>, prefix: String) {
            for (definition in defs) {
                if (!definition.savesValue) continue
                val name = prefix + definition.name
                // A control widget the server lists itself (rare): its value isn't an input
                if (definition.name == "control_after_generate") {
                    if (index < values.length()) index++
                    continue
                }
                // A value the frontend added for its own widget where this one's number or switch is due
                while (spare > 0 && index < values.length() && !fitsWidget(definition, values.opt(index))) {
                    index++
                    spare--
                }
                val saved = index < values.length()
                val value = if (saved) values.opt(index).takeIf { it != JSONObject.NULL } ?: definition.getEffectiveDefault()
                else definition.getEffectiveDefault()
                inputs.put(name, value)
                if (saved) index++

                definition.dynamicOptions?.get(value as? String)?.let { mapWidgets(it, "$name.") }

                val next = values.opt(index)
                if ((definition.controlAfterGenerate || definition.name in SEED_INPUT_NAMES) &&
                    next is String && next in CONTROL_WIDGET_VALUES
                ) index++
                else if (definition.upload && next is String && next in UPLOAD_WIDGET_VALUES) index++
            }
        }
        mapWidgets(definitions, prefix = "")
    }

    /** The values a list holds for these widgets, without dynamic options (an estimate of the frontend's own extras). */
    private fun widgetSlotCount(definitions: List<InputDefinition>): Int =
        definitions.filter { it.savesValue }.sumOf { 1 + (if (it.controlAfterGenerate) 1 else 0) }

    /** Whether a saved value can be this widget's: numbers and switches only take numbers and booleans. */
    private fun fitsWidget(definition: InputDefinition, value: Any?): Boolean = when (definition.type) {
        "INT", "FLOAT" -> value is Number || (value is String && value.toDoubleOrNull() != null)
        "BOOLEAN" -> value is Boolean
        else -> true
    }

    /**
     * Widget values saved by name: each widget of the definition (a dynamic combo's chosen option too), with the node's
     * default where the file has no value; with [anyInput], also any other input the file has a value for by name.
     */
    private fun mapNamedWidgetValues(
        values: JSONObject,
        defs: List<InputDefinition>,
        inputs: JSONObject,
        prefix: String,
        anyInput: Boolean
    ) {
        for (inputDef in defs) {
            val name = prefix + inputDef.name
            val savedValue = values.opt(name)?.takeIf { it != JSONObject.NULL }
            if (!inputDef.savesValue && !(anyInput && savedValue != null)) continue
            val value = savedValue ?: inputDef.getEffectiveDefault()
            inputs.put(name, value)
            inputDef.dynamicOptions?.get(value as? String)?.let { optionInputs ->
                mapNamedWidgetValues(values, optionInputs, inputs, "$name.", anyInput)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Notes and groups
    // ---------------------------------------------------------------------------------------------------------------

    private fun extractNote(node: JSONObject, id: Int): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", node.optString("title", "Note"))
        put("content", node.optJSONArray("widgets_values")?.optString(0, "") ?: "")
    }

    /**
     * Groups of the top level, with the nodes and notes inside their rectangles (by top-left corner, as ComfyUI places
     * a node in a group); empty groups are left out.
     */
    private fun convertGroups(groupsArray: JSONArray?, positions: Map<String, FloatArray>): JSONArray {
        val result = JSONArray()
        groupsArray.objects().forEachIndexed { i, group ->
            val bounding = group.optJSONArray("bounding")?.takeIf { it.length() >= 4 } ?: return@forEachIndexed
            val x = bounding.optDouble(0, 0.0).toFloat()
            val y = bounding.optDouble(1, 0.0).toFloat()
            val w = bounding.optDouble(2, 0.0).toFloat()
            val h = bounding.optDouble(3, 0.0).toFloat()
            val members = positions.filterValues { p -> p[0] >= x && p[1] >= y && p[0] <= x + w && p[1] <= y + h }.keys
            if (members.isEmpty()) return@forEachIndexed
            result.put(JSONObject().apply {
                put("id", group.optInt("id", i + 1))
                put("title", group.optString("title", "Group"))
                put("member_nodes", JSONArray(members.toList()))
            })
        }
        return result
    }
}
