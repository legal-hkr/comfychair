package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ServerImage
import sh.hnet.comfychair.model.ServerImageSort
import sh.hnet.comfychair.viewmodel.ServerImagePickerState
import sh.hnet.comfychair.viewmodel.ServerPickerScrollPolicy

/**
 * The server gallery's grid, used directly by the Gallery tab: the lazy grid IS the root here, so it always
 * receives the tab's full remaining height (no nested Column/weight chain to collapse).
 *
 * Search and sort sit in a full-span header row; the end status and retry footer are full-span items.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerGalleryGrid(
    state: ServerImagePickerState,
    loadPreview: suspend (ServerImage) -> Bitmap,
    selecting: Boolean,
    selectedImages: Set<ServerImage>,
    onToggleSelect: (ServerImage) -> Unit,
    onSelect: (ServerImage) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    onRefresh: () -> Unit,
    onQuery: (String, ServerImageSort) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val visible by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
    val hasMore = state.nextOffset != null || state.nextCursor != null
    var sortMenu by remember(state.session) { mutableStateOf(false) }

    // Load the next page when the grid is within a few rows of its end. Independent of whether previews have
    // finished: thumbnails are their own concern and must never gate pagination.
    val latestState by rememberUpdatedState(state)
    val latestSelecting by rememberUpdatedState(selecting)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, state.session) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val total = layout.totalItemsCount
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(total, lastVisible, latestState)
        }.collect { (total, lastVisible, current) ->
            if (latestSelecting) return@collect
            if (current.loading || current.loadingMore || current.downloading) return@collect
            if (current.nextCursor == null && current.nextOffset == null) return@collect
            // At the end (or within the last six rows) ask for the next page
            if (lastVisible >= total - 7) latestLoadMore()
        }
    }
    LaunchedEffect(state.session) { gridState.scrollToItem(0) }

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Search and sort (assets lists only), in the grid so no sibling row can take the height
            if (state.assets == true) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "picker-search") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // Compact search field: a pill the height of an icon button, not a full outlined text field
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = state.query,
                                    onValueChange = { onQuery(it, state.sort) },
                                    singleLine = true,
                                    enabled = !state.downloading && !selecting,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                        MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { inner ->
                                        if (state.query.isEmpty()) {
                                            Text(stringResource(R.string.server_assets_search),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        inner()
                                    }
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }, enabled = !state.downloading && !selecting,
                                modifier = Modifier.size(48.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Sort,
                                    stringResource(R.string.server_assets_sort, stringResource(sortLabel(state.sort))))
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                ServerImageSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(sortLabel(sort)), style = MaterialTheme.typography.bodySmall) },
                                        trailingIcon = { if (sort == state.sort) Text("✓") },
                                        onClick = { sortMenu = false; onQuery(state.query, sort) })
                                }
                            }
                        }
                        IconButton(onClick = onRefresh, enabled = !state.loading && !state.downloading && !selecting,
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.server_image_refresh))
                        }
                    }
                }
            }

            if (state.loading || state.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "picker-progress") { MediaTopProgress() }
            }

            if (state.images.isEmpty() && !state.loading && !state.error) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "picker-empty") {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.server_image_empty), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            items(state.images, key = { it.stableKey }) { image ->
                ServerGalleryTile(
                    image = image,
                    visible = image.stableKey in visible,
                    loadPreview = loadPreview,
                    selecting = selecting,
                    isSelected = image in selectedImages,
                    onTap = { if (selecting) onToggleSelect(image) else onSelect(image) },
                    onLongPress = { onToggleSelect(image) }
                )
            }

            // Always shows what the list holds, so an empty or stalled list is visible at a glance
            if (state.images.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "picker-end") {
                    Text(
                        text = when {
                            state.loadingMore -> stringResource(R.string.server_image_loading)
                            hasMore -> stringResource(R.string.server_assets_count, state.images.size, state.totalAssets ?: 0L)
                            else -> stringResource(R.string.server_image_end)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Errors and manual continuation, as grid items so they never compete for height
            if (!state.downloading && (state.downloadError || state.error || state.moreError ||
                    (!state.loading && !state.loadingMore && hasMore &&
                        state.sparsePages >= ServerPickerScrollPolicy.MAX_SPARSE_PAGES))) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "picker-footer") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(when {
                            state.downloadError || state.error -> R.string.server_image_error
                            state.moreError -> R.string.server_image_more_error
                            else -> R.string.server_assets_more
                        }), style = MaterialTheme.typography.bodySmall,
                            color = if (state.downloadError || state.error || state.moreError)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = when {
                            state.downloadError -> onRetryDownload
                            state.error -> onRefresh
                            else -> onRetryMore
                        }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(
                                if (state.downloadError || state.error || state.moreError) R.string.server_image_retry_more
                                else R.string.server_assets_more
                            ), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (state.downloading) MediaLoadingOverlay(onCancel = onCancelDownload)
    }
}

internal fun sortLabel(sort: ServerImageSort): Int = when (sort) {
    ServerImageSort.NEWEST -> R.string.server_assets_newest
    ServerImageSort.OLDEST -> R.string.server_assets_oldest
    ServerImageSort.NAME -> R.string.server_assets_name
    ServerImageSort.SIZE -> R.string.server_assets_size
}

/** One server image tile: a bounded preview with the local gallery's selection check mark. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerGalleryTile(
    image: ServerImage,
    visible: Boolean,
    loadPreview: suspend (ServerImage) -> Bitmap,
    selecting: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    var bitmap by remember(image) { mutableStateOf<Bitmap?>(null) }
    var error by remember(image) { mutableStateOf(false) }
    var retry by remember(image) { mutableIntStateOf(0) }
    LaunchedEffect(image, visible, retry) {
        if (!visible) return@LaunchedEffect
        if (bitmap != null || error) return@LaunchedEffect
        try { bitmap = loadPreview(image) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.Dp>()
    val inset by androidx.compose.animation.core.animateDpAsState(if (isSelected) 10.dp else 0.dp, spec, label = "inset")
    val corner by androidx.compose.animation.core.animateDpAsState(if (isSelected) 20.dp else 8.dp, spec, label = "corner")
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner.coerceAtLeast(0.dp)))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .then(if (selecting) Modifier.semantics { selected = isSelected } else Modifier)
            .padding(inset.coerceAtLeast(0.dp))
            .clip(RoundedCornerShape((corner - 4.dp).coerceAtLeast(0.dp))),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), image.path,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            error -> Text(stringResource(R.string.server_image_preview_retry),
                modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
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
    }
}
