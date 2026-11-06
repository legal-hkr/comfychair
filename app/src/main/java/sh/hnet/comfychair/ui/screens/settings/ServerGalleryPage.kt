package sh.hnet.comfychair.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ServerImageSource
import sh.hnet.comfychair.ui.components.LocalMediaViewer
import sh.hnet.comfychair.ui.components.ServerImagePickerPanel
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.viewmodel.ServerGalleryViewModel

/**
 * The connected server's Input/Output images as a Settings page: tabs over the shared picker, and the original in the
 * app's media viewer (Save, Share, Use as source).
 */
@Composable
fun ServerGalleryPage(viewModel: ServerGalleryViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.picker.state.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val full by viewModel.viewer.collectAsState()
    var source by remember { mutableStateOf(ServerImageSource.INPUT) }
    val mediaViewer = LocalMediaViewer.current

    SettingsPage(stringResource(R.string.server_gallery_title), onNavigateBack) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
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
            onSelect = viewModel::view,
            onLoadMore = { viewModel.picker.loadMore() },
            onRetryMore = { viewModel.picker.loadMore(true) },
            onRefresh = viewModel.picker::refresh,
            onQuery = viewModel.picker::changeQuery,
            onCancelDownload = viewModel::closeViewer,
            onRetryDownload = viewModel.picker::retryDownload,
            onClose = onNavigateBack,
            standalone = true,
            browsingVisible = !loading && !error
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
