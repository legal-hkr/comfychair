package sh.hnet.comfychair.ui.components

import androidx.compose.ui.unit.Dp
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.util.DebugLogger

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
 * Provides looping video playback with configurable scaling and optional zoom/pan gestures.
 *
 * Uses a thumbnail + conditional player strategy to handle navigation transitions.
 * AndroidView renders on a separate surface layer that doesn't participate in
 * Compose animations and can "bleed through" during transitions. To solve this:
 * - When active: Shows video player with thumbnail overlay initially, then reveals video
 * - When inactive: Completely removes the video player from composition, shows only thumbnail
 *
 * @param videoUri The URI of the video to play
 * @param modifier Modifier for the player view
 * @param isActive Set to false when screen is not visible to destroy player and show thumbnail
 * @param scaleMode How to scale the video (FIT or CROP)
 * @param enableZoom Whether to enable zoom and pan gestures ([zoomable]); the video is then fitted
 * @param onSingleTap Called for a tap that didn't become a double tap (only with [enableZoom])
 * @param cacheKey Optional cache key to look up pre-fetched metadata from MediaCache
 * @param placeholder A first frame of the video the caller already has (in memory), shown until the video plays and
 *   used for its proportions, so nothing empty flashes when the player appears
 * @param showPlaybackStatus Show the pause/position chip at the top end and a position line at the bottom edge
 *   (inline results on the generation screens)
 * @param originKey Marks the player as the [mediaOrigin] of the media viewer's flight, with its first frame as the
 *   image that flies (the video itself draws on a surface the flight can't take)
 * @param originCorner Corner radius of the player's place, for the flight
 *
 * Inside the media viewer, playback waits while the viewer's medium is still flying in ([LocalMediaViewerLanding]), so
 * the video starts from its first frame once the flight has landed.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    scaleMode: VideoScaleMode = VideoScaleMode.CROP,
    enableZoom: Boolean = false,
    onSingleTap: () -> Unit = {},
    cacheKey: MediaCacheKey? = null,
    showPlaybackStatus: Boolean = false,
    placeholder: Bitmap? = null,
    originKey: String? = null,
    originCorner: Dp = 0.dp
) {
    val playbackHeld = LocalMediaViewerLanding.current
    val coveredByViewer = LocalCoveredByMediaViewer.current
    val context = LocalContext.current

    // Initialize thumbnail synchronously from cache to avoid black flash during transitions
    // This ensures the thumbnail is available on the very first composition
    // A first frame the caller already has stands in when the cache has none
    val initialThumbnail = remember(cacheKey) {
        cacheKey?.let { MediaCache.getBitmap(it) } ?: placeholder
    }
    var thumbnail by remember(cacheKey) { mutableStateOf(initialThumbnail) }

    // Video dimensions - also check cache synchronously for instant display. The placeholder's proportions are the
    // video's, which is all the layout needs, so the surface can be shown without waiting for the metadata
    val cachedDimensions = remember(cacheKey) {
        cacheKey?.let { MediaCache.getVideoDimensions(it) }
    }
    var videoWidth by remember(cacheKey) { mutableStateOf(cachedDimensions?.width ?: placeholder?.width ?: 0) }
    var videoHeight by remember(cacheKey) { mutableStateOf(cachedDimensions?.height ?: placeholder?.height ?: 0) }
    // The placeholder may arrive after the first composition
    LaunchedEffect(placeholder) {
        if (placeholder != null) {
            if (thumbnail == null) thumbnail = placeholder
            if (videoWidth == 0 || videoHeight == 0) {
                videoWidth = placeholder.width
                videoHeight = placeholder.height
            }
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomState = rememberZoomState(IntSize(videoWidth, videoHeight), resetKey = videoUri, onSingleTap = onSingleTap)

    // Extract video dimensions when not available from cache
    // Thumbnail and dimensions are initialized synchronously from cache above
    // This LaunchedEffect only handles the fallback case when cache is empty
    LaunchedEffect(videoUri, cacheKey) {
        if (videoUri != null) {
            // Only fall back to MediaMetadataRetriever if cache didn't have dimensions
            if (videoWidth == 0 || videoHeight == 0) {
                withContext(Dispatchers.IO) {
                    try {
                        val retriever = MediaMetadataRetriever()
                        InMemoryVideo.setDataSource(retriever, context, videoUri)
                        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: frame?.width ?: 0
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: frame?.height ?: 0
                        retriever.release()
                        if (thumbnail == null) {
                            thumbnail = frame
                        }
                        videoWidth = width
                        videoHeight = height
                    } catch (e: Exception) {
                        // Keep any existing values
                        DebugLogger.w("VideoPlayer", "Video metadata could not be read: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        // Note: We intentionally don't reset thumbnail/dimensions when videoUri becomes null.
        // The cached thumbnail should persist to avoid black flashes during transitions.
    }

    Box(
        modifier = modifier
            .mediaOrigin(originKey, originCorner, still = thumbnail)
            .clipToBounds()
            .onSizeChanged { containerSize = it }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (enableZoom) Modifier.zoomable(zoomState) else Modifier)
        ) {
            // Determine content scale for thumbnail
            val thumbnailContentScale = when {
                enableZoom -> ContentScale.Fit
                scaleMode == VideoScaleMode.CROP -> ContentScale.Crop
                else -> ContentScale.Fit
            }
            // Read once: the thumbnail may be set from the metadata loader meanwhile
            val currentThumbnail = thumbnail

            // Under an open media viewer the player belongs to the viewer (one surface at a time)
            if (isActive && !coveredByViewer && videoUri != null) {
                // Use shared ExoPlayer instance with reference counting
                val exoPlayer = remember { SharedVideoPlayer.registerConsumer(context) }

                // Unregister when leaving composition
                DisposableEffect(Unit) {
                    onDispose {
                        SharedVideoPlayer.unregisterConsumer()
                    }
                }

                // Prepare video (without playing) when URI changes
                LaunchedEffect(videoUri) {
                    SharedVideoPlayer.prepareVideo(context, videoUri)
                }

                // Use PresentationState to track when video surface is ready
                val presentationState = rememberPresentationState(exoPlayer)

                // Start playback only when surface is ready (first frame can be rendered)
                // This ensures video starts from frame 0 when thumbnail is replaced
                // (in the viewer, not before the flight in has landed)
                LaunchedEffect(presentationState.coverSurface, playbackHeld) {
                    if (!presentationState.coverSurface && !playbackHeld) {
                        SharedVideoPlayer.startPlayback()
                    }
                }

                // Use video dimensions from MediaMetadataRetriever (extracted earlier)
                // This is more reliable than presentationState.videoSizeDp when sharing ExoPlayer
                val hasValidDimensions = videoWidth > 0 && videoHeight > 0
                val playerVideoAspectRatio = if (hasValidDimensions) {
                    videoWidth.toFloat() / videoHeight.toFloat()
                } else {
                    16f / 9f // Default until dimensions available
                }

                // Calculate scale to maintain aspect ratio
                // CROP: scale up so video fills container completely (excess is clipped)
                // FIT: scale so video fits entirely within container (may have letterboxing)
                val aspectRatioScale = remember(containerSize, videoWidth, videoHeight, scaleMode, enableZoom) {
                    if (containerSize.width == 0 || containerSize.height == 0 || !hasValidDimensions) {
                        1f
                    } else {
                        val containerAspect = containerSize.width.toFloat() / containerSize.height.toFloat()

                        if (enableZoom || scaleMode == VideoScaleMode.FIT) {
                            // FIT mode: no additional scaling needed beyond aspectRatio modifier
                            1f
                        } else {
                            // CROP mode: scale up to fill container
                            if (playerVideoAspectRatio > containerAspect) {
                                // Video is wider - scale based on height to fill
                                playerVideoAspectRatio / containerAspect
                            } else {
                                // Video is taller - scale based on width to fill
                                containerAspect / playerVideoAspectRatio
                            }
                        }
                    }
                }

                // Only render PlayerSurface when dimensions are ready
                // SurfaceView renders on a separate hardware layer that can briefly show stretched
                // content before Compose overlays cover it, so we prevent this by not rendering at all
                if (hasValidDimensions) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerSurface(
                            player = exoPlayer,
                            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                            modifier = Modifier
                                .aspectRatio(playerVideoAspectRatio)
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = aspectRatioScale
                                    scaleY = aspectRatioScale
                                }
                        )

                        // Shutter overlay - show until surface is ready (first frame rendered)
                        if (presentationState.coverSurface) {
                            if (currentThumbnail != null) {
                                Image(
                                    bitmap = currentThumbnail.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = thumbnailContentScale
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.scrim)
                                )
                            }
                        }
                        // Only once the video shows, so the chip doesn't sit on the thumbnail
                        if (showPlaybackStatus && !presentationState.coverSurface) {
                            PlaybackStatus(exoPlayer)
                        }
                    }
                } else {
                    // Show placeholder until dimensions are extracted
                    if (currentThumbnail != null) {
                        Image(
                            bitmap = currentThumbnail.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = thumbnailContentScale
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim)
                        )
                    }
                }
            } else if (currentThumbnail != null) {
                // When inactive, show only the thumbnail
                Image(
                    bitmap = currentThumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = thumbnailContentScale
                )
            }
        }
    }
}

/**
 * Pause/position chip at the top end (tap toggles pause) over an inline video. Theme colours only. The position is
 * polled only while this is shown.
 */
@Composable
private fun BoxScope.PlaybackStatus(player: Player) {
    val playPause = rememberPlayPauseButtonState(player)
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
            delay(100)
        }
    }

    val position = formatPlaybackTime(positionMs)
    val duration = formatPlaybackTime(durationMs)
    val action = stringResource(if (playPause.showPlay) R.string.content_description_play_video else R.string.content_description_pause_video)
    val progress = stringResource(R.string.content_description_video_position, position, duration)
    Surface(
        onClick = playPause::onClick,
        enabled = playPause.isEnabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .clearAndSetSemantics {
                contentDescription = "$action, $progress"
                role = Role.Button
            }
    ) {
        Row(
            modifier = Modifier.height(32.dp).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (playPause.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "$position / $duration",
                // Tabular digits, so the chip doesn't change width while the time runs
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            )
        }
    }

}

/** Playback time as m:ss. */
internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
