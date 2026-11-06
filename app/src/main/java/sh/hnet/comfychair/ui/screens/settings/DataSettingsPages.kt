@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
        ServerImageCacheSection(viewModel)
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

/** Limits and manual clearing of the private server image cache (lists, thumbnails, upload descriptors). */
@Composable
private fun ServerImageCacheSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val policy by viewModel.imageCachePolicy.collectAsState()
    val stats by viewModel.imageCacheStats.collectAsState()
    var auto by remember(policy) { mutableStateOf(policy.automatic) }
    var mb by remember(policy) { mutableStateOf(policy.maxMb.toString()) }
    var days by remember(policy) { mutableStateOf(policy.maxDays.toString()) }

    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SettingsDescription(stringResource(R.string.image_cache_scope))
    SettingsGroup {
        row { shapes ->
            SettingsSwitchRow(
                shapes = shapes,
                title = stringResource(R.string.image_cache_auto),
                checked = auto,
                onCheckedChange = { auto = it }
            )
        }
        row { shapes ->
            OutlinedTextField(
                value = mb,
                onValueChange = { mb = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.image_cache_mb)) },
                enabled = auto,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        row { shapes ->
            OutlinedTextField(
                value = days,
                onValueChange = { days = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.image_cache_days)) },
                enabled = auto,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Text(stringResource(R.string.image_cache_rules), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = {
            val parsed = sh.hnet.comfychair.cache.ImageCachePolicy.parse(auto, mb, days)
            if (parsed == null) {
                Toast.makeText(context, R.string.image_cache_validation, Toast.LENGTH_LONG).show()
            } else {
                viewModel.setImageCachePolicy(context, parsed)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.image_cache_apply)) }

    SettingsGroup {
        row { shapes ->
            SettingsActionRow(
                shapes = shapes,
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.image_cache_clear),
                destructive = true,
                onClick = { viewModel.clearImageCache(context) }
            )
        }
        row { shapes ->
            SettingsActionRow(
                shapes = shapes,
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.image_cache_stats_refresh),
                onClick = { viewModel.refreshImageCacheStats(context) }
            )
        }
    }
    val current = stats
    Text(
        text = if (current == null) stringResource(R.string.image_cache_stats_loading) else
            stringResource(R.string.image_cache_size, formatBytes(current.bytes), current.bytes) + "\n" +
            stringResource(R.string.image_cache_count, current.entries),
        style = MaterialTheme.typography.bodySmall
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
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
