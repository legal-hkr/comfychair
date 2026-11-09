package sh.hnet.comfychair.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.workflow.LIST_OUTPUT_TYPE
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.optionValue

/**
 * Links into lists (COMBO), checked against what ComfyUI's server accepted and refused when probed (a live server,
 * November 2026): definitions below are cut down from its /object_info.
 */
class ListLinkRuleTest {

    private val samplers = listOf("euler", "euler_ancestral", "heun")
    private val schedulers = listOf("simple", "karras", "normal")

    private val registry = NodeTypeRegistry().apply {
        parseObjectInfo(
            JSONObject(
                """
                {
                  "KSampler": {"input": {"required": {
                      "steps": ["INT", {"default": 20}],
                      "sampler_name": [["euler", "euler_ancestral", "heun"], {}],
                      "scheduler": [["simple", "karras", "normal"], {}]}},
                    "output": ["LATENT"], "output_name": ["LATENT"], "category": "sampling"},
                  "KSamplerSelect": {"input": {"required": {
                      "sampler_name": ["COMBO", {"multiselect": false, "options": ["euler", "euler_ancestral", "heun"]}]}},
                    "output": ["SAMPLER"], "output_name": ["SAMPLER"], "category": "sampling"},
                  "CreateVideo": {"input": {"required": {"images": ["IMAGE", {}]}, "optional": {
                      "bit_depth": ["COMBO", {"default": "auto", "multiselect": false, "options": ["auto", 8, 10]}]}},
                    "output": ["VIDEO"], "output_name": ["VIDEO"], "category": "video"},
                  "LoadImageOutput": {"input": {"required": {
                      "image": ["COMBO", {"image_upload": true, "remote": {"route": "/internal/files/output"}}]}},
                    "output": ["IMAGE"], "output_name": ["IMAGE"], "category": "image"},
                  "ImageCompare": {"input": {"required": {
                      "mode": [["a", "b"], {"socketless": true}]}},
                    "output": [], "output_name": [], "category": "image"},
                  "KSampler Config (rgthree)": {"input": {"required": {}},
                    "output": ["INT", ["euler", "euler_ancestral", "heun"], ["simple", "karras", "normal"]],
                    "output_name": ["STEPS", "SAMPLER", "SCHEDULER"], "category": "rgthree"},
                  "ImpactSchedulerAdapter": {"input": {"required": {}},
                    "output": [["simple", "karras", "normal", "AYS SDXL"]], "output_name": ["scheduler"], "category": "ImpactPack"},
                  "GetVideoComponents": {"input": {"required": {"video": ["VIDEO", {}]}},
                    "output": ["IMAGE", "AUDIO", "FLOAT", "COMBO", "COMBO"],
                    "output_name": ["images", "audio", "fps", "bit_depth", "color_space"], "category": "video"},
                  "ImpactSwitch": {"input": {"required": {}}, "output": ["*", "STRING", "INT"],
                    "output_name": ["selected_value", "selected_label", "selected_index"], "category": "ImpactPack"},
                  "ComfySwitchNode": {"input": {"required": {}}, "output": ["COMFY_MATCHTYPE_V3"], "output_name": ["output"], "category": "logic"},
                  "PrimitiveString": {"input": {"required": {}}, "output": ["STRING"], "output_name": ["STRING"], "category": "utils"},
                  "PrimitiveInt": {"input": {"required": {}}, "output": ["INT"], "output_name": ["INT"], "category": "utils"},
                  "CustomCombo": {"input": {"required": {"choice": ["COMBO", {"multiselect": false, "options": []}]}},
                    "output": ["STRING", "INT"], "output_name": ["STRING", "INDEX"], "category": "utilities"}
                }
                """.trimIndent()
            )
        )
    }

    private fun input(node: String, name: String) = registry.getInputDefinition(node, name)!!

    /** Whether [node]'s [name] takes a link from output [index] of [source], as the app checks it. */
    private fun accepts(source: String, index: Int, node: String, name: String) =
        ConnectionValidator.acceptsListLink(registry.getOutputType(source, index), registry.getOutputOptions(source, index), input(node, name))

    @Test
    fun `an output whose type is a list of options is a list carrying them`() {
        assertEquals(LIST_OUTPUT_TYPE, registry.getOutputType("KSampler Config (rgthree)", 1))
        assertEquals(samplers, registry.getOutputOptions("KSampler Config (rgthree)", 1))
        assertEquals(schedulers, registry.getOutputOptions("KSampler Config (rgthree)", 2))
        assertEquals("INT", registry.getOutputType("KSampler Config (rgthree)", 0))
        assertNull(registry.getOutputOptions("KSampler Config (rgthree)", 0))
        // COMBO itself: a list whose options aren't known
        assertEquals(LIST_OUTPUT_TYPE, registry.getOutputType("GetVideoComponents", 3))
        assertNull(registry.getOutputOptions("GetVideoComponents", 3))
    }

    @Test
    fun `lists know how they were declared`() {
        assertFalse(input("KSampler", "sampler_name").comboDeclared)
        assertTrue(input("KSamplerSelect", "sampler_name").comboDeclared)
        assertTrue(input("CreateVideo", "bit_depth").comboDeclared)
        // A remote list: COMBO without options of its own
        assertTrue(input("LoadImageOutput", "image").comboDeclared)
        assertTrue(ConnectionValidator.isList(input("LoadImageOutput", "image")))
        assertFalse(ConnectionValidator.isList(input("KSampler", "steps")))
    }

    @Test
    fun `a list declared by its options takes only the very same options, a wildcard or a switch`() {
        assertTrue(accepts("KSampler Config (rgthree)", 1, "KSampler", "sampler_name"))
        assertTrue(accepts("KSampler Config (rgthree)", 2, "KSampler", "scheduler"))
        // Another list, or one with more options, is refused
        assertFalse(accepts("KSampler Config (rgthree)", 2, "KSampler", "sampler_name"))
        assertFalse(accepts("ImpactSchedulerAdapter", 0, "KSampler", "scheduler"))
        // COMBO with unknown options is refused
        assertFalse(accepts("GetVideoComponents", 3, "KSampler", "sampler_name"))
        assertTrue(accepts("ImpactSwitch", 0, "KSampler", "sampler_name"))
        assertTrue(accepts("ComfySwitchNode", 0, "KSampler", "sampler_name"))
    }

    @Test
    fun `a list declared as COMBO takes any list, a wildcard or a switch`() {
        assertTrue(accepts("KSampler Config (rgthree)", 1, "KSamplerSelect", "sampler_name"))
        assertTrue(accepts("KSampler Config (rgthree)", 2, "KSamplerSelect", "sampler_name"))
        assertTrue(accepts("GetVideoComponents", 3, "KSamplerSelect", "sampler_name"))
        assertTrue(accepts("GetVideoComponents", 3, "CreateVideo", "bit_depth"))
        assertTrue(accepts("ImpactSwitch", 0, "KSamplerSelect", "sampler_name"))
        assertTrue(accepts("ComfySwitchNode", 0, "CreateVideo", "bit_depth"))
    }

    @Test
    fun `text, numbers and other types never feed a list`() {
        for ((node, name) in listOf("KSampler" to "sampler_name", "KSamplerSelect" to "sampler_name", "CreateVideo" to "bit_depth")) {
            assertFalse(accepts("PrimitiveString", 0, node, name))
            assertFalse(accepts("PrimitiveInt", 0, node, name))
            assertFalse(accepts("CustomCombo", 0, node, name))
            assertFalse(accepts("KSamplerSelect", 0, node, name))
            assertFalse(accepts("ImpactSwitch", 1, node, name))
        }
    }

    @Test
    fun `options that aren't text are shown as text and sent as given`() {
        val bitDepth = input("CreateVideo", "bit_depth")
        assertEquals(listOf("auto", "8", "10"), bitDepth.options)
        assertEquals(8, bitDepth.optionValue("8"))
        assertEquals("auto", bitDepth.optionValue("auto"))
        // Lists of text only keep no separate values
        assertNull(input("KSampler", "sampler_name").optionValues)
        assertEquals("heun", input("KSampler", "sampler_name").optionValue("heun"))
    }

    @Test
    fun `a node added from an output offers the inputs it can feed, lists among them`() {
        val ksampler = registry.getNodeDefinition("KSampler")!!
        val config = registry.getNodeDefinition("KSampler Config (rgthree)")!!
        assertEquals(listOf("sampler_name"), ConnectionValidator.inputsTaking(ksampler, LIST_OUTPUT_TYPE, samplers, false).map { it.name })
        // (the test's org.json keeps no key order: compared as sets)
        assertEquals(setOf("sampler_name", "scheduler"), ConnectionValidator.inputsTaking(ksampler, "*", null).map { it.name }.toSet())
        assertTrue(ConnectionValidator.inputsTaking(ksampler, "STRING", null).isEmpty())
        // A node added to a list input: the outputs that list takes
        assertEquals(listOf("SCHEDULER"), ConnectionValidator.outputsFeeding(config, LIST_OUTPUT_TYPE, input("KSampler", "scheduler")).map { it.name })
        assertEquals(setOf("SAMPLER", "SCHEDULER"), ConnectionValidator.outputsFeeding(config, LIST_OUTPUT_TYPE, input("KSamplerSelect", "sampler_name")).map { it.name }.toSet())
        // The socket a list row shows
        assertEquals(LIST_OUTPUT_TYPE, ConnectionValidator.linkableValueType(ksampler, "sampler_name"))
        assertEquals("INT", ConnectionValidator.linkableValueType(ksampler, "steps"))
        assertNull(ConnectionValidator.linkableValueType(registry.getNodeDefinition("ImageCompare"), "mode"))
    }

    @Test
    fun `a subgraph's input fits any list, a list without a socket takes nothing`() {
        assertTrue(ConnectionValidator.acceptsListLink(LIST_OUTPUT_TYPE, null, input("KSampler", "sampler_name"), fromSubgraphInput = true))
        assertFalse(ConnectionValidator.acceptsListLink("*", null, input("ImageCompare", "mode")))
        assertFalse(ConnectionValidator.takesListLink(input("ImageCompare", "mode")))
    }

    @Test
    fun `a switch's matched type takes and gives any type`() {
        assertTrue(ConnectionValidator.isTypeCompatible(LIST_OUTPUT_TYPE, "COMFY_MATCHTYPE_V3"))
        assertTrue(ConnectionValidator.isTypeCompatible("MODEL", "COMFY_MATCHTYPE_V3"))
        assertTrue(ConnectionValidator.isTypeCompatible("COMFY_MATCHTYPE_V3", "MODEL"))
        assertFalse(ConnectionValidator.isTypeCompatible("IMAGE", "MODEL"))
    }
}
