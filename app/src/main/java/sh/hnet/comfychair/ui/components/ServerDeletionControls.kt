package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ServerImage
import sh.hnet.comfychair.storage.DeletionCredentialStorage
import sh.hnet.comfychair.viewmodel.ServerImageIdentity

/** UI-owned selection is scoped to picker session AND exact authenticated client epoch. */
@Stable
class ServerDeletionSelection {
    var enabled by mutableStateOf(false)
    var active by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var selected by mutableStateOf(setOf<ServerImage>())
    fun toggle(image: ServerImage) { if (enabled && !busy) { active = true; selected = if (image in selected) selected - image else (selected + image).take(50).toSet() } }
}

fun currentMediaIdentity(): ServerImageIdentity? {
    val client = ConnectionManager.clientOrNull ?: return null
    val server = ConnectionManager.currentServerId ?: return null
    return ServerImageIdentity(server, client, client.imageUploadIdentity())
}

@Composable
fun ServerDeletionControls(selection: ServerDeletionSelection, images: List<ServerImage>, session: Long,
    source: String, onDeleted: (List<ServerImage>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = currentMediaIdentity()
    val latestSession by rememberUpdatedState(session)
    var confirm by remember(session, owner) { mutableStateOf(false) }
    var results by remember(session, owner) { mutableStateOf("") }
    LaunchedEffect(session, owner) {
        selection.active = false; selection.selected = emptySet(); selection.enabled = false
        try {
            if (owner != null) {
                val client = owner.client as sh.hnet.comfychair.ComfyUIClient
                val api = withContext(Dispatchers.IO) {
                    val storage = DeletionCredentialStorage(context)
                    val key = client.deletionCredentialScope(owner.serverId)
                    client.mediaDeletion(storage.get(key), storage.lan(key))
                }
                if (currentMediaIdentity() != owner) return@LaunchedEffect
                val available = api.available()
                client.mediaExtensionPresent = true
                if (currentMediaIdentity() == owner) selection.enabled = available
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { selection.enabled = false }
    }
    if (selection.active) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.server_delete_selected, selection.selected.size), style = MaterialTheme.typography.labelMedium)
                IconButton(enabled = !selection.busy, onClick = { selection.selected = images.take(50).toSet() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SelectAll, stringResource(R.string.server_delete_loaded))
                }
                IconButton(enabled = !selection.busy, onClick = { selection.selected = emptySet() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Deselect, stringResource(R.string.server_delete_clear))
                }
                IconButton(enabled = !selection.busy, onClick = { selection.active = false; selection.selected = emptySet() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.button_cancel))
                }
                IconButton(enabled = selection.enabled && !selection.busy && selection.selected.isNotEmpty(), onClick = { confirm = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.server_delete_action))
                }
            }
            if (selection.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (results.isNotEmpty()) {
        AlertDialog(onDismissRequest = { results = "" }, title = { Text(stringResource(R.string.server_delete_results)) },
            text = { Text(results, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { results = "" }) { Text(stringResource(android.R.string.ok)) } })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.server_delete_action)) },
        text = { Text(stringResource(R.string.server_delete_confirm, selection.selected.size, source)) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = {
            confirm = false
            val captured = selection.selected.toList()
            val expected = owner ?: return@TextButton
            selection.busy = true
            scope.launch {
                try {
                    check(currentMediaIdentity() == expected)
                    val client = expected.client as sh.hnet.comfychair.ComfyUIClient
                    val api = withContext(Dispatchers.IO) {
                        val storage = DeletionCredentialStorage(context)
                        val key = client.deletionCredentialScope(expected.serverId)
                        client.mediaDeletion(storage.get(key), storage.lan(key))
                    }
                    check(currentMediaIdentity() == expected)
                    val manifest = api.prepare(captured)
                    check(currentMediaIdentity() == expected)
                    val outcomes = api.delete(manifest) // No automatic retries, even on ambiguous disconnect.
                    val deleted = outcomes.filter { it.status == "success" }.map { captured[it.index] }
                    withContext(Dispatchers.IO) { client.invalidateDeletedImages(deleted, expected.epoch) }
                    if (currentMediaIdentity() == expected && latestSession == session) {
                        onDeleted(deleted)
                        selection.selected = selection.selected - deleted.toSet()
                        results = outcomes.joinToString("\n") { "${captured[it.index].path}: ${context.getString(statusLabel(it.status))}" }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { results = context.getString(R.string.server_delete_uncertain) }
                finally { selection.busy = false }
            }
        }) { Text(stringResource(R.string.server_delete_action)) } })
}

private fun statusLabel(status: String): Int = when (status) {
    "success" -> R.string.server_delete_success
    "not_found" -> R.string.server_delete_not_found
    "conflict" -> R.string.server_delete_conflict
    "busy" -> R.string.server_delete_busy
    "invalid" -> R.string.server_delete_invalid
    else -> R.string.server_delete_failed
}
