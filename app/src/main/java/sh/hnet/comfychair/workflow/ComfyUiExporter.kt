package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.util.UUID

/**
 * Writes a stored workflow ([Subgraphs]: nodes, groups, notes, subgraph definitions; app placeholders already replaced
 * by values) as a document in ComfyUI's UI format (version 0.4), the format its "Save" writes and its frontend opens
 * with layout, groups, notes and subgraphs:
 * - nodes with their type, position and size (from ComfyChair's layout, scaled), mode, title, the inputs that are
 *   sockets or linked values, outputs, and `widgets_values` in the frontend's widget order (the inverse of
 *   `LiteGraphConverter`'s reading: values and lists in definition order, a dynamic combo's chosen option's widgets
 *   after it, the "control after generate" value after its widget, an upload button's value after its widget);
 * - links as `[id, origin, originSlot, target, targetSlot, type]`, the target slot being the input's place in its
 *   node's list;
 * - notes as Markdown notes, groups as rectangles around their members;
 * - subgraphs in `definitions.subgraphs` with input and output nodes (-10, -20) and links as objects; instances list
 *   the values they show in `properties.proxyWidgets` (values live inside, as recent frontends save them).
 *
 * The format is described in `comfyui-format-research.md` (project notes); nothing here is taken from ComfyUI's code.
 *
 * @param lookup The server's node definitions
 * @param layout Positions of a level's members in ComfyChair's units: (x, y, width, height) by node ID and by
 *        "note:N"; the level is a JSON object with `nodes`, `groups` and `notes` (the top level or a definition)
 */
class ComfyUiExporter(
    private val lookup: (String) -> NodeTypeDefinition?,
    private val layout: (level: JSONObject) -> Map<String, FloatArray>
) {
    companion object {
        private const val TAG = "ComfyUiExporter"
        const val FORMAT_VERSION = 0.4

        // ComfyChair's nodes are made for touch: ComfyUI's are about three quarters of their size
        private const val SCALE = 0.75f

        private const val INPUT_NODE_ID = -10
        private const val OUTPUT_NODE_ID = -20
        private const val GROUP_PADDING = 20f
        private const val GROUP_TITLE_HEIGHT = 46f
        private const val BOUNDARY_NODE_WIDTH = 140f
        private const val BOUNDARY_NODE_GAP = 120f

        // The value the frontend's "control after generate" widget is saved with: keep the value as it is
        private const val CONTROL_FIXED = "fixed"

        // Upload buttons that save a value after their widget (always "image"); others (audio, files) save none
        private val UPLOAD_KINDS_SAVING = setOf("image", "video")
        private const val UPLOAD_VALUE = "image"
        private const val CONTROL_WIDGET = "control_after_generate"
        private const val UPLOAD_WIDGET = "upload"

        // Inputs that hold sockets of their own ("name.slot"), listed by those
        private val CONTAINER_TYPES = setOf("COMFY_AUTOGROW_V3", "COMFY_DYNAMICGROUP_V3")

        private const val NOTE_TYPE = "MarkdownNote"

        // A socket of the type of whatever it's linked to
        private const val MATCH_TYPE = "COMFY_MATCHTYPE_V3"
        private val PRIMITIVE_TYPES = setOf("STRING", "INT", "FLOAT", "BOOLEAN")
    }

    private class Entry(val name: String, var type: String, val widget: Boolean)

    /** A level being written: the top level, or one definition. */
    private inner class Level(
        val source: JSONObject,
        val definitions: JSONObject?,
        val isDefinition: Boolean,
        val ports: List<JSONObject> = emptyList(),
        val outputPorts: List<JSONObject> = emptyList()
    ) {
        val nodes = source.optJSONObject("nodes") ?: JSONObject()
        // A definition laid out with the definitions, so instances nested in it are drawn as instances
        val positions = layout(if (isDefinition && definitions != null) JSONObject(source.toString()).put(Subgraphs.KEY, definitions) else source)
        val intIds = mutableMapOf<String, Int>()
        val noteIds = mutableMapOf<Int, Int>()
        val outNodes = mutableMapOf<String, JSONObject>()
        val entries = mutableMapOf<String, List<Entry>>()
        val links = JSONArray()
        var lastLinkId = 0
        var lastNodeId = 0

        init {
            // Node IDs: numbers keep theirs, anything else and the notes get the next free ones
            val numeric = nodes.keys().asSequence().mapNotNull { it.toIntOrNull()?.takeIf { n -> n > 0 } }.toSet()
            var next = (numeric.maxOrNull() ?: 0) + 1
            for (id in nodes.keys()) intIds[id] = id.toIntOrNull()?.takeIf { it > 0 && it in numeric } ?: next++
            notes().forEach { note -> noteIds[note.optInt("id")] = next++ }
            lastNodeId = next - 1
        }

        fun notes(): List<JSONObject> = source.optJSONArray("notes").objects()

        fun position(key: String): FloatArray = positions[key] ?: floatArrayOf(0f, 0f, 480f, 200f)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun isLink(value: Any?): Boolean =
        value is JSONArray && value.length() == 2 && value.opt(0) is String && value.opt(1) is Number

    /** Inputs the frontend saves a value for: widgets, and widgets without a socket. */
    private val InputDefinition.savesValue: Boolean get() = isWidget || socketless

    /** Export [root] (stored, placeholders replaced). */
    fun export(root: JSONObject): JSONObject {
        val stored = JSONObject(root.toString())
        val definitions = Subgraphs.definitionsOf(stored)
        definitions?.let { promoteNested(stored, it) }
        materializeSocketValues(stored.optJSONObject("nodes"), emptySet())
        definitions?.keys()?.forEach { id ->
            val definition = definitions.optJSONObject(id) ?: return@forEach
            // A value a subgraph's input feeds is given by the instance, not by a node inside
            val fed = definition.optJSONArray("inputs").objects().flatMap { port ->
                val targets = port.optJSONArray("targets") ?: JSONArray()
                (0 until targets.length()).mapNotNull { targets.optJSONArray(it)?.let { t -> t.optString(0) to t.optString(1) } }
            }.toSet()
            materializeSocketValues(definition.optJSONObject("nodes"), fed)
        }

        val top = Level(stored, definitions, isDefinition = false)
        val nodes = writeLevel(top)
        val document = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("revision", 0)
            put("last_node_id", top.lastNodeId)
            put("last_link_id", top.lastLinkId)
            put("nodes", nodes)
            put("links", top.links)
            put("groups", groupsOf(top))
            if (definitions != null) {
                put("definitions", JSONObject().put("subgraphs", JSONArray().apply {
                    for (id in definitions.keys()) put(writeDefinition(id, definitions.getJSONObject(id), definitions))
                }))
            }
            put("config", JSONObject())
            put("extra", JSONObject().put("ds", JSONObject().put("scale", 1).put("offset", JSONArray().put(0).put(0))))
            put("version", FORMAT_VERSION)
        }
        DebugLogger.i(TAG, "Exported ${nodes.length()} nodes, ${top.links.length()} links, ${definitions?.length() ?: 0} subgraphs")
        return document
    }

    /**
     * ComfyUI promotes a value through a nested instance by that instance's own widget: a path "3/20/x" on an instance
     * needs the instance 3 inside to show "20/x" first.
     */
    private fun promoteNested(root: JSONObject, definitions: JSONObject) {
        fun visit(nodes: JSONObject?, depth: Int) {
            if (nodes == null || depth > 32) return
            for (id in nodes.keys()) {
                val node = nodes.optJSONObject(id) ?: continue
                val definition = definitions.optJSONObject(node.optString("class_type")) ?: continue
                val promoted = node.optJSONArray(Subgraphs.PROMOTED_KEY) ?: continue
                for (i in 0 until promoted.length()) {
                    val path = promoted.optString(i)
                    val inner = path.substringBefore('/')
                    val rest = path.substringAfter('/')
                    if (Subgraphs.isPromotedKey(rest)) {
                        val innerRoot = JSONObject().put("nodes", definition.optJSONObject("nodes")).put(Subgraphs.KEY, definitions)
                        Subgraphs.promote(innerRoot, inner, rest)
                    }
                }
            }
            for (id in nodes.keys()) {
                val type = nodes.optJSONObject(id)?.optString("class_type") ?: continue
                definitions.optJSONObject(type)?.let { visit(it.optJSONObject("nodes"), depth + 1) }
            }
        }
        visit(root.optJSONObject("nodes"), 0)
    }

    /**
     * A value on an input ComfyUI only links (a switch's branch, an input forced to be a socket): it came from a
     * primitive node at import, and goes back as one of the server's primitive nodes linked to it.
     */
    private fun materializeSocketValues(nodes: JSONObject?, fedByPorts: Set<Pair<String, String>>) {
        if (nodes == null) return
        var next = 0
        for (id in nodes.keys().asSequence().toList()) {
            val node = nodes.optJSONObject(id) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val definition = lookup(node.optString("class_type")) ?: continue
            for (input in definition.inputs) {
                if (!hasValue(inputs, input.name) || input.isWidget || input.socketless || !takesPlainValue(input)) continue
                if ((id to input.name) in fedByPorts) continue
                val value = inputs.get(input.name)
                val primitive = primitiveFor(value) ?: continue
                var primitiveId: String
                do { primitiveId = "primitive_${next++}" } while (nodes.has(primitiveId))
                nodes.put(primitiveId, JSONObject().put("class_type", primitive).put("inputs", JSONObject().put("value", value)))
                inputs.put(input.name, JSONArray().put(primitiveId).put(0))
            }
        }
    }

    /** A socket a plain value can feed: of a matching type, or forced to be a socket of a value type. */
    private fun takesPlainValue(input: InputDefinition): Boolean =
        input.type == MATCH_TYPE || input.type == "*" ||
            (input.forceInput && input.type.split(',').any { it.trim() in PRIMITIVE_TYPES })

    private fun primitiveFor(value: Any): String? {
        val type = when (value) {
            is Boolean -> "PrimitiveBoolean"
            is Int, is Long -> "PrimitiveInt"
            is Number -> if (value.toDouble() % 1.0 == 0.0 && value.toDouble() in -1e15..1e15) "PrimitiveInt" else "PrimitiveFloat"
            is String -> if ('\n' in value) "PrimitiveStringMultiline" else "PrimitiveString"
            else -> return null
        }
        return type.takeIf { lookup(it) != null }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Nodes and links
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A node's input list as the frontend saves it: its sockets (a dynamic group's or a growing list's by their own
     * slots "name.slot", a dynamic combo's chosen option's after it), and its values that take a link.
     */
    private fun entriesOf(level: Level, node: JSONObject, fedByPorts: Set<String>): List<Entry> {
        val inputs = node.optJSONObject("inputs") ?: JSONObject()
        val type = node.optString("class_type")
        level.definitions?.optJSONObject(type)?.let { definition ->
            // An instance: the subgraph's inputs; those holding values are widgets too
            return definition.optJSONArray("inputs").objects().map { port ->
                val name = port.optString("name")
                Entry(name, port.optString("type", "*"), Subgraphs.valueAt(level.definitions, type, name) != null)
            }
        }
        val linked = (inputs.keys().asSequence().filter { isLink(inputs.opt(it)) } + fedByPorts.asSequence()).distinct().toList()
        val result = mutableListOf<Entry>()
        val listed = mutableSetOf<String>()
        fun walk(defs: List<InputDefinition>, prefix: String) {
            for (input in defs) {
                val name = prefix + input.name
                when {
                    input.type in CONTAINER_TYPES -> linked.filter { it.startsWith("$name.") && it !in listed }.forEach {
                        result.add(Entry(it, "*", widget = false))
                        listed.add(it)
                    }
                    input.savesValue || hasValue(inputs, name) -> if (name in linked) {
                        result.add(Entry(name, input.type, widget = true))
                        listed.add(name)
                    }
                    else -> {
                        result.add(Entry(name, input.type, widget = false))
                        listed.add(name)
                    }
                }
                input.dynamicOptions?.get(inputs.opt(name) as? String)?.let { walk(it, "$name.") }
            }
        }
        lookup(type)?.let { walk(it.inputs, prefix = "") }
        // Links to inputs the definition doesn't list (a node the server lacks, an older dynamic input)
        linked.filter { it !in listed }.forEach { result.add(Entry(it, "*", widget = false)) }
        return result
    }

    private fun hasValue(inputs: JSONObject, name: String): Boolean =
        inputs.has(name) && inputs.opt(name) != JSONObject.NULL && !isLink(inputs.opt(name))

    private fun outputsOf(level: Level, node: JSONObject): List<OutputSlot> {
        val type = node.optString("class_type")
        level.definitions?.optJSONObject(type)?.let { definition ->
            return definition.optJSONArray("outputs").objects().map { OutputSlot(it.optString("type", "*"), it.optString("name")) }
        }
        lookup(type)?.let { return it.outputs }
        // Unknown: as many as its links use
        val used = level.nodes.keys().asSequence().flatMap { id ->
            val inputs = level.nodes.optJSONObject(id)?.optJSONObject("inputs") ?: return@flatMap emptySequence()
            inputs.keys().asSequence().map { inputs.opt(it) }.filter { isLink(it) && (it as JSONArray).optString(0) == node.optString("__id") }
        }.map { (it as JSONArray).optInt(1) }.maxOrNull() ?: -1
        return (0..used).map { OutputSlot("*", "output_$it") }
    }

    private fun writeLevel(level: Level): JSONArray {
        // Values of a definition's nodes fed by its inputs: listed as linked
        val fedByPorts = mutableMapOf<String, MutableSet<String>>()
        level.ports.forEach { port ->
            port.optJSONArray("targets")?.let { targets ->
                for (i in 0 until targets.length()) {
                    val t = targets.optJSONArray(i) ?: continue
                    fedByPorts.getOrPut(t.optString(0)) { mutableSetOf() }.add(t.optString(1))
                }
            }
        }

        // 1. The nodes, without links yet
        var order = 0
        for (id in level.nodes.keys()) {
            val node = level.nodes.optJSONObject(id) ?: continue
            node.put("__id", id)
            val entries = entriesOf(level, node, fedByPorts[id].orEmpty())
            level.entries[id] = entries
            val (x, y, w, h) = level.position(id).toList()
            val type = node.optString("class_type")
            val isInstance = level.definitions?.has(type) == true
            level.outNodes[id] = JSONObject().apply {
                put("id", level.intIds.getValue(id))
                put("type", type)
                put("pos", JSONArray().put((x * SCALE).toDouble()).put((y * SCALE).toDouble()))
                put("size", JSONArray().put((w * SCALE).toDouble()).put((h * SCALE).toDouble()))
                put("flags", JSONObject())
                put("order", order++)
                put("mode", node.optInt("mode", 0))
                put("inputs", JSONArray().apply {
                    entries.forEach { entry ->
                        put(JSONObject().apply {
                            put("name", entry.name)
                            put("type", entry.type)
                            if (entry.widget) put("widget", JSONObject().put("name", entry.name))
                            put("link", JSONObject.NULL)
                        })
                    }
                })
                put("outputs", JSONArray().apply {
                    outputsOf(level, node).forEach { output ->
                        put(JSONObject().put("name", output.name).put("type", output.type).put("links", JSONArray()))
                    }
                })
                put("properties", JSONObject().apply {
                    if (isInstance) {
                        put("proxyWidgets", proxyWidgetsOf(level, type, node).first)
                    } else {
                        put("Node name for S&R", type)
                    }
                })
                if (isInstance) {
                    // The shown values in the same order: set on the widgets they stand for when ComfyUI loads it
                    put("widgets_values", proxyWidgetsOf(level, type, node).second)
                } else {
                    val (values, named) = widgetValuesOf(node)
                    put("widgets_values", values)
                    named?.let { put("widgets_values_named", it) }
                }
                node.optJSONObject("_meta")?.optString("title")?.takeIf { it.isNotEmpty() && it != type }?.let { put("title", it) }
            }
        }

        // 2. Links between them
        for (id in level.nodes.keys()) {
            val node = level.nodes.optJSONObject(id) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            for (key in inputs.keys()) {
                val value = inputs.opt(key)
                if (!isLink(value)) continue
                value as JSONArray
                val sourceId = value.optString(0)
                if (sourceId !in level.intIds) continue
                val sourceType = level.nodes.optJSONObject(sourceId)?.let { outputsOf(level, it).getOrNull(value.optInt(1))?.type }
                link(level, level.intIds.getValue(sourceId), value.optInt(1), sourceId, id, key, sourceType)
            }
        }

        // 3. Notes
        val result = JSONArray()
        level.nodes.keys().forEach { id -> level.outNodes[id]?.let { result.put(it) } }
        level.notes().forEach { note ->
            val (x, y, w, h) = level.position(WorkflowNote.noteIdToMemberId(note.optInt("id"))).toList()
            result.put(JSONObject().apply {
                put("id", level.noteIds.getValue(note.optInt("id")))
                put("type", NOTE_TYPE)
                put("pos", JSONArray().put((x * SCALE).toDouble()).put((y * SCALE).toDouble()))
                put("size", JSONArray().put((w * SCALE).toDouble()).put((h * SCALE).toDouble().coerceAtLeast(120.0)))
                put("flags", JSONObject())
                put("order", order++)
                put("mode", 0)
                put("inputs", JSONArray())
                put("outputs", JSONArray())
                put("title", note.optString("title", "Note"))
                put("properties", JSONObject())
                put("widgets_values", JSONArray().put(note.optString("content", "")))
            })
        }
        level.nodes.keys().forEach { level.nodes.optJSONObject(it)?.remove("__id") }
        return result
    }

    /**
     * Add a link from output [originSlot] of node [originId] (an int ID, or the definition's input node) to input
     * [inputName] of node [targetId] (by its ID in the level).
     */
    private fun link(
        level: Level,
        originId: Int,
        originSlot: Int,
        originKey: String?,
        targetId: String,
        inputName: String,
        sourceType: String?
    ): Int? {
        val entries = level.entries[targetId] ?: return null
        val slot = entries.indexOfFirst { it.name == inputName }.takeIf { it >= 0 } ?: return null
        val target = level.outNodes[targetId] ?: return null
        // A slot of unknown type (a dynamic one) takes the type of what feeds it
        if (entries[slot].type == "*" && sourceType != null && sourceType != "*") {
            entries[slot].type = sourceType
            target.getJSONArray("inputs").getJSONObject(slot).put("type", sourceType)
        }
        val type = entries[slot].type
        val linkId = ++level.lastLinkId
        target.getJSONArray("inputs").getJSONObject(slot).put("link", linkId)
        originKey?.let { key ->
            level.outNodes[key]?.getJSONArray("outputs")?.optJSONObject(originSlot)?.getJSONArray("links")?.put(linkId)
        }
        val targetInt = level.intIds.getValue(targetId)
        if (level.isDefinition) {
            level.links.put(linkObject(linkId, originId, originSlot, targetInt, slot, type))
        } else {
            level.links.put(JSONArray().put(linkId).put(originId).put(originSlot).put(targetInt).put(slot).put(type))
        }
        return linkId
    }

    private fun linkObject(id: Int, originId: Int, originSlot: Int, targetId: Int, targetSlot: Int, type: String) =
        JSONObject().put("id", id).put("origin_id", originId).put("origin_slot", originSlot)
            .put("target_id", targetId).put("target_slot", targetSlot).put("type", type)

    /**
     * An instance's promoted widgets and their values, in one order: the subgraph's inputs holding values ("-1", as
     * recent frontends list them), then its paths into the definition, each by its first node and that node's widget.
     * A subgraph's input feeding only sockets inside has no widget there to hold its value: the instance's does.
     */
    private fun proxyWidgetsOf(level: Level, definitionId: String, node: JSONObject): Pair<JSONArray, JSONArray> {
        val definitions = level.definitions ?: return JSONArray() to JSONArray()
        val entries = JSONArray()
        val values = JSONArray()
        val inputs = node.optJSONObject("inputs") ?: JSONObject()
        Subgraphs.portsOf(definitions, definitionId, "inputs").forEach { (name, _) ->
            if (isLink(inputs.opt(name))) return@forEach
            val value = sharedValueOf(definitions, definitionId, name) ?: return@forEach
            entries.put(JSONArray().put("-1").put(name))
            values.put(value)
        }
        node.optJSONArray(Subgraphs.PROMOTED_KEY)?.let { promoted ->
            for (i in 0 until promoted.length()) {
                val path = promoted.optString(i)
                val value = Subgraphs.valueAt(definitions, definitionId, path) ?: continue
                entries.put(JSONArray().put(path.substringBefore('/')).put(Subgraphs.displayName(path)))
                values.put(value)
            }
        }
        return entries to values
    }

    /**
     * The value of a subgraph's input when everything it feeds inside has that same value; null otherwise (values
     * differ, or a socket inside has none): then each keeps its own and the instance shows no value for it.
     */
    private fun sharedValueOf(definitions: JSONObject, definitionId: String, portName: String): Any? {
        val definition = definitions.optJSONObject(definitionId) ?: return null
        val nodes = definition.optJSONObject("nodes") ?: return null
        val port = definition.optJSONArray("inputs").objects().find { it.optString("name") == portName } ?: return null
        val targets = port.optJSONArray("targets") ?: return null
        val found = (0 until targets.length()).map { i ->
            val target = targets.optJSONArray(i) ?: return null
            val node = nodes.optJSONObject(target.optString(0)) ?: return null
            val type = node.optString("class_type")
            if (definitions.has(type)) sharedValueOf(definitions, type, target.optString(1))
            else node.optJSONObject("inputs")?.opt(target.optString(1))?.takeIf { it != JSONObject.NULL && !isLink(it) }
        }
        val first = found.firstOrNull() ?: return null
        return first.takeIf { found.all { it != null && it.toString() == first.toString() } }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Widget values
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A node's widget values in the frontend's order; a linked value keeps its place with the node's default. Values
     * of inputs the server doesn't take as widgets but the frontend draws (a box editor, a recorder…) go where the
     * definition has them, values of the frontend's own hidden widgets after all. Without a definition (a node the
     * server lacks), its values in the order the workflow has them.
     */
    private fun widgetValuesOf(node: JSONObject): Pair<JSONArray, JSONObject?> {
        val inputs = node.optJSONObject("inputs") ?: JSONObject()
        val definition = lookup(node.optString("class_type"))
            ?: return JSONArray().apply { inputs.keys().forEach { key -> inputs.opt(key)?.takeIf { !isLink(it) }?.let { put(it) } } } to null
        val values = JSONArray()
        // The same by name, as recent frontends save them beside the list (it lays the list out on import)
        val named = JSONObject()
        fun add(name: String, value: Any?) {
            values.put(value ?: JSONObject.NULL)
            named.put(name, value ?: JSONObject.NULL)
        }
        val written = mutableSetOf<String>()
        fun write(defs: List<InputDefinition>, prefix: String) {
            for (input in defs) {
                val name = prefix + input.name
                if (input.type in CONTAINER_TYPES || !(input.savesValue || hasValue(inputs, name))) continue
                written.add(name)
                if (input.name == "control_after_generate") {
                    add(name, inputs.opt(name)?.takeIf { !isLink(it) } ?: CONTROL_FIXED)
                    continue
                }
                val value = inputs.opt(name)?.takeIf { it != JSONObject.NULL && !isLink(it) } ?: input.getEffectiveDefault()
                add(name, value)
                input.dynamicOptions?.get(value as? String)?.let { write(it, "$name.") }
                if (input.controlAfterGenerate) add(CONTROL_WIDGET, CONTROL_FIXED)
                if (input.upload && input.uploadKind in UPLOAD_KINDS_SAVING) add(UPLOAD_WIDGET, UPLOAD_VALUE)
            }
        }
        write(definition.inputs, prefix = "")
        for (key in inputs.keys()) if (key !in written && hasValue(inputs, key)) add(key, inputs.get(key))
        // Two widgets of one name (two seeds' controls): the names can't lay the list out, the list alone goes
        return values to named.takeIf { it.length() == values.length() }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Groups and subgraphs
    // ---------------------------------------------------------------------------------------------------------------

    /** Groups as rectangles around their members (nodes and notes), in ComfyUI's units. */
    private fun groupsOf(level: Level): JSONArray {
        val result = JSONArray()
        level.source.optJSONArray("groups").objects().forEach { group ->
            val members = group.optJSONArray("member_nodes") ?: return@forEach
            val boxes = (0 until members.length()).mapNotNull { level.positions[members.optString(it)] }
            if (boxes.isEmpty()) return@forEach
            val left = boxes.minOf { it[0] } * SCALE - GROUP_PADDING
            val top = boxes.minOf { it[1] } * SCALE - GROUP_PADDING - GROUP_TITLE_HEIGHT
            val right = boxes.maxOf { it[0] + it[2] } * SCALE + GROUP_PADDING
            val bottom = boxes.maxOf { it[1] + it[3] } * SCALE + GROUP_PADDING
            result.put(JSONObject().apply {
                put("id", group.optInt("id"))
                put("title", group.optString("title", "Group"))
                put("bounding", JSONArray().put(left.toDouble()).put(top.toDouble()).put((right - left).toDouble()).put((bottom - top).toDouble()))
                put("flags", JSONObject())
            })
        }
        return result
    }

    /** A definition in ComfyUI's format: its nodes, its input and output nodes and the links through them. */
    private fun writeDefinition(id: String, definition: JSONObject, definitions: JSONObject): JSONObject {
        val ports = definition.optJSONArray("inputs").objects()
        val outputPorts = definition.optJSONArray("outputs").objects()
        val level = Level(definition, definitions, isDefinition = true, ports = ports, outputPorts = outputPorts)
        val nodes = writeLevel(level)

        // Input node -> the values each input feeds
        val inputLinks = ports.map { JSONArray() }
        ports.forEachIndexed { index, port ->
            port.optJSONArray("targets")?.let { targets ->
                for (i in 0 until targets.length()) {
                    val t = targets.optJSONArray(i) ?: continue
                    link(level, INPUT_NODE_ID, index, null, t.optString(0), t.optString(1), port.optString("type", "*"))
                        ?.let { inputLinks[index].put(it) }
                }
            }
        }
        // What feeds each output -> the output node
        val outputLinks = outputPorts.map { JSONArray() }
        outputPorts.forEachIndexed { index, port ->
            val (originId, originSlot, originKey) = when {
                port.optJSONArray("source") != null -> {
                    val source = port.getJSONArray("source")
                    val key = source.optString(0)
                    Triple(level.intIds[key] ?: return@forEachIndexed, source.optInt(1), key)
                }
                port.has("input") -> Triple(INPUT_NODE_ID, port.optInt("input"), null)
                else -> {
                    DebugLogger.w(TAG, "Subgraph output fed by a value: left unlinked")
                    return@forEachIndexed
                }
            }
            val linkId = ++level.lastLinkId
            originKey?.let { key -> level.outNodes[key]?.getJSONArray("outputs")?.optJSONObject(originSlot)?.getJSONArray("links")?.put(linkId) }
            if (originId == INPUT_NODE_ID) inputLinks.getOrNull(originSlot)?.put(linkId)
            level.links.put(linkObject(linkId, originId, originSlot, OUTPUT_NODE_ID, index, port.optString("type", "*")))
            outputLinks[index].put(linkId)
        }

        // The input and output nodes beside the nodes
        val boxes = level.positions.values
        val minX = (boxes.minOfOrNull { it[0] } ?: 0f) * SCALE
        val minY = (boxes.minOfOrNull { it[1] } ?: 0f) * SCALE
        val maxX = (boxes.maxOfOrNull { it[0] + it[2] } ?: 480f) * SCALE
        fun boundary(nodeId: Int, x: Float, count: Int) = JSONObject().put("id", nodeId).put(
            "bounding", JSONArray().put(x.toDouble()).put(minY.toDouble()).put(BOUNDARY_NODE_WIDTH.toDouble()).put((40 + 20 * count).toDouble())
        )
        fun portsJson(list: List<JSONObject>, links: List<JSONArray>) = JSONArray().apply {
            list.forEachIndexed { index, port ->
                put(JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("name", port.optString("name"))
                    put("type", port.optString("type", "*"))
                    put("linkIds", links[index])
                })
            }
        }
        return JSONObject().apply {
            put("id", id)
            put("version", 1)
            put("state", JSONObject().put("lastGroupId", 0).put("lastNodeId", level.lastNodeId)
                .put("lastLinkId", level.lastLinkId).put("lastRerouteId", 0))
            put("revision", 0)
            put("config", JSONObject())
            put("name", definition.optString("name", "Subgraph"))
            put("inputNode", boundary(INPUT_NODE_ID, minX - BOUNDARY_NODE_WIDTH - BOUNDARY_NODE_GAP, ports.size))
            put("outputNode", boundary(OUTPUT_NODE_ID, maxX + BOUNDARY_NODE_GAP, outputPorts.size))
            put("inputs", portsJson(ports, inputLinks))
            put("outputs", portsJson(outputPorts, outputLinks))
            put("widgets", JSONArray())
            put("nodes", nodes)
            put("groups", groupsOf(level))
            put("links", level.links)
            put("extra", JSONObject())
        }
    }
}
