package sh.hnet.comfychair.util

import org.json.JSONObject

/**
 * Readable descriptions of the "node_errors" object ComfyUI returns from /prompt when nodes fail
 * validation, e.g. "CreateVideo (fps): Value not in list".
 */
object NodeErrors {

    /**
     * One description per failing input, in server order, without duplicates.
     *
     * @param withDetails Also the server's details (e.g. "bit_depth: 'x' not in ['auto', 8, 10]"), for the full error
     */
    fun describe(nodeErrors: JSONObject?, withDetails: Boolean = false): List<String> {
        if (nodeErrors == null) return emptyList()
        val descriptions = LinkedHashSet<String>()
        for (nodeId in nodeErrors.keys()) {
            val node = nodeErrors.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type").ifEmpty { "Node $nodeId" }
            val errors = node.optJSONArray("errors")
            if (errors == null || errors.length() == 0) {
                descriptions.add(classType)
                continue
            }
            for (i in 0 until errors.length()) {
                val error = errors.optJSONObject(i) ?: continue
                val inputName = error.optJSONObject("extra_info")?.optString("input_name").orEmpty()
                val details = error.optString("details")
                val message = error.optString("message").ifEmpty { details }
                val target = if (inputName.isNotEmpty()) "$classType ($inputName)" else classType
                val full = if (withDetails && details.isNotEmpty() && details != message) "$message – $details" else message
                descriptions.add(if (full.isNotEmpty()) "$target: $full" else target)
            }
        }
        return descriptions.toList()
    }
}
