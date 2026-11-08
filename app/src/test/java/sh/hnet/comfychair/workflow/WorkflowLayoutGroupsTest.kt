package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowLayoutGroupsTest {

    private fun node(id: String) = WorkflowNode(
        id = id, classType = "Test", title = id, category = NodeCategory.OTHER,
        inputs = emptyMap(), templateInputKeys = emptySet()
    )

    // Two independent groups feed one node: the groups share the first column, one above the other
    private val graph = WorkflowGraph(
        name = "test", description = "",
        nodes = listOf(node("a"), node("b"), node("c")),
        edges = listOf(WorkflowEdge("a", 0, "c", "x"), WorkflowEdge("b", 0, "c", "y")),
        groups = listOf(WorkflowGroup(1, "First", setOf("a")), WorkflowGroup(2, "Second", setOf("b"))),
        templateVariables = emptySet()
    )

    private fun frames(): List<RenderedGroup> {
        val engine = WorkflowLayoutEngine()
        val laidOut = engine.layoutGraph(graph)
        return engine.calculateRenderedGroups(laidOut.groups, laidOut.nodes).sortedBy { it.y }
    }

    @Test
    fun `stacked groups are apart by the usual spacing, frame to frame`() {
        val (upper, lower) = frames()
        assertEquals(upper.x, lower.x, 0.01f)
        assertEquals(WorkflowLayoutEngine.VERTICAL_SPACING, lower.y - (upper.y + upper.height), 0.01f)
    }

    @Test
    fun `the first group's frame starts inside the canvas`() {
        assertTrue(frames().first().y >= 0f)
    }

    // An outer group holding an inner one and a node of its own, beside a group of its own that it doesn't hold
    private val nested = WorkflowGraph(
        name = "nested", description = "",
        nodes = listOf(node("a"), node("b"), node("c"), node("d"), node("e")),
        edges = listOf(WorkflowEdge("a", 0, "b", "x"), WorkflowEdge("b", 0, "c", "x"), WorkflowEdge("d", 0, "c", "y"), WorkflowEdge("c", 0, "e", "x")),
        groups = listOf(
            WorkflowGroup(1, "Outer", setOf("a", "b", "c")),
            WorkflowGroup(2, "Inner", setOf("a", "b")),
            WorkflowGroup(3, "Apart", setOf("d"))
        ),
        templateVariables = emptySet()
    )

    @Test
    fun `a nested group's frame lies inside its parent's, below the parent's label`() {
        val engine = WorkflowLayoutEngine()
        val laidOut = engine.layoutGraph(nested)
        assertEquals(nested.nodes.map { it.id }.sorted(), laidOut.nodes.map { it.id }.sorted())
        val frames = engine.calculateRenderedGroups(laidOut.groups, laidOut.nodes)
        assertEquals(listOf("Outer", "Apart", "Inner"), frames.map { it.title })
        val outer = frames.first { it.title == "Outer" }
        val inner = frames.first { it.title == "Inner" }
        val apart = frames.first { it.title == "Apart" }
        val label = WorkflowLayoutEngine.GROUP_PADDING_TOP + WorkflowLayoutEngine.GROUP_HEADER_HEIGHT
        assertTrue(inner.x >= outer.x + WorkflowLayoutEngine.GROUP_PADDING_SIDE - 0.01f)
        assertTrue(inner.y >= outer.y + label - 0.01f)
        assertTrue(inner.x + inner.width <= outer.x + outer.width - WorkflowLayoutEngine.GROUP_PADDING_SIDE + 0.01f)
        assertTrue(inner.y + inner.height <= outer.y + outer.height - WorkflowLayoutEngine.GROUP_PADDING_BOTTOM + 0.01f)
        // The group apart doesn't overlap the outer one
        val overlaps = apart.x < outer.x + outer.width && apart.x + apart.width > outer.x &&
            apart.y < outer.y + outer.height && apart.y + apart.height > outer.y
        assertTrue(!overlaps)
    }
}
