package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeCategory
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.WorkflowEdge
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowNode

/** The prompt and image analysis shared by the import and the editor ([FieldMappingAnalyzer]). */
class FieldMappingPromptsTest {

    private fun node(id: String, classType: String, outputs: List<String>, vararg inputs: Pair<String, InputValue>) = WorkflowNode(
        id = id, classType = classType, title = classType, category = NodeCategory.OTHER,
        inputs = linkedMapOf(*inputs), templateInputKeys = emptySet(),
        outputs = outputs.map { OutputSlot(it, it.lowercase()) }
    )

    private fun text(value: String) = InputValue.Literal(value)
    private fun link(id: String, index: Int = 0) = InputValue.Connection(id, index)

    /** The graph with its edges taken from the nodes' links. */
    private fun graph(vararg nodes: WorkflowNode) = WorkflowGraph(
        name = "", description = "", nodes = nodes.toList(), templateVariables = emptySet(),
        edges = nodes.flatMap { node ->
            node.inputs.mapNotNull { (key, value) ->
                (value as? InputValue.Connection)?.let { WorkflowEdge(it.sourceNodeId, it.outputIndex, node.id, key) }
            }
        }
    )

    private fun prompts(graph: WorkflowGraph, registry: NodeTypeRegistry = NodeTypeRegistry()) =
        FieldMappingAnalyzer.createPromptFieldMappings(graph, registry).mapValues { (_, list) -> list.map { "${it.nodeId}/${it.inputKey}" } }

    private val conditioning = listOf("CONDITIONING")

    @Test
    fun encoderFeedingTheNegativeOnlyZeroedIsPositive() {
        // Flux: the prompt goes through guidance to the positive, and zeroed to the negative
        val result = prompts(graph(
            node("6", "CLIPTextEncode", conditioning, "text" to text("a cat")),
            node("26", "FluxGuidance", conditioning, "conditioning" to link("6")),
            node("37", "ConditioningZeroOut", conditioning, "conditioning" to link("6")),
            node("35", "InstructPixToPixConditioning", listOf("CONDITIONING", "CONDITIONING", "LATENT"),
                "positive" to link("26"), "negative" to link("37")),
            node("3", "KSampler", listOf("LATENT"), "positive" to link("35", 0), "negative" to link("35", 1))
        ))
        assertEquals(listOf("6/text"), result["positive_text"])
        assertEquals(emptyList<String>(), result["negative_text"])
    }

    @Test
    fun nodeTakingBothPromptsEndsTheTrace() {
        // WanImageToVideo passes each prompt on as its own output: the negative one doesn't become positive behind it
        val result = prompts(graph(
            node("6", "CLIPTextEncode", conditioning, "text" to text("a dancer")),
            node("7", "CLIPTextEncode", conditioning, "text" to text("blurry")),
            node("50", "WanImageToVideo", listOf("CONDITIONING", "CONDITIONING", "LATENT"),
                "positive" to link("6"), "negative" to link("7")),
            node("3", "KSampler", listOf("LATENT"), "positive" to link("50", 0), "negative" to link("50", 1))
        ))
        assertEquals("6/text", result["positive_text"]?.first())
        assertEquals("7/text", result["negative_text"]?.first())
    }

    @Test
    fun linkedPromptIsOfferedAtItsSourceNotTheEnhancersInstruction() {
        // A user prompt reaching the encoder through an enhancer's switch; the system prompt lies further up
        val result = prompts(graph(
            node("19", "PrimitiveStringMultiline", listOf("STRING"), "value" to text("a yeti reading")),
            node("18", "PrimitiveStringMultiline", listOf("STRING"), "value" to text("You rewrite prompts")),
            node("16", "TextGenerate", listOf("STRING"), "prompt" to link("19"), "system_prompt" to link("18")),
            node("21", "ComfySwitchNode", listOf("COMFY_MATCHTYPE_V3"), "on_true" to link("16"), "on_false" to link("19"),
                "switch" to InputValue.Literal(false)),
            node("20", "PreviewAny", listOf("STRING"), "source" to link("21")),
            node("52", "TextEncodeQwenImageEditPlus", conditioning, "prompt" to link("20")),
            node("57", "CFGGuider", listOf("GUIDER"), "positive" to link("52"))
        ))
        assertEquals("19/value", result["positive_text"]?.first())
        assertFalse(result.values.flatten().any { it.startsWith("18/") })
    }

    @Test
    fun encoderTakingBothPromptsOffersItsNegativeOne() {
        val result = prompts(graph(
            node("452", "TextEncodeQwenImage21", listOf("CONDITIONING", "CONDITIONING", "LATENT"),
                "prompt" to text("a portrait"), "negative_prompt" to text("")),
            node("489", "CFGGuider", listOf("GUIDER"), "positive" to link("452", 0), "negative" to link("452", 1))
        ))
        assertEquals("452/prompt", result["positive_text"]?.first())
        assertEquals("452/negative_prompt", result["negative_text"]?.first())
    }

    @Test
    fun imageFallbackTakesUploadInputsOnly() {
        val registry = NodeTypeRegistry().withLocalTypes(mapOf(
            "ApiImageNode" to NodeTypeDefinition(
                "ApiImageNode", "api",
                inputs = listOf(InputDefinition("aspect_ratio", "ENUM", isRequired = true, options = listOf("1:1", "16:9"))),
                outputs = listOf(OutputSlot("IMAGE", "IMAGE"))
            ),
            "LoadPictureCN" to NodeTypeDefinition(
                "LoadPictureCN", "image",
                inputs = listOf(InputDefinition("图片", "ENUM", isRequired = true, options = listOf("a.png"), upload = true, uploadKind = "image")),
                outputs = listOf(OutputSlot("IMAGE", "IMAGE"))
            )
        ))
        fun image(vararg nodes: WorkflowNode) = FieldMappingAnalyzer.findCandidatesForField("image", graph(*nodes), emptyMap(), registry)
            .map { "${it.nodeId}/${it.inputKey}" }
        val api = node("1", "ApiImageNode", listOf("IMAGE"), "aspect_ratio" to text("1:1"))
        val loader = node("2", "LoadPictureCN", listOf("IMAGE"), "图片" to text("a.png"))
        assertEquals(emptyList<String>(), image(api))
        assertEquals(listOf("2/图片"), image(api, loader))
    }
}
