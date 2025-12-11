package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R

/**
 * Scale mode for video display
 */
enum class VideoScaleMode {
    /** Fit video to container, letterboxing if needed */
    FIT,
    /** Fill container, cropping edges if needed */
    CROP
}

/**
 * Composable wrapper for Media3 ExoPlayer
 * Provides video playback with looping, configurable scaling, and optional zoom/pan gestures.
 *
 * Uses a thumbnail + conditional player strategy to handle navigation transitions.
 * AndroidView renders on a separate surface layer that doesn't participate in
 * Compose animations and can "bleed through" during transitions. To solve this:
 * - When active: Shows video player with thumbnail overlay initially, then reveals video
 * - When inactive: Completely removes the video player from composition, shows only thumbnail
 *
 * @param videoUri The URI of the video to play
 * @param modifier Modifier for the player view
 * @param showController Whether to show playback controls
 * @param isActive Set to false when screen is not visible to destroy player and show thumbnail
 * @param scaleMode How to scale the video (FIT or CROP)
 * @param enableZoom Whether to enable pinch-to-zoom and double-tap zoom gestures (only works with FIT mode)
 * @param onSingleTap Callback when user taps on the video (only works when showController is false)
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    showController: Boolean = false,
    isActive: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.CROP,
    enableZoom: Boolean = false,
    onSingleTap: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Thumbnail for displaying when player is destroyed during transitions
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    // Controls whether to show thumbnail overlay on top of video
    var showThumbnailOverlay by remember { mutableStateOf(true) }

    // Video dimensions for aspect ratio calculations
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }

    // Zoom/pan state
    val scaleAnimatable = remember { Animatable(1f) }
    val offsetAnimatable = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isAnimating by remember { mutableStateOf(false) }

    // Tap detection state
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Zero) }
    var pendingSingleTap by remember { mutableStateOf(false) }
    var pendingSingleTapTime by remember { mutableStateOf(0L) }

    // Handle delayed single tap
    LaunchedEffect(pendingSingleTap, pendingSingleTapTime) {
        if (pendingSingleTap) {
            delay(300L)
            if (pendingSingleTap && System.currentTimeMillis() - pendingSingleTapTime >= 280L) {
                pendingSingleTap = false
                onSingleTap?.invoke()
            }
        }
    }

    // Extract first frame as thumbnail and video dimensions when video URI changes
    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            showThumbnailOverlay = true
            // Reset zoom when video changes
            scale = 1f
            offset = Offset.Zero
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, videoUri)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: frame?.width ?: 0
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: frame?.height ?: 0
                    retriever.release()
                    thumbnail = frame
                    videoWidth = width
                    videoHeight = height
                } catch (e: Exception) {
                    thumbnail = null
                    videoWidth = 0
                    videoHeight = 0
                }
            }
        } else {
            thumbnail = null
            videoWidth = 0
            videoHeight = 0
        }
    }

    // Reset thumbnail overlay when becoming inactive
    LaunchedEffect(isActive) {
        if (!isActive) {
            showThumbnailOverlay = true
        }
    }

    // Calculate video display size for zoom calculations
    val videoAspectRatio = if (videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 16f / 9f
    val containerAspectRatio = if (containerSize.height > 0) containerSize.width.toFloat() / containerSize.height.toFloat() else 1f

    val displayedVideoSize = remember(containerSize, videoWidth, videoHeight) {
        if (containerSize.width == 0 || containerSize.height == 0 || videoWidth == 0 || videoHeight == 0) {
            Size.Zero
        } else if (videoAspectRatio > containerAspectRatio) {
            // Video is wider - fit to width
            val width = containerSize.width.toFloat()
            val height = width / videoAspectRatio
            Size(width, height)
        } else {
            // Video is taller - fit to height
            val height = containerSize.height.toFloat()
            val width = height * videoAspectRatio
            Size(width, height)
        }
    }

    // Calculate scale needed to fill/crop the screen
    val cropScale = remember(containerSize, videoWidth, videoHeight) {
        if (containerSize.width == 0 || containerSize.height == 0 || videoWidth == 0 || videoHeight == 0) {
            1f
        } else {
            val scaleToFillWidth = containerSize.width.toFloat() / videoWidth.toFloat()
            val scaleToFillHeight = containerSize.height.toFloat() / videoHeight.toFloat()
            val fitScale = minOf(scaleToFillWidth, scaleToFillHeight)
            val cropScaleAbs = maxOf(scaleToFillWidth, scaleToFillHeight)
            cropScaleAbs / fitScale
        }
    }

    // Constrain offset to keep video on screen
    fun constrainOffset(newOffset: Offset, currentScale: Float): Offset {
        if (displayedVideoSize == Size.Zero) return Offset.Zero

        val scaledWidth = displayedVideoSize.width * currentScale
        val scaledHeight = displayedVideoSize.height * currentScale

        val excessWidth = (scaledWidth - containerSize.width).coerceAtLeast(0f) / 2f
        val excessHeight = (scaledHeight - containerSize.height).coerceAtLeast(0f) / 2f

        return Offset(
            x = newOffset.x.coerceIn(-excessWidth, excessWidth),
            y = newOffset.y.coerceIn(-excessHeight, excessHeight)
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerSize = it }
    ) {
        // Content with zoom transformation
        val currentScale = if (isAnimating) scaleAnimatable.value else scale
        val currentOffset = if (isAnimating) offsetAnimatable.value else offset

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = currentScale,
                    scaleY = currentScale,
                    translationX = currentOffset.x,
                    translationY = currentOffset.y
                )
                .then(
                    if (enableZoom && !showController) {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val firstDownTime = System.currentTimeMillis()
                                val firstDownPosition = firstDown.position

                                var wasPanOrZoom = false
                                var pointerCount = 1
                                var totalMovement = 0f

                                do {
                                    val event = awaitPointerEvent()
                                    val currentPointerCount = event.changes.size

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
                                        val change = event.changes.first()
                                        if (change.positionChanged()) {
                                            val movement = (change.position - change.previousPosition).getDistance()
                                            totalMovement += movement

                                            if (scale > 1f) {
                                                val pan = change.position - change.previousPosition
                                                if (pan != Offset.Zero) {
                                                    wasPanOrZoom = true
                                                    offset = constrainOffset(
                                                        Offset(offset.x + pan.x, offset.y + pan.y),
                                                        scale
                                                    )
                                                    change.consume()
                                                }
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                // Handle taps
                                val upTime = System.currentTimeMillis()
                                val gestureTime = upTime - firstDownTime
                                val maxTapMovement = 30f
                                val isTap = !wasPanOrZoom && gestureTime < 300 && pointerCount == 1 && totalMovement < maxTapMovement

                                if (isTap) {
                                    val timeSinceLastTap = upTime - lastTapTime
                                    val distanceFromLastTap = (firstDownPosition - lastTapPosition).getDistance()

                                    if (lastTapTime > 0 && timeSinceLastTap < 300 && distanceFromLastTap < 100) {
                                        // Double tap - toggle zoom
                                        pendingSingleTap = false
                                        val currentScaleVal = scale
                                        val currentOffsetVal = offset

                                        if (currentScaleVal > 1.05f) {
                                            // Zoom out to fit
                                            scope.launch {
                                                isAnimating = true
                                                scaleAnimatable.snapTo(currentScaleVal)
                                                offsetAnimatable.snapTo(currentOffsetVal)
                                                launch { scaleAnimatable.animateTo(1f, tween(250)) }
                                                launch { offsetAnimatable.animateTo(Offset.Zero, tween(250)) }
                                            }.invokeOnCompletion {
                                                scale = 1f
                                                offset = Offset.Zero
                                                isAnimating = false
                                            }
                                        } else {
                                            // Zoom in to crop
                                            val targetScale = cropScale.coerceIn(1f, 5f)
                                            val newOffset = Offset(
                                                x = (size.width / 2f - firstDownPosition.x) * (targetScale - 1f) / targetScale,
                                                y = (size.height / 2f - firstDownPosition.y) * (targetScale - 1f) / targetScale
                                            )
                                            val constrainedOffset = constrainOffset(newOffset, targetScale)

                                            scope.launch {
                                                isAnimating = true
                                                scaleAnimatable.snapTo(currentScaleVal)
                                                offsetAnimatable.snapTo(currentOffsetVal)
                                                launch { scaleAnimatable.animateTo(targetScale, tween(250)) }
                                                launch { offsetAnimatable.animateTo(constrainedOffset, tween(250)) }
                                            }.invokeOnCompletion {
                                                scale = targetScale
                                                offset = constrainedOffset
                                                isAnimating = false
                                            }
                                        }
                                        lastTapTime = 0L
                                        lastTapPosition = Offset.Zero
                                    } else {
                                        // First tap - schedule single tap
                                        lastTapTime = upTime
                                        lastTapPosition = firstDownPosition
                                        pendingSingleTapTime = upTime
                                        pendingSingleTap = true
                                    }
                                } else {
                                    lastTapTime = 0L
                                    lastTapPosition = Offset.Zero
                                    pendingSingleTap = false
                                }
                            }
                        }
                    } else if (onSingleTap != null && !showController) {
                        // Simple tap detection without zoom
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val firstDownTime = System.currentTimeMillis()
                                var totalMovement = 0f

                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size == 1) {
                                        val change = event.changes.first()
                                        if (change.positionChanged()) {
                                            totalMovement += (change.position - change.previousPosition).getDistance()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                val upTime = System.currentTimeMillis()
                                val gestureTime = upTime - firstDownTime
                                if (gestureTime < 300 && totalMovement < 30f) {
                                    onSingleTap()
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            if (isActive && videoUri != null) {
                // Only create ExoPlayer when active
                val exoPlayer = remember(videoUri) {
                    ExoPlayer.Builder(context).build().apply {
                        repeatMode = Player.REPEAT_MODE_ALL
                        setMediaItem(MediaItem.fromUri(videoUri))
                        prepare()
                        play()
                    }
                }

                // Release player when it leaves composition
                DisposableEffect(exoPlayer) {
                    onDispose {
                        exoPlayer.release()
                    }
                }

                // Hide thumbnail overlay after delay to reveal video
                LaunchedEffect(Unit) {
                    delay(150)
                    showThumbnailOverlay = false
                }

                // Determine resize mode based on scaleMode and enableZoom
                val resizeMode = when {
                    enableZoom -> AspectRatioFrameLayout.RESIZE_MODE_FIT // Zoom needs FIT as base
                    scaleMode == VideoScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                val thumbnailContentScale = when {
                    enableZoom -> ContentScale.Fit
                    scaleMode == VideoScaleMode.CROP -> ContentScale.Crop
                    else -> ContentScale.Fit
                }

                // Video player
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = showController
                            this.resizeMode = resizeMode
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { playerView ->
                        playerView.useController = showController
                    }
                )

                // Thumbnail overlay while video is starting
                if (showThumbnailOverlay && thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = thumbnailContentScale
                    )
                }
            } else if (thumbnail != null) {
                // When inactive, show only the thumbnail
                val thumbnailContentScale = when {
                    enableZoom -> ContentScale.Fit
                    scaleMode == VideoScaleMode.CROP -> ContentScale.Crop
                    else -> ContentScale.Fit
                }
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = thumbnailContentScale
                )
            }
        }
    }
}

/**
 * Composable for fullscreen video playback with controls and close button
 * @param videoUri The URI of the video to play
 * @param modifier Modifier for the player view
 * @param onDismiss Callback when user wants to dismiss
 */
@OptIn(UnstableApi::class)
@Composable
fun FullscreenVideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true
        }
    }

    // Handle video URI changes and release player on dispose
    DisposableEffect(videoUri) {
        if (videoUri != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
            exoPlayer.play()
        }
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // Fit for fullscreen
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Close button
        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.content_description_close),
                    tint = Color.White
                )
            }
        }
    }
}
