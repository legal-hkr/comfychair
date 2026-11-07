package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.util.LiteGraphConverter

/**
 * The export in ComfyUI's UI format: what its frontend reads (widget order, links by slot, subgraphs), and that importing
 * the export gives back the same workflow. Small workflows written for each case.
 */
class ComfyUiExporterTest {

    private fun input(
        name: String,
        type: String,
        control: Boolean = false,
        uploadKind: String? = null,
        options: List<String>? = null,
        default: Any? = null,
        forceInput: Boolean = false,
        dynamicOptions: Map<String, List<InputDefinition>>? = null
    ) = InputDefinition(
        name = name, type = type, isRequired = true, controlAfterGenerate = control, upload = uploadKind != null,
        uploadKind = uploadKind, options = options, default = default, forceInput = forceInput, dynamicOptions = dynamicOptions
    )

    private val definitions = mapOf(
        "Loader" to NodeTypeDefinition(
            "Loader", "test", listOf(input("image", "COMBO", uploadKind = "image", options = listOf("a.png"))),
            listOf(OutputSlot("IMAGE", "IMAGE"), OutputSlot("MASK", "MASK"))
        ),
        "Sampler" to NodeTypeDefinition(
            "Sampler", "test",
            listOf(input("image", "IMAGE"), input("seed", "INT", control = true), input("steps", "INT", default = 20),
                input("mode", "COMFY_DYNAMICCOMBO_V3", dynamicOptions = mapOf("fast" to listOf(input("speed", "FLOAT")))),
                input("extra", "STRING", forceInput = true)),
            listOf(OutputSlot("IMAGE", "IMAGE"))
        ),
        "Switch" to NodeTypeDefinition(
            "Switch", "test", listOf(input("on_false", "COMFY_MATCHTYPE_V3"), input("switch", "BOOLEAN")), listOf(OutputSlot("*", "output"))
        ),
        "Save" to NodeTypeDefinition("Save", "test", listOf(input("images", "IMAGE"), input("prefix", "STRING")), emptyList()),
        "PrimitiveString" to NodeTypeDefinition("PrimitiveString", "test", listOf(input("value", "STRING")), listOf(OutputSlot("STRING", "STRING")))
    )

    // One column per node: enough to place them
    private val exporter = ComfyUiExporter(definitions::get) { level ->
        val nodes = level.optJSONObject("nodes") ?: JSONObject()
        val result = mutableMapOf<String, FloatArray>()
        nodes.keys().asSequence().forEachIndexed { i, id -> result[id] = floatArrayOf(i * 600f, 0f, 480f, 320f) }
        level.optJSONArray("notes")?.let { notes ->
            for (i in 0 until notes.length()) result["note:${notes.getJSONObject(i).getInt("id")}"] = floatArrayOf(0f, 600f, 480f, 160f)
        }
        result
    }

    private val stored = JSONObject(
        """
        {"name": "w", "nodes": {
           "1": {"class_type": "Loader", "inputs": {"image": "a.png"}},
           "2": {"class_type": "Sampler", "_meta": {"title": "My sampler"}, "mode": 4,
                 "inputs": {"image": ["1", 0], "seed": 7, "steps": 30, "mode": "fast", "mode.speed": 1.5}},
           "3": {"class_type": "Save", "inputs": {"images": ["2", 0], "prefix": "out"}}},
         "groups": [{"id": 1, "title": "Load", "member_nodes": ["1"]}],
         "notes": [{"id": 1, "title": "Read me", "content": "**Hi**"}]}
        """
    )

    private fun JSONObject.node(id: Int): JSONObject {
        val nodes = getJSONArray("nodes")
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }.single { it.getInt("id") == id }
    }

    @Test
    fun `nodes carry their widget values in the frontend's order`() {
        val doc = exporter.export(stored)
        assertEquals(0.4, doc.getDouble("version"), 0.0)
        // An upload button's value after the image
        assertEquals("[\"a.png\",\"image\"]", doc.node(1).getJSONArray("widgets_values").toString())
        // The control after the seed, a dynamic combo's chosen option's widget after it
        assertEquals("[7,\"fixed\",30,\"fast\",1.5]", doc.node(2).getJSONArray("widgets_values").toString())
        assertEquals("My sampler", doc.node(2).getString("title"))
        assertEquals(4, doc.node(2).getInt("mode"))
    }

    @Test
    fun `links point at the input's place in its node's list`() {
        val doc = exporter.export(stored)
        val sampler = doc.node(2)
        val inputs = sampler.getJSONArray("inputs")
        // Sockets only (and the forced string): values without links aren't listed
        assertEquals(listOf("image", "extra"), (0 until inputs.length()).map { inputs.getJSONObject(it).getString("name") })
        val links = doc.getJSONArray("links")
        val toSampler = (0 until links.length()).map { links.getJSONArray(it) }.single { it.getInt(3) == 2 }
        assertEquals(1, toSampler.getInt(1))
        assertEquals(0, toSampler.getInt(4))
        assertEquals("IMAGE", toSampler.getString(5))
        assertEquals(toSampler.getInt(0), inputs.getJSONObject(0).getInt("link"))
        val loaderOut = doc.node(1).getJSONArray("outputs").getJSONObject(0).getJSONArray("links")
        assertEquals(toSampler.getInt(0), loaderOut.getInt(0))
        assertEquals(links.length(), doc.getInt("last_link_id"))
    }

    @Test
    fun `notes become Markdown notes and groups surround their members`() {
        val doc = exporter.export(stored)
        val note = doc.node(4)
        assertEquals("MarkdownNote", note.getString("type"))
        assertEquals("**Hi**", note.getJSONArray("widgets_values").getString(0))
        val group = doc.getJSONArray("groups").getJSONObject(0)
        val bounding = group.getJSONArray("bounding")
        val pos = doc.node(1).getJSONArray("pos")
        assertTrue(bounding.getDouble(0) < pos.getDouble(0) && bounding.getDouble(1) < pos.getDouble(1))
        assertEquals("Load", group.getString("title"))
    }

    @Test
    fun `a value on a socket-only input goes back as a primitive node`() {
        val doc = exporter.export(JSONObject("""{"nodes": {"1": {"class_type": "Switch", "inputs": {"on_false": "text", "switch": false}}}}"""))
        val nodes = doc.getJSONArray("nodes")
        val primitive = (0 until nodes.length()).map { nodes.getJSONObject(it) }.single { it.getString("type") == "PrimitiveString" }
        assertEquals("text", primitive.getJSONArray("widgets_values").getString(0))
        val link = doc.getJSONArray("links").getJSONArray(0)
        assertEquals(primitive.getInt("id"), link.getInt(1))
        assertEquals("[false]", doc.node(1).getJSONArray("widgets_values").toString())
    }

    /** Loader -> instance of "sub" (Sampler inside, its steps promoted; seed from the subgraph's input) -> Save. */
    private val withSubgraph = JSONObject(
        """
        {"nodes": {
           "1": {"class_type": "Loader", "inputs": {"image": "a.png"}},
           "5": {"class_type": "sub", "inputs": {"image": ["1", 0]}, "promoted": ["11/steps"], "_meta": {"title": "Sampling"}},
           "7": {"class_type": "Save", "inputs": {"images": ["5", 0], "prefix": "out"}}},
         "subgraphs": {"sub": {"name": "Sampling",
           "inputs": [{"name": "image", "type": "IMAGE", "targets": [["11", "image"]]},
                      {"name": "seed", "type": "INT", "targets": [["11", "seed"]]}],
           "outputs": [{"name": "IMAGE", "type": "IMAGE", "source": ["11", 0]}],
           "nodes": {"11": {"class_type": "Sampler", "inputs": {"seed": 3, "steps": 12, "mode": "slow"}}}}}}
        """
    )

    @Test
    fun `subgraphs are definitions with input and output nodes, instances show their values`() {
        val doc = exporter.export(withSubgraph)
        val definition = doc.getJSONObject("definitions").getJSONArray("subgraphs").getJSONObject(0)
        assertEquals("sub", definition.getString("id"))
        assertEquals(-10, definition.getJSONObject("inputNode").getInt("id"))
        val links = definition.getJSONArray("links")
        val fromInput = (0 until links.length()).map { links.getJSONObject(it) }.filter { it.getInt("origin_id") == -10 }
        assertEquals(2, fromInput.size)
        // The seed, a value fed by the subgraph's input, is listed on the node inside as a linked widget
        val sampler = definition.getJSONArray("nodes").getJSONObject(0)
        val seedSlot = fromInput.single { it.getInt("origin_slot") == 1 }.getInt("target_slot")
        assertEquals("seed", sampler.getJSONArray("inputs").getJSONObject(seedSlot).getJSONObject("widget").getString("name"))
        val toOutput = (0 until links.length()).map { links.getJSONObject(it) }.single { it.getInt("target_id") == -20 }
        assertEquals(11, toOutput.getInt("origin_id"))
        assertEquals("[${fromInput.single { it.getInt("origin_slot") == 0 }.getInt("id")}]",
            definition.getJSONArray("inputs").getJSONObject(0).getJSONArray("linkIds").toString())

        val instance = doc.node(5)
        assertEquals("sub", instance.getString("type"))
        assertEquals("[[\"-1\",\"seed\"],[\"11\",\"steps\"]]", instance.getJSONObject("properties").getJSONArray("proxyWidgets").toString())
        assertEquals("[3,12]", instance.getJSONArray("widgets_values").toString())
    }

    @Test
    fun `importing the export gives back the same workflow`() {
        val converter = LiteGraphConverter(definitions::get)
        for (source in listOf(stored, withSubgraph)) {
            val doc = exporter.export(source)
            // Without the names: the JVM's org.json keeps no key order (Android's does)
            fun strip(nodes: JSONArray) { for (i in 0 until nodes.length()) nodes.getJSONObject(i).remove("widgets_values_named") }
            strip(doc.getJSONArray("nodes"))
            doc.optJSONObject("definitions")?.getJSONArray("subgraphs")?.let { for (i in 0 until it.length()) strip(it.getJSONObject(i).getJSONArray("nodes")) }
            val again = JSONObject(converter.convert(doc).jsonContent)
            val before = Subgraphs.flatten(source).getJSONObject("nodes")
            val after = Subgraphs.flatten(again).getJSONObject("nodes")
            for (id in before.keys()) {
                val a = before.getJSONObject(id); val b = after.getJSONObject(id)
                assertEquals(a.getString("class_type"), b.getString("class_type"))
                val ai = a.getJSONObject("inputs"); val bi = b.getJSONObject("inputs")
                for (key in ai.keys()) assertEquals("$id.$key", ai.get(key).toString(), bi.opt(key).toString())
                assertEquals(a.optInt("mode"), b.optInt("mode"))
            }
            assertFalse(after.keys().asSequence().any { it !in before.keys().asSequence().toSet() })
        }
    }
}
