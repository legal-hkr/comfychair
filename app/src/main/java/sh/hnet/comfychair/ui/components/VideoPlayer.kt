package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Composable wrapper for Media3 ExoPlayer
 * Provides video playback with looping and center-crop scaling
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
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    videoUri: Uri?,
    modifier: Modifier = Modifier,
    showController: Boolean = false,
    isActive: Boolean = true
) {
    val context = LocalContext.current

    // Thumbnail for displaying when player is destroyed during transitions
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    // Controls whether to show thumbnail overlay on top of video
    var showThumbnailOverlay by remember { mutableStateOf(true) }

    // Extract first frame as thumbnail when video URI changes
    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            showThumbnailOverlay = true
            thumbnail = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, videoUri)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    frame
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            thumbnail = null
        }
    }

    // Reset thumbnail overlay when becoming inactive
    LaunchedEffect(isActive) {
        if (!isActive) {
            showThumbnailOverlay = true
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        if (isActive && videoUri != null) {
            // Only create ExoPlayer when active - this ensures the player surface
            // is completely removed from the view hierarchy during transitions
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
                delay(150) // Give ExoPlayer time to render first frame
                showThumbnailOverlay = false
            }

            // Video player (renders on separate surface layer)
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = showController
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // Center crop
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
                    contentScale = ContentScale.Crop
                )
            }
        } else if (thumbnail != null) {
            // When inactive, show only the thumbnail (no video player in composition)
            // This ensures nothing bleeds through during navigation transitions
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
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

    DisposableEffect(videoUri) {
        if (videoUri != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
            exoPlayer.play()
        }

        onDispose { }
    }

    DisposableEffect(Unit) {
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
