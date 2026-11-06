@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.ui.components.ErrorSnackbars
import sh.hnet.comfychair.ui.components.ImageViewer
import sh.hnet.comfychair.ui.components.MetadataBottomSheet
import sh.hnet.comfychair.ui.components.SharedVideoPlayer
import sh.hnet.comfychair.ui.components.SourceMedia
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.VideoScaleMode
import sh.hnet.comfychair.ui.components.ViewerCloseFrom
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.ui.components.rememberViewerDismissState
import sh.hnet.comfychair.ui.components.shared.CircleArrowButton
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.viewerSwipeToDismiss
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.viewmodel.MediaViewerEvent
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onClose: () -> Unit,
    onUseAsSource: (SourceTarget, SourceMedia) -> Unit,
    onRequestClose: (ViewerCloseFrom) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val currentMetadata by viewModel.currentMetadata.collectAsState()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsState()
    val scope = rememberCoroutineScope()

    // Layout constants - 8dp because navigationBarsPadding() adds ~24dp, totaling ~32dp like Workflow Editor
    val toolbarBottomPadding = 8.dp

    // Swipe down (and predictive back) to close: the media shrinks, rounds and the backdrop fades
    val dismiss = rememberViewerDismissState()
    val overlaysShown = uiState.isUiVisible && !dismiss.active
    val currentIsVideo = uiState.currentItem?.isVideo == true || uiState.currentVideoUri != null
    val density = LocalDensity.current
    // Back into the origin when the host can fly it there, else a fade
    fun animateClose() {
        if (dismiss.closing) return
        if (onRequestClose(dismiss.closeFrom(with(density) { 28.dp.toPx() }))) {
            dismiss.markClosing()
        } else {
            scope.launch {
                dismiss.fadeOut()
                onClose()
            }
        }
    }

    // Metadata bottom sheet state
    var showMetadataSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // The app's errors while the viewer is open (the main screens' snackbars are under it)
    ErrorSnackbars(snackbarHostState)
    // Results of the job about to be deleted, while asking (only when there's more than the one shown)
    var confirmJobDeletion by remember { mutableStateOf<Int?>(null) }
    val resources = LocalResources.current

    // Load metadata when sheet is shown
    LaunchedEffect(showMetadataSheet) {
        if (showMetadataSheet) {
            viewModel.loadMetadata()
        }
    }

    // System back: the same shrink as the swipe, driven by the back progress; cancelling brings the media back
    PredictiveBackHandler(enabled = !dismiss.closing) { progress ->
        val wasPlaying = pauseVideo(context, currentIsVideo)
        try {
            dismiss.start()
            progress.collect { dismiss.backProgress(it.progress) }
            animateClose()
        } catch (e: CancellationException) {
            scope.launch {
                dismiss.settle()
                if (wasPlaying) resumeVideo(context)
            }
            throw e
        }
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MediaViewerEvent.ShowToast -> {
                    AppErrors.notify(context, event.messageResId)
                }
                is MediaViewerEvent.ItemDeleted -> {
                    // Items list changed, pager will recompose with new key
                }
                is MediaViewerEvent.DeletionPending -> {
                    // A new deletion replaces the previous Undo (already sent to the server)
                    snackbarHostState.currentSnackbarData?.dismiss()
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = resources.getQuantityString(R.plurals.msg_deleted_from_history, event.count, event.count),
                            actionLabel = resources.getString(R.string.button_undo),
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeletion(event.id)
                        else viewModel.commitDeletion(event.id)
                    }
                }
                is MediaViewerEvent.Close -> {
                    animateClose()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Fades out while swiping down, revealing the screen underneath (the window is translucent)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = dismiss.backdropAlpha))
            .onSizeChanged { dismiss.containerHeight = it.height }
            .viewerSwipeToDismiss(
                state = dismiss,
                onStart = { pauseVideo(context, currentIsVideo) },
                onClose = { animateClose() },
                onSettle = { wasPlaying -> if (wasPlaying) resumeVideo(context) }
            )
    ) {
        // Media content, moved, scaled and rounded by the swipe (the gesture above reads untransformed positions)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismiss.offset
                    val scale = 1f - 0.15f * dismiss.progress
                    scaleX = scale
                    scaleY = scale
                    alpha = dismiss.contentAlpha
                    shape = RoundedCornerShape(28.dp * dismiss.progress)
                    clip = dismiss.progress > 0f
                }
        ) {
        when (uiState.mode) {
            ViewerMode.GALLERY -> {
                if (uiState.items.isNotEmpty()) {
                    // Key the pager by items list identity to force recreation on deletion
                    val itemsKey = uiState.items.map { "${it.promptId}_${it.filename}" }.joinToString(",")

                    key(itemsKey) {
                        val pagerState = rememberPagerState(
                            initialPage = uiState.currentIndex.coerceIn(0, uiState.items.size - 1),
                            pageCount = { uiState.items.size }
                        )

                        // Sync pager state with ViewModel (user swipes)
                        LaunchedEffect(pagerState) {
                            snapshotFlow { pagerState.currentPage }
                                .collect { page ->
                                    if (page != uiState.currentIndex) {
                                        viewModel.setCurrentIndex(page)

                                        // Update cache priorities based on new position
                                        val allKeys = uiState.items.map {
                                            MediaCacheKey(it.promptId, it.filename)
                                        }
                                        MediaCache.updateNavigationPriorities(page, allKeys)
                                    }
                                }
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            key = { index -> "${uiState.items[index].promptId}_${uiState.items[index].filename}" }
                        ) { page ->
                            val item = uiState.items[page]
                            val isCurrentPage = page == pagerState.currentPage
                            val cacheKey = MediaCacheKey(item.promptId, item.filename)

                            // Lazy load bitmap from cache
                            val (bitmap, isBitmapLoading) = rememberLazyBitmap(
                                cacheKey = cacheKey,
                                isVideo = item.isVideo,
                                subfolder = item.subfolder,
                                type = item.type
                            )

                            // Video URI only needed for current page (VideoPlayer)
                            val videoUri = if (isCurrentPage && item.isVideo) uiState.currentVideoUri else null

                            // Show loading if content not available yet
                            val showLoading = if (isCurrentPage) {
                                uiState.isLoading && (if (item.isVideo) videoUri == null else bitmap == null)
                            } else {
                                isBitmapLoading
                            }

                            // Use clipToBounds to ensure content doesn't bleed during transitions
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                            ) {
                                MediaContent(
                                    isVideo = item.isVideo,
                                    bitmap = bitmap,
                                    videoUri = videoUri,
                                    isLoading = showLoading,
                                    onSingleTap = { viewModel.toggleUiVisibility() },
                                    cacheKey = cacheKey
                                )
                            }
                        }

                        // Navigation arrows need pagerState, so move them inside
                        // Left navigation arrow
                        if (uiState.totalCount > 1) {
                            AnimatedVisibility(
                                visible = overlaysShown && uiState.currentIndex > 0,
                                enter = fadeIn(animationSpec = tween(250)),
                                exit = fadeOut(animationSpec = tween(250)),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 16.dp)
                            ) {
                                CircleArrowButton(
                                    forward = false,
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    },
                                    contentDescription = stringResource(R.string.media_viewer_previous),
                                    size = 48.dp,
                                    iconSize = 32.dp
                                )
                            }

                            // Right navigation arrow
                            AnimatedVisibility(
                                visible = overlaysShown && uiState.currentIndex < uiState.totalCount - 1,
                                enter = fadeIn(animationSpec = tween(250)),
                                exit = fadeOut(animationSpec = tween(250)),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp)
                            ) {
                                CircleArrowButton(
                                    forward = true,
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    },
                                    contentDescription = stringResource(R.string.media_viewer_next),
                                    size = 48.dp,
                                    iconSize = 32.dp
                                )
                            }
                        }
                    }
                }
            }
            ViewerMode.SINGLE -> {
                val item = uiState.currentItem
                MediaContent(
                    isVideo = item?.isVideo ?: false,
                    bitmap = uiState.currentBitmap,
                    videoUri = uiState.currentVideoUri,
                    isLoading = uiState.isLoading,
                    onSingleTap = { viewModel.toggleUiVisibility() }
                )
            }
        }
        }

        // Counter chip (gallery mode only, when multiple items)
        if (uiState.mode == ViewerMode.GALLERY && uiState.totalCount > 1) {
            AnimatedVisibility(
                visible = overlaysShown,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.totalCount}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Back at the top start, centred with the counter chip
        AnimatedVisibility(
            visible = overlaysShown,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 16.dp)
        ) {
            FilledTonalIconButton(
                onClick = { animateClose() },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
            }
        }

        // Actions on the item, centred at the bottom
        AnimatedVisibility(
            visible = overlaysShown,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = toolbarBottomPadding)
        ) {
            MediaViewerFloatingToolbar(
                // Decided by the item alone: loading state changes with every page and would make the menu flicker
                isVideo = uiState.currentItem?.isVideo == true,
                // Gallery items only (a result on a generation screen isn't deleted from here)
                onDelete = if (uiState.mode == ViewerMode.GALLERY) {
                    {
                        // The server deletes whole jobs: with more results than this one, ask first
                        val job = uiState.currentItem?.promptId
                        val count = uiState.items.count { it.promptId == job }
                        if (count > 1) confirmJobDeletion = count else viewModel.deleteCurrentItem()
                    }
                } else null,
                onSave = { viewModel.saveCurrentItem() },
                onShare = { viewModel.shareCurrentItem() },
                onUseAsSource = { target ->
                    scope.launch {
                        // Read before closing: the viewer's state is cleared on close
                        val media = viewModel.currentSourceMedia()
                        if (media != null) {
                            onUseAsSource(target, media)
                            onClose()
                        } else {
                            val messageRes = if (uiState.currentItem?.isVideo == true) {
                                R.string.error_source_video_unreadable
                            } else {
                                R.string.error_failed_load_image
                            }
                            AppErrors.report(context, messageRes)
                        }
                    }
                },
                onInfo = { showMetadataSheet = true }
            )
        }

        // Undo of a deletion, above the toolbar
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = toolbarBottomPadding + 72.dp)
        ) { data ->
            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // Metadata bottom sheet
        if (showMetadataSheet) {
            MetadataBottomSheet(
                metadata = currentMetadata,
                isLoading = isLoadingMetadata,
                onDismiss = { showMetadataSheet = false }
            )
        }
    }
    confirmJobDeletion?.let { count ->
        ConfirmDialog(
            title = pluralStringResource(R.plurals.title_delete_job_results, count, count),
            text = stringResource(R.string.msg_delete_job_results),
            confirmLabel = stringResource(R.string.button_delete),
            icon = Icons.Rounded.Delete,
            destructive = true,
            onConfirm = {
                confirmJobDeletion = null
                viewModel.deleteCurrentItem()
            },
            onDismiss = { confirmJobDeletion = null }
        )
    }
}

/**
 * The viewer's actions, in the same order as everywhere: Save, Share, Use as source, Details, then Delete last.
 * Use as source opens a small menu upward with the screens that take the medium: for an image Image to Image and
 * Image to Video, for a video Video to Video and Image to Video (its last frame).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaViewerFloatingToolbar(
    isVideo: Boolean,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onUseAsSource: (SourceTarget) -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        TooltipIconButton(Icons.Rounded.Download, stringResource(R.string.media_viewer_save), onSave)
        TooltipIconButton(Icons.Rounded.Share, stringResource(R.string.media_viewer_share), onShare)
        Box {
            TooltipIconButton(
                Icons.AutoMirrored.Rounded.Input,
                stringResource(R.string.button_use_as_source),
                { sourceMenuOpen = true }
            )
            DropdownMenuPopup(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    val targets = if (isVideo) {
                        listOf(
                            Triple(SourceTarget.VIDEO_TO_VIDEO, R.string.button_edit_this_video, Icons.Rounded.MovieFilter),
                            Triple(SourceTarget.IMAGE_TO_VIDEO, R.string.button_animate_last_frame, Icons.Rounded.Animation)
                        )
                    } else {
                        listOf(
                            Triple(SourceTarget.IMAGE_TO_IMAGE, R.string.button_edit_this_image, Icons.Rounded.Edit),
                            Triple(SourceTarget.IMAGE_TO_VIDEO, R.string.button_animate_it, Icons.Rounded.Animation)
                        )
                    }
                    targets.forEachIndexed { index, (target, label, icon) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            shape = MenuDefaults.itemShape(index, targets.size).shape,
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                sourceMenuOpen = false
                                onUseAsSource(target)
                            }
                        )
                    }
                }
            }
        }
        TooltipIconButton(Icons.Outlined.Info, stringResource(R.string.button_show_metadata), onInfo)
        onDelete?.let {
            TooltipIconButton(
                Icons.Rounded.Delete,
                stringResource(R.string.media_viewer_delete),
                it,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun MediaContent(
    isVideo: Boolean,
    bitmap: android.graphics.Bitmap?,
    videoUri: android.net.Uri?,
    isLoading: Boolean,
    onSingleTap: () -> Unit,
    cacheKey: MediaCacheKey? = null
) {
    // Transparent: the viewer's backdrop is behind, and fades while swiping down
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isVideo && videoUri != null -> {
                // Current video page: show VideoPlayer
                VideoPlayer(
                    videoUri = videoUri,
                    modifier = Modifier.fillMaxSize(),
                    scaleMode = VideoScaleMode.FIT,
                    enableZoom = true,
                    onSingleTap = onSingleTap,
                    cacheKey = cacheKey
                )
            }
            bitmap != null -> {
                // Image OR video thumbnail (for non-current video pages)
                ImageViewer(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize(),
                    onSingleTap = onSingleTap
                )
            }
            isVideo || isLoading -> {
                // Video pages without content always show spinner (never black)
                // This handles the case where thumbnail is not yet in cache
                // Contained: stays visible on any media behind it
                ContainedLoadingIndicator()
            }
            // Fallback: only images without bitmap and not loading show black
        }
    }
}

/**
 * Pause the shared player while the media is dragged (a video only).
 *
 * @return Whether it was playing, to resume if the media settles back
 */
private fun pauseVideo(context: Context, isVideo: Boolean): Boolean {
    if (!isVideo) return false
    val player = SharedVideoPlayer.getPlayer(context)
    val playing = player.isPlaying
    if (playing) player.pause()
    return playing
}

private fun resumeVideo(context: Context) {
    SharedVideoPlayer.getPlayer(context).play()
}
