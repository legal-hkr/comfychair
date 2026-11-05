package sh.hnet.comfychair.workflow

/*
 * A node's values as the Node values mode sees them: the value the workflow holds (or the node's default when it holds
 * none), the user's edit, and whether the two differ. Shared by the values sheet, the pill's count and the canvas.
 */

/** Types the values sheet edits; the rest are connections (MODEL, LATENT…). */
private val EDITABLE_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM")

/**
 * The workflow's own value of an input: its literal, or the node's default when the workflow leaves it empty. This is
 * what Reset goes back to.
 */
fun workflowValueOf(node: WorkflowNode, definition: InputDefinition?, name: String): Any? {
    val raw = (node.inputs[name] as? InputValue.Literal)?.value
    return if ((raw == null || raw == "") && definition != null) definition.getEffectiveDefault() else raw
}

/** Equal values, also when one is stored as a number of another kind (8 and 8.0) or as text. */
fun nodeValuesEqual(a: Any?, b: Any?, type: String): Boolean {
    if (a == b) return true
    if (a == null || b == null) return false
    fun number(v: Any) = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    return when (type) {
        "INT" -> number(a)?.toLong().let { it != null && it == number(b)?.toLong() }
        "FLOAT" -> number(a).let { it != null && it == number(b) }
        else -> a == b
    }
}

/** Inputs the user can change here: literal values that the app doesn't fill in and that aren't connections. */
fun editableInputNames(node: WorkflowNode, nodeDefinition: NodeTypeDefinition?): List<String> {
    val definitions = nodeDefinition?.inputs?.filter { !it.forceInput && it.type in EDITABLE_TYPES }
    val names = definitions?.takeIf { it.isNotEmpty() }?.map { it.name } ?: node.inputs.keys.toList()
    return names.filter { name ->
        when (val value = node.inputs[name]) {
            is InputValue.Connection -> false
            is InputValue.Literal -> !TemplateKeyRegistry.isAppManagedValue(value.value)
            else -> true
        }
    }
}

/** Inputs the app fills in when generating (prompt, seed, images…): input name to the placeholder or marker. */
fun appManagedInputs(node: WorkflowNode): List<Pair<String, String>> =
    node.inputs.mapNotNull { (name, value) ->
        val literal = (value as? InputValue.Literal)?.value
        if (TemplateKeyRegistry.isAppManagedValue(literal)) name to literal.toString() else null
    }

/** Names of the inputs whose edit differs from the workflow's value. */
fun changedInputNames(node: WorkflowNode, nodeDefinition: NodeTypeDefinition?, edits: Map<String, Any>?): Set<String> {
    if (edits.isNullOrEmpty()) return emptySet()
    return edits.filter { (name, edit) ->
        val definition = nodeDefinition?.inputs?.find { it.name == name }
        val original = workflowValueOf(node, definition, name)
        val type = definition?.type ?: when (original) {
            is Int, is Long -> "INT"
            is Number -> "FLOAT"
            else -> "STRING"
        }
        !nodeValuesEqual(edit, original, type)
    }.keys
}

/** Number of changed values in the whole graph. */
fun changedValueCount(graph: WorkflowGraph, definitions: Map<String, NodeTypeDefinition>, edits: Map<String, Map<String, Any>>): Int =
    edits.entries.sumOf { (nodeId, nodeEdits) ->
        graph.nodes.find { it.id == nodeId }?.let { changedInputNames(it, definitions[it.classType], nodeEdits).size } ?: 0
    }
