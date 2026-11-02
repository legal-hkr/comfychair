package sh.hnet.comfychair.util

import sh.hnet.comfychair.model.LoraSelection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Value logic of the LoRA strength control: coarse ±10 buttons that move a 10-wide window,
 * a fine slider within that window, and a typed value. Pure functions, unit-tested.
 *
 * Invariant kept by the control: windowStart <= value <= windowStart + COARSE_STEP.
 */
object LoraStrengthMath {
    val MIN = LoraSelection.MIN_STRENGTH.toDouble()
    val MAX = LoraSelection.MAX_STRENGTH.toDouble()
    const val COARSE_STEP = 10.0
    const val FINE_STEP = 0.1
    private const val MINUS = '−'

    /** Round to 0.01, clamp to [MIN, MAX] and normalize -0.0. */
    fun clamp(value: Double): Double {
        val rounded = (value * 100).roundToLong() / 100.0
        val clamped = rounded.coerceIn(MIN, MAX)
        return if (clamped == 0.0) 0.0 else clamped
    }

    /** Start of the window containing [value]: a multiple of COARSE_STEP in [MIN, MAX - COARSE_STEP]. */
    fun windowFor(value: Double): Double =
        (floor((value + 1e-9) / COARSE_STEP) * COARSE_STEP)
            .coerceIn(MIN, MAX - COARSE_STEP)
            .let { if (it == 0.0) 0.0 else it }

    fun canStepDown(windowStart: Double): Boolean = windowStart > MIN

    fun canStepUp(windowStart: Double): Boolean = windowStart + COARSE_STEP < MAX

    /** Returns (new value, new window start). */
    fun stepUp(value: Double, windowStart: Double): Pair<Double, Double> {
        val newStart = (windowStart + COARSE_STEP).coerceAtMost(MAX - COARSE_STEP)
        return clamp(minOf(newStart + COARSE_STEP, value + COARSE_STEP)) to newStart
    }

    /** Returns (new value, new window start). */
    fun stepDown(value: Double, windowStart: Double): Pair<Double, Double> {
        val newStart = (windowStart - COARSE_STEP).coerceAtLeast(MIN)
        return clamp(maxOf(newStart, value - COARSE_STEP)) to newStart
    }

    /** Slider position relative to the window start, snapped to FINE_STEP. */
    fun fromSlider(windowStart: Double, relative: Double): Double =
        clamp(windowStart + (relative / FINE_STEP).roundToLong() * FINE_STEP)

    private fun normalize(text: String) = text.trim().replace(MINUS, '-').replace(',', '.')

    /** Drafts that may still become a valid number; not an error while typing. */
    fun isIncomplete(text: String): Boolean = normalize(text) in setOf("", "-", ".", "-.")

    private val NUMBER = Regex("""^-?(\d+\.?\d*|\.\d+)$""")

    /** Parses typed text, accepting "," as decimal separator and U+2212 as minus. */
    fun parse(text: String): Double? {
        val normalized = normalize(text)
        return if (NUMBER.matches(normalized)) normalized.toDoubleOrNull() else null
    }

    /** Live error while typing: complete but unparseable, or out of range. */
    fun isError(text: String): Boolean {
        if (isIncomplete(text)) return false
        val value = parse(text) ?: return true
        return value < MIN || value > MAX
    }

    /** Keeps only characters that can be part of a number. */
    fun filterInput(text: String): String = text.filter { it.isDigit() || it in ".,-$MINUS" }

    /** Display text: two decimals, U+2212 for negative values. */
    fun format(value: Double): String {
        val clamped = clamp(value)
        val text = String.format(Locale.ROOT, "%.2f", abs(clamped))
        return if (clamped < 0) "$MINUS$text" else text
    }

    /** Editable text: ASCII minus, so the sign can be edited on any keyboard. */
    fun draftOf(value: Double): String = String.format(Locale.ROOT, "%.2f", clamp(value))

    /** Window bound label without decimals, e.g. "−10" or "50". */
    fun formatBound(value: Double): String {
        val whole = value.roundToLong()
        return if (whole < 0) "$MINUS${-whole}" else "$whole"
    }
}
