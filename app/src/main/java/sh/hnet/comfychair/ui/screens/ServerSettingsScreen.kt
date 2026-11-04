@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.settings.ConfirmDestructiveDialog
import sh.hnet.comfychair.ui.components.settings.SettingsActionRow
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsInfoRow
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.SystemStats

/**
 * The server page: its memory use with Free memory and Unload models, its software, maintenance (refresh the models,
 * clear its history – confirmed, at the end) and Log out at the bottom, in thumb reach.
 */
@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.serverSettingsState.collectAsState()
    val serverName = remember {
        ConnectionManager.currentServerId?.let { ServerStorage(context).getServer(it)?.name }
    }
    var confirmClearHistory by remember { mutableStateOf(false) }

    // Load system stats on first composition and refresh them while the page is shown
    LaunchedEffect(Unit) {
        viewModel.loadSystemStats()
    }
    DisposableEffect(Unit) {
        viewModel.startResourceAutoRefresh()
        onDispose { viewModel.stopResourceAutoRefresh() }
    }

    SettingsPage(
        title = serverName ?: stringResource(R.string.title_server_settings),
        onNavigateBack = onNavigateBack,
        subtitle = uiState.hostname.takeIf { it.isNotEmpty() }?.let { "$it:${uiState.port}" }
    ) {
        val stats = uiState.systemStats
        if (stats == null) {
            if (uiState.isLoadingStats) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
        } else {
            UsageCard(
                stats = stats,
                freeing = uiState.isFreeingMemory,
                onFreeMemory = { viewModel.freeServerMemory(unloadModelsOnly = false) },
                onUnloadModels = { viewModel.freeServerMemory(unloadModelsOnly = true) }
            )
            SettingsGroup(stringResource(R.string.title_server_software)) {
                row { shapes -> SettingsInfoRow(shapes, stringResource(R.string.label_operating_system), stats.os) }
                row { shapes -> SettingsInfoRow(shapes, "ComfyUI", stats.comfyuiVersion) }
                row { shapes -> SettingsInfoRow(shapes, "Python", stats.pythonVersion) }
                row { shapes -> SettingsInfoRow(shapes, "PyTorch", stats.pytorchVersion) }
            }
        }

        SettingsGroup(stringResource(R.string.title_server_maintenance)) {
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.button_refresh_models),
                    busy = uiState.isRefreshingModels,
                    onClick = { viewModel.refreshServerData() }
                )
            }
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.DeleteSweep,
                    title = stringResource(R.string.button_clear_history),
                    destructive = true,
                    busy = uiState.isClearingHistory,
                    onClick = { confirmClearHistory = true }
                )
            }
        }

        // Ends the session; the login screen then offers the saved servers (also to switch servers)
        OutlinedButton(
            onClick = onLogout,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_logout))
        }
    }

    if (confirmClearHistory) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_confirm_clear_history),
            text = stringResource(R.string.msg_confirm_clear_history),
            confirmLabel = stringResource(R.string.button_clear),
            onConfirm = { viewModel.clearHistory() },
            onDismiss = { confirmClearHistory = false }
        )
    }
}

/**
 * The server's memory: RAM and each GPU's VRAM as meters, and the two ways to free it as connected buttons.
 */
@Composable
private fun UsageCard(
    stats: SystemStats,
    freeing: Boolean,
    onFreeMemory: () -> Unit,
    onUnloadModels: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (stats.ramTotalGB > 0) {
                Meter(stringResource(R.string.title_ram_usage), stats.ramTotalGB - stats.ramFreeGB, stats.ramTotalGB)
            }
            stats.gpus.filter { it.vramTotalGB > 0 }.forEach { gpu ->
                Meter(stringResource(R.string.label_vram_of, gpu.name), gpu.vramTotalGB - gpu.vramFreeGB, gpu.vramTotalGB)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth()
            ) {
                val round = CornerSize(50)
                val inner = CornerSize(8.dp)
                FilledTonalButton(
                    onClick = onFreeMemory,
                    enabled = !freeing,
                    shape = RoundedCornerShape(round, inner, inner, round),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.button_free_memory)) }
                FilledTonalButton(
                    onClick = onUnloadModels,
                    enabled = !freeing,
                    shape = RoundedCornerShape(inner, round, round, inner),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.button_unload_models)) }
            }
        }
    }
}

/** A labelled usage meter: used of total, in GB. */
@Composable
private fun Meter(label: String, usedGB: Double, totalGB: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.label_memory_usage, usedGB, totalGB),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { (usedGB / totalGB).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
