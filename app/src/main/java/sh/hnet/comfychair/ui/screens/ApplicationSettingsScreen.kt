package sh.hnet.comfychair.ui.screens

import sh.hnet.comfychair.storage.MediaStorageMode
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import android.app.LocaleManager
import android.net.Uri
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LanguageDropdown
import sh.hnet.comfychair.ui.components.LanguageOption
import sh.hnet.comfychair.ui.components.LogViewerDialog
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.workflow.routing.RouterProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ApplicationSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToLogin: () -> Unit = {}
) {
    val context = LocalContext.current
    val isLivePreviewEnabled by viewModel.isLivePreviewEnabled.collectAsState()
    val mediaStorageMode by viewModel.mediaStorageMode.collectAsState()
    // Offline mode browses the gallery cache, which only exists with storage on the device
    val isOfflineModeAvailable = mediaStorageMode == MediaStorageMode.DEVICE
    var showMemoryOnlyConfirm by remember { mutableStateOf(false) }
    val isDebugLoggingEnabled by viewModel.isDebugLoggingEnabled.collectAsState()
    val isAutoConnectEnabled by viewModel.isAutoConnectEnabled.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val edgeRouterId by viewModel.edgeRouterId.collectAsState()
    val isPromptSpellCheckEnabled by viewModel.isPromptSpellCheckEnabled.collectAsState()
    val isPromptExpandEnabled by viewModel.isPromptExpandEnabled.collectAsState()

    // State and effects
    // Backup/restore state
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // Debug logging state
    var showLogViewer by remember { mutableStateOf(false) }

    // File picker for creating backup
    val backupSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.createBackup(context, it) }
    }

    // File picker for restoring backup
    val backupRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.startRestore(it) }
    }

    // File picker for saving debug logs
    val logSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { saveUri ->
            try {
                context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                    outputStream.write(DebugLogger.exportToString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, R.string.msg_debug_log_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.error_debug_log_save, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is SettingsEvent.RefreshNeeded -> {
                    // Handled by SettingsContainerActivity
                }
                is SettingsEvent.ShowRestoreDialog -> {
                    pendingRestoreUri = event.uri
                    showRestoreDialog = true
                }
                is SettingsEvent.NavigateToLogin -> {
                    onNavigateToLogin()
                }
            }
        }
    }

    // Switching to memory-only storage deletes the media stored on the device
    if (showMemoryOnlyConfirm) {
        AlertDialog(
            onDismissRequest = { showMemoryOnlyConfirm = false },
            title = { Text(stringResource(R.string.title_media_storage_memory_only_confirm)) },
            text = { Text(stringResource(R.string.msg_media_storage_memory_only_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        showMemoryOnlyConfirm = false
                        viewModel.setMediaStorageMode(context, MediaStorageMode.MEMORY_ONLY)
                    }
                ) {
                    Text(stringResource(R.string.button_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showMemoryOnlyConfirm = false }) {
                    Text(stringResource(R.string.button_cancel))
                }
            }
        )
    }

    // Restore confirmation dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestoreDialog = false
                pendingRestoreUri = null
            },
            title = { Text(stringResource(R.string.title_backup_restore_confirm)) },
            text = { Text(stringResource(R.string.msg_backup_restore_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreUri?.let { viewModel.restoreBackup(context, it) }
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(R.string.button_backup_restore_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showRestoreDialog = false
                        pendingRestoreUri = null
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Language options - System default first, then alphabetical by native name
    val languageOptions = remember {
        listOf(
            LanguageOption("", R.string.language_system_default),
            LanguageOption("de", R.string.language_name_de),
            LanguageOption("en", R.string.language_name_en),
            LanguageOption("es", R.string.language_name_es),
            LanguageOption("fr", R.string.language_name_fr),
            LanguageOption("pl", R.string.language_name_pl),
            LanguageOption("zh", R.string.language_name_zh)
        )
    }

    // Get current app locale from LocaleManager
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val currentLocales = localeManager.applicationLocales
    val currentLocaleTag = if (currentLocales.isEmpty) {
        ""
    } else {
        currentLocales.get(0)?.language ?: ""
    }

    SettingsScreenScaffold(
        title = stringResource(R.string.title_application_settings),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Language Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_language),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                LanguageDropdown(
                    languages = languageOptions,
                    selectedLocaleTag = currentLocaleTag,
                    onLanguageSelected = { localeTag ->
                        val newLocales = if (localeTag.isEmpty()) {
                            LocaleList.getEmptyLocaleList()
                        } else {
                            LocaleList.forLanguageTags(localeTag)
                        }
                        localeManager.applicationLocales = newLocales
                        // Activity will be recreated automatically by the system
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Prompt spell check toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_prompt_spell_check),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_prompt_spell_check),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPromptSpellCheckEnabled,
                        onCheckedChange = { viewModel.setPromptSpellCheckEnabled(context, it) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_connection),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-connect toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_auto_connect),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_auto_connect),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAutoConnectEnabled,
                        onCheckedChange = { viewModel.setAutoConnectEnabled(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Offline mode toggle (only available when disk-first mode is enabled)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_offline_mode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isOfflineModeAvailable)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        Text(
                            text = stringResource(
                                if (!isOfflineModeAvailable) R.string.msg_offline_mode_requires_disk_cache
                                else R.string.desc_offline_mode
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOfflineModeAvailable)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                    Switch(
                        checked = isOfflineMode,
                        onCheckedChange = { viewModel.setOfflineMode(context, it) },
                        enabled = isOfflineModeAvailable
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Generation Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_generation),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_generation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Show live preview toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_live_preview),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_live_preview),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isLivePreviewEnabled,
                        onCheckedChange = { viewModel.setLivePreviewEnabled(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Prompt expand toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_prompt_expand),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_prompt_expand),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPromptExpandEnabled,
                        onCheckedChange = { viewModel.setPromptExpandEnabled(context, it) }
                    )
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Workflow Editor Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_workflow_editor),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_workflow_editor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Edge style label
                Text(
                    text = stringResource(R.string.label_edge_style),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Edge router selector using connected button group
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = edgeRouterId == "hermite",
                        onCheckedChange = { isChecked ->
                            if (isChecked) viewModel.setEdgeRouterId(context, "hermite")
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ) {
                        Text(stringResource(R.string.option_edge_router_hermite))
                    }
                    ToggleButton(
                        checked = edgeRouterId == "bezier",
                        onCheckedChange = { isChecked ->
                            if (isChecked) viewModel.setEdgeRouterId(context, "bezier")
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                    ) {
                        Text(stringResource(R.string.option_edge_router_bezier))
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cache and Storage Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_cache),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_cache),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Media storage: one choice, with the consequences of each option spelled out
                Column(modifier = Modifier.selectableGroup()) {
                    MediaStorageOption(
                        selected = mediaStorageMode == MediaStorageMode.MEMORY_ONLY,
                        label = stringResource(R.string.label_media_storage_memory_only),
                        description = stringResource(R.string.desc_media_storage_memory_only),
                        onSelect = {
                            // Stored media gets deleted, so ask first if there is any
                            if (viewModel.hasStoredMedia(context)) {
                                showMemoryOnlyConfirm = true
                            } else {
                                viewModel.setMediaStorageMode(context, MediaStorageMode.MEMORY_ONLY)
                            }
                        }
                    )
                    MediaStorageOption(
                        selected = mediaStorageMode == MediaStorageMode.MEMORY_PERSISTED,
                        label = stringResource(R.string.label_media_storage_memory_persisted),
                        description = stringResource(R.string.desc_media_storage_memory_persisted),
                        onSelect = { viewModel.setMediaStorageMode(context, MediaStorageMode.MEMORY_PERSISTED) }
                    )
                    MediaStorageOption(
                        selected = mediaStorageMode == MediaStorageMode.DEVICE,
                        label = stringResource(R.string.label_media_storage_device),
                        description = stringResource(R.string.desc_media_storage_device),
                        onSelect = { viewModel.setMediaStorageMode(context, MediaStorageMode.DEVICE) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Clear cache button
                Button(
                    onClick = { viewModel.clearCache(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_clear_cache))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Backup and Restore Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_backup_restore),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_backup_restore),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        backupSaveLauncher.launch("comfychair_backup_$timestamp.json")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_backup_create))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        backupRestoreLauncher.launch("application/json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(stringResource(R.string.button_backup_restore))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reset Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_settings_reset),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_settings_reset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reset prompts and library button
                Button(
                    onClick = { viewModel.resetPromptsAndLibrary(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_reset_prompts_and_library))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Restore defaults button
                Button(
                    onClick = { viewModel.restoreDefaults(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_restore_defaults))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Debug Logging Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_debug_logging),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.desc_debug_logging),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Enable toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_debug_logging_enable),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.desc_debug_logging_enable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isDebugLoggingEnabled,
                        onCheckedChange = { viewModel.setDebugLoggingEnabled(context, it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // View logs button
                Button(
                    onClick = { showLogViewer = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isDebugLoggingEnabled
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.button_debug_logging_view))
                }
            }
        }

        // Bottom padding
        Spacer(modifier = Modifier.height(16.dp))
    }

    // Log viewer dialog
    if (showLogViewer) {
        LogViewerDialog(
            onDismiss = { showLogViewer = false },
            onSave = {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                logSaveLauncher.launch("comfychair_log_$timestamp.txt")
            }
        )
    }
}

/**
 * One option of the media storage choice: radio button, label and what the option means.
 */
@Composable
private fun MediaStorageOption(
    selected: Boolean,
    label: String,
    description: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // The row handles the click, so the radio button itself stays passive
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
