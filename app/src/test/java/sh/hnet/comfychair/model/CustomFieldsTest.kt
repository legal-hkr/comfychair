package sh.hnet.comfychair.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFieldsTest {
    private val id1 = "0123456789abcdef0123456789abcdef"
    private val id2 = "fedcba9876543210fedcba9876543210"

    private fun field(id: String, type: FieldValueType, default: String, vararg targets: Pair<String, String>) =
        CustomField(id, "Field", type, default, targets.map { FieldTarget(it.first, it.second) })

    @Test
    fun roundTripsThroughJson() {
        val fields = listOf(field(id1, FieldValueType.INT, "832", "4" to "width", "10" to "width"))
        val back = CustomFields.fromJson(CustomFields.toJson(fields))
        assertEquals(fields, back)
        assertEquals(mapOf("clip2_name" to "Projection"), CustomFields.labelsFromJson(CustomFields.labelsToJson(mapOf("clip2_name" to "Projection"))))
    }

    @Test
    fun skipsBrokenEntries() {
        val json = """[{"id":"x","name":"a","type":"INT","default":"1","targets":[{"node":"1","input":"w"}]},
            {"id":"$id1","name":"a","type":"NOPE","default":"1","targets":[{"node":"1","input":"w"}]},
            {"id":"$id2","name":"b","type":"INT","default":"1","targets":[]}]"""
        assertTrue(CustomFields.fromJson(org.json.JSONArray(json)).isEmpty())
    }

    @Test
    fun fillsEveryTargetWithItsType() {
        val f1 = field(id1, FieldValueType.INT, "832", "4" to "width", "10" to "width")
        val f2 = field(id2, FieldValueType.STRING, "", "6" to "text")
        val workflow = JSONObject().put("nodes", JSONObject()
            .put("4", JSONObject().put("inputs", JSONObject().put("width", f1.placeholder)))
            .put("10", JSONObject().put("inputs", JSONObject().put("width", f1.placeholder)))
            .put("6", JSONObject().put("inputs", JSONObject().put("text", f2.placeholder)))).toString()
        val out = JSONObject(CustomFields.apply(workflow, listOf(f1, f2), mapOf(id1 to "1024", id2 to "a \"quoted\" line\nand more")))
        val nodes = out.getJSONObject("nodes")
        assertEquals(1024L, (nodes.getJSONObject("4").getJSONObject("inputs").get("width") as Number).toLong())
        assertEquals(1024L, (nodes.getJSONObject("10").getJSONObject("inputs").get("width") as Number).toLong())
        assertEquals("a \"quoted\" line\nand more", nodes.getJSONObject("6").getJSONObject("inputs").getString("text"))
    }

    @Test
    fun mistypedOrMissingValuesUseTheDefault() {
        val f = field(id1, FieldValueType.FLOAT, "0.5", "3" to "denoise")
        val workflow = JSONObject().put("nodes", JSONObject().put("3", JSONObject().put("inputs", JSONObject().put("denoise", f.placeholder)))).toString()
        assertEquals(0.5, JSONObject(CustomFields.apply(workflow, listOf(f), mapOf(id1 to "abc"))).getJSONObject("nodes").getJSONObject("3").getJSONObject("inputs").getDouble("denoise"), 1e-9)
        assertEquals(0.5, JSONObject(CustomFields.apply(workflow, listOf(f), emptyMap())).getJSONObject("nodes").getJSONObject("3").getJSONObject("inputs").getDouble("denoise"), 1e-9)
    }

    @Test
    fun booleansAndTypesFromDefinitions() {
        assertEquals(true, CustomFields.typedValue(FieldValueType.BOOLEAN, "true"))
        assertEquals(null, CustomFields.typedValue(FieldValueType.BOOLEAN, "yes"))
        assertEquals(FieldValueType.LIST, FieldValueType.of("STRING", listOf("a", "b")))
        assertEquals(FieldValueType.INT, FieldValueType.of("INT", null))
        assertEquals(null, FieldValueType.of("MODEL", null))
    }
}
