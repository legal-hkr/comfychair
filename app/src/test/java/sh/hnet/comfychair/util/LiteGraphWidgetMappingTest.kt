package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.hnet.comfychair.workflow.DYNAMIC_COMBO_TYPE
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition

/**
 * widgets_values of a UI workflow mapped to input names the way ComfyUI's frontend lays out widgets: sockets of any
 * type take no value, a dynamic combo's option adds its widgets right after it.
 */
class LiteGraphWidgetMappingTest {

    private fun def(name: String, type: String, dynamicOptions: Map<String, List<InputDefinition>>? = null) =
        InputDefinition(name = name, type = type, isRequired = true, dynamicOptions = dynamicOptions)

    private val definitions = mapOf(
        // ComfyUI's CreateVideo: an AUDIO socket between the frame rate and the bit depth
        "CreateVideo" to listOf(
            def("images", "IMAGE"), def("fps", "FLOAT"), def("audio", "AUDIO"),
            def("bit_depth", "ENUM"), def("color_space", "ENUM"), def("codec", "ENUM")
        ),
        // A dynamic combo whose chosen option adds a widget
        "ResizeImageMaskNode" to listOf(
            def("input", "COMFY_MATCHTYPE_V3"),
            def(
                "resize_type", DYNAMIC_COMBO_TYPE,
                mapOf(
                    "scale by multiplier" to listOf(def("multiplier", "FLOAT")),
                    "scale dimensions" to listOf(def("width", "INT"), def("height", "INT"))
                )
            ),
            def("scale_method", "ENUM")
        ),
        // Growing image sockets before a widget
        "Batch" to listOf(def("images", "COMFY_AUTOGROW_V3"), def("render_text_images", "BOOLEAN"))
    )

    private val converter = LiteGraphConverter { classType ->
        definitions[classType]?.let { NodeTypeDefinition(classType, "test", it, emptyList()) }
    }

    private fun convert(type: String, widgets: String): JSONObject {
        val ui = JSONObject(
            """{"nodes": [{"id": 1, "type": "$type", "pos": [0, 0], "size": [100, 100], "widgets_values": $widgets}],
                "links": []}"""
        )
        val api = JSONObject(converter.convert(ui).jsonContent)
        val nodes = api.optJSONObject("nodes") ?: api
        return nodes.getJSONObject("1").getJSONObject("inputs")
    }

    @Test
    fun `a socket of any type takes no widget value`() {
        val inputs = convert("CreateVideo", """[24, "auto", "sRGB", "auto"]""")
        assertEquals(24, inputs.get("fps"))
        assertFalse(inputs.has("audio"))
        assertEquals("auto", inputs.get("bit_depth"))
        assertEquals("sRGB", inputs.get("color_space"))
        assertEquals("auto", inputs.get("codec"))
    }

    @Test
    fun `a dynamic combo's chosen option takes the values right after it`() {
        val inputs = convert("ResizeImageMaskNode", """["scale by multiplier", 2, "lanczos"]""")
        assertFalse(inputs.has("input"))
        assertEquals("scale by multiplier", inputs.get("resize_type"))
        assertEquals(2, inputs.get("resize_type.multiplier"))
        assertEquals("lanczos", inputs.get("scale_method"))
    }

    @Test
    fun `growing sockets take no widget value`() {
        assertEquals(true, convert("Batch", "[true]").get("render_text_images"))
    }
}
