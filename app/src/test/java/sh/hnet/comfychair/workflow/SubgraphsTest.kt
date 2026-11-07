package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.CustomField
import sh.hnet.comfychair.model.CustomFields
import sh.hnet.comfychair.model.FieldTarget
import sh.hnet.comfychair.model.FieldValueType
import sh.hnet.comfychair.util.LiteGraphConverter

/**
 * Subgraphs kept in the stored workflow: what the import keeps, the exposed view the editor and the fields work on,
 * and the flat view that runs. Small workflows written for each case.
 */
class SubgraphsTest {

    private fun input(name: String, type: String) = InputDefinition(name = name, type = type, isRequired = true)

    private val definitions = mapOf(
        "Loader" to NodeTypeDefinition("Loader", "test", listOf(input("name", "ENUM")), listOf(OutputSlot("IMAGE", "IMAGE"))),
        "Blur" to NodeTypeDefinition(
            "Blur", "test", listOf(input("image", "IMAGE"), input("radius", "INT"), input("sigma", "FLOAT")),
            listOf(OutputSlot("IMAGE", "IMAGE"))
        ),
        "Invert" to NodeTypeDefinition("Invert", "test", listOf(input("image", "IMAGE")), listOf(OutputSlot("IMAGE", "IMAGE"))),
        "Save" to NodeTypeDefinition("Save", "test", listOf(input("images", "IMAGE"), input("prefix", "STRING")), emptyList())
    )

    private val converter = LiteGraphConverter { definitions[it] }

    /**
     * A UI workflow: Loader -> instance of "Outer" (image in, image out) -> Save. Outer: its input -> instance of "Inner"
     * (Invert) -> Blur (radius 3, sigma 1.0) -> its output; the instance promotes Blur's radius.
     */
    private fun uiWorkflow(proxyValues: String = "[]", innerSigmaFromInput: Boolean = false) = JSONObject(
        """
        {"nodes": [
           {"id": 1, "type": "Loader", "widgets_values": ["a.png"]},
           {"id": 5, "type": "outer", "title": "Blur and invert", "mode": 0,
            "inputs": [{"name": "image", "type": "IMAGE", "link": 1}],
            "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": [2]}],
            "properties": {"proxyWidgets": [["11", "radius"], ["11", "$${'$'}canvas-image-preview"]]},
            "widgets_values": $proxyValues},
           {"id": 7, "type": "Save", "widgets_values": ["out"], "inputs": [{"name": "images", "type": "IMAGE", "link": 2}]}],
         "links": [[1, 1, 0, 5, 0, "IMAGE"], [2, 5, 0, 7, 0, "IMAGE"]],
         "definitions": {"subgraphs": [
           {"id": "outer", "name": "Outer", "inputNode": {"id": -10}, "outputNode": {"id": -20},
            "inputs": [{"name": "image", "type": "IMAGE"}, {"name": "sigma", "type": "FLOAT"}],
            "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
            "nodes": [
              {"id": 10, "type": "inner", "inputs": [{"name": "image", "type": "IMAGE", "link": 1}],
               "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": [2]}]},
              {"id": 11, "type": "Blur", "widgets_values": [3, 1.0],
               "inputs": [{"name": "image", "type": "IMAGE", "link": 2}${if (innerSigmaFromInput) """, {"name": "sigma", "type": "FLOAT", "widget": {"name": "sigma"}, "link": 4}""" else ""}],
               "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": [3]}]}],
            "links": [{"id": 1, "origin_id": -10, "origin_slot": 0, "target_id": 10, "target_slot": 0},
                      {"id": 2, "origin_id": 10, "origin_slot": 0, "target_id": 11, "target_slot": 0},
                      {"id": 3, "origin_id": 11, "origin_slot": 0, "target_id": -20, "target_slot": 0},
                      {"id": 4, "origin_id": -10, "origin_slot": 1, "target_id": 11, "target_slot": 1}]},
           {"id": "inner", "name": "Inner", "inputNode": {"id": -10}, "outputNode": {"id": -20},
            "inputs": [{"name": "image", "type": "IMAGE"}], "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
            "nodes": [{"id": 20, "type": "Invert", "inputs": [{"name": "image", "type": "IMAGE", "link": 1}],
                       "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": [2]}]}],
            "links": [{"id": 1, "origin_id": -10, "origin_slot": 0, "target_id": 20, "target_slot": 0},
                      {"id": 2, "origin_id": 20, "origin_slot": 0, "target_id": -20, "target_slot": 0}]}]}}
        """
    )

    private fun stored(json: JSONObject = uiWorkflow()) = JSONObject(converter.convert(json).jsonContent)

    private fun JSONObject.node(id: String) = getJSONObject("nodes").getJSONObject(id)
    private fun JSONObject.definition(id: String) = getJSONObject(Subgraphs.KEY).getJSONObject(id)
    private fun JSONObject.inputs(id: String) = node(id).getJSONObject("inputs")

    @Test
    fun `the import keeps subgraphs as definitions and instances`() {
        val root = stored()
        val instance = root.node("5")
        assertEquals("outer", instance.getString("class_type"))
        assertEquals(JSONArray().put("1").put(0).toString(), instance.getJSONObject("inputs").getJSONArray("image").toString())
        assertEquals("[\"11/radius\"]", instance.getJSONArray(Subgraphs.PROMOTED_KEY).toString())
        assertEquals("Blur and invert", instance.getJSONObject("_meta").getString("title"))

        val outer = root.definition("outer")
        assertEquals("[[\"10\",\"image\"]]", outer.getJSONArray("inputs").getJSONObject(0).getJSONArray("targets").toString())
        assertEquals("[\"11\",0]", outer.getJSONArray("outputs").getJSONObject(0).getJSONArray("source").toString())
        // The nested instance and the node inside keep their own IDs and values
        assertEquals("inner", outer.getJSONObject("nodes").getJSONObject("10").getString("class_type"))
        assertEquals(3, outer.getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getInt("radius"))
        assertTrue(root.getJSONObject(Subgraphs.KEY).has("inner"))
    }

    @Test
    fun `values an instance gives go into its definition`() {
        val root = stored(uiWorkflow(proxyValues = "[9, null]"))
        assertEquals(9, root.definition("outer").getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getInt("radius"))
    }

    @Test
    fun `a subgraph's input feeding a value keeps the value inside for an instance that gives none`() {
        val root = stored(uiWorkflow(innerSigmaFromInput = true))
        val sigma = root.definition("outer").getJSONArray("inputs").getJSONObject(1)
        assertEquals("[[\"11\",\"sigma\"]]", sigma.getJSONArray("targets").toString())
        assertEquals(1.0, root.definition("outer").getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getDouble("sigma"), 0.0)
        // Exposed: the instance shows it as a value of its own
        Subgraphs.expose(root)
        assertEquals(1.0, root.inputs("5").getDouble("sigma"), 0.0)
    }

    @Test
    fun `an unnamed subgraph input gets a name and keeps its link`() {
        val ui = uiWorkflow()
        val outer = ui.getJSONObject("definitions").getJSONArray("subgraphs").getJSONObject(0)
        outer.getJSONArray("inputs").getJSONObject(0).put("name", "")
        ui.getJSONArray("nodes").getJSONObject(1).getJSONArray("inputs").getJSONObject(0).put("name", "")
        val root = stored(ui)
        assertEquals("input_1", root.definition("outer").getJSONArray("inputs").getJSONObject(0).getString("name"))
        assertTrue(root.inputs("5").has("input_1"))
        val flat = Subgraphs.flatten(root).getJSONObject("nodes")
        assertEquals("[\"1\",0]", flat.getJSONObject("5:10:20").getJSONObject("inputs").getJSONArray("image").toString())
    }

    @Test
    fun `the flat view runs the nodes inside with ComfyUI's IDs`() {
        val root = stored()
        root.node("5").put("mode", 4)
        val flat = Subgraphs.flatten(root)
        val nodes = flat.getJSONObject("nodes")
        assertEquals(setOf("1", "5:10:20", "5:11", "7"), nodes.keys().asSequence().toSet())
        assertFalse(flat.has(Subgraphs.KEY))
        // A link into the instance reaches the nested instance's node; a link from it comes from the node inside
        assertEquals("[\"1\",0]", nodes.getJSONObject("5:10:20").getJSONObject("inputs").getJSONArray("image").toString())
        assertEquals("[\"5:10:20\",0]", nodes.getJSONObject("5:11").getJSONObject("inputs").getJSONArray("image").toString())
        assertEquals("[\"5:11\",0]", nodes.getJSONObject("7").getJSONObject("inputs").getJSONArray("images").toString())
        // A bypassed instance takes its nodes along
        assertEquals(4, nodes.getJSONObject("5:11").getInt("mode"))
        assertEquals(4, nodes.getJSONObject("5:10:20").getInt("mode"))
        assertEquals(0, nodes.getJSONObject("7").optInt("mode", 0))
    }

    @Test
    fun `an output passing a subgraph's input through comes from what feeds the instance`() {
        val root = stored()
        val output = root.definition("outer").getJSONArray("outputs").getJSONObject(0)
        output.remove("source")
        output.put("input", 0)
        val nodes = Subgraphs.flatten(root).getJSONObject("nodes")
        assertEquals("[\"1\",0]", nodes.getJSONObject("7").getJSONObject("inputs").getJSONArray("images").toString())
    }

    @Test
    fun `exposing shows promoted values, absorbing writes changed ones back`() {
        val root = stored()
        Subgraphs.expose(root)
        assertEquals(3, root.inputs("5").getInt("11/radius"))
        assertFalse(root.node("5").has(Subgraphs.PROMOTED_KEY))

        root.inputs("5").put("11/radius", 12)
        Subgraphs.absorb(root)
        assertFalse(root.inputs("5").has("11/radius"))
        assertEquals("[\"11/radius\"]", root.node("5").getJSONArray(Subgraphs.PROMOTED_KEY).toString())
        assertEquals(12, root.definition("outer").getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getInt("radius"))
    }

    @Test
    fun `an instance left alone doesn't undo another instance's change`() {
        val root = stored()
        root.getJSONObject("nodes").put("6", JSONObject(root.node("5").toString()))
        Subgraphs.expose(root)
        root.inputs("5").put("11/radius", 12)  // "6" still shows 3
        Subgraphs.absorb(root)
        assertEquals(12, root.definition("outer").getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getInt("radius"))
    }

    @Test
    fun `definitions no instance uses go`() {
        val root = stored()
        root.getJSONObject("nodes").remove("5")
        Subgraphs.absorb(root)
        assertFalse(root.has(Subgraphs.KEY))
    }

    @Test
    fun `a value of the flat view is found where the editor shows it`() {
        val root = stored(uiWorkflow(innerSigmaFromInput = true))
        // Fed by the subgraph's input: that input
        assertEquals("5" to "sigma", Subgraphs.exposedAddress(root, "5:11", "sigma"))
        // Not fed by one: its path, promoted for the editor
        assertEquals("5" to "11/radius", Subgraphs.exposedAddress(root, "5:11", "radius"))
        // Through a nested instance, fed by the subgraphs' inputs on the way out
        assertEquals("5" to "image", Subgraphs.exposedAddress(root, "5:10:20", "image"))
        assertEquals("7" to "prefix", Subgraphs.exposedAddress(root, "7", "prefix"))
        Subgraphs.promote(root, "5", "10/20/image")
        assertEquals(2, root.node("5").getJSONArray(Subgraphs.PROMOTED_KEY).length())
    }

    @Test
    fun `a custom field on a promoted value fills the node inside`() {
        val field = CustomField(CustomFields.newId(), "Radius", FieldValueType.INT, "3", listOf(FieldTarget("5", "11/radius")))
        val exposed = stored().also { Subgraphs.expose(it) }
        exposed.inputs("5").put("11/radius", field.placeholder)
        Subgraphs.absorb(exposed)
        // As generation prepares it: exposed, filled, flattened
        val filled = CustomFields.apply(Subgraphs.exposeJson(exposed.toString()), listOf(field), mapOf(field.id to "21"))
        val nodes = JSONObject(Subgraphs.flattenJson(filled)).getJSONObject("nodes")
        assertEquals(21L, nodes.getJSONObject("5:11").getJSONObject("inputs").getLong("radius"))
    }

    @Test
    fun `the editor shows an instance with its values and saves them into the definition`() {
        val stored = stored().toString()
        val graph = WorkflowParser(logDetails = false).parse(stored, "w", "")
        val instance = graph.nodes.single { it.id == "5" }
        assertEquals(InputValue.Literal(3), instance.inputs["11/radius"])
        assertEquals(InputValue.Connection("1", 0), instance.inputs["image"])
        assertEquals("radius", Subgraphs.displayName("11/radius"))

        val edited = WorkflowSerializer().applyEdits(graph, mapOf("5" to mapOf("11/radius" to 7)))
        val saved = JSONObject(WorkflowSerializer().serializeWithWrapper(edited))
        assertNull(saved.inputs("5").opt("11/radius"))
        assertEquals(7, saved.definition("outer").getJSONObject("nodes").getJSONObject("11").getJSONObject("inputs").getInt("radius"))
        assertEquals("[\"11/radius\"]", saved.node("5").getJSONArray(Subgraphs.PROMOTED_KEY).toString())
    }

    @Test
    fun `an instance's type shows its inputs, promoted values and outputs`() {
        val root = stored(uiWorkflow(innerSigmaFromInput = true))
        val types = Subgraphs.nodeTypes(root.getJSONObject(Subgraphs.KEY), mapOf("outer" to listOf("11/radius")), definitions::get)
        val outer = types.getValue("outer")
        assertEquals(listOf("image", "sigma", "11/radius"), outer.inputs.map { it.name })
        assertEquals("FLOAT", outer.inputs[1].type)
        assertEquals("INT", outer.inputs[2].type)
        assertEquals(listOf("IMAGE"), outer.outputs.map { it.type })
        assertEquals("Outer", outer.displayName)
    }
}
