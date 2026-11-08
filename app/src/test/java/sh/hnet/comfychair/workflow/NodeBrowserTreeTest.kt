package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

/** The node browser's tree and search (spec §22). */
class NodeBrowserTreeTest {

    private fun node(classType: String, category: String, name: String? = null, custom: Boolean = false, deprecated: Boolean = false, description: String? = null) =
        NodeTypeDefinition(classType, category, emptyList(), emptyList(), displayName = name, description = description, deprecated = deprecated, isCustom = custom)

    private val nodes = listOf(
        node("KSampler", "sampling", "KSampler"),
        node("SamplerCustom", "sampling/custom_sampling", "SamplerCustom"),
        node("OldSampler", "sampling", "Old Sampler", deprecated = true),
        node("ImageScale", "image/upscaling", "Upscale Image"),
        node("UpscaleModelLoader", "loaders", "Load Upscale Model"),
        node("FluxProUltra", "partner/image/BFL", "Flux Pro Ultra"),
        node("Stray", ""),
        node("RES_Sampler", "RES4LYF/samplers", "ClownSampler", custom = true),
        node("WAS_Upscale", "WAS Suite/Image", "Image Upscale (WAS)", custom = true)
    )

    @Test
    fun `built-in categories by name, API nodes and the rest last, extension packs apart`() {
        val tree = NodeBrowserTree.build(nodes)
        assertEquals(listOf("image", "loaders", "sampling", "partner", ""), tree.builtIn.map { it.name })
        assertEquals(listOf(NodeBrowserTree.Kind.API, NodeBrowserTree.Kind.OTHER), tree.builtIn.takeLast(2).map { it.kind })
        assertEquals(listOf("RES4LYF", "WAS Suite"), tree.extensions.map { it.name })
        assertEquals(NodeBrowserTree.Kind.PACK, tree.extensions.first().kind)
        val sampling = tree.builtIn.single { it.name == "sampling" }
        assertEquals(3, sampling.count)
        assertEquals(listOf("custom_sampling"), sampling.folders.map { it.name })
        // Deprecated last
        assertEquals(listOf("KSampler", "OldSampler"), sampling.nodes.map { it.classType })
    }

    @Test
    fun `one folder open per level`() {
        val tree = NodeBrowserTree.build(nodes)
        val sampling = tree.builtIn.single { it.name == "sampling" }
        val image = tree.builtIn.single { it.name == "image" }
        var open = NodeBrowserTree.open(emptyList(), sampling)
        open = NodeBrowserTree.open(open, sampling.folders.single())
        assertEquals(listOf(sampling.key, sampling.folders.single().key), open)
        // A sibling closes the branch
        assertEquals(listOf(image.key), NodeBrowserTree.open(open, image))
        assertEquals(emptyList<String>(), NodeBrowserTree.close(open, sampling))
    }

    @Test
    fun `search ranks the name's start first, deprecated after others of their rank, every word must match`() {
        assertEquals(
            listOf("ImageScale", "WAS_Upscale", "UpscaleModelLoader"),
            NodeBrowserTree.search(nodes, "upscale").map { it.classType }
        )
        // The name starts with it, then a word of the name does (deprecated after the others there), then it's inside
        assertEquals(listOf("SamplerCustom", "OldSampler", "RES_Sampler", "KSampler"), NodeBrowserTree.search(nodes, "sampler").map { it.classType })
        assertEquals(listOf("UpscaleModelLoader"), NodeBrowserTree.search(nodes, "load upscale").map { it.classType })
        assertEquals(emptyList<String>(), NodeBrowserTree.search(nodes, "   ").map { it.classType })
        // Diacritics don't matter
        assertEquals(listOf("ImageScale", "WAS_Upscale"), NodeBrowserTree.search(nodes, "Úpscalé imagé").map { it.classType })
    }
}
