package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StripCenteringTest {

    // Viewport 0..300 px, middle at 150; thumbnails 44 px wide

    @Test
    fun itemRightOfMiddleScrollsForward() {
        // Item 200..244, middle 222 → 72 px forward
        assertEquals(72, centeringDelta(StripItemBounds(200, 44), 0, 300))
    }

    @Test
    fun itemLeftOfMiddleScrollsBack() {
        // Item 10..54, middle 32 → 118 px back
        assertEquals(-118, centeringDelta(StripItemBounds(10, 44), 0, 300))
    }

    @Test
    fun centeredItemDoesNotScroll() {
        assertEquals(0, centeringDelta(StripItemBounds(128, 44), 0, 300))
    }

    @Test
    fun partlyCutOffItemIsCentered() {
        // Item -20..24 cut off on the left, middle 2 → 148 px back
        assertEquals(-148, centeringDelta(StripItemBounds(-20, 44), 0, 300))
    }

    @Test
    fun viewportWithContentPaddingUsesItsOwnMiddle() {
        // Viewport -6..294 (start padding reported as a negative start offset), middle 144
        assertEquals(78, centeringDelta(StripItemBounds(200, 44), -6, 294))
    }
}
