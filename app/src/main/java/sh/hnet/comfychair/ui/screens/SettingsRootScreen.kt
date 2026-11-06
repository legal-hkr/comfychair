@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.storage.MediaStorageMode
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsNavigationRow
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.screens.settings.appLanguages
import sh.hnet.comfychair.ui.screens.settings.currentLanguageTag
import sh.hnet.comfychair.ui.screens.settings.edgeRouters
import sh.hnet.comfychair.viewmodel.SettingsViewModel

/**
 * The Settings tab: the server the app is connected to, then the app's and the data's topic pages, each row showing
 * its current value where it has one.
 */
@Composable
fun SettingsRootScreen(viewModel: SettingsViewModel, onOpenPage: (SettingsRoute) -> Unit) {
    val context = LocalContext.current
    val serverName = remember {
        ConnectionManager.currentServerId?.let { ServerStorage(context).getServer(it)?.name }
    }
    // Address shown so it's clear which server these settings are about
    val address = ConnectionManager.hostname.takeIf { it.isNotEmpty() }?.let { "$it:${ConnectionManager.port}" }
    val mediaStorageMode by viewModel.mediaStorageMode.collectAsState()
    val debugLogging by viewModel.isDebugLoggingEnabled.collectAsState()
    val routerId by viewModel.edgeRouterId.collectAsState()
    val languageTag = remember { currentLanguageTag(context) }

    SettingsPage(
        title = stringResource(R.string.action_settings),
        onNavigateBack = null,
        subtitle = (serverName ?: address)?.let { stringResource(R.string.label_settings_connected_to, it) }
    ) {
        SettingsGroup(stringResource(R.string.title_settings_group_server)) {
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.Storage, serverName ?: stringResource(R.string.nav_server_settings),
                    supporting = address
                ) { onOpenPage(SettingsRoute.Server) }
            }
            row { shapes ->
                SettingsNavigationRow(shapes, Icons.Rounded.AccountTree, stringResource(R.string.nav_workflows_settings)) {
                    onOpenPage(SettingsRoute.Workflows)
                }
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.Wifi, stringResource(R.string.title_settings_connection),
                    supporting = stringResource(R.string.desc_settings_connection)
                ) { onOpenPage(SettingsRoute.Connection) }
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.AutoAwesome, stringResource(R.string.title_settings_generation),
                    supporting = stringResource(R.string.desc_settings_generation_row)
                ) { onOpenPage(SettingsRoute.Generation) }
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.Shield, stringResource(R.string.title_settings_media_privacy),
                    supporting = stringResource(
                        when (mediaStorageMode) {
                            MediaStorageMode.MEMORY_ONLY -> R.string.label_media_storage_memory_only
                            MediaStorageMode.MEMORY_PERSISTED -> R.string.label_media_storage_memory_persisted
                            MediaStorageMode.DEVICE -> R.string.label_media_storage_device
                        }
                    )
                ) { onOpenPage(SettingsRoute.MediaPrivacy) }
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.Translate, stringResource(R.string.title_settings_language_page),
                    supporting = stringResource(
                        (appLanguages.firstOrNull { it.localeTag == languageTag } ?: appLanguages.first()).nameRes
                    )
                ) { onOpenPage(SettingsRoute.Language) }
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.Polyline, stringResource(R.string.title_settings_workflow_editor),
                    supporting = edgeRouters.firstOrNull { it.first == routerId }?.let { stringResource(it.second) }
                ) { onOpenPage(SettingsRoute.WorkflowEditor) }
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.SettingsBackupRestore, stringResource(R.string.title_backup_restore)
                ) { onOpenPage(SettingsRoute.Backup) }
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.Cloud, stringResource(R.string.server_gallery_title)
                ) { onOpenPage(SettingsRoute.ServerGallery) }
            }
            row { shapes ->
                SettingsNavigationRow(shapes, Icons.Rounded.DeleteSweep, stringResource(R.string.title_settings_cache_page)) {
                    onOpenPage(SettingsRoute.Cache)
                }
            }
            row { shapes ->
                SettingsNavigationRow(
                    shapes, Icons.Rounded.BugReport, stringResource(R.string.title_settings_debug_log),
                    supporting = stringResource(if (debugLogging) R.string.state_enabled else R.string.state_disabled)
                ) { onOpenPage(SettingsRoute.DebugLog) }
            }
            row { shapes ->
                SettingsNavigationRow(shapes, Icons.Rounded.RestartAlt, stringResource(R.string.title_settings_reset)) {
                    onOpenPage(SettingsRoute.Reset)
                }
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, Icons.Rounded.Info, stringResource(R.string.nav_about_settings)) {
                    onOpenPage(SettingsRoute.About)
                }
            }
        }
    }
}
