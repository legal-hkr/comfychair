package sh.hnet.comfychair.util

import androidx.compose.ui.geometry.Offset
import sh.hnet.comfychair.workflow.GraphBounds

/**
 * Result of a viewport transformation calculation.
 * Contains the new scale and offset to apply to the graph view.
 */
data class ViewportTransform(
    val scale: Float,
    val offset: Offset
)

/**
 * Utility for calculating graph viewport transformations.
 * Provides pure functions for zoom, pan, and fit-to-screen calculations.
 *
 * All functions are stateless and can be called from any context.
 */
object GraphViewportCalculator {

    /** Minimum allowed zoom scale */
    const val MIN_SCALE = 0.2f

    /** Maximum allowed zoom scale */
    const val MAX_SCALE = 3f

    /** Maximum scale for fit operations (prevents over-zooming on small graphs) */
    const val MAX_FIT_SCALE = 1.5f

    /** Standard zoom step (20% per zoom in/out) */
    const val ZOOM_STEP = 1.2f

    /**
     * Zoom by a factor while keeping a point stationary (typically the canvas center).
     *
     * @param focusPoint The point to keep stationary during zoom (usually canvas center)
     * @param currentScale Current zoom scale
     * @param currentOffset Current pan offset
     * @param zoomFactor Multiplier for zoom (>1 zooms in, <1 zooms out)
     * @return New ViewportTransform with adjusted scale and offset
     */
    fun zoomTowardPoint(
        focusPoint: Offset,
        currentScale: Float,
        currentOffset: Offset,
        zoomFactor: Float
    ): ViewportTransform {
        val newScale = (currentScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        val scaleChange = newScale / currentScale

        // Adjust offset to keep the focus point stationary
        val newOffset = Offset(
            x = focusPoint.x - (focusPoint.x - currentOffset.x) * scaleChange,
            y = focusPoint.y - (focusPoint.y - currentOffset.y) * scaleChange
        )

        return ViewportTransform(newScale, newOffset)
    }

    /**
     * The part of the canvas the graph can use: the canvas less what floats over it (the editor's top row and
     * toolbar) and a margin at the sides, in pixels.
     */
    data class FreeArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val valid get() = width > 0 && height > 0
    }

    /** The whole graph in the free area, centred in both directions. */
    fun fitToScreen(bounds: GraphBounds, area: FreeArea): ViewportTransform? {
        if (bounds.width <= 0 || bounds.height <= 0 || !area.valid) return null
        val scale = minOf(area.width / bounds.width, area.height / bounds.height, MAX_FIT_SCALE)
        return centred(bounds, area, scale)
    }

    /**
     * The graph's width in the free area: centred when its height fits too, otherwise its top at the top of the free
     * area.
     */
    fun fitToWidth(bounds: GraphBounds, area: FreeArea): ViewportTransform? {
        if (bounds.width <= 0 || bounds.height <= 0 || !area.valid) return null
        val scale = minOf(area.width / bounds.width, MAX_FIT_SCALE)
        val transform = centred(bounds, area, scale)
        return if (bounds.height * scale <= area.height) transform
        else transform.copy(offset = Offset(transform.offset.x, area.top - bounds.minY * scale))
    }

    /**
     * The graph's height in the free area: centred when its width fits too, otherwise its left side at the left of
     * the free area.
     */
    fun fitToHeight(bounds: GraphBounds, area: FreeArea): ViewportTransform? {
        if (bounds.width <= 0 || bounds.height <= 0 || !area.valid) return null
        val scale = minOf(area.height / bounds.height, MAX_FIT_SCALE)
        val transform = centred(bounds, area, scale)
        return if (bounds.width * scale <= area.width) transform
        else transform.copy(offset = Offset(area.left - bounds.minX * scale, transform.offset.y))
    }

    private fun centred(bounds: GraphBounds, area: FreeArea, scale: Float): ViewportTransform = ViewportTransform(
        scale,
        Offset(
            x = area.left + (area.width - bounds.width * scale) / 2 - bounds.minX * scale,
            y = area.top + (area.height - bounds.height * scale) / 2 - bounds.minY * scale
        )
    )

    /**
     * Constrain a scale value to the allowed range.
     *
     * @param scale Scale value to constrain
     * @return Scale clamped to [MIN_SCALE, MAX_SCALE]
     */
    fun constrainScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
