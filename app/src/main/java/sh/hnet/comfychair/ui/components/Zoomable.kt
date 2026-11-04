package sh.hnet.comfychair.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Zoom and pan of content shown fitted (ContentScale.Fit) in its container, shared by the image and video viewers.
 *
 * Everything the gesture handler needs is read from this state when a gesture happens, so the handler never works
 * with values from an earlier composition.
 */
@Stable
class ZoomState internal constructor() {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    internal var containerSize = IntSize.Zero
    /** Size of the content in pixels; zero while unknown (then the content can be zoomed but not panned). */
    internal var contentSize = IntSize.Zero
    internal var onSingleTap: () -> Unit = {}

    private val center get() = Offset(containerSize.width / 2f, containerSize.height / 2f)

    /** Size of the content fitted in the container at scale 1. */
    private val fittedSize: Size
        get() {
            if (containerSize.width == 0 || containerSize.height == 0 || contentSize.width == 0 || contentSize.height == 0) return Size.Zero
            val fit = minOf(containerSize.width.toFloat() / contentSize.width, containerSize.height.toFloat() / contentSize.height)
            return Size(contentSize.width * fit, contentSize.height * fit)
        }

    /** Scale at which the content fills the container (crop). */
    private val cropScale: Float
        get() {
            val fitted = fittedSize
            if (fitted == Size.Zero) return 1f
            return maxOf(containerSize.width / fitted.width, containerSize.height / fitted.height)
        }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Keeps the content on screen: it can move only as far as it is larger than the container. */
    private fun constrain(newOffset: Offset, atScale: Float): Offset {
        val fitted = fittedSize
        if (fitted == Size.Zero) return Offset.Zero
        val excessX = (fitted.width * atScale - containerSize.width).coerceAtLeast(0f) / 2f
        val excessY = (fitted.height * atScale - containerSize.height).coerceAtLeast(0f) / 2f
        return Offset(newOffset.x.coerceIn(-excessX, excessX), newOffset.y.coerceIn(-excessY, excessY))
    }

    /** Offset that keeps the content point under [focus] (container coordinates) in place when the scale changes. */
    private fun offsetFor(newScale: Float, focus: Offset): Offset {
        val change = newScale / scale
        val fromCenter = focus - center
        return offset * change + fromCenter * (1f - change)
    }

    internal fun transformBy(zoom: Float, pan: Offset, centroid: Offset) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        offset = constrain(offsetFor(newScale, centroid) + pan, newScale)
        scale = newScale
    }

    internal fun panBy(pan: Offset) {
        offset = constrain(offset + pan, scale)
    }

    /** Double tap: back to fit when zoomed, otherwise zoom to fill the container around the tapped point. */
    internal suspend fun toggleZoom(tap: Offset) {
        val targetScale = if (scale > 1.05f) 1f else cropScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val targetOffset = if (targetScale == 1f) Offset.Zero else constrain(offsetFor(targetScale, tap), targetScale)
        val startScale = scale
        val startOffset = offset
        animate(0f, 1f, animationSpec = tween(ANIMATION_MS)) { fraction, _ ->
            scale = startScale + (targetScale - startScale) * fraction
            offset = startOffset + (targetOffset - startOffset) * fraction
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
        const val ANIMATION_MS = 250
    }
}

/**
 * @param contentSize Size of the content in pixels, zero while unknown
 * @param resetKey The zoom returns to fit when this changes (e.g. another video)
 * @param onSingleTap Called for a tap that didn't become a double tap
 */
@Composable
fun rememberZoomState(contentSize: IntSize, resetKey: Any? = Unit, onSingleTap: () -> Unit = {}): ZoomState {
    val state = remember { ZoomState() }
    SideEffect {
        state.contentSize = contentSize
        state.onSingleTap = onSingleTap
    }
    LaunchedEffect(resetKey) { state.reset() }
    return state
}

/**
 * Pinch to zoom (1–5x) towards the fingers, pan when zoomed (the content always stays on screen), double tap to
 * toggle between fit and fill, single tap after the double-tap timeout. Gestures are read in the container's
 * coordinates, before the zoom is applied, so the content follows the fingers at any scale. Single-finger drags
 * at fit scale are left to the parent (e.g. a pager).
 */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .onSizeChanged { state.containerSize = it }
    .pointerInput(state) { zoomGestures(state) }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

private const val TAP_TIMEOUT_MS = 300L
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val TAP_SLOP_PX = 30f
private const val DOUBLE_TAP_SLOP_PX = 100f

private suspend fun PointerInputScope.zoomGestures(state: ZoomState) = coroutineScope {
    var pendingSingleTap: Job? = null
    var animation: Job? = null
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A new touch takes over from a running double-tap animation
        animation?.cancel()
        var transformed = false
        var multiTouch = false
        var movement = 0f
        var upTime = down.uptimeMillis

        do {
            val event = awaitPointerEvent()
            upTime = event.changes.first().uptimeMillis
            if (event.changes.size >= 2) {
                multiTouch = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    transformed = true
                    state.transformBy(zoom, pan, event.calculateCentroid())
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else {
                val change = event.changes.first()
                if (change.positionChanged()) {
                    val pan = change.position - change.previousPosition
                    movement += pan.getDistance()
                    // At fit scale the drag belongs to the parent
                    if (state.scale > 1f) {
                        transformed = true
                        state.panBy(pan)
                        change.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        val isTap = !transformed && !multiTouch && upTime - down.uptimeMillis < TAP_TIMEOUT_MS && movement < TAP_SLOP_PX
        pendingSingleTap?.cancel()
        when {
            !isTap -> lastTapTime = 0L
            lastTapTime > 0L && upTime - lastTapTime < DOUBLE_TAP_TIMEOUT_MS &&
                (down.position - lastTapPosition).getDistance() < DOUBLE_TAP_SLOP_PX -> {
                lastTapTime = 0L
                animation = launch { state.toggleZoom(down.position) }
            }
            else -> {
                lastTapTime = upTime
                lastTapPosition = down.position
                pendingSingleTap = launch {
                    delay(DOUBLE_TAP_TIMEOUT_MS)
                    state.onSingleTap()
                }
            }
        }
    }
}
