package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A node's inputs in another order are another node: the order is the rows' order (an instance's shown values). */
class WorkflowNodeEqualityTest {

    private fun node(vararg keys: String) = WorkflowNode(
        id = "5", classType = "sub", title = "Sub", category = NodeCategory.SUBGRAPH,
        inputs = keys.associateWith { InputValue.Literal(1) }, templateInputKeys = emptySet()
    )

    @Test
    fun `inputs reordered make a different node`() {
        assertNotEquals(node("12/upscale_method", "12/crop"), node("12/crop", "12/upscale_method"))
        assertEquals(node("12/upscale_method", "12/crop"), node("12/upscale_method", "12/crop"))
        assertEquals(node("a", "b").hashCode(), node("a", "b").hashCode())
    }
}
