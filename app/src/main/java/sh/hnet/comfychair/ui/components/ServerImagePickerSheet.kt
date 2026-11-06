package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ServerImageSource
import sh.hnet.comfychair.viewmodel.ServerGalleryViewModel

/**
 * The server's Input/Output images as a bottom sheet: the shared picker over a shared view model, delivering the chosen
 * original as a Bitmap (the generation screen's new source). One sheet instance lives at a time, so the view model's
 * picker is stopped when it leaves composition.
 */
@Composable
fun ServerImagePickerSheet(
    viewModel: ServerGalleryViewModel,
    onPick: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.picker.state.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val events by viewModel.eventsFlow.collectAsState()
    var source by remember { mutableStateOf(ServerImageSource.INPUT) }

    androidx.compose.runtime.LaunchedEffect(events) {
        if (events > 0L) Toast.makeText(context, R.string.server_image_error, Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) {
        viewModel.open(source)
        onDispose { viewModel.stop() }
    }

    Column {
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ServerImageSource.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = source == item,
                    onClick = { source = item; viewModel.open(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, ServerImageSource.entries.size)
                ) { Text(item.type.replaceFirstChar { it.uppercase() }) }
            }
        }
        ServerImagePickerPanel(
            state = state,
            loadPreview = viewModel::preview,
            onSelect = { image ->
                // The picker's own download lane stays free; fetch the original directly.
                viewModel.loadOriginalIn(image, onPick)
                onClose()
            },
            onLoadMore = { viewModel.picker.loadMore() },
            onRetryMore = { viewModel.picker.loadMore(true) },
            onRefresh = viewModel.picker::refresh,
            onQuery = viewModel.picker::changeQuery,
            onCancelDownload = onClose,
            onRetryDownload = onClose,
            onClose = onClose,
            standalone = false,
            browsingVisible = !loading && !error
        )
    }
}
