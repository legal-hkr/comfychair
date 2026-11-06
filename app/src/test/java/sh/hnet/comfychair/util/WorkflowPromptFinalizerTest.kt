package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkflowPromptFinalizerTest {
    private val wrappers = listOf<String?>(null, "nodes", "prompt")

    private fun wrap(nodes: JSONObject, wrapper: String?): String =
        if (wrapper == null) nodes.toString() else JSONObject().put(wrapper, nodes).put("client_id", "keep").toString()

    private fun nodes(json: String, wrapper: String?): JSONObject =
        JSONObject(json).let { if (wrapper == null) it else it.getJSONObject(wrapper) }

    /** Sorted-key canonical form, so equal content compares equal regardless of order. */
    private fun canonical(json: String): String = canonicalValue(JSONObject(json))

    private fun canonicalValue(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonicalValue(value.get(it))}" }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalValue(value.get(it)) }
        else -> value.toString()
    }

    @Test
    fun editsSupportAllWrappersPreserveConnectionsAndLongs() {
        for (wrapper in wrappers) {
            val original = JSONObject("""{"1":{"class_type":"KSampler","inputs":{"seed":1,"model":["2",0],"cfg":1,"enabled":false,"text":"old"}}}""")
            val result = WorkflowPromptFinalizer.applyNodeAttributeEdits(wrap(original, wrapper), mapOf(
                "1" to mapOf("seed" to 999999999999L, "model" to "not a connection", "cfg" to 2.5f,
                    "enabled" to true, "text" to "new", "missing" to 5),
                "absent" to mapOf("seed" to 42L)
            ))
            val updatedNodes = nodes(result, wrapper)
            val inputs = updatedNodes.getJSONObject("1").getJSONObject("inputs")
            assertEquals(999999999999L, inputs.getLong("seed"))
            assertEquals("[\"2\",0]", inputs.getJSONArray("model").toString())
            assertEquals(2.5, inputs.getDouble("cfg"), 0.0)
            assertTrue(inputs.getBoolean("enabled"))
            assertEquals("new", inputs.getString("text"))
            assertFalse(inputs.has("missing"))
            assertFalse(updatedNodes.has("absent"))
            if (wrapper != null) assertEquals("keep", JSONObject(result).getString("client_id"))
        }
    }

    @Test
    fun pngDefaultsFillOnlyMissingNullOrBlankFlattenedInputs() {
        for (wrapper in wrappers) {
            for (missing in listOf("{}", """{"format.bit_depth":"  ","format.input_color_space":"\t"}""", """{"format.bit_depth":null,"format.input_color_space":null}""")) {
                val inputs = JSONObject(missing).put("format", "png")
                val original = JSONObject().put("1", JSONObject().put("class_type", "SaveImageAdvanced").put("inputs", inputs))
                val result = WorkflowPromptFinalizer.fillMissingPngSaveInputs(wrap(original, wrapper))
                val actual = nodes(result, wrapper).getJSONObject("1").getJSONObject("inputs")
                assertEquals("png", actual.getString("format"))
                assertEquals("8-bit", actual.getString("format.bit_depth"))
                assertEquals("sRGB", actual.getString("format.input_color_space"))
                assertFalse(actual.has("bit_depth"))
                assertFalse(actual.has("input_color_space"))
                if (wrapper != null) assertEquals("keep", JSONObject(result).getString("client_id"))
            }
        }
    }

    @Test
    fun pngPreservesExplicitValuesConnectionsAndUnresolvedValues() {
        for (explicit in listOf(
            """{"format.bit_depth":"16-bit","format.input_color_space":"linear"}""",
            """{"format.bit_depth":["2",0],"format.input_color_space":"{{color}}"}"""
        )) {
            val inputs = JSONObject(explicit).put("format", "png")
            val original = JSONObject().put("1", JSONObject().put("class_type", "SaveImageAdvanced").put("inputs", inputs))
            assertEquals(canonical(original.toString()), canonical(WorkflowPromptFinalizer.fillMissingPngSaveInputs(original.toString())))
        }
    }

    @Test
    fun doesNotGuessFormatsOrRepairUnrelatedWidgets() {
        val original = JSONObject("""{
            "1":{"class_type":"SaveImageAdvanced","inputs":{"format":"exr"}},
            "2":{"class_type":"SaveImageAdvanced","inputs":{"format":"avif","format.bit_depth":"","format.input_color_space":""}},
            "3":{"class_type":"SaveImageAdvanced","inputs":{}},
            "4":{"class_type":"SaveImageAdvanced","inputs":{"format":""}},
            "5":{"class_type":"SaveImageAdvanced","inputs":{"format":"PNG"}},
            "6":{"class_type":"SaveImageAdvanced","inputs":{"format":"{{format}}"}},
            "7":{"class_type":"OtherSave","inputs":{"format":"png"}},
            "8":{"class_type":"EmptyLatentImage","inputs":{"width":"","height":"{{height}}"}},
            "9":{"class_type":"KSampler","inputs":{"cfg":"","sampler_name":"","scheduler":""}},
            "10":{"class_type":"ImageScaleToTotalPixels","inputs":{"megapixels":""}}
        }""")
        for (wrapper in wrappers) {
            val json = wrap(original, wrapper)
            assertEquals(canonical(json), canonical(WorkflowPromptFinalizer.fillMissingPngSaveInputs(json)))
        }
    }

    @Test
    fun finalizationRepairsPngAfterWidgetEdits() {
        val json = """{"1":{"class_type":"SaveImageAdvanced","inputs":{"format":"exr","format.bit_depth":"16-bit","format.input_color_space":"linear"}}}"""
        val edited = WorkflowPromptFinalizer.applyNodeAttributeEdits(json, mapOf("1" to mapOf(
            "format" to "png", "format.bit_depth" to "", "format.input_color_space" to " "
        )))
        val result = nodes(WorkflowPromptFinalizer.fillMissingPngSaveInputs(edited), null).getJSONObject("1").getJSONObject("inputs")
        assertEquals("8-bit", result.getString("format.bit_depth"))
        assertEquals("sRGB", result.getString("format.input_color_space"))
    }

    @Test
    fun malformedJsonAndEmptyEditsAreUnchanged() {
        val malformed = "{broken"
        assertEquals(malformed, WorkflowPromptFinalizer.fillMissingPngSaveInputs(malformed))
        assertEquals(malformed, WorkflowPromptFinalizer.applyNodeAttributeEdits(malformed, mapOf("1" to mapOf("seed" to 2L))))
        val spaced = "{  \"1\": {} }"
        assertEquals(spaced, WorkflowPromptFinalizer.applyNodeAttributeEdits(spaced, emptyMap()))
    }
}
