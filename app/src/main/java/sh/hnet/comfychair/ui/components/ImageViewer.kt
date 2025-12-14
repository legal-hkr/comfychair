package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * Image viewer with constrained pinch-to-zoom and pan.
 * - Pinch to zoom between 1x and 5x
 * - Double-tap to toggle between fit (1x) and crop (fill screen) zoom
 * - Pan with boundary constraints (image always stays on screen)
 * - Single tap callback for UI toggle (with delay to detect double-tap)
 */
@Composable
fun ImageViewer(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit = {}
) {
    // Animatable values for smooth zoom transitions
    val scaleAnimatable = remember { Animatable(1f) }
    val offsetAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    // Current values (used during gestures for immediate feedback)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isAnimating by remember { mutableStateOf(false) }

    // Track last tap for double-tap detection (must persist across gestures)
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }

    // Pending single tap - we delay single tap action to check for double-tap
    var pendingSingleTap by remember { mutableStateOf(false) }
    var pendingSingleTapTime by remember { mutableStateOf(0L) }

    // Handle delayed single tap (wait to see if it becomes a double-tap)
    androidx.compose.runtime.LaunchedEffect(pendingSingleTap, pendingSingleTapTime) {
        if (pendingSingleTap) {
            kotlinx.coroutines.delay(300L) // Wait for potential second tap
            // If still pending after delay, it's a true single tap
            if (pendingSingleTap && System.currentTimeMillis() - pendingSingleTapTime >= 280L) {
                pendingSingleTap = false
                onSingleTap()
            }
        }
    }

    // Calculate the displayed image size based on Fit scaling
    val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val containerAspectRatio = if (containerSize.height > 0) {
        containerSize.width.toFloat() / containerSize.height.toFloat()
    } else 1f

    // Calculate scale needed to fill/crop the screen
    val cropScale = remember(containerSize, bitmap.width, bitmap.height) {
        if (containerSize.width == 0 || containerSize.height == 0) {
            1f
        } else {
            val scaleToFillWidth = containerSize.width.toFloat() / bitmap.width.toFloat()
            val scaleToFillHeight = containerSize.height.toFloat() / bitmap.height.toFloat()
            val fitScale = minOf(scaleToFillWidth, scaleToFillHeight)
            val cropScaleAbs = maxOf(scaleToFillWidth, scaleToFillHeight)
            // Return the ratio: how much to zoom from fit to crop
            cropScaleAbs / fitScale
        }
    }

    val displayedImageSize = remember(containerSize, bitmap.width, bitmap.height) {
        if (containerSize.width == 0 || containerSize.height == 0) {
            Size.Zero
        } else if (imageAspectRatio > containerAspectRatio) {
            // Image is wider - fit to width
            val width = containerSize.width.toFloat()
            val height = width / imageAspectRatio
            Size(width, height)
        } else {
            // Image is taller - fit to height
            val height = containerSize.height.toFloat()
            val width = height * imageAspectRatio
            Size(width, height)
        }
    }

    // Calculate constrained offset to keep image on screen
    fun constrainOffset(newOffset: Offset, currentScale: Float): Offset {
        if (displayedImageSize == Size.Zero) return Offset.Zero

        val scaledWidth = displayedImageSize.width * currentScale
        val scaledHeight = displayedImageSize.height * currentScale

        // Calculate how much the image exceeds the container
        val excessWidth = (scaledWidth - containerSize.width).coerceAtLeast(0f) / 2f
        val excessHeight = (scaledHeight - containerSize.height).coerceAtLeast(0f) / 2f

        return Offset(
            x = newOffset.x.coerceIn(-excessWidth, excessWidth),
            y = newOffset.y.coerceIn(-excessHeight, excessHeight)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = if (isAnimating) scaleAnimatable.value else scale,
                    scaleY = if (isAnimating) scaleAnimatable.value else scale,
                    translationX = if (isAnimating) offsetAnimatable.value.x else offset.x,
                    translationY = if (isAnimating) offsetAnimatable.value.y else offset.y
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val firstDownTime = System.currentTimeMillis()
                        val firstDownPosition = firstDown.position

                        var wasPanOrZoom = false
                        var pointerCount = 1
                        var totalMovement = 0f  // Track total finger movement

                        do {
                            val event = awaitPointerEvent()
                            val currentPointerCount = event.changes.size

                            // Track if we ever had multiple pointers
                            if (currentPointerCount > pointerCount) {
                                pointerCount = currentPointerCount
                            }

                            if (currentPointerCount >= 2) {
                                // Multi-touch: zoom and pan
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()

                                if (zoom != 1f || pan != Offset.Zero) {
                                    wasPanOrZoom = true

                                    val newScale = (scale * zoom).coerceIn(1f, 5f)

                                    // Zoom toward centroid
                                    val zoomChange = newScale / scale
                                    val newOffset = Offset(
                                        x = offset.x * zoomChange + (1 - zoomChange) * (centroid.x - size.width / 2f) + pan.x,
                                        y = offset.y * zoomChange + (1 - zoomChange) * (centroid.y - size.height / 2f) + pan.y
                                    )

                                    scale = newScale
                                    offset = constrainOffset(newOffset, newScale)
                                }

                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (currentPointerCount == 1) {
                                // Single finger
                                val change = event.changes.first()
                                if (change.positionChanged()) {
                                    val movement = (change.position - change.previousPosition).getDistance()
                                    totalMovement += movement

                                    // Pan only when zoomed
                                    if (scale > 1f) {
                                        val pan = change.position - change.previousPosition
                                        if (pan != Offset.Zero) {
                                            wasPanOrZoom = true
                                            // Scale the pan delta to match the zoom level
                                            // (translation is in pre-scale coordinates)
                                            offset = constrainOffset(
                                                Offset(offset.x + pan.x * scale, offset.y + pan.y * scale),
                                                scale
                                            )
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // Handle taps after gesture ends
                        val upTime = System.currentTimeMillis()
                        val gestureTime = upTime - firstDownTime

                        // Only treat as tap if:
                        // - No pan/zoom occurred
                        // - Gesture was quick (< 300ms)
                        // - Single finger only
                        // - Total movement was minimal (< 30 pixels) - this prevents swipes from being taps
                        val maxTapMovement = 30f
                        val isTap = !wasPanOrZoom &&
                                    gestureTime < 300 &&
                                    pointerCount == 1 &&
                                    totalMovement < maxTapMovement

                        if (isTap) {
                            // This was a tap
                            val timeSinceLastTap = upTime - lastTapTime
                            val distanceFromLastTap = (firstDownPosition - lastTapPosition).getDistance()

                            if (lastTapTime > 0 && timeSinceLastTap < 300 && distanceFromLastTap < 100) {
                                // Double tap detected - cancel pending single tap and animate zoom
                                pendingSingleTap = false

                                val currentScale = scale
                                val currentOffset = offset

                                if (currentScale > 1.05f) {
                                    // Animate zoom out to 1x (fit to screen)
                                    scope.launch {
                                        isAnimating = true
                                        scaleAnimatable.snapTo(currentScale)
                                        offsetAnimatable.snapTo(currentOffset)
                                        // Run both animations in parallel
                                        launch { scaleAnimatable.animateTo(1f, tween(250)) }
                                        launch { offsetAnimatable.animateTo(Offset.Zero, tween(250)) }
                                    }.invokeOnCompletion {
                                        scale = 1f
                                        offset = Offset.Zero
                                        isAnimating = false
                                    }
                                } else {
                                    // Animate zoom in to crop scale (fill screen)
                                    val targetScale = cropScale.coerceIn(1f, 5f)
                                    val newOffset = Offset(
                                        x = (size.width / 2f - firstDownPosition.x) * (targetScale - 1f) / targetScale,
                                        y = (size.height / 2f - firstDownPosition.y) * (targetScale - 1f) / targetScale
                                    )
                                    val constrainedOffset = constrainOffset(newOffset, targetScale)

                                    scope.launch {
                                        isAnimating = true
                                        scaleAnimatable.snapTo(currentScale)
                                        offsetAnimatable.snapTo(currentOffset)
                                        // Run both animations in parallel
                                        launch { scaleAnimatable.animateTo(targetScale, tween(250)) }
                                        launch { offsetAnimatable.animateTo(constrainedOffset, tween(250)) }
                                    }.invokeOnCompletion {
                                        scale = targetScale
                                        offset = constrainedOffset
                                        isAnimating = false
                                    }
                                }
                                // Reset tap tracking after double-tap
                                lastTapTime = 0L
                                lastTapPosition = Offset.Zero
                            } else {
                                // First tap - record it and schedule delayed single tap action
                                lastTapTime = upTime
                                lastTapPosition = firstDownPosition
                                pendingSingleTapTime = upTime
                                pendingSingleTap = true
                            }
                        } else {
                            // Not a tap (was a pan/zoom/swipe) - reset tap tracking
                            lastTapTime = 0L
                            lastTapPosition = Offset.Zero
                            pendingSingleTap = false
                        }
                    }
                },
            contentScale = ContentScale.Fit
        )
    }
}
