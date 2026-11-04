package sh.hnet.comfychair.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeTest {

    @Test
    fun formatsMinutesAndPaddedSeconds() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:03", formatPlaybackTime(3_400))
        assertEquals("1:05", formatPlaybackTime(65_000))
        assertEquals("12:00", formatPlaybackTime(720_999))
    }
}
