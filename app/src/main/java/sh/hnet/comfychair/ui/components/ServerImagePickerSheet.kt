package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ServerImage
import sh.hnet.comfychair.model.ServerImageSource
import sh.hnet.comfychair.viewmodel.ServerGalleryViewModel

/**
 * The server's Input/Output images as the generation screens' picker: Input and Output tabs over the shared grid,
 * delivering the chosen original as a Bitmap (the screen's new source).
 *
 * The caller supplies the sheet (a [androidx.compose.material3.ModalBottomSheet]); this content only draws the tabs
 * and the grid, so it stays bounded and clear of the status bar. Picking shows progress over the grid while the
 * original downloads, then hands it over and closes.
 */
@Composable
fun ServerImagePickerSheet(
    viewModel: ServerGalleryViewModel,
    onPick: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.picker.state.collectAsState()
    val events by viewModel.eventsFlow.collectAsState()
    var source by remember { mutableStateOf(ServerImageSource.INPUT) }
    // The original being fetched for the screen, shown as progress over the grid
    var downloading by remember { mutableStateOf<ServerImage?>(null) }

    LaunchedEffect(events) {
        if (events > 0L) Toast.makeText(context, R.string.server_image_error, Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) {
        viewModel.open(source)
        onDispose { viewModel.stop() }
    }

    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ServerImageSource.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = source == item,
                    onClick = {
                        if (downloading == null) {
                            source = item
                            viewModel.open(item)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ServerImageSource.entries.size)
                ) { Text(item.type.replaceFirstChar { it.uppercase() }) }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            ServerGalleryGrid(
                state = state,
                loadPreview = viewModel::preview,
                selecting = false,
                selectedImages = emptySet(),
                onToggleSelect = {},
                onSelect = { image ->
                    if (downloading != null) return@ServerGalleryGrid
                    downloading = image
                    scope.launch {
                        try {
                            val bitmap = viewModel.loadOriginal(image)
                            onPick(bitmap)
                            onClose()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            downloading = null
                            Toast.makeText(context, R.string.error_failed_load_image, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onLoadMore = { viewModel.picker.loadMore() },
                onRetryMore = { viewModel.picker.loadMore(true) },
                onRefresh = viewModel.picker::refresh,
                onQuery = viewModel.picker::changeQuery,
                onCancelDownload = { downloading = null },
                onRetryDownload = {}
            )
            if (downloading != null) MediaLoadingOverlay(onCancel = { downloading = null })
        }
    }
}
