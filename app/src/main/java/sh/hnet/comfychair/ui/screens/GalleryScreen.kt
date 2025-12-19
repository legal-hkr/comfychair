package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.ActiveView
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
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
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by galleryViewModel.uiState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

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
        generationViewModel.getClient()?.let { client ->
            galleryViewModel.initialize(client)
        }
    }

    // Load gallery when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            galleryViewModel.loadGallery()
        }
    }

    // Handle events
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
                columnsInGrid = 2
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

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (uiState.isSelectionMode) {
                    Text(stringResource(R.string.gallery_selected_count, uiState.selectedItems.size))
                } else {
                    Text(stringResource(R.string.gallery_title))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                if (uiState.isSelectionMode) {
                    IconButton(onClick = { galleryViewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_selection))
                    }
                }
            },
            actions = {
                if (uiState.isSelectionMode) {
                    // Selection mode actions: Delete, Save, and Share
                    IconButton(onClick = { galleryViewModel.deleteSelected() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_history_item))
                    }
                    IconButton(onClick = { galleryViewModel.saveSelectedToGallery(context) }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save_image))
                    }
                    IconButton(onClick = { galleryViewModel.shareSelected(context) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    }
                } else {
                    // Normal mode actions: Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_description_menu))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSettings()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_logout)) },
                                onClick = {
                                    showMenu = false
                                    onLogout()
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }
        )

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { galleryViewModel.manualRefresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            // Always use LazyVerticalGrid for consistent nested scroll behavior with pull-to-refresh
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (uiState.isLoading && uiState.items.isEmpty()) {
                    // Loading state - show as full-span item
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (uiState.items.isEmpty()) {
                    // Empty state - show as full-span item
                    item(span = { GridItemSpan(2) }) {
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(R.string.gallery_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Gallery items
                    itemsIndexed(uiState.items, key = { _, item -> "${item.promptId}_${item.filename}" }) { index, item ->
                        GalleryItemCard(
                            item = item,
                            isSelected = galleryViewModel.isItemSelected(item),
                            onTap = {
                                if (uiState.isSelectionMode) {
                                    // In selection mode, tap toggles selection
                                    galleryViewModel.toggleSelection(item)
                                } else {
                                    // Normal mode, tap opens MediaViewer
                                    launchMediaViewer(index)
                                }
                            },
                            onLongPress = {
                                // Long press enters selection mode and selects this item
                                galleryViewModel.toggleSelection(item)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Gallery item card with lazy bitmap loading.
 * Bitmaps are loaded from MediaCache on-demand.
 */
@Composable
private fun GalleryItemCard(
    item: GalleryItem,
    isSelected: Boolean,
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.medium
                    )
                } else {
                    Modifier
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail from cache
            when {
                bitmap != null -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = if (item.isVideo) {
                            stringResource(R.string.gallery_video_thumbnail_description)
                        } else {
                            stringResource(R.string.gallery_thumbnail_description)
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
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                else -> {
                    // Fallback placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.isVideo) Icons.Default.PlayArrow else Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Selection indicator
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.item_selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Video indicator (only show when not selected and not loading)
            if (item.isVideo && !isSelected && bitmap != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.content_description_video),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
