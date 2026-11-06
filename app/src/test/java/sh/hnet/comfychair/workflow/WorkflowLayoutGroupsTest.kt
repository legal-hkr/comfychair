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
}
