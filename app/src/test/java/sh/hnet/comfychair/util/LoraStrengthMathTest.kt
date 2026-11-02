package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraStrengthMathTest {

    private val delta = 1e-9

    @Test
    fun windowFor_returnsWindowContainingValue() {
        val cases = mapOf(
            1.0 to 0.0, 0.0 to 0.0, 9.99 to 0.0, 10.0 to 10.0, 42.0 to 40.0,
            -0.01 to -10.0, -4.13 to -10.0, -10.0 to -10.0, 100.0 to 90.0, -100.0 to -100.0
        )
        cases.forEach { (value, start) -> assertEquals("windowFor($value)", start, LoraStrengthMath.windowFor(value), delta) }
    }

    @Test
    fun stepUp_movesValueAndWindow() {
        assertStep(52.0, 50.0, LoraStrengthMath.stepUp(42.0, 40.0))
        assertStep(100.0, 90.0, LoraStrengthMath.stepUp(95.0, 90.0))
        assertStep(100.0, 90.0, LoraStrengthMath.stepUp(100.0, 90.0))
    }

    @Test
    fun stepDown_movesValueAndWindow() {
        assertStep(32.0, 30.0, LoraStrengthMath.stepDown(42.0, 40.0))
        assertStep(-100.0, -100.0, LoraStrengthMath.stepDown(-95.0, -100.0))
        assertStep(-5.0, -10.0, LoraStrengthMath.stepDown(5.0, 0.0))
    }

    @Test
    fun stepButtons_areDisabledAtRangeEnds() {
        assertFalse(LoraStrengthMath.canStepDown(-100.0))
        assertTrue(LoraStrengthMath.canStepDown(-90.0))
        assertFalse(LoraStrengthMath.canStepUp(90.0))
        assertTrue(LoraStrengthMath.canStepUp(80.0))
    }

    @Test
    fun fromSlider_snapsToFineStep() {
        assertEquals(42.0, LoraStrengthMath.fromSlider(40.0, 2.04), delta)
        assertEquals(-4.1, LoraStrengthMath.fromSlider(-10.0, 5.87), delta)
        assertEquals(100.0, LoraStrengthMath.fromSlider(90.0, 10.0), delta)
    }

    @Test
    fun parse_acceptsNumbersWithEitherSeparatorAndMinus() {
        assertEquals(1.0, LoraStrengthMath.parse("1")!!, delta)
        assertEquals(1.0, LoraStrengthMath.parse("1.")!!, delta)
        assertEquals(0.5, LoraStrengthMath.parse(".5")!!, delta)
        assertEquals(-0.5, LoraStrengthMath.parse("-0,5")!!, delta)
        assertEquals(-2.25, LoraStrengthMath.parse("−2.25")!!, delta)
        listOf("", "-", ".", "-.", "1.2.3", "abc").forEach { assertNull("parse($it)", LoraStrengthMath.parse(it)) }
    }

    @Test
    fun format_usesTwoDecimalsAndRealMinus() {
        assertEquals("1.00", LoraStrengthMath.format(1.0))
        assertEquals("−4.13", LoraStrengthMath.format(-4.13))
        assertEquals("0.00", LoraStrengthMath.format(-0.0))
        assertEquals("-4.13", LoraStrengthMath.draftOf(-4.13))
    }

    @Test
    fun isError_ignoresIncompleteDrafts() {
        listOf("", "-", ".", "-.").forEach { assertFalse("isError($it)", LoraStrengthMath.isError(it)) }
        assertTrue(LoraStrengthMath.isError("150"))
        assertTrue(LoraStrengthMath.isError("abc"))
        assertFalse(LoraStrengthMath.isError("99.99"))
    }

    @Test
    fun clamp_roundsAndLimitsToRange() {
        assertEquals(100.0, LoraStrengthMath.clamp(150.0), delta)
        assertEquals(-100.0, LoraStrengthMath.clamp(-150.0), delta)
        assertEquals(0.65, LoraStrengthMath.clamp(0.6499999761581421), delta)
    }

    private fun assertStep(value: Double, start: Double, actual: Pair<Double, Double>) {
        assertEquals("value", value, actual.first, delta)
        assertEquals("window start", start, actual.second, delta)
    }
}
