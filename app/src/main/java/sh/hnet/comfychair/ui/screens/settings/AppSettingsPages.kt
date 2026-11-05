@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens.settings

import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.Icons
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.MediaStorageMode
import sh.hnet.comfychair.ui.components.settings.ConfirmDestructiveDialog
import sh.hnet.comfychair.ui.components.settings.SettingsDescription
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.components.settings.SettingsRadioRow
import sh.hnet.comfychair.ui.components.settings.SettingsSwitchRow
import sh.hnet.comfychair.viewmodel.SettingsViewModel

/** Connection: auto-connect, and offline mode (only with media stored on the device, where its cache lives). */
@Composable
fun ConnectionSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val autoConnect by viewModel.isAutoConnectEnabled.collectAsState()
    val offlineMode by viewModel.isOfflineMode.collectAsState()
    val mediaStorageMode by viewModel.mediaStorageMode.collectAsState()
    val offlineAvailable = mediaStorageMode == MediaStorageMode.DEVICE

    SettingsPage(stringResource(R.string.title_settings_connection), onNavigateBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_auto_connect),
                    supporting = stringResource(R.string.desc_auto_connect),
                    checked = autoConnect,
                    onCheckedChange = { viewModel.setAutoConnectEnabled(context, it) }
                )
            }
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_offline_mode),
                    supporting = stringResource(
                        if (offlineAvailable) R.string.desc_offline_mode else R.string.msg_offline_mode_requires_disk_cache
                    ),
                    checked = offlineMode,
                    enabled = offlineAvailable,
                    onCheckedChange = { viewModel.setOfflineMode(context, it) }
                )
            }
        }
    }
}

/** Generation: live preview and prompt spell check. */
@Composable
fun GenerationSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val livePreview by viewModel.isLivePreviewEnabled.collectAsState()
    val spellCheck by viewModel.isPromptSpellCheckEnabled.collectAsState()

    SettingsPage(stringResource(R.string.title_settings_generation), onNavigateBack) {
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_live_preview),
                    supporting = stringResource(R.string.desc_live_preview),
                    checked = livePreview,
                    onCheckedChange = { viewModel.setLivePreviewEnabled(context, it) }
                )
            }
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_prompt_spell_check),
                    supporting = stringResource(R.string.desc_prompt_spell_check),
                    checked = spellCheck,
                    onCheckedChange = { viewModel.setPromptSpellCheckEnabled(context, it) }
                )
            }
        }
    }
}

/**
 * Media and privacy: where generated media are kept, each option with its consequences. Switching to memory only
 * deletes the media stored on the device, so it's confirmed when there are any.
 */
@Composable
fun MediaPrivacySettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val mode by viewModel.mediaStorageMode.collectAsState()
    var confirmMemoryOnly by remember { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.title_settings_media_privacy), onNavigateBack) {
        SettingsDescription(stringResource(R.string.desc_settings_cache))
        SettingsGroup {
            row { shapes ->
                SettingsRadioRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_media_storage_memory_only),
                    supporting = stringResource(R.string.desc_media_storage_memory_only),
                    selected = mode == MediaStorageMode.MEMORY_ONLY,
                    onSelect = {
                        if (mode == MediaStorageMode.MEMORY_ONLY) return@SettingsRadioRow
                        if (viewModel.hasStoredMedia(context)) confirmMemoryOnly = true
                        else viewModel.setMediaStorageMode(context, MediaStorageMode.MEMORY_ONLY)
                    }
                )
            }
            row { shapes ->
                SettingsRadioRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_media_storage_memory_persisted),
                    supporting = stringResource(R.string.desc_media_storage_memory_persisted),
                    selected = mode == MediaStorageMode.MEMORY_PERSISTED,
                    onSelect = { viewModel.setMediaStorageMode(context, MediaStorageMode.MEMORY_PERSISTED) }
                )
            }
            row { shapes ->
                SettingsRadioRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_media_storage_device),
                    supporting = stringResource(R.string.desc_media_storage_device),
                    selected = mode == MediaStorageMode.DEVICE,
                    onSelect = { viewModel.setMediaStorageMode(context, MediaStorageMode.DEVICE) }
                )
            }
        }
    }

    if (confirmMemoryOnly) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_media_storage_memory_only_confirm),
            text = stringResource(R.string.msg_media_storage_memory_only_confirm),
            confirmLabel = stringResource(R.string.button_delete),
            icon = Icons.Rounded.Memory,
            onConfirm = { viewModel.setMediaStorageMode(context, MediaStorageMode.MEMORY_ONLY) },
            onDismiss = { confirmMemoryOnly = false }
        )
    }
}

/** A language the app is translated to, by its own name; an empty tag follows the system. */
class LanguageOption(val localeTag: String, @StringRes val nameRes: Int)

/** System default first, then by native name. */
val appLanguages = listOf(
    LanguageOption("", R.string.language_system_default),
    LanguageOption("de", R.string.language_name_de),
    LanguageOption("en", R.string.language_name_en),
    LanguageOption("es", R.string.language_name_es),
    LanguageOption("fr", R.string.language_name_fr),
    LanguageOption("pl", R.string.language_name_pl),
    LanguageOption("zh", R.string.language_name_zh)
)

/** The app's language tag; empty when it follows the system. */
fun currentLanguageTag(context: Context): String {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) "" else locales.get(0)?.language ?: ""
}

/** Language: one choice among the translations; the system recreates the activity in the new language. */
@Composable
fun LanguageSettingsPage(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val current = remember { currentLanguageTag(context) }

    SettingsPage(stringResource(R.string.title_settings_language_page), onNavigateBack) {
        SettingsGroup {
            appLanguages.forEach { language ->
                row { shapes ->
                    SettingsRadioRow(
                        shapes = shapes,
                        title = stringResource(language.nameRes),
                        selected = language.localeTag == current,
                        onSelect = {
                            if (language.localeTag == current) return@SettingsRadioRow
                            context.getSystemService(LocaleManager::class.java).applicationLocales =
                                if (language.localeTag.isEmpty()) LocaleList.getEmptyLocaleList()
                                else LocaleList.forLanguageTags(language.localeTag)
                        }
                    )
                }
            }
        }
    }
}

/** Edge routers of the workflow editor, with their names. */
val edgeRouters = listOf("hermite" to R.string.option_edge_router_hermite, "bezier" to R.string.option_edge_router_bezier)

/** Workflow editor: how connections are drawn. */
@Composable
fun WorkflowEditorSettingsPage(viewModel: SettingsViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val routerId by viewModel.edgeRouterId.collectAsState()

    SettingsPage(stringResource(R.string.title_settings_workflow_editor), onNavigateBack) {
        // How connections are drawn: one choice
        SettingsGroup(stringResource(R.string.label_edge_style)) {
            edgeRouters.forEach { (id, label) ->
                row { shapes ->
                    SettingsRadioRow(
                        shapes = shapes,
                        title = stringResource(label),
                        selected = id == routerId,
                        onSelect = { viewModel.setEdgeRouterId(context, id) }
                    )
                }
            }
        }
    }
}
