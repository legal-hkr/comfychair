package sh.hnet.comfychair.ui.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey

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
    cacheKey: MediaCacheKey? = null
) {
    val context = LocalContext.current

    // Initialize thumbnail synchronously from cache to avoid black flash during transitions
    // This ensures the thumbnail is available on the very first composition
    val initialThumbnail = remember(cacheKey) {
        cacheKey?.let { MediaCache.getBitmap(it) }
    }
    var thumbnail by remember(cacheKey) { mutableStateOf(initialThumbnail) }

    // Video dimensions - also check cache synchronously for instant display
    val cachedDimensions = remember(cacheKey) {
        cacheKey?.let { MediaCache.getVideoDimensions(it) }
    }
    var videoWidth by remember(cacheKey) { mutableStateOf(cachedDimensions?.width ?: 0) }
    var videoHeight by remember(cacheKey) { mutableStateOf(cachedDimensions?.height ?: 0) }

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
                    }
                }
            }
        }
        // Note: We intentionally don't reset thumbnail/dimensions when videoUri becomes null.
        // The cached thumbnail should persist to avoid black flashes during transitions.
    }

    Box(
        modifier = modifier
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

            if (isActive && videoUri != null) {
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
                LaunchedEffect(presentationState.coverSurface) {
                    if (!presentationState.coverSurface) {
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
                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = thumbnailContentScale
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                )
                            }
                        }
                    }
                } else {
                    // Show placeholder until dimensions are extracted
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = thumbnailContentScale
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    }
                }
            } else if (thumbnail != null) {
                // When inactive, show only the thumbnail
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
