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

    private fun def(
        name: String,
        type: String,
        dynamicOptions: Map<String, List<InputDefinition>>? = null,
        default: Any? = null,
        options: List<String>? = null
    ) = InputDefinition(name = name, type = type, isRequired = true, default = default, options = options, dynamicOptions = dynamicOptions)

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
        "Batch" to listOf(def("images", "COMFY_AUTOGROW_V3"), def("render_text_images", "BOOLEAN")),
        // ComfyUI's SaveImageAdvanced: PNG adds two fields, which workflows saved before them lack
        "SaveImageAdvanced" to listOf(
            def("images", "IMAGE"), def("filename_prefix", "STRING", default = "ComfyUI"),
            def(
                "format", DYNAMIC_COMBO_TYPE,
                mapOf(
                    "png" to listOf(
                        def("bit_depth", "ENUM", default = "8-bit", options = listOf("8-bit", "16-bit")),
                        def("input_color_space", "ENUM", default = "sRGB", options = listOf("sRGB"))
                    ),
                    "webp" to listOf(def("quality", "INT", default = 90))
                )
            )
        ),
        // VideoHelperSuite saves its widget values by name
        "VHS_VideoCombine" to listOf(
            def("images", "IMAGE"), def("frame_rate", "FLOAT"), def("filename_prefix", "STRING"),
            def("format", "ENUM"), def("audio", "AUDIO")
        )
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
    fun `widget values saved by name are taken by name, other keys left out`() {
        val inputs = convert(
            "VHS_VideoCombine",
            """{"frame_rate": 24, "filename_prefix": "ComfyChair", "format": "video/h264-mp4",
                "videopreview": {"hidden": false}, "audio": "x"}"""
        )
        assertEquals(24, inputs.get("frame_rate"))
        assertEquals("ComfyChair", inputs.get("filename_prefix"))
        assertEquals("video/h264-mp4", inputs.get("format"))
        assertFalse(inputs.has("videopreview"))
        assertFalse(inputs.has("audio"))
    }

    @Test
    fun `widgets a workflow was saved without get the node's defaults, as in ComfyUI`() {
        val inputs = convert("SaveImageAdvanced", """["ComfyChair", "png"]""")
        assertEquals("ComfyChair", inputs.get("filename_prefix"))
        assertEquals("png", inputs.get("format"))
        assertEquals("8-bit", inputs.get("format.bit_depth"))
        assertEquals("sRGB", inputs.get("format.input_color_space"))
        assertFalse(inputs.has("format.quality"))
    }

    @Test
    fun `a node saved without widget values gets all its defaults`() {
        val ui = JSONObject("""{"nodes": [{"id": 1, "type": "SaveImageAdvanced", "pos": [0, 0], "size": [100, 100]}], "links": []}""")
        val api = JSONObject(converter.convert(ui).jsonContent)
        val inputs = (api.optJSONObject("nodes") ?: api).getJSONObject("1").getJSONObject("inputs")
        assertEquals("ComfyUI", inputs.get("filename_prefix"))
        assertEquals("png", inputs.get("format"))
        assertEquals("8-bit", inputs.get("format.bit_depth"))
    }

    @Test
    fun `growing sockets take no widget value`() {
        assertEquals(true, convert("Batch", "[true]").get("render_text_images"))
    }
}
