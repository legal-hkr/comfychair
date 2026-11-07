package sh.hnet.comfychair.model

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A custom field's value type: the ComfyUI widget it fills. */
enum class FieldValueType {
    INT, FLOAT, BOOLEAN, STRING, LIST;

    companion object {
        /** The type of a node input from its definition (`/object_info`), or null for an input that isn't a value. */
        fun of(definitionType: String?, options: List<String>?): FieldValueType? = when {
            options != null -> LIST
            else -> when (definitionType?.uppercase()) {
                "INT" -> INT
                "FLOAT" -> FLOAT
                "BOOLEAN" -> BOOLEAN
                "STRING" -> STRING
                "COMBO", "ENUM" -> LIST
                else -> null
            }
        }
    }
}

/** A node value a field fills: the node's ID and the input's name. */
data class FieldTarget(val nodeId: String, val input: String)

/**
 * A field the user made from node values (spec §18): a name of their own on the generation screen, filling every
 * [targets] value with what's set there. Its range, step and options come from the first target's node definition.
 *
 * @param id Stable: the saved value and a future dock chip refer to it, so renaming keeps them
 * @param default The value the first target had when the field was made, used until the user sets one
 */
data class CustomField(
    val id: String,
    val name: String,
    val type: FieldValueType,
    val default: String,
    val targets: List<FieldTarget>
) {
    /** What stands in each target's value in the stored workflow: a placeholder like the built-in fields'. */
    val placeholder: String get() = "{{${CustomFields.PLACEHOLDER_PREFIX}$id}}"
}

/**
 * Custom fields and labels of built-in fields as stored in a workflow's app wrapper (next to `defaults`, `groups`,
 * `notes`), so they go with the workflow when it's saved, duplicated, exported for ComfyChair or backed up. And
 * filling the fields' values into the workflow when it's prepared.
 */
object CustomFields {
    const val FIELDS_KEY = "customFields"
    const val LABELS_KEY = "fieldLabels"
    const val PLACEHOLDER_PREFIX = "field_"
    const val MAX_NAME_LENGTH = 40

    /** A custom field's placeholder; its name is a word (`\w+`), as built-in placeholders are matched by. */
    val PLACEHOLDER = Regex("""^\{\{field_([0-9a-f]{32})\}\}$""")

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun fromJson(array: JSONArray?): List<CustomField> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val json = array.optJSONObject(i) ?: return@mapNotNull null
            val id = json.optString("id").takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return@mapNotNull null
            val type = runCatching { FieldValueType.valueOf(json.optString("type")) }.getOrNull() ?: return@mapNotNull null
            val targetsJson = json.optJSONArray("targets") ?: return@mapNotNull null
            val targets = (0 until targetsJson.length()).mapNotNull { j ->
                targetsJson.optJSONObject(j)?.let { t ->
                    val node = t.optString("node")
                    val input = t.optString("input")
                    if (node.isNotEmpty() && input.isNotEmpty()) FieldTarget(node, input) else null
                }
            }
            if (targets.isEmpty()) return@mapNotNull null
            CustomField(id, json.optString("name").take(MAX_NAME_LENGTH), type, json.optString("default"), targets)
        }
    }

    fun toJson(fields: List<CustomField>): JSONArray = JSONArray().apply {
        fields.forEach { field ->
            put(JSONObject().apply {
                put("id", field.id)
                put("name", field.name)
                put("type", field.type.name)
                put("default", field.default)
                put("targets", JSONArray().apply {
                    field.targets.forEach { put(JSONObject().put("node", it.nodeId).put("input", it.input)) }
                })
            })
        }
    }

    fun labelsFromJson(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return json.keys().asSequence()
            .mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() }?.let { key to it.take(MAX_NAME_LENGTH) } }
            .toMap()
    }

    fun labelsToJson(labels: Map<String, String>): JSONObject = JSONObject().apply { labels.forEach { (k, v) -> put(k, v) } }

    /** The custom fields of a stored workflow (none for one that isn't wrapped, e.g. a plain API file). */
    fun fieldsOf(workflowJson: String): List<CustomField> =
        runCatching { fromJson(JSONObject(workflowJson).optJSONArray(FIELDS_KEY)) }.getOrDefault(emptyList())

    /** The labels a stored workflow gives its built-in fields, by field key. */
    fun labelsOf(workflowJson: String): Map<String, String> =
        runCatching { labelsFromJson(JSONObject(workflowJson).optJSONObject(LABELS_KEY)) }.getOrDefault(emptyMap())

    /** The JSON value of [text] for a field of [type], or null when the text isn't one (an empty or mistyped number). */
    fun typedValue(type: FieldValueType, text: String): Any? = when (type) {
        FieldValueType.INT -> text.trim().toLongOrNull()
        FieldValueType.FLOAT -> text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        FieldValueType.BOOLEAN -> text.trim().lowercase().toBooleanStrictOrNull()
        FieldValueType.STRING, FieldValueType.LIST -> text
    }

    /**
     * Fill the custom fields' [values] (by field ID, as text) into [workflowJson]: every target holding the field's
     * placeholder gets the value as its type (a number, true/false or a string), the field's default when the value
     * isn't one. Structural, not text replacement: strings need no escaping and numbers aren't quoted.
     */
    fun apply(workflowJson: String, fields: List<CustomField>, values: Map<String, String>): String {
        if (fields.isEmpty()) return workflowJson
        return try {
            val root = JSONObject(workflowJson)
            val nodes = root.optJSONObject("nodes") ?: root
            for (field in fields) {
                val value = values[field.id]?.let { typedValue(field.type, it) } ?: typedValue(field.type, field.default) ?: continue
                for (target in field.targets) {
                    val inputs = nodes.optJSONObject(target.nodeId)?.optJSONObject("inputs") ?: continue
                    if (inputs.optString(target.input) == field.placeholder) inputs.put(target.input, value)
                }
            }
            root.toString()
        } catch (e: Exception) {
            workflowJson
        }
    }
}
