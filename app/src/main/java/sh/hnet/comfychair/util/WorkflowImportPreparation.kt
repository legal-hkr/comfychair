package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject

/** Shared shape check for local SAF and server /userdata imports. Does not save or execute. */
object WorkflowImportPreparation {
    data class Prepared(val jsonContent: String, val warnings: List<String>)

    fun prepare(content: String, getNodeDefinition: (String) -> sh.hnet.comfychair.workflow.NodeTypeDefinition?): Prepared? {
        if (!isImportableJson(content)) return null
        return try {
            val json = JSONObject(content)
            if (WorkflowJsonAnalyzer.isLiteGraphFormat(json)) {
                val result = LiteGraphConverter(getNodeDefinition).convert(json)
                if (!isImportableJson(result.jsonContent)) null
                else Prepared(result.jsonContent, result.warnings)
            } else Prepared(content, emptyList())
        } catch (e: Exception) { null }
    }

    fun isImportableJson(content: String): Boolean = try {
        val json = JSONObject(content)
        when (val nodes = json.opt("nodes")) {
            is JSONArray -> nodes.length() > 0 && json.optJSONArray("links") != null
            is JSONObject -> nodes.length() > 0 && nodes.keys().asSequence().any {
                nodes.optJSONObject(it)?.has("class_type") == true
            }
            else -> json.keys().asSequence().any { json.optJSONObject(it)?.has("class_type") == true }
        }
    } catch (e: Exception) { false }
}
