package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/*
 * Small pieces of the stored format shared by the subgraph code, the ComfyUI export and the import (spec §19, format
 * in Subgraphs' KDoc): one definition of a link, of a definition's input targets and of a name made unique.
 */

/** A link in a node's inputs: `["node", output]`. */
internal fun isLink(value: Any?): Boolean =
    value is JSONArray && value.length() == 2 && value.opt(0) is String && value.opt(1) is Number

/** The objects of a JSON array (none for a missing one), other entries skipped. */
internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** The values a definition's input feeds inside it: its `targets`, (node, input) each. */
internal fun targetsOf(port: JSONObject): List<Pair<String, String>> {
    val targets = port.optJSONArray("targets") ?: return emptyList()
    return (0 until targets.length()).mapNotNull { i ->
        targets.optJSONArray(i)?.takeIf { it.length() >= 2 }?.let { it.optString(0) to it.optString(1) }
    }
}

/** [base], or once [taken] it with _1, _2… (names of inputs and outputs, keys of the Outputs node). */
internal fun uniqueName(base: String, taken: (String) -> Boolean): String {
    var candidate = base
    var n = 1
    while (taken(candidate)) candidate = "${base}_${n++}"
    return candidate
}
