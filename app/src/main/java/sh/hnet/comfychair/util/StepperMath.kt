package sh.hnet.comfychair.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Stepping a number field with − and +. Allowed values are `min + k·step` within `[min, max]` (the grid ComfyUI
 * checks, e.g. a video length of 1, 5, 9…). From an allowed value a button moves one step; from a value that isn't
 * allowed (off the grid, below or above the range) it moves to the nearest allowed value below (−) or above (+) it,
 * so a mistyped value becomes valid in one tap. Exact decimal arithmetic: seeds have up to 20 digits and steps such as
 * 0.01 aren't exact in binary. Pure functions, unit-tested.
 */
object StepperMath {
    /** The highest allowed value: [max] itself, or the last grid value below it. */
    fun top(min: BigDecimal, max: BigDecimal, step: BigDecimal): BigDecimal {
        if (step.signum() <= 0 || max <= min) return max.max(min)
        val k = (max - min).divide(step, 0, RoundingMode.FLOOR)
        return min + k * step
    }

    /** The nearest allowed value below [value], or null when there is none (at or below [min]). */
    fun decrement(value: BigDecimal, min: BigDecimal, max: BigDecimal, step: BigDecimal): BigDecimal? {
        if (value <= min) return null
        val top = top(min, max, step)
        if (value > top) return top
        if (step.signum() <= 0) return min
        // The grid index just below the value: one step down from a grid value, rounded down from one off it
        val k = (value - min).divide(step, 0, RoundingMode.CEILING) - BigDecimal.ONE
        return (min + k * step).max(min)
    }

    /** The nearest allowed value above [value], or null when there is none (at or above the highest). */
    fun increment(value: BigDecimal, min: BigDecimal, max: BigDecimal, step: BigDecimal): BigDecimal? {
        val top = top(min, max, step)
        if (value >= top) return null
        if (value < min) return min
        if (step.signum() <= 0) return top
        val k = (value - min).divide(step, 0, RoundingMode.FLOOR) + BigDecimal.ONE
        return (min + k * step).min(top)
    }
}
