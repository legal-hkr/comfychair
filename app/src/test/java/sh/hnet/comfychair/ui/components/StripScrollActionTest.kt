package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StripScrollActionTest {

    // Strip of 64 dp items (56 dp thumbnail + 8 dp gap) as px at density 1, viewport 0..300
    private val itemSize = 56
    private val step = 64

    /** Items laid out after scrolling [scrolled] px; only those overlapping the viewport are "visible". */
    private fun layout(scrolled: Int, itemCount: Int, viewportEnd: Int = 300): (Int) -> StripItemBounds? = { index ->
        val offset = 12 + index * step - scrolled
        if (index in 0 until itemCount && offset + itemSize > 0 && offset < viewportEnd) StripItemBounds(offset, itemSize) else null
    }

    @Test
    fun noScrollWhenItemAndNeighboursAreVisible() {
        assertEquals(StripScrollAction.None, stripScrollAction(1, 8, 0, 300, layout(0, 8)))
    }

    @Test
    fun scrollsForwardJustEnoughForNextNeighbour() {
        // Item 3 at 204..260 is visible, its neighbour 4 at 268..324 sticks out by 24 px
        assertEquals(StripScrollAction.ScrollBy(24), stripScrollAction(3, 8, 0, 300, layout(0, 8)))
    }

    @Test
    fun scrollsBackToPreviousNeighbourWhenItIsCutOff() {
        // After scrolling 200 px, item 2 starts at -60 and is cut off on the left
        assertEquals(StripScrollAction.ScrollToItem(2), stripScrollAction(3, 8, 0, 300, layout(200, 8)))
    }

    @Test
    fun jumpsToFarItemThatIsNotLaidOut() {
        assertEquals(StripScrollAction.ScrollToItem(29), stripScrollAction(30, 64, 0, 300, layout(0, 64)))
    }

    @Test
    fun firstAndLastItemsHaveOnlyOneNeighbour() {
        assertEquals(StripScrollAction.None, stripScrollAction(0, 8, 0, 300, layout(0, 8)))
        // Last item 7 of 8 after scrolling to the end (strip is 12 + 8 * 64 - 8 + 12 = 528 px wide)
        assertEquals(StripScrollAction.None, stripScrollAction(7, 8, 0, 300, layout(228, 8)))
    }

    @Test
    fun emptyStripDoesNothing() {
        assertEquals(StripScrollAction.None, stripScrollAction(0, 0, 0, 300) { null })
    }
}
