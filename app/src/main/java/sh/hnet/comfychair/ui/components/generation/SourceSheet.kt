package sh.hnet.comfychair.ui.components.generation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.DurationBadge
import sh.hnet.comfychair.ui.components.rememberLazyBitmap
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.ServerInputs

/** Opens the source sheet (spec §21) of a screen; [SourceSheet] shows it while open. */
@Stable
class SourcePickerState {
    var isOpen by mutableStateOf(false)
        private set

    fun open() { isOpen = true }
    fun close() { isOpen = false }
}

@Composable
fun rememberSourcePickerState(): SourcePickerState = remember { SourcePickerState() }

/**
 * The media cache key of a server input file (the cache keys files by job; these have none): the folder and subfolder
 * in place of the job, the bare file name, which /view takes.
 */
fun serverInputKey(name: String): MediaCacheKey =
    ServerInputs.File(name).let { MediaCacheKey("${ServerInputs.TYPE}:${it.subfolder}", it.filename) }

private sealed interface Listing {
    data object Loading : Listing
    data object Offline : Listing
    data class Files(val files: List<ServerInputs.File>) : Listing
    data object Failed : Listing
}

/**
 * The source sheet: From device first (the system picker, as the source button opened before), then the server's
 * input [video]s or images, newest first where the server tells it. Tapping a server file uses it ([onServer], by
 * name, nothing uploaded); [current] is the server file in use, marked. One sheet for a screen's source and its
 * reference slots: the caller knows which one it opened for.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SourceSheet(
    state: SourcePickerState,
    video: Boolean,
    current: String?,
    onDevice: (Uri) -> Unit,
    onServer: (String) -> Unit
) {
    val deviceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onDevice)
    }
    if (!state.isOpen) return

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    fun dismiss(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            state.close()
            then()
        }
    }
    val serverName = remember { ConnectionManager.currentServerId?.let { ServerStorage(context).getServer(it)?.name } }
    var listing by remember { mutableStateOf<Listing>(Listing.Loading) }
    val failedMessage = stringResource(R.string.source_load_failed)
    LaunchedEffect(Unit) {
        val client = ConnectionManager.clientOrNull
        if (client == null || AppSettings.isOfflineMode(context)) {
            listing = Listing.Offline
            return@LaunchedEffect
        }
        listing = when (val result = ServerInputs.list(client, video)) {
            is ServerInputs.ListResult.Ok -> Listing.Files(result.files)
            is ServerInputs.ListResult.Failed -> {
                AppErrors.report(AppError(failedMessage, text = result.details))
                Listing.Failed
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { state.close() }, sheetState = sheetState) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SourceSheetHeader(video, serverName, listing)
            }
            item {
                FromDeviceTile { dismiss { deviceLauncher.launch(if (video) "video/*" else "image/*") } }
            }
            when (val shown = listing) {
                Listing.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                Listing.Offline -> item(span = { GridItemSpan(maxLineSpan) }) { OfflineNote() }
                Listing.Failed -> Unit
                is Listing.Files -> if (shown.files.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(if (video) R.string.source_empty_videos else R.string.source_empty_images),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                        )
                    }
                } else {
                    items(shown.files, key = { it.name }) { file ->
                        ServerFileTile(file, video, selected = file.name == current) {
                            if (file.name == current) dismiss() else dismiss { onServer(file.name) }
                        }
                    }
                }
            }
        }
    }
}

/** Title, then the server (bold) with the count in parentheses, or offline after a dash in italics. */
@Composable
private fun SourceSheetHeader(video: Boolean, serverName: String?, listing: Listing) {
    val count = (listing as? Listing.Files)?.files?.size
    val countText = count?.let {
        pluralStringResource(if (video) R.plurals.source_count_videos else R.plurals.source_count_images, it, it)
    }
    val offline = stringResource(R.string.source_offline)
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)) {
        Text(
            stringResource(if (video) R.string.source_sheet_title_video else R.string.source_sheet_title_image),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        if (serverName != null) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(serverName) }
                    when {
                        listing == Listing.Offline -> {
                            append(" – ")
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(offline) }
                        }
                        countText != null -> append(" ($countText)")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FromDeviceTile(onClick: () -> Unit) {
    Column(
        Modifier
            .aspectRatio(1f)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.source_from_device),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center
        )
    }
}

/** A server file's thumbnail, loaded as the Gallery loads its tiles; a video with its length. */
@Composable
private fun ServerFileTile(file: ServerInputs.File, video: Boolean, selected: Boolean, onClick: () -> Unit) {
    val key = serverInputKey(file.name)
    val (bitmap, isLoading) = rememberLazyBitmap(cacheKey = key, isVideo = video, subfolder = file.subfolder, type = ServerInputs.TYPE)
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(TileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .selectable(selected = selected, role = Role.Image, onClick = onClick)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center
    ) {
        when {
            image != null -> Image(image, contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            !isLoading -> Icon(Icons.Rounded.BrokenImage, contentDescription = file.name, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (video && bitmap != null) {
            MediaCache.getVideoDurationMs(key)?.let { DurationBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
        }
        if (selected) {
            Box(Modifier.matchParentSize().border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), TileShape))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun OfflineNote() {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.source_offline_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.source_offline_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private val TileShape = RoundedCornerShape(12.dp)
