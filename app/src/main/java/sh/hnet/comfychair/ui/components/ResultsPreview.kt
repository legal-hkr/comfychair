package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.cache.ResultThumbnails
import sh.hnet.comfychair.ui.components.shared.CircleArrowButton
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.viewmodel.MediaViewerItem

private val StripHeight = 80.dp
private val ThumbSize = 56.dp
private val ThumbGap = 8.dp
private val StripPadding = 12.dp
private val ThumbCorner = 12.dp
private val ThumbShape = RoundedCornerShape(ThumbCorner)
private val MinHeightForStrip = 232.dp
private val ChevronFade = 64.dp
// Half of the largest FAB size, so the container stays a circle while it shrinks when expanded
private val FabCornerRadius = 48.dp
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
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
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
                    settled = pagerState.settledPage,
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
        ) {
            // Only the settled page counts as current, so a single player is swapped, not duplicated
            hero(output, page == pagerState.settledPage)
        }
    }
}

@Composable
private fun CounterPill(current: Int, count: Int, modifier: Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, shape = CircleShape, modifier = modifier) {
        Box(Modifier.height(28.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.label_result_counter, current + 1, count),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultStrip(
    results: List<GenerationOutput>,
    current: Int,
    settled: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Keep the current thumbnail and one neighbour on each side visible, without centering
    LaunchedEffect(settled) { listState.ensureVisibleWithNeighbours(settled) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        // No scrollbar: LazyRow draws none, and none is added on purpose
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = StripPadding),
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

        val canScrollBack by remember { derivedStateOf { listState.canScrollBackward } }
        val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
        // A chevron pages by the viewport minus one thumbnail of context
        val pagePx = {
            val viewport = listState.layoutInfo.viewportSize.width
            (viewport - with(density) { ChevronFade.toPx() }).coerceAtLeast(0f)
        }
        ScrollChevron(visible = canScrollBack && enabled, forward = false) {
            scope.launch { listState.animateScrollBy(-pagePx()) }
        }
        ScrollChevron(visible = canScrollForward && enabled, forward = true) {
            scope.launch { listState.animateScrollBy(pagePx()) }
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

    val ringWidth by animateDpAsState(if (selected) 3.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ring")
    val ringColor = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    val focusColor = MaterialTheme.colorScheme.onSurface

    Box(
        Modifier
            .size(ThumbSize)
            .alpha(alpha.value)
            .drawBehind {
                // Current ring: 3 dp, 2 dp outside the thumbnail
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
            .then(if (focused) Modifier.border(2.dp, focusColor, ThumbShape) else Modifier)
            .clip(ThumbShape)
            .then(if (failed) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, ThumbShape) else Modifier)
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
            PlayBadge(Modifier.align(Alignment.BottomStart).padding(4.dp))
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
 * Play badge marking video thumbnails: white play icon on a translucent black circle, placed in
 * a corner by the caller.
 */
@Composable
fun PlayBadge(modifier: Modifier = Modifier, size: Dp = 20.dp, iconSize: Dp = 14.dp) {
    Box(
        modifier.size(size).background(Color.Black.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.ScrollChevron(visible: Boolean, forward: Boolean, onClick: () -> Unit) {
    val background = MaterialTheme.colorScheme.surfaceContainer
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart),
        // The fade only fades in, at its full size, so no gap opens between it and the strip edge
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
    ) {
        // Fade over the thumbnails under the button
        Box(
            Modifier
                .width(ChevronFade)
                .height(StripHeight)
                .background(
                    if (forward) Brush.horizontalGradient(0f to Color.Transparent, 0.55f to background)
                    else Brush.horizontalGradient(0.45f to background, 1f to Color.Transparent)
                ),
            contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            CircleArrowButton(
                forward = forward,
                onClick = onClick,
                contentDescription = stringResource(
                    if (forward) R.string.content_description_scroll_results_forward
                    else R.string.content_description_scroll_results_back
                ),
                modifier = Modifier
                    // Only the button scales in and out
                    .animateEnterExit(enter = scaleIn(initialScale = 0.8f), exit = scaleOut(targetScale = 0.8f))
                    .padding(horizontal = 4.dp)
            )
        }
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
                cacheKey = key
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
    hostname: String,
    port: Int,
    results: List<GenerationOutput>,
    onReturn: (Int) -> Unit
): (Int) -> Unit {
    val context = LocalContext.current
    val currentResults by rememberUpdatedState(results)
    val currentOnReturn by rememberUpdatedState(onReturn)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val filename = result.data?.getStringExtra(MediaViewerActivity.RESULT_CURRENT_FILENAME) ?: return@rememberLauncherForActivityResult
        val index = currentResults.indexOfFirst { it.filename == filename }
        if (index >= 0) currentOnReturn(index)
    }
    return { index ->
        val items = currentResults.mapIndexed { i, output ->
            MediaViewerItem(output.promptId, output.filename, output.subfolder, output.type, output.isVideo, i)
        }
        launcher.launch(MediaViewerActivity.createGalleryIntent(context, hostname, port, items, index))
    }
}

/** Screens a generated image can be sent to as their source image. */
enum class SourceTarget { IMAGE_TO_IMAGE, IMAGE_TO_VIDEO }

/**
 * "Use as source" over a result image: an Expressive FAB menu in the bottom-end corner that sends
 * the image to Image to Image or Image to Video, which then shows it as its source. The icon is
 * the one of the screens' own "add source image" button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.UseAsSourceButton(onUse: (SourceTarget) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    val description = stringResource(R.string.button_use_as_source)

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = Modifier.align(Alignment.BottomEnd),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                // Round in both states, unlike the square gallery FAB in the navigation bar
                containerCornerRadius = { FabCornerRadius },
                // A toggle: TalkBack announces its expanded state itself
                modifier = Modifier.semantics { contentDescription = description }
            ) {
                val icon by remember {
                    derivedStateOf { if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.AddPhotoAlternate }
                }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress })
                )
            }
        }
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onUse(SourceTarget.IMAGE_TO_IMAGE)
            },
            icon = { Icon(painterResource(R.drawable.image_to_image_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.nav_image_to_image)) }
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onUse(SourceTarget.IMAGE_TO_VIDEO)
            },
            icon = { Icon(painterResource(R.drawable.image_to_video_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.nav_image_to_video)) }
        )
    }
}

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

/** What the strip has to do to show an item and its neighbours. */
internal sealed class StripScrollAction {
    data object None : StripScrollAction()
    data class ScrollToItem(val index: Int) : StripScrollAction()
    data class ScrollBy(val delta: Int) : StripScrollAction()
}

/**
 * Decides the minimal scroll that brings item [index] and one neighbour on each side fully into
 * the viewport, without centering. [boundsOf] returns the position of a laid-out item or null if
 * it isn't visible.
 */
internal fun stripScrollAction(
    index: Int,
    itemCount: Int,
    viewportStart: Int,
    viewportEnd: Int,
    boundsOf: (Int) -> StripItemBounds?
): StripScrollAction {
    if (itemCount == 0) return StripScrollAction.None
    val first = (index - 1).coerceAtLeast(0)
    val last = (index + 1).coerceAtMost(itemCount - 1)
    val firstBounds = boundsOf(first)
    val lastBounds = boundsOf(last)
    val firstVisible = firstBounds != null && firstBounds.offset >= viewportStart
    val lastVisible = lastBounds != null && lastBounds.offset + lastBounds.size <= viewportEnd
    return when {
        firstVisible && lastVisible -> StripScrollAction.None
        !firstVisible -> StripScrollAction.ScrollToItem(first)
        lastBounds != null -> StripScrollAction.ScrollBy(lastBounds.offset + lastBounds.size - viewportEnd)
        else -> StripScrollAction.ScrollToItem(first)
    }
}

private suspend fun LazyListState.ensureVisibleWithNeighbours(index: Int) {
    val info = layoutInfo
    val action = stripScrollAction(index, info.totalItemsCount, info.viewportStartOffset, info.viewportEndOffset) { i ->
        info.visibleItemsInfo.firstOrNull { it.index == i }?.let { StripItemBounds(it.offset, it.size) }
    }
    when (action) {
        StripScrollAction.None -> Unit
        is StripScrollAction.ScrollToItem -> animateScrollToItem(action.index)
        is StripScrollAction.ScrollBy -> animateScrollBy(action.delta.toFloat())
    }
}
