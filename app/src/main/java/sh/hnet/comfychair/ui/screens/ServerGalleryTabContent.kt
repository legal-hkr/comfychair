package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ServerImage
import sh.hnet.comfychair.model.ServerImageSource
import sh.hnet.comfychair.storage.DeletionCredentialStorage
import sh.hnet.comfychair.ui.components.LocalMediaViewer
import sh.hnet.comfychair.ui.components.ServerGalleryGrid
import sh.hnet.comfychair.viewmodel.currentMediaIdentity
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.viewmodel.ServerGalleryViewModel

/**
 * The server gallery in the gallery tab: tabs over the shared picker, the original in the app's media viewer.
 * Selection works like the local gallery's: long press on a tile enters selection mode regardless of deletion
 * capability; only the toolbar's permanent-delete action is gated on the companion extension and the key.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
internal fun ServerGalleryTabContent(
    viewModel: ServerGalleryViewModel,
    source: ServerImageSource,
    selecting: Boolean,
    onSelectAll: Boolean,
    onSelectionChange: (selecting: Boolean, count: Int, keys: Set<String>) -> Unit,
    onConsumedSelectAll: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.picker.state.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val full by viewModel.viewer.collectAsState()
    val mediaViewer = LocalMediaViewer.current

    // Selection lives above the picker AND the top bar: long press on a tile enters it, like the local gallery's
    var selected by remember(state.session) { mutableStateOf(emptySet<ServerImage>()) }
    fun toggle(image: ServerImage) {
        selected = if (image in selected) selected - image else (selected + image).take(50).toSet()
        onSelectionChange(selected.isNotEmpty(), selected.size, selected.map { it.stableKey }.toSet())
    }
    // The top bar's Select all / Close act on this tab through the hoisted flags
    LaunchedEffect(onSelectAll) {
        if (onSelectAll) {
            selected = state.images.take(50).toSet()
            onSelectionChange(selected.isNotEmpty(), selected.size, selected.map { it.stableKey }.toSet())
            onConsumedSelectAll()
        }
    }
    LaunchedEffect(selecting) {
        if (!selecting && selected.isNotEmpty()) {
            selected = emptySet()
            onSelectionChange(false, 0, emptySet())
        }
    }
    // Report the initial state (count is 0 until a tile is toggled)
    LaunchedEffect(state.images.size) {
        if (selected.isNotEmpty()) onSelectionChange(selected.isNotEmpty(), selected.size, selected.map { it.stableKey }.toSet())
    }

    // Whether the companion extension permits physical deletion: only the toolbar's delete action depends on it
    var deleteAvailable by remember(state.session, source) { mutableStateOf(false) }
    LaunchedEffect(state.session, source) {
        deleteAvailable = false
        val owner = currentMediaIdentity() ?: return@LaunchedEffect
        try {
            val client = owner.client as sh.hnet.comfychair.ComfyUIClient
            val api = withContext(Dispatchers.IO) {
                val storage = DeletionCredentialStorage(context)
                val key = client.deletionCredentialScope(owner.serverId)
                client.mediaDeletion(storage.get(key), storage.lan(key))
            }
            if (currentMediaIdentity() == owner) deleteAvailable = api.available()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { deleteAvailable = false }
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Per-file outcomes of the last deletion, shown in a dialog (null = none to show)
    var deleteResults by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }

    DisposableEffect(source) {
        viewModel.open(source)
        onDispose { viewModel.stop() }
    }
    LaunchedEffect(state.session) {
        if (selected.isNotEmpty()) { selected = emptySet(); onSelectionChange(false, 0, emptySet()) }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ServerGalleryGrid(
                state = state,
                loadPreview = viewModel::preview,
                selecting = selecting,
                selectedImages = selected,
                onToggleSelect = ::toggle,
                onSelect = viewModel::view,
                onLoadMore = { viewModel.picker.loadMore() },
                onRetryMore = { viewModel.picker.loadMore(true) },
                onRefresh = viewModel.picker::refresh,
                onQuery = viewModel.picker::changeQuery,
                onCancelDownload = viewModel::closeViewer,
                onRetryDownload = viewModel.picker::retryDownload
            )
            // Selection actions, where the navigation bar was: permanent delete only when the extension permits it
            androidx.compose.animation.AnimatedVisibility(
                visible = selecting,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
                val hasSelection = selected.isNotEmpty()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!deleteAvailable) {
                    Text(
                        stringResource(R.string.hint_media_deletion_setup),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }
                HorizontalFloatingToolbar(expanded = true) {
                    TooltipIconButton(
                        Icons.Rounded.Download,
                        stringResource(R.string.button_save_image),
                        {
                            scope.launch {
                                var saved = 0
                                for (image in selected) {
                                    try { viewModel.exportOriginal(context, image); saved++ }
                                    catch (e: CancellationException) { throw e } catch (_: Exception) { }
                                }
                                Toast.makeText(context,
                                    if (saved == selected.size) R.string.msg_image_saved_to_gallery else R.string.error_save_image,
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = hasSelection && !deleting
                    )
                    TooltipIconButton(
                        Icons.Rounded.Delete,
                        stringResource(R.string.server_delete_action),
                        { confirmDelete = true },
                        enabled = hasSelection && deleteAvailable && !deleting,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.server_delete_action)) },
            text = { Text(stringResource(R.string.server_delete_confirm, selected.size,
                if (source == ServerImageSource.INPUT) "Input" else "Output")) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.button_cancel)) } },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                confirmDelete = false
                val captured = selected.toList()
                val expected = currentMediaIdentity() ?: return@TextButton
                deleting = true
                scope.launch {
                    val outcomes = try {
                        check(currentMediaIdentity() == expected)
                        val client = expected.client as sh.hnet.comfychair.ComfyUIClient
                        val api = withContext(Dispatchers.IO) {
                            val storage = DeletionCredentialStorage(context)
                            val key = client.deletionCredentialScope(expected.serverId)
                            client.mediaDeletion(storage.get(key), storage.lan(key))
                        }
                        check(currentMediaIdentity() == expected)
                        val manifest = withContext(Dispatchers.IO) { api.prepare(captured) }
                        check(currentMediaIdentity() == expected)
                        withContext(Dispatchers.IO) { api.delete(manifest) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    deleting = false
                    if (outcomes == null) {
                        Toast.makeText(context, R.string.server_delete_uncertain, Toast.LENGTH_LONG).show()
                    } else {
                        val deleted = outcomes.filter { it.status == "success" }.map { captured[it.index] }
                        withContext(Dispatchers.IO) {
                            runCatching { (expected.client as sh.hnet.comfychair.ComfyUIClient).invalidateDeletedImages(deleted, expected.epoch) }
                        }
                        selected = selected - deleted.toSet()
                        // Tell the top bar: the count shrinks, and an emptied selection leaves selection mode
                        onSelectionChange(
                            selected.isNotEmpty(), selected.size,
                            selected.map { it.stableKey }.toSet()
                        )
                        viewModel.purgeDeleted(deleted)
                        // One line per requested file, in the order they were sent
                        deleteResults = captured.mapIndexed { position, image ->
                            val status = outcomes.firstOrNull { it.index == position }?.status ?: "failed"
                            image.path to statusLabel(status)
                        }
                    }
                }
            }) { Text(stringResource(R.string.server_delete_action)) } })
    }

    // What happened to each file: the server reports per-file outcomes, so show them all
    deleteResults?.let { results ->
        AlertDialog(
            onDismissRequest = { deleteResults = null },
            title = { Text(stringResource(R.string.server_delete_results)) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { (path, label) ->
                        Text(
                            text = "$path: " + stringResource(label),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteResults = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    // When the original has landed, hand it to the app's media viewer (Save, Share, Use as source) and release it here
    if (full != null) {
        val image = full!!
        LaunchedEffect(image) {
            mediaViewer.openImage(image.bitmap, filename = image.image.filename,
                subfolder = image.image.subfolder, type = image.image.type)
            viewModel.closeViewer()
        }
    }
}

/** The server's per-file outcome, as a string resource. */
private fun statusLabel(status: String): Int = when (status) {
    "success" -> R.string.server_delete_success
    "not_found" -> R.string.server_delete_not_found
    "conflict" -> R.string.server_delete_conflict
    "busy" -> R.string.server_delete_busy
    "invalid" -> R.string.server_delete_invalid
    else -> R.string.server_delete_failed
}
