package sh.hnet.comfychair.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Stepping a number field with − and +. Allowed values are `min + k·step` within `[min, max]` (the grid ComfyUI
 * checks, e.g. a video length of 1, 5, 9…); without a minimum the grid starts at 0, without a bound the range is open
 * on that side. From an allowed value a button moves one step; from a value that isn't allowed (off the grid, below or
 * above the range) it moves to the nearest allowed value below (−) or above (+) it, so a mistyped value becomes valid
 * in one tap. Exact decimal arithmetic: seeds and ComfyUI's INT bounds have up to 20 digits and steps such as 0.01
 * aren't exact in binary. Pure functions, unit-tested.
 */
object StepperMath {
    /** The highest allowed value: [max] itself, or the last grid value below it; null without a maximum. */
    fun top(min: BigDecimal?, max: BigDecimal?, step: BigDecimal): BigDecimal? {
        if (max == null) return null
        val origin = min ?: BigDecimal.ZERO
        if (step.signum() <= 0 || max <= origin) return if (min != null) max.max(min) else max
        val k = (max - origin).divide(step, 0, RoundingMode.FLOOR)
        return origin + k * step
    }

    /** The nearest allowed value below [value], or null when there is none (at or below [min]). */
    fun decrement(value: BigDecimal, min: BigDecimal?, max: BigDecimal?, step: BigDecimal): BigDecimal? {
        if (min != null && value <= min) return null
        val top = top(min, max, step)
        if (top != null && value > top) return top
        if (step.signum() <= 0) return min
        // The grid index just below the value: one step down from a grid value, rounded down from one off it
        val origin = min ?: BigDecimal.ZERO
        val k = (value - origin).divide(step, 0, RoundingMode.CEILING) - BigDecimal.ONE
        val below = origin + k * step
        return if (min != null) below.max(min) else below
    }

    /** The nearest allowed value above [value], or null when there is none (at or above the highest). */
    fun increment(value: BigDecimal, min: BigDecimal?, max: BigDecimal?, step: BigDecimal): BigDecimal? {
        val top = top(min, max, step)
        if (top != null && value >= top) return null
        if (min != null && value < min) return min
        if (step.signum() <= 0) return top
        val origin = min ?: BigDecimal.ZERO
        val k = (value - origin).divide(step, 0, RoundingMode.FLOOR) + BigDecimal.ONE
        val above = origin + k * step
        return if (top != null) above.min(top) else above
    }
}
