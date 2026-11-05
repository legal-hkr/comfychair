package sh.hnet.comfychair.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel

/**
 * The one place that shows what the settings report: messages, and the confirmation of a restore once its file is
 * checked. The settings are several pages now; collecting on each would show messages twice or lose them between
 * pages. Reloading and returning to login are handled by the activity.
 */
@Composable
fun SettingsEventsHost(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                is SettingsEvent.ShowRestoreDialog -> pendingRestore = event.uri
                is SettingsEvent.RefreshNeeded, is SettingsEvent.NavigateToLogin -> Unit
            }
        }
    }

    pendingRestore?.let { uri ->
        // Replaces the settings and deletes stored results: the error colour
        ConfirmDialog(
            title = stringResource(R.string.title_backup_restore_confirm),
            text = stringResource(R.string.msg_backup_restore_confirm),
            confirmLabel = stringResource(R.string.button_restore),
            icon = Icons.Rounded.SettingsBackupRestore,
            destructive = true,
            onDismiss = { pendingRestore = null },
            onConfirm = {
                pendingRestore = null
                viewModel.restoreBackup(context, uri)
            }
        )
    }
}
