package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.util.LiteGraphConverter

/**
 * The values of a subgraph's inputs belong to each instance, as in ComfyUI (review #4): two copies of one subgraph run
 * with their own values, the import reads them per copy and the export writes them per copy.
 */
class SubgraphInstanceValuesTest {

    private fun input(name: String, type: String) = InputDefinition(name = name, type = type, isRequired = true)

    private val definitions = mapOf(
        "Loader" to NodeTypeDefinition("Loader", "test", listOf(input("image", "ENUM")), listOf(OutputSlot("IMAGE", "IMAGE"))),
        "Blur" to NodeTypeDefinition("Blur", "test", listOf(input("image", "IMAGE"), input("radius", "INT")), listOf(OutputSlot("IMAGE", "IMAGE"))),
        "Save" to NodeTypeDefinition("Save", "test", listOf(input("images", "IMAGE"), input("prefix", "STRING")), emptyList())
    )

    /** Two copies of subgraph "sg" (Blur, its radius fed by the subgraph's input): radius 3 and 9; a third with none. */
    private fun stored() = JSONObject(
        """
        {"nodes": {
           "1": {"class_type": "Loader", "inputs": {"image": "a.png"}},
           "5": {"class_type": "sg", "inputs": {"image": ["1", 0], "radius": 3}},
           "6": {"class_type": "sg", "inputs": {"image": ["1", 0], "radius": 9}},
           "4": {"class_type": "sg", "inputs": {"image": ["1", 0]}},
           "7": {"class_type": "Save", "inputs": {"images": ["5", 0], "prefix": "a"}},
           "8": {"class_type": "Save", "inputs": {"images": ["6", 0], "prefix": "b"}},
           "9": {"class_type": "Save", "inputs": {"images": ["4", 0], "prefix": "c"}}},
         "subgraphs": {"sg": {"name": "Soften",
           "inputs": [{"name": "image", "type": "IMAGE", "targets": [["11", "image"]]},
                      {"name": "radius", "type": "INT", "targets": [["11", "radius"]]}],
           "outputs": [{"name": "IMAGE", "type": "IMAGE", "source": ["11", 0]}],
           "nodes": {"11": {"class_type": "Blur", "inputs": {"radius": 1}}}}}}
        """
    )

    private fun radiusRun(flat: JSONObject, instance: String) =
        flat.getJSONObject("nodes").getJSONObject("$instance:11").getJSONObject("inputs").getInt("radius")

    @Test
    fun `each copy runs with its own value, a copy without one with the value inside`() {
        val flat = Subgraphs.flatten(stored())
        assertEquals(3, radiusRun(flat, "5"))
        assertEquals(9, radiusRun(flat, "6"))
        assertEquals(1, radiusRun(flat, "4"))
    }

    @Test
    fun `a value changed on one copy stays on that copy`() {
        val root = stored()
        Subgraphs.expose(root)
        val nodes = root.getJSONObject("nodes")
        assertEquals(1, nodes.getJSONObject("4").getJSONObject("inputs").getInt("radius"))
        nodes.getJSONObject("6").getJSONObject("inputs").put("radius", 12)
        Subgraphs.absorb(root)
        assertEquals(3, nodes.getJSONObject("5").getJSONObject("inputs").getInt("radius"))
        assertEquals(12, nodes.getJSONObject("6").getJSONObject("inputs").getInt("radius"))
        // The value inside stays the default
        assertEquals(1, root.getJSONObject(Subgraphs.KEY).getJSONObject("sg").getJSONObject("nodes")
            .getJSONObject("11").getJSONObject("inputs").getInt("radius"))
        val flat = Subgraphs.flatten(root)
        assertEquals(3, radiusRun(flat, "5"))
        assertEquals(12, radiusRun(flat, "6"))
    }

    @Test
    fun `unpacking a copy gives its nodes its own values`() {
        val root = stored()
        val level = JSONObject().put("nodes", root.getJSONObject("nodes"))
        var next = 100
        val unpacked = SubgraphEditing.unpack(level, root.getJSONObject(Subgraphs.KEY), "6", { (next++).toString() }, { 1 }, { 1 }, null)!!
        val blur = unpacked.level.getJSONObject("nodes").getJSONObject(unpacked.nodeIds.getValue("11"))
        assertEquals(9, blur.getJSONObject("inputs").getInt("radius"))
        assertEquals("[\"1\",0]", blur.getJSONObject("inputs").getJSONArray("image").toString())
    }

    @Test
    fun `the export writes each copy's value and the import reads it back per copy`() {
        val exporter = ComfyUiExporter(definitions::get) { level ->
            val nodes = level.optJSONObject("nodes") ?: JSONObject()
            nodes.keys().asSequence().withIndex().associate { (i, id) -> id to floatArrayOf(i * 600f, 0f, 480f, 320f) }
        }
        val doc = exporter.export(stored())
        val ui = doc.getJSONArray("nodes").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
        fun instance(id: Int) = ui.single { it.getInt("id") == id }
        assertEquals("[[\"-1\",\"radius\"]]", instance(5).getJSONObject("properties").getJSONArray("proxyWidgets").toString())
        assertEquals("[3]", instance(5).getJSONArray("widgets_values").toString())
        assertEquals("[9]", instance(6).getJSONArray("widgets_values").toString())

        val back = JSONObject(LiteGraphConverter(definitions::get).convert(doc).jsonContent)
        val flat = Subgraphs.flatten(back)
        assertEquals(3, radiusRun(flat, "5"))
        assertEquals(9, radiusRun(flat, "6"))
    }

    @Test
    fun `an older file's values of the subgraph's inputs are each copy's own`() {
        // No proxyWidgets: the instance's values are those of the subgraph's value inputs, in order
        fun instance(id: Int, radius: Int, link: Int) = """
            {"id": $id, "type": "sg", "inputs": [{"name": "image", "type": "IMAGE", "link": $link},
              {"name": "radius", "type": "INT", "widget": {"name": "radius"}, "link": null}],
             "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": []}], "widgets_values": [$radius]}"""
        val ui = JSONObject(
            """
            {"nodes": [{"id": 1, "type": "Loader", "widgets_values": ["a.png"], "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": [1, 2]}]},
                       ${instance(5, 3, 1)}, ${instance(6, 9, 2)}],
             "links": [[1, 1, 0, 5, 0, "IMAGE"], [2, 1, 0, 6, 0, "IMAGE"]],
             "definitions": {"subgraphs": [{"id": "sg", "name": "Soften", "inputNode": {"id": -10}, "outputNode": {"id": -20},
               "inputs": [{"name": "image", "type": "IMAGE"}, {"name": "radius", "type": "INT"}],
               "outputs": [{"name": "IMAGE", "type": "IMAGE"}],
               "nodes": [{"id": 11, "type": "Blur", "widgets_values": [1],
                          "inputs": [{"name": "image", "type": "IMAGE", "link": 1}, {"name": "radius", "type": "INT", "widget": {"name": "radius"}, "link": 2}],
                          "outputs": [{"name": "IMAGE", "type": "IMAGE", "links": [3]}]}],
               "links": [{"id": 1, "origin_id": -10, "origin_slot": 0, "target_id": 11, "target_slot": 0},
                         {"id": 2, "origin_id": -10, "origin_slot": 1, "target_id": 11, "target_slot": 1},
                         {"id": 3, "origin_id": 11, "origin_slot": 0, "target_id": -20, "target_slot": 0}]}]}}
            """
        )
        val stored = JSONObject(LiteGraphConverter(definitions::get).convert(ui).jsonContent)
        assertEquals(JSONArray().put("1").put(0).toString(), stored.getJSONObject("nodes").getJSONObject("5").getJSONObject("inputs").getJSONArray("image").toString())
        val flat = Subgraphs.flatten(stored)
        assertEquals(3, radiusRun(flat, "5"))
        assertEquals(9, radiusRun(flat, "6"))
    }
}
