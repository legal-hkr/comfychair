package sh.hnet.comfychair.util

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepperMathTest {
    private fun d(v: String) = BigDecimal(v)
    private fun dec(v: String, min: String?, max: String?, step: String) = StepperMath.decrement(d(v), min?.let(::d), max?.let(::d), d(step))?.stripTrailingZeros()?.toPlainString()
    private fun inc(v: String, min: String?, max: String?, step: String) = StepperMath.increment(d(v), min?.let(::d), max?.let(::d), d(step))?.stripTrailingZeros()?.toPlainString()

    @Test
    fun lengthGridFromValidValues() {
        // 4n+1, as Wan's length: 1, 5, 9…
        assertEquals("77", dec("81", "1", "3600", "4"))
        assertEquals("85", inc("81", "1", "3600", "4"))
    }

    @Test
    fun offGridGoesToTheNearestAllowedValue() {
        assertEquals("81", dec("83", "1", "3600", "4"))
        assertEquals("85", inc("83", "1", "3600", "4"))
        assertEquals("129", dec("130", "1", "3600", "4"))
        assertEquals("133", inc("130", "1", "3600", "4"))
    }

    @Test
    fun outOfRangeGoesToTheRange() {
        // Above the range: − to the highest allowed (the grid below max), + nothing
        assertEquals("3597", dec("5000", "1", "3600", "4"))
        assertNull(inc("5000", "1", "3600", "4"))
        // Below: + to min, − nothing
        assertEquals("16", inc("3", "16", "16384", "8"))
        assertNull(dec("3", "16", "16384", "8"))
    }

    @Test
    fun endsOfTheRange() {
        assertNull(dec("1", "1", "3600", "4"))
        assertNull(inc("3597", "1", "3600", "4"))
        assertEquals("3593", dec("3597", "1", "3600", "4"))
    }

    @Test
    fun decimals() {
        // CFG 0–100 by 0.5
        assertEquals("7", dec("7.5", "0", "100", "0.5"))
        assertEquals("7", dec("7.3", "0", "100", "0.5"))
        assertEquals("7.5", inc("7.3", "0", "100", "0.5"))
        // Denoise by 0.01
        assertEquals("0.76", inc("0.75", "0", "1", "0.01"))
        assertEquals("0.74", dec("0.75", "0", "1", "0.01"))
    }

    @Test
    fun largeSeedsStayExact() {
        assertEquals("1234567890123456790", inc("1234567890123456789", "0", "18446744073709551615", "1"))
        assertEquals("18446744073709551614", dec("18446744073709551615", "0", "18446744073709551615", "1"))
    }

    @Test
    fun open64BitAndMissingBounds() {
        // PrimitiveInt: the full Long range
        assertEquals("1025", inc("1024", "-9223372036854775807", "9223372036854775807", "1"))
        assertEquals("1023", dec("1024", "-9223372036854775807", "9223372036854775807", "1"))
        // No bounds: the grid starts at 0, nothing stops the steps (negative floats included)
        assertEquals("-0.1", dec("0", null, null, "0.1"))
        assertEquals("0.3", inc("0.25", null, null, "0.1"))
    }
}
