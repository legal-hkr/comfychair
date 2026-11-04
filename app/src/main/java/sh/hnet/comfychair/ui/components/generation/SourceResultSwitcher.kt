package sh.hnet.comfychair.ui.components.generation

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.cache.ResultThumbnails
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationOutput

private val PipSize = 64.dp
private val PipShape = RoundedCornerShape(18.dp)
private const val SOURCE_KEY = "source"
private const val RESULT_KEY = "result"

/**
 * Preview area of a screen with a source (Image to Image, Image to Video): one side fills the area, the other is a
 * picture-in-picture thumbnail at the top start; tapping it swaps them with a container transform. Replaces the
 * Source / Preview toggle. The thumbnail is hidden when the other side is empty.
 *
 * @param showSource Which side fills the area (the screen keeps its automatic switching: job start → result,
 *   new source → source)
 * @param sourceThumb The source as shown in the thumbnail; null when there is none
 * @param resultThumb The result (or live frames while generating) as shown in the thumbnail; null when there is none.
 *   It also stands in for the full-size result while the sides swap, so it should be sharp enough for that
 * @param source Full-size source; `inTransition` is true while the sides swap
 * @param result Full-size result; `inTransition` is true while the sides swap (e.g. to show a still instead of a
 *   video surface, which can't follow the animation)
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SourceResultSwitcher(
    showSource: Boolean,
    onShowSourceChange: (Boolean) -> Unit,
    sourceThumb: ImageBitmap?,
    resultThumb: ImageBitmap?,
    modifier: Modifier = Modifier,
    source: @Composable BoxScope.(inTransition: Boolean) -> Unit,
    result: @Composable BoxScope.(inTransition: Boolean) -> Unit
) {
    val showResultLabel = stringResource(R.string.content_description_show_result)
    val showSourceLabel = stringResource(R.string.content_description_show_source)

    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val boundsSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()

    SharedTransitionLayout(modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showSource,
            transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
            label = "source result swap"
        ) { sourceShown ->
            // Settled only once this side has fully entered. isRunning alone is still false in the first frame of the
            // swap, when this side is measured at the thumbnail's size – too small for e.g. a toolbar with a FAB
            val inTransition = transition.isRunning ||
                transition.currentState != EnterExitState.Visible ||
                transition.targetState != EnterExitState.Visible
            val otherThumb = if (sourceShown) resultThumb else sourceThumb
            val swapLabel = if (sourceShown) showResultLabel else showSourceLabel

            Box(
                Modifier
                    .fillMaxSize()
                    // TalkBack: swap without finding the small thumbnail
                    .semantics {
                        if (otherThumb != null) {
                            customActions = listOf(CustomAccessibilityAction(swapLabel) { onShowSourceChange(!sourceShown); true })
                        }
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .sharedBounds(
                            rememberSharedContentState(if (sourceShown) SOURCE_KEY else RESULT_KEY),
                            animatedVisibilityScope = this@AnimatedContent,
                            boundsTransform = { _, _ -> boundsSpec },
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            clipInOverlayDuringTransition = OverlayClip(PipShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        sourceShown -> source(inTransition)
                        // The result's own content (player, pager) is created anew and would stay empty while it
                        // grows, so the still from the thumbnail stands in until the swap is done
                        inTransition && resultThumb != null -> Image(
                            bitmap = resultThumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        else -> result(inTransition)
                    }
                }

                if (otherThumb != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .sharedBounds(
                                rememberSharedContentState(if (sourceShown) RESULT_KEY else SOURCE_KEY),
                                animatedVisibilityScope = this@AnimatedContent,
                                boundsTransform = { _, _ -> boundsSpec },
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                clipInOverlayDuringTransition = OverlayClip(PipShape),
                                // The side shrinking into the thumbnail is drawn over the one growing to full size,
                                // otherwise the growing side hides it for the whole swap
                                zIndexInOverlay = 1f
                            )
                            .size(PipSize)
                            .clip(PipShape)
                            .clickable(role = Role.Button, onClickLabel = swapLabel) { onShowSourceChange(!sourceShown) },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Image(
                            bitmap = otherThumb,
                            contentDescription = swapLabel,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = stringResource(if (sourceShown) R.string.label_pip_result else R.string.label_pip_source),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * First frame of a video as a small still, read in memory (RAM or a stored file), for thumbnails where no cached
 * result thumbnail exists, e.g. a video restored after a restart.
 */
@Composable
fun rememberVideoFrame(videoUri: Uri?, sizeDp: Dp = PipSize): ImageBitmap? {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }
    val frame by produceState<ImageBitmap?>(null, videoUri, sizePx) {
        value = videoUri?.let { uri ->
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    InMemoryVideo.setDataSource(retriever, context, uri)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2)
                        ?.asImageBitmap()
                } catch (e: Exception) {
                    DebugLogger.w("VideoFrame", "First frame could not be read: ${e.javaClass.simpleName}")
                    null
                } finally {
                    retriever.release()
                }
            }
        }
    }
    return frame
}

/** Cached thumbnail of a result (RAM only), loaded if needed; null for no result. */
@Composable
fun rememberOutputThumbnail(output: GenerationOutput?, sizeDp: Dp = PipSize): ImageBitmap? {
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }
    val thumbnail by produceState(output?.let { ResultThumbnails.get(it) }, output?.id) {
        if (value == null && output != null) value = ResultThumbnails.load(output, sizePx * 2)
    }
    return thumbnail
}
