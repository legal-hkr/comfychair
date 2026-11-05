@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LogViewerDialog
import sh.hnet.comfychair.ui.components.settings.ConfirmDestructiveDialog
import sh.hnet.comfychair.ui.components.settings.SettingsActionRow
import sh.hnet.comfychair.ui.components.settings.SettingsDescription
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.components.settings.SettingsSwitchRow
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.SettingsViewModel

private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Backup and restore: export the configuration to a file, or restore it from one (confirmed after picking it). */
@Composable
fun BackupSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.createBackup(context, it) }
    }
    // The restore is confirmed by the settings event host once the file is checked
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.startRestore(it) }
    }

    SettingsPage(stringResource(R.string.title_backup_restore), onNavigateBack) {
        SettingsDescription(stringResource(R.string.desc_backup_restore))
        SettingsGroup {
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.SaveAlt,
                    title = stringResource(R.string.button_backup_create),
                    onClick = { saveLauncher.launch("comfychair_backup_${timestamp()}.json") }
                )
            }
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.SettingsBackupRestore,
                    title = stringResource(R.string.button_backup_restore),
                    onClick = { restoreLauncher.launch("application/json") }
                )
            }
        }
    }
}

/** Cache: what clearing it deletes, and the action, confirmed. */
@Composable
fun CacheSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.title_settings_cache_page), onNavigateBack) {
        SettingsDescription(stringResource(R.string.desc_settings_cache_page))
        SettingsGroup {
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.button_clear_cache),
                    destructive = true,
                    onClick = { confirm = true }
                )
            }
        }
    }

    if (confirm) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_confirm_clear_cache),
            text = stringResource(R.string.msg_confirm_clear_cache),
            confirmLabel = stringResource(R.string.button_clear),
            icon = Icons.Rounded.DeleteSweep,
            onConfirm = { viewModel.clearCache(context) },
            onDismiss = { confirm = false }
        )
    }
}

/** Debug log: capturing it, and viewing or saving what was captured. */
@Composable
fun DebugLogSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by viewModel.isDebugLoggingEnabled.collectAsState()
    var showViewer by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(DebugLogger.exportToString().toByteArray(Charsets.UTF_8)) }
            Toast.makeText(context, R.string.msg_debug_log_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.error_debug_log_save, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsPage(stringResource(R.string.title_settings_debug_log), onNavigateBack) {
        SettingsDescription(stringResource(R.string.desc_debug_logging))
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_debug_logging_enable),
                    supporting = stringResource(R.string.desc_debug_logging_enable),
                    checked = enabled,
                    onCheckedChange = { viewModel.setDebugLoggingEnabled(context, it) }
                )
            }
            // Only while logging: before, there is nothing to view
            if (enabled) {
                row { shapes ->
                    SettingsActionRow(
                        shapes = shapes,
                        icon = Icons.Rounded.BugReport,
                        title = stringResource(R.string.button_debug_logging_view),
                        onClick = { showViewer = true }
                    )
                }
            }
        }
    }

    if (showViewer) {
        LogViewerDialog(
            onDismiss = { showViewer = false },
            onSave = { saveLauncher.launch("comfychair_log_${timestamp()}.txt") }
        )
    }
}

/** Reset: prompts and library, or all saved defaults; each confirmed with what returns to its default. */
@Composable
fun ResetSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var confirmPrompts by remember { mutableStateOf(false) }
    var confirmDefaults by remember { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.title_settings_reset), onNavigateBack) {
        SettingsDescription(stringResource(R.string.desc_settings_reset))
        SettingsGroup {
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.RestartAlt,
                    title = stringResource(R.string.button_reset_prompts_and_library),
                    destructive = true,
                    onClick = { confirmPrompts = true }
                )
            }
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.Restore,
                    title = stringResource(R.string.button_restore_defaults),
                    destructive = true,
                    onClick = { confirmDefaults = true }
                )
            }
        }
    }

    if (confirmPrompts) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_confirm_reset_prompts),
            text = stringResource(R.string.msg_confirm_reset_prompts),
            confirmLabel = stringResource(R.string.button_reset),
            icon = Icons.Rounded.RestartAlt,
            onConfirm = { viewModel.resetPromptsAndLibrary(context) },
            onDismiss = { confirmPrompts = false }
        )
    }
    if (confirmDefaults) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_confirm_restore_defaults),
            text = stringResource(R.string.msg_confirm_restore_defaults),
            confirmLabel = stringResource(R.string.button_restore),
            icon = Icons.Rounded.Restore,
            onConfirm = { viewModel.restoreDefaults(context) },
            onDismiss = { confirmDefaults = false }
        )
    }
}
