package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
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
 * @param showPlaybackStatus Show the playback group (pause/position and sound) at the top end
 * @param surface Where it's shown, which decides the sound and the looping (the preview: muted, always looping)
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
    surface: SharedVideoPlayer.Surface = SharedVideoPlayer.Surface.PREVIEW,
    placeholder: Bitmap? = null,
    originKey: String? = null,
    originCorner: Dp = 0.dp
) {
    val playbackHeld = LocalMediaViewerLanding.current
    val coveredByViewer = LocalCoveredByMediaViewer.current
    val context = LocalContext.current

    // The video shown: its cache key, else its address (a source or a result played from RAM has no key). The still and
    // the dimensions belong to it, so another video doesn't take the previous one's proportions
    val shownVideo: Any? = cacheKey ?: videoUri

    // Initialize thumbnail synchronously from cache to avoid black flash during transitions
    // This ensures the thumbnail is available on the very first composition
    // A first frame the caller already has stands in when the cache has none
    val initialThumbnail = remember(shownVideo) {
        cacheKey?.let { MediaCache.getBitmap(it) } ?: placeholder
    }
    var thumbnail by remember(shownVideo) { mutableStateOf(initialThumbnail) }

    // Video dimensions - also check cache synchronously for instant display. The placeholder's proportions are the
    // video's, which is all the layout needs, so the surface can be shown without waiting for the metadata
    val cachedDimensions = remember(shownVideo) {
        cacheKey?.let { MediaCache.getVideoDimensions(it) }
    }
    var videoWidth by remember(shownVideo) { mutableStateOf(cachedDimensions?.width ?: placeholder?.width ?: 0) }
    var videoHeight by remember(shownVideo) { mutableStateOf(cachedDimensions?.height ?: placeholder?.height ?: 0) }
    // The placeholder may arrive after the first composition
    LaunchedEffect(placeholder, shownVideo) {
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
                    SharedVideoPlayer.prepareVideo(context, videoUri, surface)
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
                    }
                    // Over the whole preview card (the video may be cropped to it): playback at the top end
                    if (showPlaybackStatus) {
                        Box(Modifier.fillMaxSize()) { PreviewPlaybackControls(videoUri, cachedDimensions?.durationMs) }
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
 * The preview's playback controls (spec §14.2, without its position line, which the preview had and dropped before): at
 * the top end one connected group – play/pause with the time, and the sound toggle (muted by default; hidden for a
 * video without sound, leaving the round chip). Theme colours only. The position is polled only while the video plays.
 *
 * The state is the shared player's, so a screen handing it to another (switching the source) briefly sees it loading:
 * meanwhile the controls keep what they know of their own video ([videoUri]: its length, [knownDurationMs] from the
 * cache before it played, and whether it has sound), and show loading only when it takes longer than
 * [LOADING_SHOWN_AFTER_MS], so a quick handover doesn't flicker.
 */
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.PreviewPlaybackControls(videoUri: Uri?, knownDurationMs: Long?) {
    val state by SharedVideoPlayer.state.collectAsState()
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isPlaying, state.durationMs) {
        do {
            positionMs = SharedVideoPlayer.positionMs()
            if (state.isPlaying) delay(100)
        } while (state.isPlaying)
    }
    // What this video showed once loaded
    var ownDurationMs by remember(videoUri) { mutableStateOf(knownDurationMs) }
    var ownHasAudio by remember(videoUri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.isLoading, state.durationMs, state.hasAudio) {
        if (!state.isLoading) {
            state.durationMs?.let { ownDurationMs = it }
            state.hasAudio?.let { ownHasAudio = it }
        }
    }
    var loadingShown by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (state.isLoading) {
            delay(LOADING_SHOWN_AFTER_MS)
            loadingShown = true
        } else {
            loadingShown = false
        }
    }
    val loading = loadingShown
    val durationMs = if (state.isLoading) ownDurationMs else state.durationMs ?: ownDurationMs
    val elapsed = formatPlaybackTime(if (state.isLoading) 0L else positionMs)
    val total = durationMs?.let { formatPlaybackTime(it, roundUp = true) } ?: UNKNOWN_DURATION
    val hasAudio = if (state.isLoading) ownHasAudio else state.hasAudio ?: ownHasAudio
    // Hidden only once the video is known to have none
    val withSound = hasAudio != false

    val playLabel = stringResource(if (state.isPlaying) R.string.content_description_pause_video else R.string.content_description_play_video)
    val positionText = stringResource(R.string.content_description_video_position, elapsed, total)
    val loadingLabel = stringResource(R.string.video_loading)
    val restColors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
    ) {
        // Play/pause and the time: never shown as checked, only the icon changes
        ToggleButton(
            checked = false,
            onCheckedChange = { if (!state.isLoading) SharedVideoPlayer.togglePlayback() },
            enabled = !loading,
            shapes = if (withSound) ButtonGroupDefaults.connectedLeadingButtonShapes() else ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size),
            colors = restColors,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier
                .height(ButtonDefaults.ExtraSmallContainerHeight)
                .clearAndSetSemantics {
                    contentDescription = if (loading) loadingLabel else playLabel
                    stateDescription = positionText
                    role = Role.Button
                    if (!loading) onClick { if (!state.isLoading) SharedVideoPlayer.togglePlayback(); true }
                }
        ) {
            if (loading) {
                LoadingIndicator(Modifier.size(18.dp))
            } else {
                Icon(
                    imageVector = if (state.isPlaying && !state.isLoading) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.video_time_display, elapsed, total),
                // Tabular digits, so the segment doesn't change width while the time runs
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
            )
        }
        if (withSound) {
            val soundLabel = stringResource(R.string.video_sound)
            val soundState = stringResource(if (state.muted) R.string.video_sound_off else R.string.video_sound_on)
            ToggleButton(
                checked = !state.muted,
                onCheckedChange = { soundOn -> if (!state.isLoading) SharedVideoPlayer.setMuted(!soundOn) },
                // Until the tracks are known
                enabled = !loading && hasAudio == true,
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                colors = restColors,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .height(ButtonDefaults.ExtraSmallContainerHeight)
                    .width(40.dp)
                    .semantics {
                        contentDescription = soundLabel
                        stateDescription = soundState
                    }
            ) {
                Icon(
                    imageVector = if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// A shorter load (a screen handing the shared player to another) keeps the controls as they were
private const val LOADING_SHOWN_AFTER_MS = 300L

/**
 * Playback time as m:ss, or h:mm:ss from an hour. The elapsed time is floored; a total is rounded up ([roundUp]), so a
 * 1.2 s clip shows 0:02 and the elapsed time never passes it.
 */
internal fun formatPlaybackTime(ms: Long, roundUp: Boolean = false): String {
    val totalSeconds = if (roundUp) (ms + 999) / 1000 else ms / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** A total time not known yet. */
internal const val UNKNOWN_DURATION = "–:––"
