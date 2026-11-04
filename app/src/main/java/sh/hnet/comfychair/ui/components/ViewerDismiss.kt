package sh.hnet.comfychair.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** M3 standard easing: no spring, so nothing overshoots. */
internal val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val SETTLE_MS = 300
private const val FADE_OUT_MS = 200

/** Below this share of the screen height a released drag settles back (unless flung). */
private const val CLOSE_THRESHOLD = 0.25f

/** Where the viewer's media is when closing starts, so a flight back can start from there. */
class ViewerCloseFrom(val offsetY: Float, val scale: Float, val cornerPx: Float, val backdropAlpha: Float)

/**
 * Closing the media viewer by swiping the media down or by predictive back: the media follows the finger (or the
 * back progress), shrinks to 85 % and rounds its corners by the threshold, and the backdrop fades.
 */
@Stable
class ViewerDismissState internal constructor() {
    private val offsetAnim = Animatable(0f)
    private val backAnim = Animatable(0f)
    private val alphaAnim = Animatable(1f)

    /** Height of the viewer in pixels, for the threshold. */
    var containerHeight by mutableIntStateOf(0)

    /** A swipe or back gesture is in progress, or the viewer is closing: overlays hide. */
    var active by mutableStateOf(false)
        private set

    /** The viewer is fading out; no new gesture starts. */
    var closing by mutableStateOf(false)
        private set

    private val threshold: Float get() = containerHeight * CLOSE_THRESHOLD

    /** Vertical offset of the media in pixels. */
    val offset: Float get() = offsetAnim.value

    /** 0 at rest, 1 at the threshold (drag) or at full back progress. */
    val progress: Float
        get() {
            val drag = if (threshold > 0f) (offsetAnim.value / threshold).coerceIn(0f, 1f) else 0f
            return maxOf(drag, backAnim.value)
        }

    val contentAlpha: Float get() = alphaAnim.value
    val backdropAlpha: Float get() = (1f - progress) * alphaAnim.value

    internal val pastThreshold: Boolean get() = threshold > 0f && offsetAnim.value > threshold

    fun start() {
        active = true
    }

    internal suspend fun dragBy(dy: Float) {
        offsetAnim.snapTo((offsetAnim.value + dy).coerceAtLeast(0f))
    }

    suspend fun backProgress(progress: Float) {
        backAnim.snapTo(progress.coerceIn(0f, 1f))
    }

    /** Back to full screen; the overlays return. */
    suspend fun settle() {
        coroutineScope {
            launch { offsetAnim.animateTo(0f, tween(SETTLE_MS, easing = StandardEasing)) }
            launch { backAnim.animateTo(0f, tween(SETTLE_MS, easing = StandardEasing)) }
        }
        active = false
    }

    /** The media's place now: offset, scale, corner (28 dp at the threshold) and backdrop. */
    fun closeFrom(cornerAtThresholdPx: Float): ViewerCloseFrom =
        ViewerCloseFrom(offset, 1f - 0.15f * progress, cornerAtThresholdPx * progress, backdropAlpha)

    /** Closing by another animation (the flight back): no new gesture, overlays stay hidden. */
    fun markClosing() {
        closing = true
        active = true
    }

    /** Fade the media out from where it is (the viewer then closes without a window animation). */
    suspend fun fadeOut() {
        closing = true
        active = true
        alphaAnim.animateTo(0f, tween(FADE_OUT_MS, easing = StandardEasing))
    }
}

@Composable
fun rememberViewerDismissState(): ViewerDismissState = remember { ViewerDismissState() }

/**
 * Swipe down to close. Starts only on a mostly vertical downward drag of one finger that nothing below has taken:
 * the pager takes horizontal swipes, and zoomed media take their own pans. Upward drags do nothing.
 *
 * @param onStart Called when the swipe starts; returns whether a video was playing (paused meanwhile)
 * @param onClose Released past a quarter of the height, or flung down
 * @param onSettle Released short of it, after the media is back; gets [onStart]'s result
 */
@Composable
fun Modifier.viewerSwipeToDismiss(
    state: ViewerDismissState,
    onStart: () -> Boolean,
    onClose: () -> Unit,
    onSettle: (wasPlaying: Boolean) -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnSettle by rememberUpdatedState(onSettle)
    val minFlingVelocity = remember(context) {
        android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    }

    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (state.closing) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var total = Offset.Zero
            var dragging = false
            var wasPlaying = false

            fun settle() {
                scope.launch {
                    state.settle()
                    currentOnSettle(wasPlaying)
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                // A second finger: pinch belongs to the media
                if (event.changes.size > 1) {
                    if (dragging) settle()
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    if (dragging) {
                        val velocity = tracker.calculateVelocity().y
                        if (state.pastThreshold || velocity > minFlingVelocity) currentOnClose() else settle()
                    }
                    break
                }
                if (!dragging) {
                    // Taken by the pager or a zoomed image
                    if (change.isConsumed) break
                    total += change.positionChange()
                    if (total.getDistance() > viewConfiguration.touchSlop) {
                        if (total.y > 0f && abs(total.y) > abs(total.x)) {
                            dragging = true
                            wasPlaying = currentOnStart()
                            state.start()
                            change.consume()
                        } else {
                            break
                        }
                    }
                } else {
                    val dy = change.positionChange().y
                    change.consume()
                    scope.launch { state.dragBy(dy) }
                }
            }
        }
    }
}
