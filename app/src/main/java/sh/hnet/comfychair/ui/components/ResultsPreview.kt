package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.cache.ResultThumbnails
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.viewmodel.MediaViewerItem

private val StripHeight = 64.dp
private val ThumbSize = 44.dp
private val ThumbGap = 6.dp
private val ThumbCorner = 10.dp
private val ThumbShape = RoundedCornerShape(ThumbCorner)
private val MinHeightForStrip = 216.dp
private const val THUMB_STAGGER_MS = 30
private const val THUMB_STAGGER_MAX_ITEMS = 8

/**
 * Preview area content for a finished job with several results: a pager over all results
 * ("hero"), a counter and a strip of thumbnails under it. The caller keeps the preview area
 * container and shows live previews, the empty state and single results itself.
 *
 * Only the current index is hoisted; the pager follows [currentIndex] and reports settled pages
 * through [onCurrentIndexChange].
 *
 * @param stripEnabled False while the screen's next job is queued: the strip is dimmed and inert
 * @param hero Renders one page (image Crop, or the inline player for the current video)
 * @param heroOverlay Extra content over the hero, e.g. a "use as source" button
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ResultsPreview(
    results: List<GenerationOutput>,
    currentIndex: Int,
    onCurrentIndexChange: (Int) -> Unit,
    onOpenViewer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    stripEnabled: Boolean = true,
    heroOverlay: @Composable BoxScope.(current: GenerationOutput) -> Unit = {},
    hero: @Composable (output: GenerationOutput, isCurrent: Boolean) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    ) { results.size }
    val scope = rememberCoroutineScope()

    // The ViewModel's index wins when it changes from outside (new job, return from viewer)
    LaunchedEffect(currentIndex) {
        if (currentIndex in results.indices && currentIndex != pagerState.settledPage) {
            pagerState.scrollToPage(currentIndex)
        }
    }
    val currentOnIndexChange by rememberUpdatedState(onCurrentIndexChange)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { currentOnIndexChange(it) }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val showStrip = results.size > 1 && maxHeight >= MinHeightForStrip

        // Announce the finished job once, politely
        val doneMessage = pluralStringResource(R.plurals.msg_results_done, results.size, results.size)
        Box(Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = doneMessage
        })

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Hero(results, pagerState, onOpenViewer, hero)
                CounterPill(
                    current = pagerState.currentPage,
                    count = results.size,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                )
                results.getOrNull(pagerState.currentPage)?.let { heroOverlay(it) }
            }

            // Enters when the results appear, leaves when the area gets too small
            val stripState = remember { MutableTransitionState(false) }
            stripState.targetState = showStrip
            AnimatedVisibility(
                visibleState = stripState,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Bottom) +
                        fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec(), shrinkTowards = Alignment.Bottom) +
                        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                ResultStrip(
                    results = results,
                    current = pagerState.currentPage,
                    enabled = stripEnabled,
                    onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
                )
            }
        }
    }
}

@Composable
private fun Hero(
    results: List<GenerationOutput>,
    pagerState: PagerState,
    onOpenViewer: (Int) -> Unit,
    hero: @Composable (GenerationOutput, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val openLabel = stringResource(R.string.content_description_open_in_viewer)
    val nextLabel = stringResource(R.string.content_description_next_result)
    val previousLabel = stringResource(R.string.content_description_previous_result)

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 0,
        key = { results[it].id },
        userScrollEnabled = results.size > 1,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val output = results[page]
        val description = resultDescription(output, page, results.size)
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = description
                    // TalkBack captures swipes, so offer explicit navigation
                    customActions = listOfNotNull(
                        if (page < results.size - 1) CustomAccessibilityAction(nextLabel) {
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                            true
                        } else null,
                        if (page > 0) CustomAccessibilityAction(previousLabel) {
                            scope.launch { pagerState.animateScrollToPage(page - 1) }
                            true
                        } else null
                    )
                }
                .clickable(onClickLabel = openLabel) { onOpenViewer(page) }
                // The viewer flies the result out of the preview and back (fitted, in the rounded preview)
                .mediaOrigin(mediaOriginKey(output.promptId, output.filename), fit = true, corner = 28.dp)
        ) {
            // Only the settled page counts as current, so a single player is swapped, not duplicated
            hero(output, page == pagerState.settledPage)
        }
    }
}

@Composable
private fun CounterPill(current: Int, count: Int, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = modifier
    ) {
        Box(Modifier.height(28.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.label_result_counter, current + 1, count),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * Thumbnails of all results along the preview's bottom edge. The current one has a ring and is kept in the middle while
 * the pager is swiped; with more results than fit, the row scrolls and thumbnails are cut off at both edges, which
 * shows that there are more.
 */
@Composable
private fun ResultStrip(
    results: List<GenerationOutput>,
    current: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    // Follows the pager's current page, which changes halfway through a swipe
    LaunchedEffect(current) { listState.centerItem(current) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        // No scrollbar, arrows or fades: the cut-off thumbnails show that the row scrolls
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = ThumbGap),
            horizontalArrangement = Arrangement.spacedBy(ThumbGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = enabled,
            modifier = Modifier.fillMaxSize().selectableGroup()
        ) {
            itemsIndexed(results, key = { _, output -> output.id }) { index, output ->
                ResultThumbnail(
                    output = output,
                    selected = index == current,
                    enabled = enabled,
                    description = resultDescription(output, index, results.size),
                    staggerIndex = index,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultThumbnail(
    output: GenerationOutput,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    staggerIndex: Int,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val sizePx = with(density) { ThumbSize.roundToPx() }
    val thumbnail by produceState(ResultThumbnails.get(output), output.id) {
        if (value == null) value = ResultThumbnails.load(output, sizePx)
    }
    val failed = thumbnail == null && ResultThumbnails.isFailed(output)

    // Thumbnails fade in one after another when the strip appears
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(durationMillis = 150, delayMillis = minOf(staggerIndex, THUMB_STAGGER_MAX_ITEMS) * THUMB_STAGGER_MS))
    }

    // The current thumbnail keeps its shape and gets a ring
    val ringWidth by animateDpAsState(if (selected) 2.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ring")
    val shape = ThumbShape
    val ringColor = MaterialTheme.colorScheme.onSurface
    var focused by remember { mutableStateOf(false) }
    val focusColor = MaterialTheme.colorScheme.onSurface

    Box(
        Modifier
            .size(ThumbSize)
            .alpha(alpha.value)
            .drawBehind {
                // Current ring, 2 dp outside the thumbnail
                val width = ringWidth.toPx()
                if (width > 0f) {
                    val inset = 2.dp.toPx() + width / 2f
                    drawRoundRect(
                        color = ringColor,
                        topLeft = Offset(-inset, -inset),
                        size = Size(size.width + 2 * inset, size.height + 2 * inset),
                        cornerRadius = CornerRadius(ThumbCorner.toPx() + inset),
                        style = Stroke(width)
                    )
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(2.dp, focusColor, shape) else Modifier)
            .clip(shape)
            .then(if (failed) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        val image = thumbnail
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            else -> ThumbnailPlaceholder(isVideo = output.isVideo, failed = failed)
        }
        if (output.isVideo && image != null) {
            PlayBadge(Modifier.align(Alignment.BottomStart).padding(3.dp), size = 16.dp, iconSize = 12.dp)
        }
    }
}

@Composable
private fun ThumbnailPlaceholder(isVideo: Boolean, failed: Boolean) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                failed -> Icons.Default.BrokenImage
                isVideo -> Icons.Default.Movie
                else -> Icons.Default.Image
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Play badge marking video thumbnails, placed in a corner by the caller. Theme colours, like every overlay on media.
 */
@Composable
fun PlayBadge(modifier: Modifier = Modifier, size: Dp = 20.dp, iconSize: Dp = 14.dp) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Full-size image of a result for the hero: [preloaded] if given (the image the screen already
 * shows), otherwise loaded through MediaCache, with the thumbnail as placeholder meanwhile.
 * Videos show their first frame; the caller puts the player over the current one.
 */
@Composable
fun ResultHeroImage(output: GenerationOutput, preloaded: Bitmap? = null) {
    val image by produceState<ImageBitmap?>(preloaded?.asImageBitmap(), output.id, preloaded) {
        if (value == null && !output.isVideo) {
            val key = MediaCacheKey(output.promptId, output.filename)
            value = (MediaCache.getBitmap(key) ?: MediaCache.fetchImage(key, output.subfolder, output.type))?.asImageBitmap()
        }
    }
    val shown = image ?: ResultThumbnails.get(output)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (shown != null) {
            Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            ThumbnailPlaceholder(isVideo = output.isVideo, failed = false)
        }
    }
}

/**
 * Hero page of a video result: the inline looping player for the current page, the first frame
 * otherwise. The video is read from RAM in memory-first mode (MediaCache, InMemoryVideo).
 *
 * @param preloadedUri URI of the video the screen already plays, to avoid loading it again
 */
@Composable
fun ResultHeroVideo(output: GenerationOutput, isCurrent: Boolean, isActive: Boolean, preloadedUri: Uri? = null) {
    val context = LocalContext.current
    val key = MediaCacheKey(output.promptId, output.filename)
    val uri by produceState(preloadedUri, output.id, preloadedUri, isCurrent) {
        if (value == null && isCurrent) value = MediaCache.fetchVideoUri(key, output.subfolder, output.type, context)
    }
    Box(Modifier.fillMaxSize()) {
        // First frame underneath, so loading and non-current pages aren't empty
        ResultHeroImage(output)
        val videoUri = uri
        if (isCurrent && videoUri != null) {
            VideoPlayer(
                videoUri = videoUri,
                modifier = Modifier.fillMaxSize(),
                isActive = isActive,
                cacheKey = key,
                showPlaybackStatus = true
            )
        }
    }
}

/**
 * Returns a function that opens the full-screen viewer in gallery mode over all [results], at the
 * given index. When the viewer is closed, [onReturn] gets the index of the result it showed last,
 * matched by filename (the viewer's own list shrinks if items are deleted there).
 */
@Composable
fun rememberResultsViewer(
    results: List<GenerationOutput>,
    onReturn: (Int) -> Unit
): (Int) -> Unit {
    val mediaViewer = LocalMediaViewer.current
    val currentResults by rememberUpdatedState(results)
    val currentOnReturn by rememberUpdatedState(onReturn)
    return { index ->
        val items = currentResults.mapIndexed { i, output ->
            MediaViewerItem(output.promptId, output.filename, output.subfolder, output.type, output.isVideo, i)
        }
        mediaViewer.openGallery(items, index) { filename ->
            val shown = currentResults.indexOfFirst { it.filename == filename }
            if (shown >= 0) currentOnReturn(shown)
        }
    }
}

/** Screens a generated image can be sent to as their source image. */
enum class SourceTarget { IMAGE_TO_IMAGE, IMAGE_TO_VIDEO }

/**
 * Full-size image of a result, from MediaCache (RAM only in memory-first mode).
 */
suspend fun loadResultBitmap(output: GenerationOutput): Bitmap? {
    if (output.isVideo) return null
    val key = MediaCacheKey(output.promptId, output.filename)
    return MediaCache.getBitmap(key) ?: MediaCache.fetchImage(key, output.subfolder, output.type)
}

@Composable
private fun resultDescription(output: GenerationOutput, index: Int, count: Int): String {
    if (!output.isVideo) return stringResource(R.string.content_description_result_image, index + 1, count)
    val seconds = ResultThumbnails.durationMs(output)?.let { ((it + 500) / 1000).toInt() }
    return if (seconds != null) {
        val duration = pluralStringResource(R.plurals.label_duration_seconds, seconds, seconds)
        stringResource(R.string.content_description_result_video_duration, index + 1, count, duration)
    } else {
        stringResource(R.string.content_description_result_video, index + 1, count)
    }
}

/** Visible position of a strip item, in viewport coordinates. */
internal data class StripItemBounds(val offset: Int, val size: Int)

/** Scroll that puts the middle of an item in the middle of the viewport (the list clamps it at both ends). */
internal fun centeringDelta(item: StripItemBounds, viewportStart: Int, viewportEnd: Int): Int =
    item.offset + item.size / 2 - (viewportStart + viewportEnd) / 2

private suspend fun LazyListState.centerItem(index: Int) {
    if (index !in 0 until layoutInfo.totalItemsCount) return
    fun bounds() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { StripItemBounds(it.offset, it.size) }
    // A far item isn't laid out yet: jump to it first, then center
    val item = bounds() ?: run {
        scrollToItem(index)
        bounds()
    } ?: return
    val info = layoutInfo
    animateScrollBy(centeringDelta(item, info.viewportStartOffset, info.viewportEndOffset).toFloat())
}
