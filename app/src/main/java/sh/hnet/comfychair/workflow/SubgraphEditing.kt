package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Changing a workflow's subgraphs (spec §19.4, §19.5), on a level in its stored form: `nodes` (instances with their
 * `promoted` lists, links as `["id", slot]`; inside a subgraph also the level's port nodes, as any other node), `groups`
 * and `notes`, with the workflow's definitions ([Subgraphs]).
 */
object SubgraphEditing {
    /** The longest subgraph, input or output name (the templates' longest is 45). */
    const val MAX_NAME_LENGTH = 45

    private fun isLink(value: Any?): Boolean =
        value is JSONArray && value.length() == 2 && value.opt(0) is String && value.opt(1) is Number

    private fun link(nodeId: String, slot: Int) = JSONArray().put(nodeId).put(slot)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun unique(name: String, taken: MutableSet<String>): String {
        val base = name.ifEmpty { "input" }
        var candidate = base
        var n = 1
        while (!taken.add(candidate)) candidate = "${base}_${n++}"
        return candidate
    }

    /** Links that cross a selection's edge, each outside socket once: (into it, out of it). */
    fun crossingCounts(nodes: JSONObject, selected: Set<String>): Pair<Int, Int> {
        val into = mutableSetOf<Pair<String, Int>>()
        val out = mutableSetOf<Pair<String, Int>>()
        for (id in nodes.keys()) {
            val inputs = nodes.optJSONObject(id)?.optJSONObject("inputs") ?: continue
            for (key in inputs.keys()) {
                val value = inputs.opt(key)
                if (!isLink(value)) continue
                val source = (value as JSONArray).optString(0) to value.optInt(1)
                if (id in selected && source.first !in selected) into.add(source)
                if (id !in selected && source.first in selected) out.add(source)
            }
        }
        return into.size to out.size
    }

    /** What making a subgraph gave: the level with the instance in place of the selection, and the definitions. */
    class Made(val level: JSONObject, val definitions: JSONObject, val definitionId: String)

    /**
     * Make a subgraph of [selected] nodes (and [selectedNotes]) of [level], named [name] (spec §19.4.1): they move
     * into a new definition, with their links and the groups wholly among them; instance [instanceId] takes their
     * place. Each link crossing the selection's edge becomes one of its inputs or outputs, one per outside socket
     * (named after the inner input, or the inner output, made unique), typed by [typeOf] (source node, slot). A value
     * that took a link and has its own value in [kept] keeps it inside. [promote] lists values (node, input key as the
     * level shows them) the instance shows: those fields fill. [position] places the instance (manual placement).
     */
    fun make(
        level: JSONObject,
        definitions: JSONObject,
        selected: Set<String>,
        selectedNotes: Set<Int>,
        name: String,
        instanceId: String,
        kept: Map<Pair<String, String>, Any>,
        typeOf: (nodeId: String, slot: Int) -> String,
        outputNameOf: (nodeId: String, slot: Int) -> String,
        promote: Set<Pair<String, String>>,
        position: Pair<Float, Float>?
    ): Made {
        val result = JSONObject(level.toString())
        val nodes = result.getJSONObject("nodes")
        val defs = JSONObject(definitions.toString())
        val definitionId = UUID.randomUUID().toString()
        val inner = JSONObject()
        selected.filter(nodes::has).forEach { id -> inner.put(id, nodes.getJSONObject(id)); nodes.remove(id) }

        // Inputs: an outside socket feeding values inside, once
        val inputs = JSONArray()
        val inputIndex = mutableMapOf<Pair<String, Int>, Int>()
        val inputNames = mutableSetOf<String>()
        val instanceInputs = JSONObject()
        for (id in inner.keys().asSequence().toList()) {
            val nodeInputs = inner.getJSONObject(id).optJSONObject("inputs") ?: continue
            for (key in nodeInputs.keys().asSequence().toList()) {
                val value = nodeInputs.opt(key)
                if (!isLink(value)) continue
                val source = (value as JSONArray).optString(0) to value.optInt(1)
                if (source.first in selected) continue
                val index = inputIndex.getOrPut(source) {
                    val portName = unique(key.substringAfterLast('/'), inputNames)
                    inputs.put(JSONObject().put("name", portName).put("type", typeOf(source.first, source.second)).put("targets", JSONArray()))
                    instanceInputs.put(portName, link(source.first, source.second))
                    inputs.length() - 1
                }
                inputs.getJSONObject(index).getJSONArray("targets").put(JSONArray().put(id).put(key))
                // In the stored form a value an input feeds keeps its own value; a socket has none
                val own = kept[id to key]
                if (own != null) nodeInputs.put(key, own) else nodeInputs.remove(key)
            }
        }

        // Outputs: an inside socket feeding something outside, once; the outside links come from the instance
        val outputs = JSONArray()
        val outputIndex = mutableMapOf<Pair<String, Int>, Int>()
        val outputNames = mutableSetOf<String>()
        for (id in nodes.keys()) {
            val nodeInputs = nodes.optJSONObject(id)?.optJSONObject("inputs") ?: continue
            for (key in nodeInputs.keys().asSequence().toList()) {
                val value = nodeInputs.opt(key)
                if (!isLink(value)) continue
                val source = (value as JSONArray).optString(0) to value.optInt(1)
                if (source.first !in selected) continue
                val index = outputIndex.getOrPut(source) {
                    val portName = unique(outputNameOf(source.first, source.second), outputNames)
                    outputs.put(JSONObject().put("name", portName).put("type", typeOf(source.first, source.second)).put("source", link(source.first, source.second)))
                    outputs.length() - 1
                }
                nodeInputs.put(key, link(instanceId, index))
            }
        }

        // Groups wholly among the moved nodes (and notes) move with them; others lose those members
        val noteMembers = selectedNotes.map { WorkflowNote.noteIdToMemberId(it) }.toSet()
        val movedMembers = selected + noteMembers
        val innerGroups = JSONArray()
        val keptGroups = JSONArray()
        result.optJSONArray("groups").objects().forEach { group ->
            val members = group.optJSONArray("member_nodes")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
            if (members.isNotEmpty() && members.all { it in movedMembers }) {
                innerGroups.put(group)
            } else {
                val left = members.filter { it !in movedMembers }
                if (left.isNotEmpty()) keptGroups.put(group.put("member_nodes", JSONArray(left)))
            }
        }
        result.put("groups", keptGroups)
        val innerNotes = JSONArray()
        val keptNotes = JSONArray()
        result.optJSONArray("notes").objects().forEach { note -> if (note.optInt("id") in selectedNotes) innerNotes.put(note) else keptNotes.put(note) }
        result.put("notes", keptNotes)

        val definition = JSONObject()
            .put("name", name)
            .put("inputs", inputs)
            .put("outputs", outputs)
            .put("nodes", inner)
        if (innerGroups.length() > 0) definition.put("groups", innerGroups)
        if (innerNotes.length() > 0) definition.put("notes", innerNotes)
        defs.put(definitionId, definition)

        val meta = JSONObject().put("title", name)
        position?.let { (x, y) -> meta.put("position", JSONArray().put(x.toDouble()).put(y.toDouble())) }
        val promoted = JSONArray()
        promote.filter { it.first in selected }.forEach { (node, input) -> promoted.put("$node/$input") }
        val instance = JSONObject().put("class_type", definitionId).put("inputs", instanceInputs).put("_meta", meta)
        if (promoted.length() > 0) instance.put(Subgraphs.PROMOTED_KEY, promoted)
        nodes.put(instanceId, instance)
        return Made(result, defs, definitionId)
    }

    /** What unpacking gave: the level with the subgraph's nodes in the instance's place; new IDs by inner ID. */
    class Unpacked(val level: JSONObject, val nodeIds: Map<String, String>)

    /**
     * Unpack instance [instanceId] of [level] (spec §19.4.2): its subgraph's nodes, groups and notes in its place with
     * new IDs ([newNodeId], [newGroupId], [newNoteId]), linked to what the instance was linked to; the values it showed
     * from nested subgraphs shown by those (nested) instances. A muted or bypassed instance's mode goes to its nodes.
     * [offset] moves the inner positions (manual placement) to where the instance was. Null for an unknown instance.
     */
    fun unpack(
        level: JSONObject,
        definitions: JSONObject,
        instanceId: String,
        newNodeId: () -> String,
        newGroupId: () -> Int,
        newNoteId: () -> Int,
        offset: Pair<Float, Float>?
    ): Unpacked? {
        val result = JSONObject(level.toString())
        val nodes = result.getJSONObject("nodes")
        val instance = nodes.optJSONObject(instanceId) ?: return null
        val definition = definitions.optJSONObject(instance.optString("class_type")) ?: return null
        val innerNodes = definition.optJSONObject("nodes") ?: JSONObject()
        val ids = innerNodes.keys().asSequence().toList().associateWith { newNodeId() }
        val instanceInputs = instance.optJSONObject("inputs") ?: JSONObject()
        val mode = instance.optInt("mode", 0)

        // The definition's inputs: what the instance's inputs were linked to, by index
        val ports = definition.optJSONArray("inputs").objects()
        val fedBy = ports.map { port -> instanceInputs.opt(port.optString("name"))?.takeIf(::isLink) as JSONArray? }
        val minPosition = innerNodes.keys().asSequence().mapNotNull { id ->
            innerNodes.getJSONObject(id).optJSONObject("_meta")?.optJSONArray("position")?.let { it.optDouble(0).toFloat() to it.optDouble(1).toFloat() }
        }.toList().let { list -> if (list.isEmpty()) null else list.minOf { it.first } to list.minOf { it.second } }

        for ((oldId, newId) in ids) {
            val node = JSONObject(innerNodes.getJSONObject(oldId).toString())
            node.optJSONObject("inputs")?.let { inputs ->
                for (key in inputs.keys().asSequence().toList()) {
                    val value = inputs.opt(key)
                    if (isLink(value)) inputs.put(key, link(ids[(value as JSONArray).optString(0)] ?: value.optString(0), value.optInt(1)))
                }
            }
            if (mode != 0 && node.optInt("mode", 0) == 0) node.put("mode", mode)
            // Placed where the instance was, keeping their layout
            val position = node.optJSONObject("_meta")?.optJSONArray("position")
            if (offset != null && position != null && minPosition != null) {
                position.put(0, (position.optDouble(0) - minPosition.first + offset.first))
                position.put(1, (position.optDouble(1) - minPosition.second + offset.second))
            }
            nodes.put(newId, node)
        }
        // Values the instance's inputs feed: linked to what fed the instance (an unlinked input leaves their own value)
        ports.forEachIndexed { index, port ->
            val source = fedBy.getOrNull(index) ?: return@forEachIndexed
            port.optJSONArray("targets")?.let { targets ->
                for (i in 0 until targets.length()) {
                    val target = targets.optJSONArray(i) ?: continue
                    val node = nodes.optJSONObject(ids[target.optString(0)] ?: continue) ?: continue
                    val inputs = node.optJSONObject("inputs") ?: JSONObject().also { node.put("inputs", it) }
                    inputs.put(target.optString(1), JSONArray(source.toString()))
                }
            }
        }
        // Values shown from nested subgraphs: shown by those instances now
        instance.optJSONArray(Subgraphs.PROMOTED_KEY)?.let { promoted ->
            for (i in 0 until promoted.length()) {
                val key = promoted.optString(i)
                val rest = key.substringAfter('/')
                if (!Subgraphs.isPromotedKey(rest)) continue
                val nested = nodes.optJSONObject(ids[key.substringBefore('/')] ?: continue) ?: continue
                if (!definitions.has(nested.optString("class_type"))) continue
                val list = nested.optJSONArray(Subgraphs.PROMOTED_KEY) ?: JSONArray().also { nested.put(Subgraphs.PROMOTED_KEY, it) }
                if ((0 until list.length()).none { list.optString(it) == rest }) list.put(rest)
            }
        }
        // What the instance's outputs fed: from what feeds the definition's outputs now
        val outputs = definition.optJSONArray("outputs").objects()
        nodes.remove(instanceId)
        for (id in nodes.keys()) {
            val inputs = nodes.optJSONObject(id)?.optJSONObject("inputs") ?: continue
            for (key in inputs.keys().asSequence().toList()) {
                val value = inputs.opt(key)
                if (!isLink(value) || (value as JSONArray).optString(0) != instanceId) continue
                val port = outputs.getOrNull(value.optInt(1))
                val replacement = when {
                    port == null -> null
                    port.optJSONArray("source") != null -> port.getJSONArray("source").let { link(ids[it.optString(0)] ?: it.optString(0), it.optInt(1)) }
                    port.has("input") -> fedBy.getOrNull(port.optInt("input"))?.let { JSONArray(it.toString()) }
                    port.has("value") -> port.opt("value")
                    else -> null
                }
                if (replacement != null) inputs.put(key, replacement) else inputs.remove(key)
            }
        }
        // Groups and notes inside, with new IDs
        val groups = result.optJSONArray("groups") ?: JSONArray().also { result.put("groups", it) }
        val noteIds = mutableMapOf<Int, Int>()
        val notes = result.optJSONArray("notes") ?: JSONArray().also { result.put("notes", it) }
        definition.optJSONArray("notes").objects().forEach { note ->
            val copy = JSONObject(note.toString())
            val newId = newNoteId()
            noteIds[note.optInt("id")] = newId
            notes.put(copy.put("id", newId))
        }
        definition.optJSONArray("groups").objects().forEach { group ->
            val copy = JSONObject(group.toString())
            val members = copy.optJSONArray("member_nodes")?.let { array ->
                JSONArray((0 until array.length()).map { i ->
                    val member = array.optString(i)
                    WorkflowNote.memberIdToNoteId(member)?.let { noteIds[it]?.let(WorkflowNote::noteIdToMemberId) } ?: ids[member] ?: member
                })
            }
            if (members != null) copy.put("member_nodes", members)
            groups.put(copy.put("id", newGroupId()))
        }
        return Unpacked(result, ids)
    }

    /** Instances of [definitionId] among [nodes]: their IDs. */
    private fun instancesIn(nodes: JSONObject, definitionId: String): List<String> =
        nodes.keys().asSequence().filter { nodes.optJSONObject(it)?.optString("class_type") == definitionId }.toList()

    /**
     * A subgraph's input renamed ([to] a name) or removed ([to] null) in every definition holding instances of it:
     * their links into it and the values they show by it follow.
     */
    fun changeInputInDefinitions(definitions: JSONObject, definitionId: String, from: String, to: String?) {
        for (id in definitions.keys()) {
            val definition = definitions.optJSONObject(id) ?: continue
            val nodes = definition.optJSONObject("nodes") ?: continue
            val instances = instancesIn(nodes, definitionId).toSet()
            for (instanceId in instances) {
                val inputs = nodes.getJSONObject(instanceId).optJSONObject("inputs") ?: continue
                val value = inputs.opt(from) ?: continue
                inputs.remove(from)
                if (to != null) inputs.put(to, value)
            }
            if (instances.isEmpty()) continue
            // That definition's own inputs feeding the instances by the input's name
            definition.optJSONArray("inputs").objects().forEach { port ->
                val targets = port.optJSONArray("targets") ?: return@forEach
                val kept = JSONArray()
                for (i in 0 until targets.length()) {
                    val target = targets.optJSONArray(i) ?: continue
                    when {
                        target.optString(0) !in instances || target.optString(1) != from -> kept.put(target)
                        to != null -> kept.put(JSONArray().put(target.optString(0)).put(to))
                    }
                }
                port.put("targets", kept)
            }
        }
    }

    /** A subgraph's output [index] removed in every definition holding instances of it: links from it go, later ones move up. */
    fun removeOutputInDefinitions(definitions: JSONObject, definitionId: String, index: Int) {
        for (id in definitions.keys()) {
            val definition = definitions.optJSONObject(id) ?: continue
            val nodes = definition.optJSONObject("nodes") ?: continue
            val instances = instancesIn(nodes, definitionId).toSet()
            if (instances.isEmpty()) continue
            fun shifted(value: JSONArray): JSONArray? = when {
                value.optString(0) !in instances -> value
                value.optInt(1) == index -> null
                value.optInt(1) > index -> link(value.optString(0), value.optInt(1) - 1)
                else -> value
            }
            for (nodeId in nodes.keys()) {
                val inputs = nodes.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
                for (key in inputs.keys().asSequence().toList()) {
                    val value = inputs.opt(key)
                    if (!isLink(value)) continue
                    val now = shifted(value as JSONArray)
                    if (now == null) inputs.remove(key) else inputs.put(key, now)
                }
            }
            definition.optJSONArray("outputs").objects().forEach { port ->
                val source = port.optJSONArray("source") ?: return@forEach
                val now = shifted(source)
                if (now == null) port.remove("source") else port.put("source", now)
            }
        }
    }
}
