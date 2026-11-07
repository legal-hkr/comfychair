package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.Subgraphs

/**
 * What a UI workflow runs as, once the frontend's own nodes (reroutes, primitives) are gone and its subgraphs are
 * flattened for generation, on small workflows written for each case.
 */
class LiteGraphResolveTest {

    private fun input(name: String, type: String, socketless: Boolean = false, control: Boolean = false) =
        InputDefinition(name = name, type = type, isRequired = true, socketless = socketless, controlAfterGenerate = control)

    private val definitions = mapOf(
        "Loader" to (listOf(input("name", "ENUM")) to listOf("MODEL")),
        "Sampler" to (listOf(input("model", "MODEL"), input("seed", "INT", control = true), input("steps", "INT")) to listOf("LATENT")),
        "Compare" to (listOf(input("compare_view", "IMAGECOMPARE", socketless = true), input("image", "IMAGE")) to emptyList()),
        "Viewer3D" to (listOf(input("file", "ENUM"), input("width", "INT"), input("height", "INT")) to listOf("IMAGE"))
    )

    private val converter = LiteGraphConverter { type ->
        definitions[type]?.let { (inputs, outputs) ->
            NodeTypeDefinition(type, "test", inputs, outputs.mapIndexed { i, t -> OutputSlot(t, t) })
        }
    }

    /** The nodes that run: subgraphs kept by the conversion, flattened as for generation. */
    private fun convert(json: String): JSONObject =
        JSONObject(Subgraphs.flattenJson(converter.convert(JSONObject(json)).jsonContent)).getJSONObject("nodes")

    private fun JSONObject.inputs(id: String) = getJSONObject(id).getJSONObject("inputs")

    private fun JSONObject.link(name: String) = getJSONArray(name).let { it.getString(0) to it.getInt(1) }

    /** A subgraph whose inner Sampler takes its model and seed from the subgraph's inputs and gives its output. */
    private val samplerSubgraph = """
        {"id": "sub-1", "name": "Sampling",
         "inputNode": {"id": -10}, "outputNode": {"id": -20},
         "inputs": [{"name": "seed", "type": "INT"}, {"name": "model", "type": "MODEL"}],
         "outputs": [{"name": "LATENT", "type": "LATENT"}],
         "nodes": [{"id": 1, "type": "Sampler", "mode": 0, "widgets_values": [7, "fixed", 20],
                    "inputs": [{"name": "model", "type": "MODEL", "link": 1},
                               {"name": "seed", "type": "INT", "widget": {"name": "seed"}, "link": 2}]}],
         "links": [{"id": 1, "origin_id": -10, "origin_slot": 1, "target_id": 1, "target_slot": 0, "type": "MODEL"},
                   {"id": 2, "origin_id": -10, "origin_slot": 0, "target_id": 1, "target_slot": 1, "type": "INT"},
                   {"id": 3, "origin_id": 1, "origin_slot": 0, "target_id": -20, "target_slot": 0, "type": "LATENT"}]}
    """

    @Test
    fun `a widget without a socket keeps its value, or gets an empty one`() {
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "Compare", "widgets_values": []},
                          {"id": 2, "type": "Compare", "widgets_values": [{"a": 1}]}], "links": []}"""
        )
        assertEquals("", nodes.inputs("1").get("compare_view"))
        assertEquals(1, nodes.inputs("2").getJSONObject("compare_view").getInt("a"))
    }

    @Test
    fun `a link through reroutes comes from their source`() {
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "Loader", "widgets_values": ["m"]},
                          {"id": 2, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 10}]},
                          {"id": 3, "type": "Reroute", "inputs": [{"name": "", "type": "*", "link": 11}]},
                          {"id": 4, "type": "Sampler", "widgets_values": [1, "fixed", 4],
                           "inputs": [{"name": "model", "type": "MODEL", "link": 12}]}],
                "links": [[10, 1, 0, 2, 0, "MODEL"], [11, 2, 0, 3, 0, "MODEL"], [12, 3, 0, 4, 0, "MODEL"]]}"""
        )
        assertEquals("1" to 0, nodes.inputs("4").link("model"))
        assertFalse(nodes.has("2") || nodes.has("3"))
    }

    @Test
    fun `a primitive node's value goes into the widgets it feeds`() {
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "PrimitiveNode", "widgets_values": [30, "fixed"],
                           "outputs": [{"name": "INT", "type": "INT", "widget": {"name": "steps"}, "links": [5]}]},
                          {"id": 2, "type": "Sampler", "widgets_values": [1, "fixed", 4],
                           "inputs": [{"name": "steps", "type": "INT", "widget": {"name": "steps"}, "link": 5}]}],
                "links": [[5, 1, 0, 2, 2, "INT"]]}"""
        )
        assertEquals(30, nodes.inputs("2").get("steps"))
        assertFalse(nodes.has("1"))
    }

    @Test
    fun `a subgraph's inputs are matched by name and its output comes from inside`() {
        // The instance lists its inputs in another order than the subgraph, and only the linked ones
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "Loader", "widgets_values": ["m"]},
                          {"id": 2, "type": "sub-1", "widgets_values": [],
                           "inputs": [{"name": "model", "type": "MODEL", "link": 20}],
                           "outputs": [{"name": "LATENT", "type": "LATENT", "links": [21]}]},
                          {"id": 3, "type": "Compare", "widgets_values": [""],
                           "inputs": [{"name": "image", "type": "IMAGE", "link": 21}]}],
                "links": [[20, 1, 0, 2, 0, "MODEL"], [21, 2, 0, 3, 1, "LATENT"]],
                "definitions": {"subgraphs": [$samplerSubgraph]}}"""
        )
        val sampler = nodes.keys().asSequence().single { nodes.getJSONObject(it).getString("class_type") == "Sampler" }
        assertEquals("1" to 0, nodes.inputs(sampler).link("model"))
        assertEquals(sampler to 0, nodes.inputs("3").link("image"))
        // No link and no value for the seed: the node inside keeps its own
        assertEquals(7, nodes.inputs(sampler).get("seed"))
    }

    @Test
    fun `values promoted to an instance fill the widgets inside`() {
        // proxyWidgets: (node inside, widget) in the order of the instance's values
        val promoted = convert(
            """{"nodes": [{"id": 2, "type": "sub-1", "widgets_values": [99],
                           "properties": {"proxyWidgets": [["1", "seed"], ["1", "$${'$'}canvas-image-preview"]]}}],
                "links": [], "definitions": {"subgraphs": [$samplerSubgraph]}}"""
        )
        // Older files: no list, the values follow the subgraph's inputs that are values
        val older = convert(
            """{"nodes": [{"id": 2, "type": "sub-1", "widgets_values": [42]}],
                "links": [], "definitions": {"subgraphs": [$samplerSubgraph]}}"""
        )
        for ((nodes, seed) in listOf(promoted to 99, older to 42)) {
            val sampler = nodes.keys().asSequence().single()
            assertEquals(seed, nodes.inputs(sampler).get("seed"))
        }
    }

    @Test
    fun `nested subgraphs are flattened and a muted instance mutes what's inside`() {
        val outer = """
            {"id": "sub-2", "name": "Outer", "inputNode": {"id": -10}, "outputNode": {"id": -20},
             "inputs": [{"name": "model", "type": "MODEL"}], "outputs": [{"name": "LATENT", "type": "LATENT"}],
             "nodes": [{"id": 5, "type": "sub-1", "inputs": [{"name": "model", "type": "MODEL", "link": 1}],
                        "outputs": [{"name": "LATENT", "type": "LATENT", "links": [2]}]}],
             "links": [{"id": 1, "origin_id": -10, "origin_slot": 0, "target_id": 5, "target_slot": 0, "type": "MODEL"},
                       {"id": 2, "origin_id": 5, "origin_slot": 0, "target_id": -20, "target_slot": 0, "type": "LATENT"}]}
        """
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "Loader", "widgets_values": ["m"]},
                          {"id": 2, "type": "sub-2", "mode": 2, "inputs": [{"name": "model", "type": "MODEL", "link": 30}],
                           "outputs": [{"name": "LATENT", "type": "LATENT", "links": [31]}]},
                          {"id": 3, "type": "Compare", "widgets_values": [""],
                           "inputs": [{"name": "image", "type": "IMAGE", "link": 31}]}],
                "links": [[30, 1, 0, 2, 0, "MODEL"], [31, 2, 0, 3, 1, "LATENT"]],
                "definitions": {"subgraphs": [$samplerSubgraph, $outer]}}"""
        )
        val sampler = nodes.keys().asSequence().single { nodes.getJSONObject(it).getString("class_type") == "Sampler" }
        assertEquals("1" to 0, nodes.inputs(sampler).link("model"))
        assertEquals(sampler to 0, nodes.inputs("3").link("image"))
        assertEquals(2, nodes.getJSONObject(sampler).getInt("mode"))
        assertTrue(nodes.keys().asSequence().none { nodes.getJSONObject(it).getString("class_type").startsWith("sub-") })
    }

    @Test
    fun `values the frontend adds for its own widgets are passed over`() {
        // Buttons saved between the file and the size, as a 3D viewer's frontend does
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "Viewer3D", "widgets_values": ["a.glb", "upload", "clear", "", 640, 480]}],
                "links": []}"""
        )
        assertEquals("a.glb", nodes.inputs("1").get("file"))
        assertEquals(640, nodes.inputs("1").get("width"))
        assertEquals(480, nodes.inputs("1").get("height"))
    }

    @Test
    fun `the frontend's names lay out the list, whose values win`() {
        // Android's org.json keeps the order of names (the app relies on it); the JVM's doesn't, so it's built in order
        val named = JSONObject().apply {
            JSONObject::class.java.getDeclaredField("map").apply { isAccessible = true }.set(this, LinkedHashMap<String, Any>())
            put("seed", 1)
            put("control_after_generate", "fixed")
            put("steps", 2)
        }
        val ui = JSONObject("""{"nodes": [{"id": 1, "type": "Sampler", "widgets_values": [5, "fixed", 9]}], "links": []}""")
        ui.getJSONArray("nodes").getJSONObject(0).put("widgets_values_named", named)
        val nodes = JSONObject(converter.convert(ui).jsonContent).getJSONObject("nodes")
        assertEquals(5, nodes.inputs("1").get("seed"))
        assertEquals(9, nodes.inputs("1").get("steps"))
        assertFalse(nodes.inputs("1").has("control_after_generate"))
    }

    @Test
    fun `a link's array keeps its source and slot`() {
        val nodes = convert(
            """{"nodes": [{"id": 1, "type": "Loader", "widgets_values": ["m"]},
                          {"id": 2, "type": "Sampler", "widgets_values": [1, "fixed", 4],
                           "inputs": [{"name": "model", "type": "MODEL", "link": 7}]}],
                "links": [[7, 1, 0, 2, 0, "MODEL"]]}"""
        )
        assertEquals(JSONArray().put("1").put(0).toString(), nodes.inputs("2").getJSONArray("model").toString())
    }
}
