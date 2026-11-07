package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.getEffectiveDefault
import sh.hnet.comfychair.workflow.isWidget

/**
 * Converts a workflow in ComfyUI's UI format (what its "Save" and "Export" write) to ComfyChair's format: nodes keyed
 * by ID with `class_type` and named `inputs` (values, or `[sourceNodeId, outputIndex]` for links), groups with their
 * member nodes and notes.
 *
 * What runs is what ComfyUI would send to the server for the same file:
 * - the frontend's own nodes leave: notes become notes, reroutes pass their link straight through, a primitive node
 *   puts its value into every widget it feeds;
 * - subgraphs (definitions.subgraphs, nested to any depth) are flattened: a link into a subgraph's input comes from
 *   whatever feeds the instance's input of that name, a link from a subgraph instance comes from the node inside that
 *   feeds the subgraph's output, and values promoted to an instance fill the widgets inside;
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

    /** A subgraph's input or output port. */
    private data class Port(val name: String, val type: String)

    /** One level of the workflow: the top level, or a subgraph definition. */
    private class Graph(
        val nodes: List<JSONObject>,
        val links: Map<Int, Link>,
        val inputs: List<Port> = emptyList(),
        val outputs: List<Port> = emptyList(),
        val inputNodeId: Int = SUBGRAPH_INPUT_NODE_ID,
        val outputNodeId: Int = SUBGRAPH_OUTPUT_NODE_ID,
        val name: String = ""
    ) {
        val nodeById: Map<Int, JSONObject> = nodes.associateBy { it.optInt("id", Int.MIN_VALUE) }
    }

    /**
     * A graph level as instantiated: the top level, or one subgraph instance (a subgraph used twice is two scopes).
     *
     * @param promoted Values the instance promotes to its widgets, by (node inside, widget name)
     * @param inputValues Values of the subgraph's inputs that no link feeds, by input name
     */
    private inner class Scope(
        val graph: Graph,
        val parent: Scope?,
        val instance: JSONObject?,
        val promoted: Map<Pair<Int, String>, Any>,
        val inputValues: Map<String, Any>
    ) {
        val flatIds = mutableMapOf<Int, String>()
        val children = mutableMapOf<Int, Scope>()

        /** The mode the instances around this level impose: muted or bypassed instances take their insides along. */
        val inheritedMode: Int = generateSequence(this) { it.parent }
            .mapNotNull { it.instance?.optInt("mode", 0)?.takeIf { mode -> mode != 0 } }.firstOrNull() ?: 0
    }

    /** Where an input's value comes from, once the frontend's nodes and subgraph boundaries are gone. */
    private sealed interface Source {
        data class Output(val nodeId: String, val slot: Int) : Source
        data class Value(val value: Any) : Source
    }

    private lateinit var subgraphs: Map<String, Graph>
    private var nextNodeId = 1

    companion object {
        private const val TAG = "LiteGraphConverter"

        private const val SUBGRAPH_INPUT_NODE_ID = -10
        private const val SUBGRAPH_OUTPUT_NODE_ID = -20

        // IDs of the groups made for subgraph instances, apart from the file's own groups
        private const val SUBGRAPH_GROUP_ID_BASE = 10000

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

        // Link chains through reroutes and subgraphs longer than this are taken as a loop in a broken file
        private const val MAX_RESOLVE_DEPTH = 256

        // The subgraph types used by widgets promoted from a subgraph without a list of them (older files)
        private val VALUE_PORT_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO", "ENUM")
    }

    /**
     * Convert a workflow in ComfyUI's UI format to ComfyChair's format.
     */
    fun convert(liteGraphJson: JSONObject): ConversionResult {
        val warnings = mutableListOf<String>()
        val topNodes = liteGraphJson.optJSONArray("nodes").objects()
        DebugLogger.i(TAG, "Converting UI workflow: ${topNodes.size} nodes")

        subgraphs = parseSubgraphs(liteGraphJson)
        if (subgraphs.isNotEmpty()) DebugLogger.i(TAG, "Found ${subgraphs.size} subgraph definitions")
        val top = Graph(topNodes, parseLinks(liteGraphJson.optJSONArray("links")))
        // Nodes inside subgraphs get IDs after the top level's, which keep their own
        nextNodeId = (topNodes.maxOfOrNull { it.optInt("id", 0) } ?: 0) + 1
        val scope = Scope(top, parent = null, instance = null, promoted = emptyMap(), inputValues = emptyMap())

        val apiNodes = JSONObject()
        val notes = JSONArray()
        val groups = JSONArray()
        // Top-level nodes and notes by position, for the groups drawn around them
        val positions = mutableMapOf<String, FloatArray>()

        for (node in topNodes) {
            val id = node.optInt("id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: continue
            val type = node.optString("type", "")
            if (type in subgraphs) {
                // A subgraph instance: its nodes, in a group named after it (the instance's title, else the subgraph's)
                val members = mutableListOf<String>()
                emitScope(childScope(scope, node), apiNodes, notes, members, warnings)
                if (members.isNotEmpty()) {
                    groups.put(JSONObject().apply {
                        put("id", SUBGRAPH_GROUP_ID_BASE + groups.length())
                        put("title", node.optString("title", "").ifEmpty { subgraphs.getValue(type).name })
                        put("member_nodes", JSONArray(members))
                    })
                }
                continue
            }
            node.optJSONArray("pos")?.let { pos ->
                val size = node.optJSONArray("size")
                positions[id.toString()] = floatArrayOf(
                    pos.optDouble(0, 0.0).toFloat(), pos.optDouble(1, 0.0).toFloat(),
                    size?.optDouble(0, 200.0)?.toFloat() ?: 200f, size?.optDouble(1, 100.0)?.toFloat() ?: 100f
                )
            }
            emitNode(scope, node, apiNodes, notes, members = null, warnings)
        }

        convertGroups(liteGraphJson.optJSONArray("groups"), positions).let { converted ->
            for (i in 0 until converted.length()) groups.put(converted.get(i))
        }

        val result = JSONObject().apply {
            put("nodes", apiNodes)
            if (groups.length() > 0) put("groups", groups)
            if (notes.length() > 0) put("notes", notes)
        }
        DebugLogger.i(TAG, "Conversion complete: ${apiNodes.length()} nodes, ${groups.length()} groups, ${notes.length()} notes")
        if (warnings.isNotEmpty()) DebugLogger.w(TAG, "Conversion warnings: ${warnings.size}")
        return ConversionResult(result.toString(2), warnings)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------------------------------

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

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
            fun ports(key: String) = definition.optJSONArray(key).objects()
                .map { Port(it.optString("name", ""), it.optString("type", "*")) }
            val graph = Graph(
                nodes = definition.optJSONArray("nodes").objects(),
                links = parseLinks(definition.optJSONArray("links")),
                inputs = ports("inputs"),
                outputs = ports("outputs"),
                inputNodeId = definition.optJSONObject("inputNode")?.optInt("id", SUBGRAPH_INPUT_NODE_ID) ?: SUBGRAPH_INPUT_NODE_ID,
                outputNodeId = definition.optJSONObject("outputNode")?.optInt("id", SUBGRAPH_OUTPUT_NODE_ID) ?: SUBGRAPH_OUTPUT_NODE_ID,
                name = definition.optString("name", "Subgraph")
            )
            DebugLogger.d(TAG, "Parsed subgraph ${Obfuscator.title(graph.name)}: ${graph.nodes.size} nodes, ${graph.links.size} links")
            id to graph
        }.toMap()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Subgraph scopes
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The scope of a subgraph instance in [scope]. Values promoted to the instance are listed in its
     * `properties.proxyWidgets` as (node inside, widget) in the order of its `widgets_values`; older files have no such
     * list, and then the values follow the subgraph's inputs that are values. Values the level around promotes to this
     * instance count as values of its inputs of those names.
     */
    private fun childScope(scope: Scope, instance: JSONObject): Scope {
        val instanceId = instance.optInt("id")
        scope.children[instanceId]?.let { return it }
        val graph = subgraphs.getValue(instance.optString("type"))
        val values = instance.optJSONArray("widgets_values")
        val proxies = instance.optJSONObject("properties")?.optJSONArray("proxyWidgets")

        val promoted = mutableMapOf<Pair<Int, String>, Any>()
        val inputValues = mutableMapOf<String, Any>()
        if (proxies != null) {
            for (i in 0 until proxies.length()) {
                val proxy = proxies.optJSONArray(i) ?: continue
                val nodeId = proxy.optString(0).toIntOrNull() ?: continue
                val widget = proxy.optString(1, "")
                if (widget.isEmpty() || widget.startsWith(PROMOTED_PREVIEW_PREFIX)) continue
                val value = values?.opt(i)?.takeIf { it != JSONObject.NULL } ?: continue
                promoted[nodeId to widget] = value
            }
        } else if (values != null) {
            graph.inputs.filter { it.type.uppercase() in VALUE_PORT_TYPES }.forEachIndexed { i, port ->
                values.opt(i)?.takeIf { it != JSONObject.NULL }?.let { inputValues[port.name] = it }
            }
        }
        scope.promoted.forEach { (key, value) -> if (key.first == instanceId) inputValues[key.second] = value }

        return Scope(graph, scope, instance, promoted, inputValues).also { scope.children[instanceId] = it }
    }

    /** A node's ID in the converted workflow: the top level keeps its own, nodes inside subgraphs get new ones. */
    private fun flatId(scope: Scope, nodeId: Int): String = scope.flatIds.getOrPut(nodeId) {
        if (scope.parent == null) nodeId.toString() else (nextNodeId++).toString()
    }

    /**
     * Where output [slot] of node [nodeId] in [scope] really comes from: through reroutes, out of subgraph instances,
     * into the level around a subgraph's input, to a primitive node's value. Null when nothing feeds it.
     */
    private fun resolve(scope: Scope, nodeId: Int, slot: Int, depth: Int = 0): Source? {
        if (depth > MAX_RESOLVE_DEPTH) return null
        val graph = scope.graph

        // A subgraph's input: what feeds the instance's input of that name, else the value given for it
        if (scope.parent != null && nodeId == graph.inputNodeId) {
            val port = graph.inputs.getOrNull(slot) ?: return null
            val instanceInput = scope.instance?.optJSONArray("inputs").objects().find { it.optString("name") == port.name }
            val link = instanceInput?.linkId()?.let { scope.parent.graph.links[it] }
            if (link != null) return resolve(scope.parent, link.originId, link.originSlot, depth + 1)
            return scope.inputValues[port.name]?.let { Source.Value(it) }
        }

        val node = graph.nodeById[nodeId] ?: return null
        val type = node.optString("type", "")
        return when {
            type in REROUTE_NODE_TYPES -> {
                val link = node.optJSONArray("inputs").objects().firstOrNull()?.linkId()?.let { graph.links[it] }
                link?.let { resolve(scope, it.originId, it.originSlot, depth + 1) }
            }
            type == PRIMITIVE_NODE_TYPE ->
                node.optJSONArray("widgets_values")?.opt(0)?.takeIf { it != JSONObject.NULL }?.let { Source.Value(it) }
            type in subgraphs -> {
                val child = childScope(scope, node)
                val link = child.graph.links.values.find { it.targetId == child.graph.outputNodeId && it.targetSlot == slot }
                link?.let { resolve(child, it.originId, it.originSlot, depth + 1) }
            }
            else -> Source.Output(flatId(scope, nodeId), slot)
        }
    }

    private fun JSONObject.linkId(): Int? = if (isNull("link")) null else optInt("link", -1).takeIf { it >= 0 }

    // ---------------------------------------------------------------------------------------------------------------
    // Emitting
    // ---------------------------------------------------------------------------------------------------------------

    /** Every node of a subgraph instance (nested ones too); their IDs are added to [members]. */
    private fun emitScope(
        scope: Scope,
        apiNodes: JSONObject,
        notes: JSONArray,
        members: MutableList<String>,
        warnings: MutableList<String>
    ) {
        for (node in scope.graph.nodes) {
            val type = node.optString("type", "")
            if (type in subgraphs) {
                emitScope(childScope(scope, node), apiNodes, notes, members, warnings)
            } else {
                emitNode(scope, node, apiNodes, notes, members, warnings)
            }
        }
    }

    /** One node of a level: a note, nothing (the frontend's reroutes and primitives), or a node that runs. */
    private fun emitNode(
        scope: Scope,
        node: JSONObject,
        apiNodes: JSONObject,
        notes: JSONArray,
        members: MutableList<String>?,
        warnings: MutableList<String>
    ) {
        val nodeId = node.optInt("id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return
        val type = node.optString("type", "").ifEmpty { return }
        when (type) {
            in NOTE_NODE_TYPES -> {
                extractNote(node, if (scope.parent == null) nodeId else nextNodeId++)?.let { notes.put(it) }
                return
            }
            in REROUTE_NODE_TYPES, PRIMITIVE_NODE_TYPE -> return
        }
        val id = flatId(scope, nodeId)
        val inputs = JSONObject()

        // 1. Widget values: the node's own, then those promoted to its instance
        val definition = getNodeDefinition(type)
        if (definition != null) {
            mapWidgetValues(node, definition.inputs, inputs)
        } else if (node.has("widgets_values")) {
            DebugLogger.w(TAG, "Node #$nodeId ($type): no definition found, cannot map widget values")
            warnings.add("Node \"$type\" (#$nodeId): inputs could not be fully mapped")
        }
        scope.promoted.forEach { (key, value) -> if (key.first == nodeId) inputs.put(key.second, value) }

        // 2. Links, resolved to the node or value that really feeds them (a widget linked to nothing keeps its value)
        for (input in node.optJSONArray("inputs").objects()) {
            val name = input.optString("name", "").ifEmpty { continue }
            val link = input.linkId()?.let { scope.graph.links[it] } ?: continue
            when (val source = resolve(scope, link.originId, link.originSlot)) {
                is Source.Output -> inputs.put(name, JSONArray().put(source.nodeId).put(source.slot))
                is Source.Value -> inputs.put(name, source.value)
                null -> Unit
            }
        }

        apiNodes.put(id, JSONObject().apply {
            put("class_type", type)
            put("inputs", inputs)
            // 0 runs, 2 is muted, 4 bypassed; a muted or bypassed instance takes the nodes inside along
            val mode = node.optInt("mode", 0).takeIf { it != 0 } ?: scope.inheritedMode
            if (mode != 0) put("mode", mode)
            val title = node.optString("title", "")
            if (title.isNotEmpty() && title != type) put("_meta", JSONObject().put("title", title))
        })
        members?.add(id)
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
