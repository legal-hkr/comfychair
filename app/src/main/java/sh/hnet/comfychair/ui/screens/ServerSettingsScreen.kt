@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsScreenScaffold
import sh.hnet.comfychair.viewmodel.GpuInfo
import sh.hnet.comfychair.viewmodel.SettingsViewModel

@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
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

    // UI composition
    SettingsScreenScaffold(
        title = stringResource(R.string.title_server_settings),
        onNavigateBack = onNavigateBack
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
                        text = stringResource(R.string.title_server_info),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Hostname
                    Row {
                        Text(
                            text = stringResource(R.string.label_hostname),
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
                            text = stringResource(R.string.label_port),
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
                        LoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        uiState.systemStats?.let { stats ->
                            Text(
                                text = "${stringResource(R.string.label_server_info_os)} ${stats.os}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${stringResource(R.string.label_server_info_comfyui)} ${stats.comfyuiVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${stringResource(R.string.label_server_info_python)} ${stats.pythonVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${stringResource(R.string.label_server_info_pytorch)} ${stats.pytorchVersion}",
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
                                text = stringResource(R.string.title_ram_usage),
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
                        key(gpu.name, index) {
                            Spacer(modifier = Modifier.height(16.dp))
                            GpuUsageCard(
                                gpu = gpu,
                                index = index,
                                showIndex = stats.gpus.size > 1
                            )
                        }
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
                        text = stringResource(R.string.title_server_management),
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
                            LoadingIndicator(
                                modifier = Modifier.size(20.dp),
                                color = LocalContentColor.current
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.button_refresh_models))
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
                        Text(stringResource(R.string.button_clear_history))
                    }
                }
            }

        // Ends the session; the login screen then offers the saved servers (also to switch servers)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = onLogout,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_logout))
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
                "${stringResource(R.string.title_gpu_usage)} $index"
            } else {
                stringResource(R.string.title_gpu_usage)
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
