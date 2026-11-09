package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeCategory
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowNode

class FieldMappingCandidatesTest {

    private fun node(id: String, classType: String, vararg inputs: Pair<String, InputValue>) = WorkflowNode(
        id = id, classType = classType, title = classType, category = NodeCategory.OTHER,
        inputs = linkedMapOf(*inputs), templateInputKeys = emptySet()
    )

    private fun candidates(field: String, vararg nodes: WorkflowNode) = FieldMappingAnalyzer.findCandidatesForField(
        field,
        WorkflowGraph(name = "", description = "", nodes = nodes.toList(), edges = emptyList(), templateVariables = emptySet()),
        emptyMap(),
        NodeTypeRegistry()
    ).map { it.nodeId to it.inputKey }

    @Test
    fun valueLinkedFromAnotherNodeIsNoCandidate() {
        val resolution = node("49", "ResolutionSelector", "megapixels" to InputValue.Literal(1))
        val latent = node(
            "5", "EmptyLatentImage",
            "width" to InputValue.Connection("49", 0),
            "height" to InputValue.Literal(1024)
        )
        assertEquals(emptyList<Pair<String, String>>(), candidates("width", resolution, latent))
        assertEquals(listOf("5" to "height"), candidates("height", resolution, latent))
    }

    @Test
    fun primitiveSourceIsOfferedOnce() {
        val size = node("7", "PrimitiveInt", "value" to InputValue.Literal(768))
        val latent = node(
            "5", "EmptyLatentImage",
            "width" to InputValue.Connection("7", 0),
            "height" to InputValue.Connection("7", 0)
        )
        val upscale = node("9", "ImageScale", "width" to InputValue.Connection("7", 0))
        assertEquals(listOf("7" to "value"), candidates("width", size, latent, upscale))
    }

    @Test
    fun listPassedOnIsOfferedAtItsSource() {
        val registry = NodeTypeRegistry().apply {
            parseObjectInfo(
                org.json.JSONObject(
                    """
                    {
                      "KSampler": {"input": {"required": {"sampler_name": [["euler", "heun"], {}], "scheduler": [["simple", "karras"], {}]}},
                        "output": ["LATENT"], "output_name": ["LATENT"], "category": "sampling"},
                      "KSampler Config (rgthree)": {"input": {"required": {"sampler_name": [["euler", "heun"], {}], "scheduler": [["simple", "karras"], {}]}},
                        "output": [["euler", "heun"], ["simple", "karras"]], "output_name": ["SAMPLER", "SCHEDULER"], "category": "rgthree"}
                    }
                    """.trimIndent()
                )
            )
        }
        val config = node("14", "KSampler Config (rgthree)", "sampler_name" to InputValue.Literal("heun"), "scheduler" to InputValue.Literal("simple"))
        val sampler = node("7", "KSampler", "sampler_name" to InputValue.Connection("14", 0), "scheduler" to InputValue.Literal("karras"))
        val found = FieldMappingAnalyzer.findCandidatesForField(
            "sampler_name",
            WorkflowGraph(name = "", description = "", nodes = listOf(sampler, config), edges = emptyList(), templateVariables = emptySet()),
            emptyMap(),
            registry
        ).map { it.nodeId to it.inputKey }
        // The list the link carries comes first, once
        assertEquals(listOf("14" to "sampler_name"), found)
    }
}
