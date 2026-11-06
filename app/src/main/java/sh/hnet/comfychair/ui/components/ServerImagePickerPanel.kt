package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ServerImage
import sh.hnet.comfychair.model.ServerImageSource
import sh.hnet.comfychair.model.ServerImageSort
import sh.hnet.comfychair.viewmodel.ServerGalleryAnchorPolicy
import sh.hnet.comfychair.viewmodel.ServerImagePickerState
import sh.hnet.comfychair.viewmodel.ServerPickerScrollPolicy

/** Server Input/Output image grid: bounded visible-only previews, sparse-page pagination, search and sort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerImagePickerPanel(
    state: ServerImagePickerState,
    loadPreview: suspend (ServerImage) -> Bitmap,
    onSelect: (ServerImage) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    onRefresh: () -> Unit,
    onQuery: (String, ServerImageSort) -> Unit,
    onCancelDownload: () -> Unit,
    onRetryDownload: () -> Unit,
    onClose: () -> Unit,
    standalone: Boolean = false,
    onDeleted: (List<ServerImage>) -> Unit = {},
    standaloneInfo: @Composable () -> Unit = {},
    browsingVisible: Boolean = true
) {
    if (state.slot == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gridState = rememberLazyGridState()
    val deletion = remember(state.session) { ServerDeletionSelection() }
    val visible by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() } }
    val latestState by rememberUpdatedState(state)
    val latestBrowsingVisible by rememberUpdatedState(browsingVisible)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    var pendingAnchor by remember(state.session) { mutableStateOf<ServerGalleryAnchorPolicy.Anchor?>(null) }
    LaunchedEffect(gridState, state.session) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val current = latestState
            // Include cursor/lifecycle, not just a near-bottom Boolean: sparse pages may
            // leave the viewport unchanged. Controller synchronously coalesces requests.
            Triple(current, layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
        }.collect { (current, last, count) ->
            if (latestBrowsingVisible && pendingAnchor == null && !deletion.busy && ServerPickerScrollPolicy.shouldLoad(current, last, count)) latestLoadMore()
        }
    }
    LaunchedEffect(state.session) { gridState.scrollToItem(0) }
    var showInfo by remember(state.session) { mutableStateOf(false) }
    var removed by remember(state.session) { mutableStateOf(setOf<ServerImage>()) }
    val displayImages = state.images.filterNot { it in removed }
    LaunchedEffect(displayImages, pendingAnchor, state.session) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        val position = ServerGalleryAnchorPolicy.resolve(anchor, displayImages)
        gridState.scrollToItem(position.index, position.offset)
        pendingAnchor = null
    }
    val sourceName = if (state.source == ServerImageSource.INPUT) "Input" else "Output"
    val scope = stringResource(if (state.assets == true) R.string.server_assets_scope
        else if (state.source == ServerImageSource.INPUT) R.string.server_image_input_scope
        else R.string.server_image_output_scope)
    val hasMore = state.nextOffset != null || state.nextCursor != null
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().fillMaxHeight(if (standalone) 1f else 0.85f)) {
        Column(Modifier.fillMaxSize()
            .then(if (standalone) Modifier else Modifier.navigationBarsPadding())) {
            if (!standalone) {
            // Source and totals share a single header; actions keep 48dp touch targets.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(sourceName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when {
                        state.loading -> stringResource(R.string.server_image_loading)
                        state.assets == true -> stringResource(R.string.server_assets_count, state.images.size, state.totalAssets ?: 0L)
                        else -> stringResource(R.string.server_image_legacy_count, state.images.size)
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Info, stringResource(R.string.server_image_info))
                    }
                    DropdownMenu(expanded = showInfo, onDismissRequest = { showInfo = false },
                        modifier = Modifier.widthIn(max = 300.dp)) {
                        Text(scope + "\n" + stringResource(R.string.server_image_limits),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                IconButton(onClick = onRefresh, enabled = !state.loading && !state.downloading,
                    modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.server_image_refresh))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.content_description_close))
                }
            }
            }
            ServerDeletionControls(deletion, displayImages, state.session, sourceName) {
                if (it.isNotEmpty()) {
                    pendingAnchor = ServerGalleryAnchorPolicy.capture(displayImages, it.toSet(),
                        gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset,
                        gridState.layoutInfo.visibleItemsInfo.sortedBy { it.index }.map { it.key.toString() })
                    removed = removed + it
                    onDeleted(it)
                }
            }
            if (!deletion.active && (standalone || state.assets == true)) {
                var sortMenu by remember(state.session) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = if (standalone) 8.dp else 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (state.assets == true) {
                OutlinedTextField(value = state.query, onValueChange = { onQuery(it, state.sort) },
                    placeholder = { Text(stringResource(R.string.server_assets_search),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = MaterialTheme.typography.bodySmall, singleLine = true,
                    enabled = !state.downloading, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { sortMenu = true }, enabled = !state.downloading,
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Sort,
                            stringResource(R.string.server_assets_sort, stringResource(sortLabel(state.sort))))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ServerImageSort.entries.forEach { sort -> DropdownMenuItem(
                            text = { Text(stringResource(sortLabel(sort)), style = MaterialTheme.typography.bodySmall) },
                            trailingIcon = { if (sort == state.sort) Text("✓") },
                            onClick = { sortMenu = false; onQuery(state.query, sort) }) }
                    }
                }
                } else Spacer(Modifier.weight(1f))
                if (standalone) {
                    IconButton(onClick = onRefresh, enabled = !state.loading && !state.downloading,
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.server_image_refresh))
                    }
                    IconButton(onClick = { showInfo = !showInfo }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Info, stringResource(R.string.server_image_info))
                    }
                }
            }
            }
            if (standalone && showInfo) {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Text(when {
                        state.loading -> stringResource(R.string.server_image_loading)
                        state.assets == true -> stringResource(R.string.server_assets_count, state.images.size, state.totalAssets ?: 0L)
                        else -> stringResource(R.string.server_image_legacy_count, state.images.size)
                    }, style = MaterialTheme.typography.labelSmall)
                    Text(scope + "\n" + stringResource(R.string.server_image_limits), style = MaterialTheme.typography.bodySmall)
                    standaloneInfo()
                }
            }
            if (state.loading || state.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            if (!state.loading && !state.error && !hasMore && state.images.isEmpty())
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.server_image_empty), style = MaterialTheme.typography.bodySmall)
                }
            else LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState,
                contentPadding = PaddingValues(if (standalone) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                items(displayImages, key = { it.stableKey }) { image ->
                    key(state.session, image) {
                        ServerPreviewTile(image, image.stableKey in visible, !state.downloading && browsingVisible, loadPreview,
                            enabled = browsingVisible && !state.downloading && !deletion.busy && image !in removed,
                            onSelect = { if (deletion.active) deletion.toggle(image) else onSelect(image) },
                            onLongSelect = { deletion.toggle(image) }, checked = image in deletion.selected, hidden = image in removed)
                    }
                }
                // End status scrolls with content instead of permanently reducing grid height.
                if (!state.loading && !hasMore && !state.error && state.images.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "picker-end") {
                        Text(stringResource(R.string.server_image_end), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // No idle footer: errors and manual continuation retain their text/actions.
            if (!state.downloading && (state.downloadError || state.error || state.moreError ||
                (!state.loading && !state.loadingMore && hasMore && state.sparsePages >= ServerPickerScrollPolicy.MAX_SPARSE_PAGES))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when {
                        state.downloadError || state.error -> R.string.server_image_error
                        state.moreError -> R.string.server_image_more_error
                        else -> R.string.server_assets_more
                    }), style = MaterialTheme.typography.bodySmall,
                        color = if (state.downloadError || state.error || state.moreError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = when {
                        state.downloadError -> onRetryDownload
                        state.error -> onRefresh
                        else -> onRetryMore
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(when {
                            state.downloadError || state.error || state.moreError -> R.string.server_image_retry_more
                            else -> R.string.server_assets_more
                        }), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (state.downloading) MediaLoadingOverlay(onCancel = onCancelDownload)
        }
    }
    if (standalone) content() else ModalBottomSheet(onDismissRequest = { onClose() }, sheetState = sheetState,
        dragHandle = null, contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) { content() }
}

private fun sortLabel(sort: ServerImageSort): Int = when (sort) {
    ServerImageSort.NEWEST -> R.string.server_assets_newest
    ServerImageSort.OLDEST -> R.string.server_assets_oldest
    ServerImageSort.NAME -> R.string.server_assets_name
    ServerImageSort.SIZE -> R.string.server_assets_size
}

@Composable
private fun ServerPreviewTile(image: ServerImage, visible: Boolean, requestAllowed: Boolean,
    loadPreview: suspend (ServerImage) -> Bitmap, enabled: Boolean, onSelect: () -> Unit,
    onLongSelect: () -> Unit = {}, checked: Boolean = false, hidden: Boolean = false) {
    var bitmap by remember(image) { mutableStateOf<Bitmap?>(null) }
    var error by remember(image) { mutableStateOf(false) }
    var retry by remember(image) { mutableIntStateOf(0) }
    LaunchedEffect(image, visible, retry, requestAllowed) {
        if (!visible) { bitmap = null; return@LaunchedEffect }
        if (!requestAllowed || bitmap != null) return@LaunchedEffect
        if (error) return@LaunchedEffect
        error = false
        try { bitmap = loadPreview(image) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
    }
    var longPressedJustFired by remember { mutableStateOf(false) }
    LaunchedEffect(longPressedJustFired) { if (longPressedJustFired) { kotlinx.coroutines.delay(100); longPressedJustFired = false } }
    if (hidden) { Spacer(Modifier.aspectRatio(1f)); return }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .combinedClickable(enabled = enabled, onLongClick = { longPressedJustFired = true; onLongSelect() },
            onClick = { if (!longPressedJustFired) { if (error) { retry++; error = false } else onSelect() }; longPressedJustFired = false }),
        contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), image.path,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            error -> Text(stringResource(R.string.server_image_preview_retry),
                modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            else -> if (requestAllowed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        if (checked) Checkbox(checked = true, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
    }
}
