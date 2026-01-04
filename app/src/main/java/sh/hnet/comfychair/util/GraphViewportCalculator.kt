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
     * Calculate viewport to fit the entire graph on screen.
     * Centers the graph horizontally and anchors to top vertically.
     *
     * @param bounds Graph bounds
     * @param canvasWidth Available canvas width
     * @param canvasHeight Available canvas height
     * @param topPadding Padding from top of canvas
     * @param bottomPadding Padding from bottom (e.g., for toolbar)
     * @return ViewportTransform to fit the graph, or null if bounds/canvas are invalid
     */
    fun fitToScreen(
        bounds: GraphBounds,
        canvasWidth: Float,
        canvasHeight: Float,
        topPadding: Float = 8f,
        bottomPadding: Float = 100f
    ): ViewportTransform? {
        if (bounds.width <= 0 || bounds.height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return null
        }

        val availableHeight = canvasHeight - topPadding - bottomPadding

        // Calculate scale to fit in available area
        val scaleX = canvasWidth / bounds.width
        val scaleY = availableHeight / bounds.height
        val scale = minOf(scaleX, scaleY, MAX_FIT_SCALE) * 0.95f // 95% of fit

        // Center horizontally, anchor to top vertically
        val scaledWidth = bounds.width * scale
        val offsetX = (canvasWidth - scaledWidth) / 2 - bounds.minX * scale
        val offsetY = topPadding - bounds.minY * scale

        return ViewportTransform(scale, Offset(offsetX, offsetY))
    }

    /**
     * Calculate viewport to fit the graph width on screen.
     * Shows the full width and top of the graph.
     *
     * @param bounds Graph bounds
     * @param canvasWidth Available canvas width
     * @param canvasHeight Available canvas height
     * @param horizontalPadding Padding on left and right sides
     * @param topPadding Padding from top of canvas
     * @return ViewportTransform to fit width, or null if bounds/canvas are invalid
     */
    fun fitToWidth(
        bounds: GraphBounds,
        canvasWidth: Float,
        canvasHeight: Float,
        horizontalPadding: Float = 16f,
        topPadding: Float = 8f
    ): ViewportTransform? {
        if (bounds.width <= 0 || bounds.height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return null
        }

        val availableWidth = canvasWidth - horizontalPadding * 2

        // Calculate scale to fit width only
        val scale = minOf(availableWidth / bounds.width, MAX_FIT_SCALE)

        // Center horizontally, anchor to top vertically
        val scaledWidth = bounds.width * scale
        val offsetX = (canvasWidth - scaledWidth) / 2 - bounds.minX * scale
        val offsetY = topPadding - bounds.minY * scale

        return ViewportTransform(scale, Offset(offsetX, offsetY))
    }

    /**
     * Calculate viewport to fit the graph height on screen.
     * Shows the full height and left side of the graph.
     *
     * @param bounds Graph bounds
     * @param canvasWidth Available canvas width
     * @param canvasHeight Available canvas height
     * @param leftPadding Padding from left of canvas
     * @param topPadding Padding from top of canvas
     * @param bottomPadding Padding from bottom (e.g., for toolbar)
     * @return ViewportTransform to fit height, or null if bounds/canvas are invalid
     */
    fun fitToHeight(
        bounds: GraphBounds,
        canvasWidth: Float,
        canvasHeight: Float,
        leftPadding: Float = 16f,
        topPadding: Float = 8f,
        bottomPadding: Float = 100f
    ): ViewportTransform? {
        if (bounds.width <= 0 || bounds.height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return null
        }

        val availableHeight = canvasHeight - topPadding - bottomPadding

        // Calculate scale to fit height only
        val scale = minOf(availableHeight / bounds.height, MAX_FIT_SCALE)

        // Anchor to left horizontally, anchor to top vertically
        val offsetX = leftPadding - bounds.minX * scale
        val offsetY = topPadding - bounds.minY * scale

        return ViewportTransform(scale, Offset(offsetX, offsetY))
    }

    /**
     * Constrain a scale value to the allowed range.
     *
     * @param scale Scale value to constrain
     * @return Scale clamped to [MIN_SCALE, MAX_SCALE]
     */
    fun constrainScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
