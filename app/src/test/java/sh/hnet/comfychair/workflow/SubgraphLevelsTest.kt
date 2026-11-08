package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inside of a subgraph as an editor level: built from its definition with Inputs and Outputs port nodes, and
 * written back into it after the editor's parse and serialize. Small definitions written for each case.
 */
class SubgraphLevelsTest {

    /**
     * "outer": its input "image" feeds Blur's image, its input "radius" Blur's radius (Blur keeps its own 3); Blur's
     * output and the input "image" itself are its outputs. Inside also an instance of "inner" (Invert, sigma 0.5).
     */
    private fun definitions() = JSONObject(
        """
        {"outer": {"name": "Blur and invert", "extra": "kept",
           "inputs": [{"name": "image", "type": "IMAGE", "targets": [["11", "image"]]},
                      {"name": "radius", "type": "INT", "targets": [["11", "radius"]]}],
           "outputs": [{"name": "IMAGE", "type": "IMAGE", "source": ["11", 0]},
                       {"name": "IMAGE", "type": "IMAGE", "input": 0}],
           "nodes": {"11": {"class_type": "Blur", "inputs": {"radius": 3, "sigma": 1.0}},
                     "12": {"class_type": "inner", "_meta": {"title": "Invert it"}, "inputs": {"image": ["11", 0]}, "promoted": ["21/sigma"]},
                     "13": {"class_type": "PrimitiveInt", "_meta": {"title": "Steps"}, "inputs": {"value": 20}}},
           "groups": [{"id": 1, "title": "G", "member_nodes": ["11"]}]},
         "inner": {"name": "Inner",
           "inputs": [{"name": "image", "type": "IMAGE", "targets": [["21", "image"]]}],
           "outputs": [{"name": "IMAGE", "type": "IMAGE", "source": ["21", 0]}],
           "nodes": {"21": {"class_type": "Invert", "inputs": {"sigma": 0.5}}}}}
        """
    )

    /** JSON with object keys sorted, so two documents compare whatever order org.json keeps. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Number -> value.toDouble().toString()
        else -> JSONObject.quote(value.toString())
    }

    /** What the editor does with a level: parse it, then serialize it as a level (no definitions). */
    private fun throughEditor(level: SubgraphLevels.Level): JSONObject {
        val graph = WorkflowParser(logDetails = false).parse(level.root.toString())
        return JSONObject(WorkflowSerializer().serializeWithWrapper(graph.copy(subgraphs = null)))
    }

    @Test
    fun `a level has port nodes, and values fed by inputs are linked from the Inputs node`() {
        val level = SubgraphLevels.levelOf(definitions(), "outer")!!
        val nodes = level.root.getJSONObject("nodes")
        assertTrue(nodes.has(SubgraphLevels.INPUTS_ID) && nodes.has(SubgraphLevels.OUTPUTS_ID))
        assertEquals("[\"-10\",1]", nodes.getJSONObject("11").getJSONObject("inputs").get("radius").toString())
        assertEquals(3, level.kept["11" to "radius"])
        // Outputs by unique keys: a repeated name gets _1; one fed straight by an input is linked from the Inputs node
        val outputs = nodes.getJSONObject(SubgraphLevels.OUTPUTS_ID).getJSONObject("inputs")
        assertEquals("[\"11\",0]", outputs.get("IMAGE").toString())
        assertEquals("[\"-10\",0]", outputs.get("IMAGE_1").toString())
        assertTrue(level.root.has(Subgraphs.KEY))
    }

    @Test
    fun `a level through the editor writes back the definition it came from`() {
        val before = definitions()
        val level = SubgraphLevels.levelOf(before, "outer")!!
        val after = SubgraphLevels.write(before, "outer", throughEditor(level), level.kept)
        // Groups gain the serializer's bounding box; the rest is as it was
        listOf("outer", "inner").forEach { id ->
            val a = before.getJSONObject(id); val b = after.getJSONObject(id)
            for (key in listOf("name", "inputs", "outputs", "extra")) assertEquals("$id.$key", canonical(a.opt(key)), canonical(b.opt(key)))
            val nodesA = a.getJSONObject("nodes"); val nodesB = b.getJSONObject("nodes")
            assertEquals(nodesA.keys().asSequence().toSet(), nodesB.keys().asSequence().toSet())
            for (node in nodesA.keys()) {
                assertEquals("$id/$node", canonical(nodesA.getJSONObject(node).opt("inputs")), canonical(nodesB.getJSONObject(node).opt("inputs")))
                assertEquals(canonical(nodesA.getJSONObject(node).opt("promoted")), canonical(nodesB.getJSONObject(node).opt("promoted")))
            }
        }
        assertEquals("G", after.getJSONObject("outer").getJSONArray("groups").getJSONObject(0).getString("title"))
    }

    @Test
    fun `changes inside go into the definition, links to the ports into its inputs and outputs`() {
        val before = definitions()
        val level = SubgraphLevels.levelOf(before, "outer")!!
        val edited = throughEditor(level)
        val nodes = edited.getJSONObject("nodes")
        // Blur's sigma changed; the input "image" no longer feeds Blur (Blur's image unlinked), now feeds the output 1
        nodes.getJSONObject("11").getJSONObject("inputs").put("sigma", 2.5).remove("image")
        // The nested instance's value it shows changed
        nodes.getJSONObject("12").getJSONObject("inputs").put("21/sigma", 0.75)
        // Output 0 now from the nested instance
        nodes.getJSONObject(SubgraphLevels.OUTPUTS_ID).getJSONObject("inputs").put("IMAGE", JSONArray().put("12").put(0))
        val after = SubgraphLevels.write(before, "outer", edited, level.kept)
        val outer = after.getJSONObject("outer")
        assertEquals(2.5, outer.getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getDouble("sigma"), 0.0)
        assertEquals("[]", outer.getJSONArray("inputs").getJSONObject(0).getJSONArray("targets").toString())
        // A value an input still feeds gets its own value back in the definition
        assertEquals(3, outer.getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getInt("radius"))
        assertEquals("[\"12\",0]", outer.getJSONArray("outputs").getJSONObject(0).getJSONArray("source").toString())
        assertEquals(0, outer.getJSONArray("outputs").getJSONObject(1).getInt("input"))
        assertEquals(0.75, after.getJSONObject("inner").getJSONObject("nodes").getJSONObject("21").getJSONObject("inputs").getDouble("sigma"), 0.0)
        // The nested instance keeps its list, without its values
        val nested = outer.getJSONObject("nodes").getJSONObject("12")
        assertEquals("[\"21/sigma\"]", nested.getJSONArray(Subgraphs.PROMOTED_KEY).toString())
        assertFalse(nested.getJSONObject("inputs").has("21/sigma"))
        assertFalse(outer.getJSONObject("nodes").has(SubgraphLevels.INPUTS_ID))
    }

    @Test
    fun `port nodes get their types from the definition`() {
        val level = SubgraphLevels.levelOf(definitions(), "outer")!!
        val types = level.root.getJSONObject("nodes").let { nodes ->
            SubgraphLevels.portTypes(definitions(), nodes.keys().asSequence().map { nodes.getJSONObject(it).getString("class_type") }.toList())
        }
        val inputs = types.values.single { it.inputs.isEmpty() }
        // The add socket last (spec §19.5)
        assertEquals(listOf("image", "radius", SubgraphLevels.ADD_SOCKET), inputs.outputs.map { it.name })
        val outputs = types.values.single { it.outputs.isEmpty() }
        assertEquals(listOf("IMAGE", "IMAGE_1", SubgraphLevels.ADD_SOCKET), outputs.inputs.map { it.name })
        assertTrue(outputs.inputs.all { it.forceInput })
    }

    @Test
    fun `counts, usage and labels`() {
        val definitions = definitions()
        // Blur, Primitive, and Invert inside the nested one
        assertEquals(3, SubgraphLevels.nodeCount(definitions, "outer"))
        assertEquals(2, SubgraphLevels.usageCount(definitions, listOf("outer", "outer", "Save"), "outer"))
        assertEquals(1, SubgraphLevels.usageCount(definitions, listOf("outer"), "inner"))
        // A primitive by its title, another value by its name, a repeated one with its node's title
        val labels = SubgraphLevels.promotedLabels(definitions, "outer", listOf("13/value", "11/sigma", "12/21/sigma", "image"))
        assertEquals("Steps", labels["13/value"])
        assertEquals("sigma (Blur)", labels["11/sigma"])
        assertEquals("sigma (Invert)", labels["12/21/sigma"])
        assertFalse(labels.containsKey("image"))
    }

    @Test
    fun `a node value changed inside a subgraph goes into its definition when the workflow is prepared`() {
        val stored = JSONObject().put("nodes", JSONObject("""{"5": {"class_type": "outer", "inputs": {}, "promoted": ["12/21/sigma"]}}"""))
            .put(Subgraphs.KEY, definitions())
        val exposed = JSONObject(Subgraphs.exposeJson(stored.toString()))
        // Changed inside: Blur's sigma (not shown on the instance) and the nested value it shows
        val edited = JSONObject(sh.hnet.comfychair.WorkflowManager.applyNodeAttributeEdits(
            exposed.toString(), mapOf("5" to mapOf("11/sigma" to 2.0, "12/21/sigma" to 0.25))
        ))
        val flat = Subgraphs.flatten(edited).getJSONObject("nodes")
        assertEquals(2.0, flat.getJSONObject("5:11").getJSONObject("inputs").getDouble("sigma"), 0.0)
        assertEquals(0.25, flat.getJSONObject("5:12:21").getJSONObject("inputs").getDouble("sigma"), 0.0)
        // The instance doesn't start showing a value changed inside
        assertFalse(edited.getJSONObject("nodes").getJSONObject("5").getJSONObject("inputs").has("11/sigma"))
    }
}
