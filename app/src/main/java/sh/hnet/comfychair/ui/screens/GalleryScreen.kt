@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.PlayBadge
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GalleryEvent
import sh.hnet.comfychair.viewmodel.GalleryItem
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    generationViewModel: GenerationViewModel,
    galleryViewModel: GalleryViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by galleryViewModel.uiState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    // State and effects
    // Activity result launcher for MediaViewerActivity
    val mediaViewerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Restore active view to Gallery
        MediaCache.setActiveView(ActiveView.GALLERY)

        // Refresh gallery if item was deleted (silent refresh, no spinner)
        if (result.resultCode == Activity.RESULT_OK) {
            val itemDeleted = result.data?.getBooleanExtra(MediaViewerActivity.RESULT_ITEM_DELETED, false) ?: false
            if (itemDeleted) {
                galleryViewModel.refresh()
            }
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        galleryViewModel.initialize()
    }

    // Back first leaves selection mode
    BackHandler(enabled = uiState.isSelectionMode) { galleryViewModel.clearSelection() }

    // Load gallery when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            galleryViewModel.loadGallery()
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        galleryViewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is GalleryEvent.ShowMedia -> {
                    // Handle media display
                }
            }
        }
    }

    // Helper function to convert GalleryItems to MediaViewerItems
    fun galleryItemsToViewerItems(items: List<GalleryItem>): List<MediaViewerItem> {
        return items.map { item ->
            MediaViewerItem(
                promptId = item.promptId,
                filename = item.filename,
                subfolder = item.subfolder,
                type = item.type,
                isVideo = item.isVideo,
                index = item.index
            )
        }
    }

    // Function to launch media viewer
    fun launchMediaViewer(clickedIndex: Int) {
        // Set active view to MediaViewer
        MediaCache.setActiveView(ActiveView.MEDIA_VIEWER)

        // Update priorities to protect items around clicked index
        val allKeys = uiState.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(clickedIndex, allKeys)

        val viewerItems = galleryItemsToViewerItems(uiState.items)
        val intent = MediaViewerActivity.createGalleryIntent(
            context = context,
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port,
            items = viewerItems,
            initialIndex = clickedIndex
        )
        mediaViewerLauncher.launch(intent)
    }

    // Grid state for scroll tracking
    val gridState = rememberLazyGridState()
    // Adaptive columns: as many as the grid lays out
    val gridColumns by remember {
        derivedStateOf { (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 1) + 1 }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmDelete by remember { mutableStateOf(false) }

    // A selection doesn't outlive the tab: the navigation bar is hidden while selecting
    DisposableEffect(Unit) { onDispose { galleryViewModel.clearSelection() } }

    // Create prefetch items list from gallery items
    val prefetchItems = remember(uiState.items) {
        uiState.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }
    }

    // Track scroll position and update cache priorities with debounce
    // Only update when Gallery is the active view (not when MediaViewer is animating closed)
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.layoutInfo.visibleItemsInfo.size) {
        if (prefetchItems.isNotEmpty() && MediaCache.isActiveView(ActiveView.GALLERY)) {
            // Debounce scroll updates to avoid excessive calls during fast scrolling
            kotlinx.coroutines.delay(100)
            MediaCache.updateGalleryPosition(
                firstVisibleIndex = gridState.firstVisibleItemIndex,
                visibleItemCount = gridState.layoutInfo.visibleItemsInfo.size,
                allItems = prefetchItems,
                columnsInGrid = gridColumns
            )
        }
    }

    // Prefetch when items become available or change (e.g., after manual refresh)
    // Use first item's key as part of the effect key to detect list changes
    val itemsKey = remember(uiState.items) {
        if (uiState.items.isEmpty()) "" else "${uiState.items.size}_${uiState.items.first().promptId}"
    }
    LaunchedEffect(itemsKey) {
        if (uiState.items.isNotEmpty()) {
            // Base prefetch on current scroll position instead of always starting from 0
            val startIndex = gridState.firstVisibleItemIndex.coerceAtLeast(0)
            val endIndex = (startIndex + 24).coerceAtMost(uiState.items.size)
            val initialItems = uiState.items.subList(startIndex, endIndex).map { item ->
                MediaCache.PrefetchItem(
                    key = item.toCacheKey(),
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type
                )
            }
            MediaCache.initialPrefetch(initialItems)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GalleryTopBar(
                selecting = uiState.isSelectionMode,
                selectedCount = uiState.selectedItems.size,
                itemCount = uiState.items.size,
                scrollBehavior = scrollBehavior,
                onSelect = { galleryViewModel.enterSelectionMode() },
                onSelectAll = { galleryViewModel.selectAll() },
                onCloseSelection = { galleryViewModel.clearSelection() }
            )

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { galleryViewModel.manualRefresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                // Always use LazyVerticalGrid for consistent nested scroll behavior with pull-to-refresh
                NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        state = gridState,
                        // Room under the last row for the selection toolbar
                        contentPadding = PaddingValues(bottom = if (uiState.isSelectionMode) 96.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            // The top bar sees the grid's scrolling before pull to refresh: at the top, a pull down
                            // first expands the bar, and only then refreshes
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .padding(horizontal = 12.dp)
                            // The grid is one block with rounded top corners, as the generation preview
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    ) {
                        if (uiState.isLoading && uiState.items.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingIndicator()
                                }
                            }
                        } else if (uiState.items.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = stringResource(R.string.msg_gallery_empty),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(uiState.items, key = { item -> "${item.promptId}_${item.filename}" }) { item ->
                                GalleryTile(
                                    item = item,
                                    selecting = uiState.isSelectionMode,
                                    isSelected = "${item.promptId}_${item.filename}" in uiState.selectedItems,
                                    isOfflineMode = isOfflineMode,
                                    onTap = {
                                        if (uiState.isSelectionMode) {
                                            galleryViewModel.toggleSelection(item)
                                        } else {
                                            // The position is looked up now: the list may have changed (refresh)
                                            // since this tile was composed
                                            val index = uiState.items.indexOfFirst {
                                                it.promptId == item.promptId && it.filename == item.filename
                                            }
                                            if (index >= 0) launchMediaViewer(index)
                                        }
                                    },
                                    // Long press enters selection mode and selects this item
                                    onLongPress = { galleryViewModel.toggleSelection(item) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Selection actions, where the navigation bar was: Delete apart from Save and Share
        AnimatedVisibility(
            visible = uiState.isSelectionMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            val hasSelection = uiState.selectedItems.isNotEmpty()
            HorizontalFloatingToolbar(expanded = true) {
                TooltipIconButton(
                    Icons.Rounded.Delete,
                    stringResource(R.string.button_delete_history_item),
                    { confirmDelete = true },
                    enabled = hasSelection,
                    tint = MaterialTheme.colorScheme.error
                )
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                TooltipIconButton(
                    Icons.Rounded.Download,
                    stringResource(R.string.button_save_image),
                    { galleryViewModel.saveSelectedToGallery(context) },
                    enabled = hasSelection
                )
                TooltipIconButton(
                    Icons.Rounded.Share,
                    stringResource(R.string.button_share),
                    { galleryViewModel.shareSelected(context) },
                    enabled = hasSelection
                )
            }
        }
    }

    if (confirmDelete) {
        val count = uiState.selectedItems.size
        val deletesMore = remember { galleryViewModel.selectionDeletesOtherResults() }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.title_delete_selected)) },
            text = {
                val message = pluralStringResource(R.plurals.msg_delete_selected, count, count)
                Text(if (deletesMore) message + " " + stringResource(R.string.msg_delete_whole_jobs) else message)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    galleryViewModel.deleteSelected()
                }) { Text(stringResource(R.string.button_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.button_cancel)) }
            }
        )
    }
}

/**
 * The gallery's top bar: the title and the number of items, collapsing on scroll; while selecting, Close, the number
 * selected and Select all.
 */
@Composable
private fun GalleryTopBar(
    selecting: Boolean,
    selectedCount: Int,
    itemCount: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    onSelect: () -> Unit,
    onSelectAll: () -> Unit,
    onCloseSelection: () -> Unit
) {
    MediumFlexibleTopAppBar(
        title = {
            Text(
                if (selecting) pluralStringResource(R.plurals.gallery_selected_count, selectedCount, selectedCount)
                else stringResource(R.string.title_gallery)
            )
        },
        subtitle = if (!selecting && itemCount > 0) {
            { Text(pluralStringResource(R.plurals.label_gallery_subtitle, itemCount, itemCount)) }
        } else null,
        navigationIcon = {
            if (selecting) TooltipIconButton(Icons.Rounded.Close, stringResource(R.string.button_cancel_selection), onCloseSelection)
        },
        actions = {
            if (selecting) {
                TooltipIconButton(Icons.Rounded.SelectAll, stringResource(R.string.button_select_all), onSelectAll)
            } else {
                TooltipIconButton(Icons.Rounded.Checklist, stringResource(R.string.button_gallery_select), onSelect)
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        scrollBehavior = scrollBehavior
    )
}

/**
 * A gallery tile with lazy bitmap loading from MediaCache. While selecting, a selected tile shrinks onto a tonal
 * container with rounder corners and a check; the others show an empty circle.
 */
@Composable
private fun GalleryTile(
    item: GalleryItem,
    selecting: Boolean,
    isSelected: Boolean,
    isOfflineMode: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    // Create cache key directly from item's stable properties
    val cacheKey = item.toCacheKey()
    val (bitmap, isLoading) = rememberLazyBitmap(
        cacheKey = cacheKey,
        isVideo = item.isVideo,
        subfolder = item.subfolder,
        type = item.type
    )
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
    val inset by animateDpAsState(if (isSelected) 10.dp else 0.dp, spec, label = "tile inset")
    val outerCorner by animateDpAsState(if (isSelected) 20.dp else 6.dp, spec, label = "tile corner")
    val innerCorner by animateDpAsState(if (isSelected) 12.dp else 6.dp, spec, label = "image corner")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(outerCorner.coerceAtLeast(0.dp)))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .then(if (selecting) Modifier.semantics { selected = isSelected } else Modifier)
            // The spring overshoots a little: on the way back to 0 it would go negative
            .padding(inset.coerceAtLeast(0.dp))
            .clip(RoundedCornerShape(innerCorner.coerceAtLeast(0.dp)))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail from cache
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (item.isVideo) {
                            stringResource(R.string.content_description_gallery_video_thumbnail)
                        } else {
                            stringResource(R.string.content_description_gallery_thumbnail)
                        },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                isLoading -> {
                    // Loading placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                else -> {
                    // Fallback placeholder - show CloudOff for offline mode, otherwise normal icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isOfflineMode) {
                                Icons.Default.CloudOff
                            } else if (item.isVideo) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.Image
                            },
                            contentDescription = if (isOfflineMode) {
                                stringResource(R.string.content_description_not_cached)
                            } else {
                                null
                            },
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Selection mark: a check when selected, an empty circle otherwise
            if (selecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) stringResource(R.string.label_item_selected) else null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(if (isSelected) 18.dp else 22.dp)
                    )
                }
            }

            // Video indicator in the corner, scaled to the tile: a quarter of its width, with the
            // icon and inset in the same proportions as on the results strip (70 % and 20 % of the badge)
            if (item.isVideo && bitmap != null) {
                val videoDescription = stringResource(R.string.content_description_video)
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val badgeSize = maxWidth * 0.25f
                    PlayBadge(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(badgeSize * 0.2f)
                            .semantics { contentDescription = videoDescription },
                        size = badgeSize,
                        iconSize = badgeSize * 0.7f
                    )
                }
            }
        }
    }
}
