package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.FullscreenImageDialog
import sh.hnet.comfychair.ui.components.FullscreenVideoPlayer
import sh.hnet.comfychair.ui.components.SaveOptionsBottomSheet
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GalleryEvent
import sh.hnet.comfychair.viewmodel.GalleryItem
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    generationViewModel: GenerationViewModel,
    galleryViewModel: GalleryViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by galleryViewModel.uiState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<GalleryItem?>(null) }
    var showSaveOptionsSheet by remember { mutableStateOf(false) }
    var showFullscreenImage by remember { mutableStateOf(false) }
    var showFullscreenVideo by remember { mutableStateOf(false) }
    var fullscreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fullscreenVideoUri by remember { mutableStateOf<Uri?>(null) }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            galleryViewModel.initialize(context, client)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                actions = {
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
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { galleryViewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading && uiState.items.isEmpty()) {
                // Loading state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.items.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
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
            } else {
                // Gallery grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { "${it.promptId}_${it.filename}" }) { item ->
                        GalleryItemCard(
                            item = item,
                            onTap = {
                                selectedItem = item
                                if (item.isVideo) {
                                    galleryViewModel.fetchVideoUri(context, item) { uri ->
                                        if (uri != null) {
                                            fullscreenVideoUri = uri
                                            showFullscreenVideo = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.error_failed_load_video),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } else {
                                    galleryViewModel.fetchFullImage(item) { bitmap ->
                                        if (bitmap != null) {
                                            fullscreenBitmap = bitmap
                                            showFullscreenImage = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.error_failed_load_image),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onLongPress = {
                                selectedItem = item
                                showSaveOptionsSheet = true
                            },
                            onDelete = {
                                galleryViewModel.deleteItem(item)
                            }
                        )
                    }
                }
            }
        }
    }

    // Save options bottom sheet
    if (showSaveOptionsSheet && selectedItem != null) {
        val item = selectedItem!!
        SaveOptionsBottomSheet(
            onDismiss = { showSaveOptionsSheet = false },
            onSaveToGallery = {
                showSaveOptionsSheet = false
                if (item.isVideo) {
                    galleryViewModel.saveVideoToGallery(context, item)
                } else {
                    galleryViewModel.saveImageToGallery(context, item)
                }
            },
            onSaveAs = {
                showSaveOptionsSheet = false
                // TODO: Implement save as file picker
            },
            onShare = {
                showSaveOptionsSheet = false
                if (item.isVideo) {
                    galleryViewModel.shareVideo(context, item)
                } else {
                    galleryViewModel.shareImage(context, item)
                }
            },
            isVideo = item.isVideo
        )
    }

    // Fullscreen image dialog
    if (showFullscreenImage && fullscreenBitmap != null) {
        FullscreenImageDialog(
            bitmap = fullscreenBitmap!!,
            onDismiss = {
                showFullscreenImage = false
                fullscreenBitmap = null
            }
        )
    }

    // Fullscreen video dialog
    if (showFullscreenVideo && fullscreenVideoUri != null) {
        Dialog(
            onDismissRequest = {
                showFullscreenVideo = false
                fullscreenVideoUri = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                FullscreenVideoPlayer(
                    videoUri = fullscreenVideoUri,
                    modifier = Modifier.fillMaxSize(),
                    onDismiss = {
                        showFullscreenVideo = false
                        fullscreenVideoUri = null
                    }
                )
            }
        }
    }
}

@Composable
private fun GalleryItemCard(
    item: GalleryItem,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Thumbnail
            if (item.thumbnail != null) {
                Image(
                    bitmap = item.thumbnail.asImageBitmap(),
                    contentDescription = if (item.isVideo) {
                        stringResource(R.string.gallery_video_thumbnail_description)
                    } else {
                        stringResource(R.string.gallery_thumbnail_description)
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
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

            // Video indicator
            if (item.isVideo) {
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

            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_history_item),
                    tint = Color.Red,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
