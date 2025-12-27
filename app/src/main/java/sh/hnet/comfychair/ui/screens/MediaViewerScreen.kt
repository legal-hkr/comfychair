package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.ui.components.ImageViewer
import sh.hnet.comfychair.ui.components.MetadataBottomSheet
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.ui.components.VideoScaleMode
import sh.hnet.comfychair.viewmodel.MediaViewerEvent
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewerScreen(
    viewModel: MediaViewerViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val currentMetadata by viewModel.currentMetadata.collectAsState()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsState()
    val scope = rememberCoroutineScope()

    // Layout constants - 8dp because navigationBarsPadding() adds ~24dp, totaling ~32dp like Workflow Editor
    val toolbarBottomPadding = 8.dp
    val fabBottomPadding = toolbarBottomPadding + 4.dp

    // Metadata bottom sheet state
    var showMetadataSheet by remember { mutableStateOf(false) }

    // Load metadata when sheet is shown
    LaunchedEffect(showMetadataSheet) {
        if (showMetadataSheet) {
            viewModel.loadMetadata()
        }
    }

    // Handle system back button - ensures onClose() is called with proper result
    BackHandler {
        onClose()
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MediaViewerEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is MediaViewerEvent.ItemDeleted -> {
                    // Items list changed, pager will recompose with new key
                }
                is MediaViewerEvent.Close -> {
                    onClose()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Media content
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
                                visible = uiState.isUiVisible && uiState.currentIndex > 0,
                                enter = fadeIn(animationSpec = tween(250)),
                                exit = fadeOut(animationSpec = tween(250)),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 16.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = stringResource(R.string.media_viewer_previous),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Right navigation arrow
                            AnimatedVisibility(
                                visible = uiState.isUiVisible && uiState.currentIndex < uiState.totalCount - 1,
                                enter = fadeIn(animationSpec = tween(250)),
                                exit = fadeOut(animationSpec = tween(250)),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.media_viewer_next),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
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

        // Counter chip (gallery mode only, when multiple items)
        if (uiState.mode == ViewerMode.GALLERY && uiState.totalCount > 1) {
            AnimatedVisibility(
                visible = uiState.isUiVisible,
                enter = fadeIn(animationSpec = tween(250)),
                exit = fadeOut(animationSpec = tween(250)),
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

        // Floating Toolbar
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = toolbarBottomPadding)
        ) {
            MediaViewerFloatingToolbar(
                isGalleryMode = uiState.mode == ViewerMode.GALLERY,
                onDelete = { viewModel.deleteCurrentItem() },
                onSave = { viewModel.saveCurrentItem() },
                onShare = { viewModel.shareCurrentItem() },
                onInfo = { showMetadataSheet = true }
            )
        }

        // Back FAB
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = fabBottomPadding)
        ) {
            FloatingActionButton(
                onClick = onClose,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.content_description_back)
                )
            }
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaViewerFloatingToolbar(
    isGalleryMode: Boolean,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toolbarColors = FloatingToolbarColors(
        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        fabContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        fabContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = toolbarColors,
        content = {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Delete button (gallery mode only)
                if (isGalleryMode) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.media_viewer_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Save button
                IconButton(onClick = onSave) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = stringResource(R.string.media_viewer_save)
                    )
                }

                // Share button
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.media_viewer_share)
                    )
                }

                // Divider before Info
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Info button
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.show_metadata)
                    )
                }
            }
        }
    )
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            isVideo && videoUri != null -> {
                // Current video page: show VideoPlayer
                VideoPlayer(
                    videoUri = videoUri,
                    modifier = Modifier.fillMaxSize(),
                    showController = false,
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
                CircularProgressIndicator(color = Color.White)
            }
            // Fallback: only images without bitmap and not loading show black
        }
    }
}
