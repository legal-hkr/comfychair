package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.workflow.GraphDetailLevel.FULL
import sh.hnet.comfychair.workflow.GraphDetailLevel.OVERVIEW

class GraphDetailLevelTest {

    @Test
    fun `first level by the threshold`() {
        assertEquals(FULL, GraphDetailLevel.next(1f, null))
        assertEquals(FULL, GraphDetailLevel.next(0.27f, null))
        assertEquals(OVERVIEW, GraphDetailLevel.next(0.2f, null))
    }

    @Test
    fun `zooming out switches at the threshold`() {
        assertEquals(FULL, GraphDetailLevel.next(0.27f, FULL))
        assertEquals(OVERVIEW, GraphDetailLevel.next(0.26f, FULL))
    }

    @Test
    fun `zooming in switches back only 5 percent beyond`() {
        assertEquals(OVERVIEW, GraphDetailLevel.next(0.28f, OVERVIEW))
        assertEquals(FULL, GraphDetailLevel.next(0.29f, OVERVIEW))
    }

    @Test
    fun `spec zoom from scale and density`() {
        // One graph unit per pixel on a 2.75 density screen is 2 / 2.75 dp per spec unit
        assertEquals(2f / 2.75f, GraphDetailLevel.specZoom(1f, 2.75f), 1e-6f)
    }
}
