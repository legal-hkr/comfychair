package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Making a subgraph of a selection and unpacking it: what runs stays the same; inputs and outputs from the links that
 * cross the selection. Small workflows written for each case.
 */
class SubgraphEditingTest {

    /** Load -> Blur (radius 3) -> Invert -> Save; Load also -> Save2; Blur's sigma from a primitive. */
    private fun level() = JSONObject(
        """
        {"nodes": {
           "1": {"class_type": "Load", "inputs": {"name": "a.png"}},
           "2": {"class_type": "Blur", "inputs": {"image": ["1", 0], "radius": 3, "sigma": ["6", 0]}},
           "3": {"class_type": "Invert", "inputs": {"image": ["2", 0]}},
           "4": {"class_type": "Save", "inputs": {"images": ["3", 0], "prefix": "{{output_prefix}}"}},
           "5": {"class_type": "Save", "inputs": {"images": ["1", 0], "prefix": "b"}},
           "6": {"class_type": "PrimitiveFloat", "inputs": {"value": 1.5}}},
         "groups": [{"id": 1, "title": "Inside", "member_nodes": ["2", "3"]}, {"id": 2, "title": "Mixed", "member_nodes": ["3", "4"]}],
         "notes": [{"id": 1, "title": "Note", "content": "x"}]}
        """
    )

    private val types = mapOf("1" to "IMAGE", "2" to "IMAGE", "3" to "IMAGE", "6" to "FLOAT")

    private fun make(level: JSONObject, selected: Set<String>, promote: Set<Pair<String, String>> = emptySet(), kept: Map<Pair<String, String>, Any> = emptyMap()) =
        SubgraphEditing.make(
            level, JSONObject(), selected, setOf(1), "Blur and invert", "9", kept,
            typeOf = { id, _ -> types[id] ?: "*" }, outputNameOf = { id, _ -> if (id == "6") "FLOAT" else "IMAGE" },
            promote = promote, position = null
        )

    /** Nodes that run, as hashes of their type, values and what feeds them (IDs don't matter). */
    private fun runs(root: JSONObject): List<String> {
        val nodes = Subgraphs.flatten(root).getJSONObject("nodes")
        val memo = mutableMapOf<String, String>()
        fun h(id: String): String = memo.getOrPut(id) {
            val node = nodes.getJSONObject(id)
            val inputs = node.optJSONObject("inputs") ?: JSONObject()
            node.optString("class_type") + inputs.keys().asSequence().sorted().joinToString(",", "(", ")") { k ->
                val v = inputs.get(k)
                if (v is JSONArray && v.length() == 2 && v.opt(0) is String) "$k=${h(v.getString(0))}:${v.optInt(1)}" else "$k=$v"
            }
        }
        return nodes.keys().asSequence().map(::h).sorted().toList()
    }

    private fun root(made: SubgraphEditing.Made) = JSONObject(made.level.toString()).put(Subgraphs.KEY, made.definitions)

    @Test
    fun `links crossing the selection become its inputs and outputs, once per outside socket`() {
        val made = make(level(), setOf("2", "3"))
        val definition = made.definitions.getJSONObject(made.definitionId)
        // Load's image and the primitive's float come in; Invert's image goes out
        // (in the order of the nodes' inputs; the JVM's org.json keeps no order)
        assertEquals(setOf("image", "sigma"), (0 until definition.getJSONArray("inputs").length()).map { definition.getJSONArray("inputs").getJSONObject(it).getString("name") }.toSet())
        assertEquals(1, definition.getJSONArray("outputs").length())
        val nodes = made.level.getJSONObject("nodes")
        assertEquals("[\"9\",0]", nodes.getJSONObject("4").getJSONObject("inputs").get("images").toString())
        assertEquals("[\"1\",0]", nodes.getJSONObject("9").getJSONObject("inputs").get("image").toString())
        assertEquals("[\"6\",0]", nodes.getJSONObject("9").getJSONObject("inputs").get("sigma").toString())
        assertFalse(nodes.has("2") || nodes.has("3"))
        // The group of moved nodes moved, the mixed one lost its moved member; the selected note moved
        assertEquals("Inside", definition.getJSONArray("groups").getJSONObject(0).getString("title"))
        assertEquals("[\"4\"]", made.level.getJSONArray("groups").getJSONObject(0).getJSONArray("member_nodes").toString())
        assertEquals(0, made.level.getJSONArray("notes").length())
        assertEquals("Blur and invert", nodes.getJSONObject("9").getJSONObject("_meta").getString("title"))
    }

    @Test
    fun `making and unpacking a subgraph keeps what runs`() {
        val before = runs(level())
        for (selection in listOf(setOf("2", "3"), setOf("2"), setOf("1", "2", "3", "4", "5", "6"), setOf("3", "4"))) {
            val made = make(level(), selection, promote = setOf("4" to "prefix"))
            val inside = root(made)
            assertEquals("$selection made", before, runs(inside))
            var next = 100
            val unpacked = SubgraphEditing.unpack(
                JSONObject(made.level.toString()), made.definitions, "9", { (next++).toString() }, { 50 }, { 60 }, null
            )!!
            assertEquals("$selection unpacked", before, runs(JSONObject(unpacked.level.toString()).put(Subgraphs.KEY, made.definitions)))
        }
    }

    @Test
    fun `a value a field fills is shown on the instance, a value that took a link keeps its own inside`() {
        val made = make(level(), setOf("3", "4"), promote = setOf("4" to "prefix"))
        val instance = made.level.getJSONObject("nodes").getJSONObject("9")
        assertEquals("[\"4/prefix\"]", instance.getJSONArray(Subgraphs.PROMOTED_KEY).toString())
        val kept = make(level(), setOf("2"), kept = mapOf(("2" to "sigma") to 2.5))
        assertEquals(2.5, kept.definitions.getJSONObject(kept.definitionId).getJSONObject("nodes").getJSONObject("2").getJSONObject("inputs").getDouble("sigma"), 0.0)
    }

    @Test
    fun `removing an output elsewhere moves the later links up`() {
        val made = make(level(), setOf("1"))
        // Load's one output feeds Blur and Save2: one output; add a second user of it inside another definition
        val definitions = JSONObject(made.definitions.toString()).put("outer", JSONObject(
            """{"name": "o", "nodes": {"1": {"class_type": "${made.definitionId}", "inputs": {}}, "2": {"class_type": "Save", "inputs": {"images": ["1", 0]}}},
               "outputs": [{"name": "IMAGE", "type": "IMAGE", "source": ["1", 0]}]}"""
        ))
        SubgraphEditing.removeOutputInDefinitions(definitions, made.definitionId, 0)
        val outer = definitions.getJSONObject("outer")
        assertFalse(outer.getJSONObject("nodes").getJSONObject("2").getJSONObject("inputs").has("images"))
        assertFalse(outer.getJSONArray("outputs").getJSONObject(0).has("source"))
        SubgraphEditing.changeInputInDefinitions(definitions, made.definitionId, "x", "y")
        assertTrue(definitions.has("outer"))
    }

    @Test
    fun `an input renamed follows in other definitions, their own inputs feeding instances by its name too`() {
        val definitions = org.json.JSONObject(
            """
            {"inner": {"name": "Inner", "inputs": [{"name": "image", "type": "IMAGE", "targets": [["1", "image"]]}],
                       "nodes": {"1": {"class_type": "Invert", "inputs": {}}}},
             "outer": {"name": "Outer", "inputs": [{"name": "picture", "type": "IMAGE", "targets": [["7", "image"], ["8", "x"]]}],
                       "nodes": {"7": {"class_type": "inner", "inputs": {}}, "8": {"class_type": "Blur", "inputs": {}}}}}
            """
        )
        SubgraphEditing.changeInputInDefinitions(definitions, "inner", "image", "img")
        val targets = definitions.getJSONObject("outer").getJSONArray("inputs").getJSONObject(0).getJSONArray("targets")
        org.junit.Assert.assertEquals("[[\"7\",\"img\"],[\"8\",\"x\"]]", targets.toString())
        // Removed: the target into the instance goes, the other stays
        SubgraphEditing.changeInputInDefinitions(definitions, "inner", "img", null)
        org.junit.Assert.assertEquals("[[\"8\",\"x\"]]", definitions.getJSONObject("outer").getJSONArray("inputs").getJSONObject(0).getJSONArray("targets").toString())
    }
}
