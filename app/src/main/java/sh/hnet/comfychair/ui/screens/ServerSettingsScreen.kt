package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.viewmodel.GpuInfo
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel

@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.serverSettingsState.collectAsState()

    // State and effects
    // Load system stats on first composition and start auto-refresh
    LaunchedEffect(Unit) {
        viewModel.loadSystemStats()
    }

    // Start/stop auto-refresh when screen is shown/hidden
    DisposableEffect(Unit) {
        viewModel.startResourceAutoRefresh()
        onDispose {
            viewModel.stopResourceAutoRefresh()
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
                    // Handled by ApplicationSettingsScreen
                }
                is SettingsEvent.NavigateToLogin -> {
                    // Handled by SettingsContainerActivity
                }
            }
        }
    }

    // UI composition
    SettingsScreenScaffold(
        title = stringResource(R.string.server_settings_title),
        onNavigateToGeneration = onNavigateToGeneration,
        onLogout = onLogout
    ) {
        Spacer(modifier = Modifier.height(16.dp))
            // Server Information Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_info_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Hostname
                    Row {
                        Text(
                            text = stringResource(R.string.hostname_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.hostname,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Port
                    Row {
                        Text(
                            text = stringResource(R.string.port_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.port.toString(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // System Stats (software versions only)
                    if (uiState.isLoadingStats && uiState.systemStats == null) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        uiState.systemStats?.let { stats ->
                            Text(
                                text = "${stringResource(R.string.server_info_label_os)} ${stats.os}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${stringResource(R.string.server_info_label_comfyui)} ${stats.comfyuiVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${stringResource(R.string.server_info_label_python)} ${stats.pythonVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${stringResource(R.string.server_info_label_pytorch)} ${stats.pytorchVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // RAM Usage Card
            uiState.systemStats?.let { stats ->
                if (stats.ramTotalGB > 0) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ram_usage_title),
                                style = MaterialTheme.typography.titleMedium
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            val ramUsedGB = stats.ramTotalGB - stats.ramFreeGB
                            val ramProgress = (ramUsedGB / stats.ramTotalGB).toFloat().coerceIn(0f, 1f)

                            LinearProgressIndicator(
                                progress = { ramProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = stringResource(
                                    R.string.resource_usage_format,
                                    ramUsedGB,
                                    stats.ramTotalGB
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // GPU Cards
                if (stats.gpus.isNotEmpty()) {
                    stats.gpus.forEachIndexed { index, gpu ->
                        Spacer(modifier = Modifier.height(16.dp))
                        GpuUsageCard(
                            gpu = gpu,
                            index = index,
                            showIndex = stats.gpus.size > 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server Management Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_management_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Refresh Models button
                    Button(
                        onClick = { viewModel.refreshServerData() },
                        enabled = !uiState.isRefreshingModels,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isRefreshingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.refresh_models_button))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Clear History button
                    Button(
                        onClick = { viewModel.clearHistory() },
                        enabled = !uiState.isClearingHistory,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.clear_history_button))
                    }
                }
            }

        // Bottom padding
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun GpuUsageCard(
    gpu: GpuInfo,
    index: Int,
    showIndex: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val title = if (showIndex) {
                "${stringResource(R.string.gpu_usage_title)} $index"
            } else {
                stringResource(R.string.gpu_usage_title)
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = gpu.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (gpu.vramTotalGB > 0) {
                Spacer(modifier = Modifier.height(12.dp))

                val vramUsedGB = gpu.vramTotalGB - gpu.vramFreeGB
                val vramProgress = (vramUsedGB / gpu.vramTotalGB).toFloat().coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { vramProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.resource_usage_format,
                        vramUsedGB,
                        gpu.vramTotalGB
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
