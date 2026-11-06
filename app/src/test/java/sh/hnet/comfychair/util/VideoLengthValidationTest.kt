package sh.hnet.comfychair.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLengthValidationTest {

    @Test
    fun `default grid is 4n plus 1 without an upper limit`() {
        listOf("1", "5", "49", "121", "16385").forEach { assertNull(it, ValidationUtils.validateVideoLength(it)) }
        listOf("0", "4", "124", "").forEach { assertNotNull(it, ValidationUtils.validateVideoLength(it)) }
    }

    @Test
    fun `node grid of MiniMax H3 is 17k plus 5`() {
        listOf("5", "22", "124", "362").forEach { assertNull(it, ValidationUtils.validateVideoLength(it, min = 5, step = 17)) }
        listOf("1", "121", "125").forEach { assertNotNull(it, ValidationUtils.validateVideoLength(it, min = 5, step = 17)) }
    }
}
