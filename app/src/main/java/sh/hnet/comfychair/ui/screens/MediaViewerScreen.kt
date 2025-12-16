package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import sh.hnet.comfychair.ui.components.VideoScaleMode
import sh.hnet.comfychair.viewmodel.MediaViewerEvent
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode

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

    // Metadata bottom sheet state
    var showMetadataSheet by remember { mutableStateOf(false) }

    // Load metadata when sheet is shown
    LaunchedEffect(showMetadataSheet) {
        if (showMetadataSheet) {
            viewModel.loadMetadata()
        }
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

                            // For current page, use uiState values
                            // For other pages, check MediaCache directly
                            val cacheKey = MediaCacheKey(item.promptId, item.filename)
                            val bitmap = if (isCurrentPage) uiState.currentBitmap else MediaCache.getImage(cacheKey)
                            // For video URI: only current page needs it (non-current pages just check if bytes are cached)
                            val videoUri = if (isCurrentPage) uiState.currentVideoUri else null
                            val hasVideoCached = if (!isCurrentPage && item.isVideo) MediaCache.getVideoBytes(cacheKey) != null else false

                            // Show loading if: current page is loading, OR content not available yet
                            val showLoading = if (isCurrentPage) {
                                uiState.isLoading
                            } else {
                                // Non-current page: show loading if no cached content
                                if (item.isVideo) !hasVideoCached else bitmap == null
                            }

                            MediaContent(
                                isVideo = item.isVideo,
                                bitmap = bitmap,
                                videoUri = videoUri,
                                isLoading = showLoading,
                                onSingleTap = { viewModel.toggleUiVisibility() },
                                cacheKey = cacheKey
                            )
                        }

                        // Navigation arrows need pagerState, so move them inside
                        // Left navigation arrow
                        if (uiState.totalCount > 1) {
                            AnimatedVisibility(
                                visible = uiState.isUiVisible && uiState.currentIndex > 0,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = stringResource(R.string.media_viewer_previous),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Right navigation arrow
                            AnimatedVisibility(
                                visible = uiState.isUiVisible && uiState.currentIndex < uiState.totalCount - 1,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.media_viewer_next),
                                        tint = Color.White,
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

        // Top bar with back button - with status bar padding
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                        tint = Color.White
                    )
                }
                if (uiState.mode == ViewerMode.GALLERY && uiState.totalCount > 0) {
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.totalCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }

        // Bottom action bar - with navigation bar padding
        AnimatedVisibility(
            visible = uiState.isUiVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Info button
                ActionButton(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.show_metadata),
                    onClick = { showMetadataSheet = true }
                )

                // Delete button (only in gallery mode)
                if (uiState.mode == ViewerMode.GALLERY) {
                    ActionButton(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.media_viewer_delete),
                        onClick = { viewModel.deleteCurrentItem() }
                    )
                }

                // Save button
                ActionButton(
                    icon = Icons.Default.Save,
                    label = stringResource(R.string.media_viewer_save),
                    onClick = { viewModel.saveCurrentItem() }
                )

                // Share button
                ActionButton(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.media_viewer_share),
                    onClick = { viewModel.shareCurrentItem() }
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

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            isVideo && videoUri != null -> {
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
            !isVideo && bitmap != null -> {
                ImageViewer(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize(),
                    onSingleTap = onSingleTap
                )
            }
            isLoading -> {
                CircularProgressIndicator(color = Color.White)
            }
            // Fallback: content not ready yet, show nothing but black background prevents flash-through
        }
    }
}
