package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger

/**
 * Subgraphs in a stored workflow: definitions in the wrapper's `subgraphs` object (by ID), used by instance nodes whose
 * `class_type` is a definition's ID. A definition is shared by its instances, as in ComfyUI.
 *
 * ```
 * "subgraphs": { "<id>": {
 *     "name": "…",
 *     "inputs":  [{"name": "image", "type": "IMAGE", "targets": [["20", "image"]]}],   // the values it feeds inside
 *     "outputs": [{"name": "IMAGE", "type": "IMAGE", "source": ["11", 0]}],            // or "input": i, or "value": v
 *     "nodes": { "11": {"class_type": …, "inputs": …}, … },                            // IDs local to the definition
 *     "groups": […], "notes": […] } }
 * instance: {"class_type": "<id>", "inputs": {"image": ["4", 0]}, "promoted": ["11/blur_radius"], "mode": …, "_meta": …}
 * ```
 *
 * Values live in the definition: an instance holds only its links and the list of values it shows (promoted, as paths
 * "node/input" into the definition, through nested instances "node/node/input"). Two views of the same workflow:
 * - **exposed** ([expose], [absorb]): the values an instance shows are inputs of the instance node, keyed by their path,
 *   and the subgraph's inputs no link feeds as inputs by name, so the editor, field mapping, custom fields and node
 *   edits work on instances as on any node. The stored workflow is never exposed.
 * - **flat** ([flatten]): the nodes that run, as ComfyUI sends them ("instance:node" IDs), for generation and for
 *   anything that looks for real node types (placeholders' nodes, ranges, missing nodes).
 */
object Subgraphs {
    private const val TAG = "Subgraphs"

    const val KEY = "subgraphs"
    const val PROMOTED_KEY = "promoted"
    private const val PATH_SEPARATOR = '/'
    private const val ID_SEPARATOR = ":"

    // Deeper nesting than this is taken as a definition containing itself
    private const val MAX_DEPTH = 32

    /** An exposed input that's a value promoted from inside (a path), not one of the subgraph's own inputs. */
    fun isPromotedKey(inputName: String): Boolean = PATH_SEPARATOR in inputName

    /** What an exposed input is called on screen: the input's own name, without its path. */
    fun displayName(inputName: String): String = inputName.substringAfterLast(PATH_SEPARATOR)

    fun definitionsOf(root: JSONObject): JSONObject? = root.optJSONObject(KEY)?.takeIf { it.length() > 0 }

    private fun nodesOf(root: JSONObject): JSONObject = root.optJSONObject("nodes") ?: root

    // ---------------------------------------------------------------------------------------------------------------
    // Values by path
    // ---------------------------------------------------------------------------------------------------------------

    private fun isLink(value: Any?): Boolean =
        value is JSONArray && value.length() == 2 && value.opt(0) is String && value.opt(1) is Number

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun targetsOf(port: JSONObject): List<Pair<String, String>> {
        val targets = port.optJSONArray("targets") ?: return emptyList()
        return (0 until targets.length()).mapNotNull { i ->
            targets.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { it.optString(0) to it.optString(1) }
        }
    }

    /**
     * The value at [key] of definition [definitionId]: a path "node/…" into it, or one of its inputs by name (the value
     * of the first value it feeds). Null when there's none (a link, a socket, an unknown path).
     */
    fun valueAt(definitions: JSONObject, definitionId: String, key: String, depth: Int = 0): Any? {
        if (depth > MAX_DEPTH) return null
        val definition = definitions.optJSONObject(definitionId) ?: return null
        val nodes = definition.optJSONObject("nodes") ?: return null
        if (isPromotedKey(key)) {
            val nodeId = key.substringBefore(PATH_SEPARATOR)
            val rest = key.substringAfter(PATH_SEPARATOR)
            val node = nodes.optJSONObject(nodeId) ?: return null
            val type = node.optString("class_type")
            if (definitions.has(type)) return valueAt(definitions, type, rest, depth + 1)
            return node.optJSONObject("inputs")?.opt(rest)?.takeIf { it != JSONObject.NULL && !isLink(it) }
        }
        val port = definition.optJSONArray("inputs").objects().find { it.optString("name") == key } ?: return null
        val (nodeId, input) = targetsOf(port).firstOrNull() ?: return null
        val node = nodes.optJSONObject(nodeId) ?: return null
        val type = node.optString("class_type")
        if (definitions.has(type)) return valueAt(definitions, type, input, depth + 1)
        return node.optJSONObject("inputs")?.opt(input)?.takeIf { it != JSONObject.NULL && !isLink(it) }
    }

    /** Set the value at [key] (as [valueAt] reads it); an input by name sets every value it feeds. Links are kept. */
    fun setValueAt(definitions: JSONObject, definitionId: String, key: String, value: Any, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        val definition = definitions.optJSONObject(definitionId) ?: return
        val nodes = definition.optJSONObject("nodes") ?: return
        fun setOn(nodeId: String, input: String) {
            val node = nodes.optJSONObject(nodeId) ?: return
            val type = node.optString("class_type")
            if (definitions.has(type)) {
                setValueAt(definitions, type, input, value, depth + 1)
                return
            }
            val inputs = node.optJSONObject("inputs") ?: JSONObject().also { node.put("inputs", it) }
            if (!isLink(inputs.opt(input))) inputs.put(input, value)
        }
        if (isPromotedKey(key)) {
            setOn(key.substringBefore(PATH_SEPARATOR), key.substringAfter(PATH_SEPARATOR))
            return
        }
        val port = definition.optJSONArray("inputs").objects().find { it.optString("name") == key } ?: return
        targetsOf(port).forEach { (nodeId, input) -> setOn(nodeId, input) }
    }

    /** Equal as values: numbers by value (7 and 7.0), anything else by its JSON text. */
    private fun sameValue(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a == null || b == null -> a == b
        else -> a == b || a.toString() == b.toString()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Instance types
    // ---------------------------------------------------------------------------------------------------------------

    /** A definition's inputs and outputs: (name, type) in order. */
    fun portsOf(definitions: JSONObject, definitionId: String, key: String): List<Pair<String, String>> =
        definitions.optJSONObject(definitionId)?.optJSONArray(key).objects()
            .map { it.optString("name") to it.optString("type", "*") }

    /** The real node input behind [key] of a definition, as [valueAt] finds it: (class type, input name). */
    private fun realInputIn(definitions: JSONObject, definitionId: String, key: String, depth: Int = 0): Pair<String, String>? {
        if (depth > MAX_DEPTH) return null
        val definition = definitions.optJSONObject(definitionId) ?: return null
        val nodes = definition.optJSONObject("nodes") ?: return null
        val (nodeId, input) = if (isPromotedKey(key)) {
            key.substringBefore(PATH_SEPARATOR) to key.substringAfter(PATH_SEPARATOR)
        } else {
            val port = definition.optJSONArray("inputs").objects().find { it.optString("name") == key } ?: return null
            targetsOf(port).firstOrNull() ?: return null
        }
        val type = nodes.optJSONObject(nodeId)?.optString("class_type") ?: return null
        if (definitions.has(type)) return realInputIn(definitions, type, input, depth + 1)
        return type to input
    }

    /**
     * The real node input behind input [input] of node [nodeId] in a workflow (stored or exposed): the node's own, or
     * for an instance the one inside its subgraph. (class type, input name), null when there's none.
     */
    fun realInput(root: JSONObject, nodeId: String, input: String): Pair<String, String>? {
        val type = nodesOf(root).optJSONObject(nodeId)?.optString("class_type") ?: return null
        val definitions = definitionsOf(root)
        if (definitions == null || !definitions.has(type)) return type to input
        return realInputIn(definitions, type, input)
    }

    private fun inputDefinitionAt(
        definitions: JSONObject,
        definitionId: String,
        key: String,
        lookup: (String) -> NodeTypeDefinition?
    ): InputDefinition? {
        val (type, input) = realInputIn(definitions, definitionId, key) ?: return null
        return lookup(type)?.inputs?.find { it.name == input }
    }

    /**
     * The node types of a workflow's subgraphs, as its instances show them: the subgraph's inputs (a value one as the
     * input it feeds inside: its type, range and options), the promoted values [promotedKeys] lists for it (by
     * definition ID), the subgraph's outputs. [lookup] gives the server's node definitions.
     */
    fun nodeTypes(
        definitions: JSONObject?,
        promotedKeys: Map<String, List<String>>,
        lookup: (String) -> NodeTypeDefinition?
    ): Map<String, NodeTypeDefinition> {
        if (definitions == null) return emptyMap()
        return definitions.keys().asSequence().toList().associateWith { id ->
            val definition = definitions.getJSONObject(id)
            val inputs = portsOf(definitions, id, "inputs").map { (name, type) ->
                inputDefinitionAt(definitions, id, name, lookup)?.copy(name = name, isRequired = false)
                    ?: InputDefinition(name = name, type = type, isRequired = false)
            } + promotedKeys[id].orEmpty().distinct().mapNotNull { key ->
                inputDefinitionAt(definitions, id, key, lookup)?.copy(name = key, isRequired = false)
            }
            NodeTypeDefinition(
                classType = id,
                category = SUBGRAPH_CATEGORY,
                inputs = inputs,
                outputs = portsOf(definitions, id, "outputs").map { (name, type) -> OutputSlot(type, name) },
                displayName = definition.optString("name").ifEmpty { null }
            )
        }
    }

    /** The category of subgraph instance types. */
    const val SUBGRAPH_CATEGORY = "subgraph"

    // ---------------------------------------------------------------------------------------------------------------
    // Exposed view
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Expose the values of every instance of the top level in place: its promoted values and the values of the
     * subgraph's inputs no link feeds become inputs of the instance node (in the subgraph's order, then the promoted
     * ones); the promoted list goes, [absorb] rebuilds it from the inputs.
     */
    fun expose(root: JSONObject) {
        val definitions = definitionsOf(root) ?: return
        val nodes = nodesOf(root)
        for (nodeId in nodes.keys()) {
            val node = nodes.optJSONObject(nodeId) ?: continue
            val definitionId = node.optString("class_type")
            val definition = definitions.optJSONObject(definitionId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: JSONObject().also { node.put("inputs", it) }
            val promoted = node.optJSONArray(PROMOTED_KEY)
            node.remove(PROMOTED_KEY)
            val exposed = JSONObject()
            // The node's own keys first (links), then the subgraph's values in order, then the promoted ones
            for (key in inputs.keys()) exposed.put(key, inputs.get(key))
            for (port in definition.optJSONArray("inputs").objects()) {
                val name = port.optString("name").ifEmpty { continue }
                if (exposed.has(name)) continue
                valueAt(definitions, definitionId, name)?.let { exposed.put(name, it) }
            }
            if (promoted != null) for (i in 0 until promoted.length()) {
                val key = promoted.optString(i).takeIf { isPromotedKey(it) } ?: continue
                if (exposed.has(key)) continue
                valueAt(definitions, definitionId, key)?.let { exposed.put(key, it) }
            }
            node.put("inputs", exposed)
        }
    }

    /**
     * Take an exposed workflow back to its stored form in place: each instance's values go into its definition where
     * they differ from it (an instance whose values weren't changed leaves the definition alone, so another instance's
     * change stays), the promoted list is rebuilt from the instance's paths, and definitions no instance uses go (unless
     * [prune] is false: a level inside a subgraph, whose own definition no node of the level uses).
     */
    fun absorb(root: JSONObject, prune: Boolean = true) {
        val definitions = definitionsOf(root) ?: return
        val original = JSONObject(definitions.toString())
        val nodes = nodesOf(root)
        for (nodeId in nodes.keys()) {
            val node = nodes.optJSONObject(nodeId) ?: continue
            val definitionId = node.optString("class_type")
            val definition = definitions.optJSONObject(definitionId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue
            val portNames = definition.optJSONArray("inputs").objects().map { it.optString("name") }.toSet()
            // A list already there (the workflow wasn't exposed) stays, the instance's paths are added to it
            val promoted = node.optJSONArray(PROMOTED_KEY) ?: JSONArray()
            for (key in inputs.keys().asSequence().toList()) {
                val value = inputs.opt(key)
                val promotedKey = isPromotedKey(key)
                if (!promotedKey && (key !in portNames || isLink(value))) continue
                if (promotedKey && (0 until promoted.length()).none { promoted.optString(it) == key }) promoted.put(key)
                if (value != null && value != JSONObject.NULL && !isLink(value) &&
                    !sameValue(value, valueAt(original, definitionId, key))
                ) {
                    setValueAt(definitions, definitionId, key, value)
                }
                inputs.remove(key)
            }
            if (promoted.length() > 0) node.put(PROMOTED_KEY, promoted) else node.remove(PROMOTED_KEY)
        }
        if (prune) pruneUnused(root)
    }

    /** Remove definitions that no instance uses, at the top level or inside a used definition. */
    private fun pruneUnused(root: JSONObject) {
        val definitions = definitionsOf(root) ?: return
        val used = mutableSetOf<String>()
        fun visit(nodes: JSONObject?, depth: Int) {
            if (nodes == null || depth > MAX_DEPTH) return
            for (id in nodes.keys()) {
                val type = nodes.optJSONObject(id)?.optString("class_type") ?: continue
                if (definitions.has(type) && used.add(type)) {
                    visit(definitions.optJSONObject(type)?.optJSONObject("nodes"), depth + 1)
                }
            }
        }
        visit(nodesOf(root), 0)
        val unused = definitions.keys().asSequence().filter { it !in used }.toList()
        unused.forEach { definitions.remove(it) }
        if (unused.isNotEmpty()) DebugLogger.d(TAG, "Removed ${unused.size} unused subgraph definitions")
        if (definitions.length() == 0) root.remove(KEY)
    }

    /** [expose] on a workflow's JSON text; the text unchanged when it has no subgraphs. */
    fun exposeJson(json: String): String = transform(json) { expose(it) }

    /** [absorb] on a workflow's JSON text; the text unchanged when it has no subgraphs. */
    fun absorbJson(json: String): String = transform(json) { absorb(it) }

    private fun transform(json: String, block: (JSONObject) -> Unit): String {
        if (!json.contains("\"$KEY\"")) return json
        return try {
            val root = JSONObject(json)
            if (definitionsOf(root) == null) return json
            block(root)
            root.toString()
        } catch (e: Exception) {
            json
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // From the flat view to the exposed one
    // ---------------------------------------------------------------------------------------------------------------

    /** The input of a definition that feeds value [input] of its node [nodeId], by name. */
    private fun portFeeding(definitions: JSONObject, definitionId: String, nodeId: String, input: String): String? =
        definitions.optJSONObject(definitionId)?.optJSONArray("inputs").objects()
            .find { port -> targetsOf(port).any { it.first == nodeId && it.second == input } }?.optString("name")

    /**
     * Where a value of the flat view (node [flatNodeId] as [flatten] names it, input [input]) is in the exposed view of
     * [root] (stored): a node of the top level and its input; for a value inside a subgraph its instance and the
     * subgraph's input feeding it, else its path.
     */
    fun exposedAddress(root: JSONObject, flatNodeId: String, input: String): Pair<String, String> {
        val parts = flatNodeId.split(ID_SEPARATOR)
        val definitions = definitionsOf(root)
        if (parts.size == 1 || definitions == null) return flatNodeId to input
        // The definitions along the way: of the top-level instance, then of each nested one
        val definitionIds = mutableListOf<String>()
        var type = nodesOf(root).optJSONObject(parts[0])?.optString("class_type") ?: return flatNodeId to input
        definitionIds.add(type)
        for (i in 1 until parts.size - 1) {
            type = definitions.optJSONObject(type)?.optJSONObject("nodes")?.optJSONObject(parts[i])?.optString("class_type")
                ?: return flatNodeId to input
            definitionIds.add(type)
        }
        // From the inside out: a subgraph's input feeding it names it, else its path
        var key = portFeeding(definitions, definitionIds.last(), parts.last(), input) ?: "${parts.last()}$PATH_SEPARATOR$input"
        for (i in definitionIds.size - 2 downTo 0) {
            val node = parts[i + 1]
            key = (if (isPromotedKey(key)) null else portFeeding(definitions, definitionIds[i], node, key))
                ?: "$node$PATH_SEPARATOR$key"
        }
        return parts[0] to key
    }

    /** Make instance [nodeId] of [root] (stored) show value [key] from inside (a path); nothing to do for its inputs. */
    fun promote(root: JSONObject, nodeId: String, key: String) {
        if (!isPromotedKey(key)) return
        val node = nodesOf(root).optJSONObject(nodeId) ?: return
        val promoted = node.optJSONArray(PROMOTED_KEY) ?: JSONArray().also { node.put(PROMOTED_KEY, it) }
        if ((0 until promoted.length()).none { promoted.optString(it) == key }) promoted.put(key)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flat view
    // ---------------------------------------------------------------------------------------------------------------

    private sealed interface Source {
        data class Link(val nodeId: String, val slot: Int) : Source
        data class Value(val value: Any) : Source
    }

    /**
     * The workflow as it runs (a copy; exposed or stored): every instance replaced by the nodes of its definition,
     * nested ones too, with IDs "instance:node" (as ComfyUI sends them). A link into an instance's input reaches the
     * values that input feeds inside, a link from an instance's output comes from what feeds the subgraph's output;
     * a muted or bypassed instance takes its nodes along. The definitions go; groups and notes stay as they are.
     */
    fun flatten(root: JSONObject): JSONObject {
        val copy = JSONObject(root.toString())
        if (definitionsOf(copy) == null) return copy
        absorb(copy)
        val definitions = definitionsOf(copy) ?: return copy.also { it.remove(KEY) }
        val wrapped = copy.optJSONObject("nodes") != null
        val flat = JSONObject()
        Level(definitions, nodesOf(copy), prefix = "", inheritedMode = 0, bindings = emptyMap(), definition = null, out = flat, depth = 0).emit()
        if (wrapped) {
            copy.put("nodes", flat)
            copy.remove(KEY)
            return copy
        }
        return flat
    }

    /** [flatten] on a workflow's JSON text; the text unchanged when it has no subgraphs. */
    fun flattenJson(json: String): String {
        if (!json.contains("\"$KEY\"")) return json
        return try {
            val root = JSONObject(json)
            if (definitionsOf(root) == null) json else flatten(root).toString()
        } catch (e: Exception) {
            json
        }
    }

    /**
     * One level being written into [out] ([nodes]: the top level, or a definition instantiated under [prefix]);
     * [bindings] are what feeds the definition's inputs (by index).
     */
    private class Level(
        private val definitions: JSONObject,
        private val nodes: JSONObject,
        private val prefix: String,
        private val inheritedMode: Int,
        private val bindings: Map<Int, Source?>,
        private val definition: JSONObject?,
        private val out: JSONObject,
        private val depth: Int
    ) {
        // Values fed by the definition's inputs: (node, input) -> source
        private val fed = mutableMapOf<Pair<String, String>, Source>()
        private val instanceOutputs = mutableMapOf<String, List<Source?>>()

        init {
            definition?.optJSONArray("inputs").objects().forEachIndexed { index, port ->
                val source = bindings[index] ?: return@forEachIndexed
                targetsOf(port).forEach { target -> fed[target] = source }
            }
        }

        private fun sourceOf(link: JSONArray): Source? {
            val id = link.optString(0)
            val node = nodes.optJSONObject(id)
            if (node != null && definitions.has(node.optString("class_type"))) {
                return instance(id, node).getOrNull(link.optInt(1))
            }
            return Source.Link(prefix + id, link.optInt(1))
        }

        private fun sourceOfValue(value: Any?): Source? = when {
            value == null || value == JSONObject.NULL -> null
            isLink(value) -> sourceOf(value as JSONArray)
            else -> Source.Value(value)
        }

        /** An instance of this level: its definition's nodes written once; its outputs' sources. */
        private fun instance(id: String, node: JSONObject): List<Source?> {
            instanceOutputs[id]?.let { return it }
            instanceOutputs[id] = emptyList()  // a loop through instances resolves to nothing
            val child = definitions.optJSONObject(node.optString("class_type")) ?: return emptyList()
            val inputs = node.optJSONObject("inputs") ?: JSONObject()
            val childBindings = child.optJSONArray("inputs").objects().mapIndexed { index, port ->
                val name = port.optString("name")
                index to (fed[id to name] ?: sourceOfValue(inputs.opt(name)))
            }.toMap()
            val mode = node.optInt("mode", 0).takeIf { it != 0 } ?: inheritedMode
            val outputs = Level(
                definitions, child.optJSONObject("nodes") ?: JSONObject(), "$prefix$id$ID_SEPARATOR", mode,
                childBindings, child, out, depth + 1
            ).emit()
            instanceOutputs[id] = outputs
            return outputs
        }

        private fun JSONObject.putSource(key: String, source: Source?) {
            when (source) {
                is Source.Link -> put(key, JSONArray().put(source.nodeId).put(source.slot))
                is Source.Value -> put(key, source.value)
                null -> remove(key)
            }
        }

        /** Write the level's nodes; returns what feeds the definition's outputs. */
        fun emit(): List<Source?> {
            if (depth > MAX_DEPTH) {
                DebugLogger.w(TAG, "Subgraphs nested too deep, not flattened further")
                return emptyList()
            }
            for (id in nodes.keys().asSequence().toList()) {
                val node = nodes.optJSONObject(id) ?: continue
                if (definitions.has(node.optString("class_type"))) {
                    instance(id, node)
                    continue
                }
                val inputs = JSONObject()
                node.optJSONObject("inputs")?.let { own ->
                    for (key in own.keys()) {
                        val value = own.get(key)
                        if (isLink(value)) inputs.putSource(key, sourceOf(value as JSONArray)) else inputs.put(key, value)
                    }
                }
                fed.forEach { (target, source) -> if (target.first == id) inputs.putSource(target.second, source) }
                val flatNode = JSONObject(node.toString())
                flatNode.put("inputs", inputs)
                flatNode.remove(PROMOTED_KEY)
                val mode = node.optInt("mode", 0).takeIf { it != 0 } ?: inheritedMode
                if (mode != 0) flatNode.put("mode", mode)
                out.put(prefix + id, flatNode)
            }
            return definition?.optJSONArray("outputs").objects().map { port ->
                when {
                    port.optJSONArray("source") != null -> sourceOf(port.getJSONArray("source"))
                    port.has("input") -> bindings[port.optInt("input", -1)]
                    port.has("value") -> sourceOfValue(port.opt("value"))
                    else -> null
                }
            } ?: emptyList()
        }
    }
}
