package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import sh.hnet.comfychair.model.ServerImageSource
import sh.hnet.comfychair.storage.DeletionCredentialStorage
import sh.hnet.comfychair.ui.components.LocalMediaViewer
import sh.hnet.comfychair.ui.components.ServerImagePickerPanel
import sh.hnet.comfychair.ui.components.currentMediaIdentity
import sh.hnet.comfychair.viewmodel.ServerGalleryViewModel

/** The server gallery in the gallery tab: tabs over the shared picker, the original in the app's media viewer. */
@Composable
internal fun ServerGalleryTabContent(viewModel: ServerGalleryViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.picker.state.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val full by viewModel.viewer.collectAsState()
    val mediaViewer = LocalMediaViewer.current
    var source by rememberSaveable { mutableStateOf(ServerImageSource.INPUT) }
    var showCredential by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") } // never saved across process recreation
    var allowLan by remember { mutableStateOf(false) }
    var credentialReady by remember { mutableStateOf(false) }
    var credentialRevision by remember { mutableIntStateOf(0) }
    val owner = currentMediaIdentity()
    LaunchedEffect(showCredential, owner) {
        secret = ""; allowLan = false; credentialReady = false
        if (showCredential && owner != null) {
            try {
                val client = owner.client as sh.hnet.comfychair.ComfyUIClient
                val saved = withContext(Dispatchers.IO) {
                    val storage = DeletionCredentialStorage(context)
                    val key = client.deletionCredentialScope(owner.serverId)
                    storage.get(key) to storage.lan(key)
                }
                if (currentMediaIdentity() == owner) {
                    secret = saved.first; allowLan = saved.second; credentialReady = true
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    DisposableEffect(Unit) {
        viewModel.open(ServerImageSource.entries[source.ordinal])
        onDispose { viewModel.stop() }
    }
    val credentialPanel: @Composable () -> Unit = {
        TextButton(onClick = { showCredential = !showCredential; secret = "" }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.server_delete_credential))
        }
        if (showCredential) Column(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.server_delete_setup), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.server_delete_credential)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.server_delete_lan), Modifier.weight(1f))
                Switch(checked = allowLan, onCheckedChange = { allowLan = it }, enabled = credentialReady)
            }
            if (allowLan) Text(stringResource(R.string.server_delete_lan_warning), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(enabled = credentialReady && (secret.isEmpty() || secret.length >= 32), onClick = {
                val expected = owner ?: return@TextButton
                val value = secret
                val consent = allowLan
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { runCatching {
                        check(currentMediaIdentity() == expected)
                        val client = expected.client as sh.hnet.comfychair.ComfyUIClient
                        DeletionCredentialStorage(context).save(client.deletionCredentialScope(expected.serverId), value, consent)
                    }.isSuccess }
                    secret = ""
                    Toast.makeText(context, if (saved) R.string.server_delete_credential_saved else R.string.server_image_error, Toast.LENGTH_LONG).show()
                    if (saved) { credentialRevision++; showCredential = false }
                }
            }) { Text(stringResource(R.string.server_delete_store)) }
        }
    }

    Column {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            onClose = {},
            standalone = true,
            browsingVisible = !loading && !error,
            standaloneInfo = credentialPanel
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