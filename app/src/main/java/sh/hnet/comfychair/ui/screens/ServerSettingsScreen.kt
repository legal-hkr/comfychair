package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.ui.theme.FailedRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.serverSettingsState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    // Load system stats on first composition
    LaunchedEffect(Unit) {
        viewModel.loadSystemStats()
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is SettingsEvent.RefreshNeeded -> {
                    // Handled by SettingsContainerActivity
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings_title)) },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.content_description_menu)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_generation)) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToGeneration()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_logout)) },
                                onClick = {
                                    showMenu = false
                                    onLogout()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
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

                    // System Stats
                    if (uiState.isLoadingStats) {
                        CircularProgressIndicator()
                    } else {
                        uiState.systemStats?.let { stats ->
                            Text(
                                text = "OS: ${stats.os}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "ComfyUI: ${stats.comfyuiVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Python: ${stats.pythonVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "PyTorch: ${stats.pytorchVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )

                            if (stats.ramTotalGB > 0) {
                                Text(
                                    text = "Available RAM: %.2f GB / %.2f GB".format(
                                        stats.ramFreeGB,
                                        stats.ramTotalGB
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            stats.gpus.forEachIndexed { index, gpu ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "GPU $index: ${gpu.name}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (gpu.vramTotalGB > 0) {
                                    Text(
                                        text = "Available VRAM: %.2f GB / %.2f GB".format(
                                            gpu.vramFreeGB,
                                            gpu.vramTotalGB
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
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
                        text = stringResource(R.string.server_management_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.server_management_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = FailedRed
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.clearQueue() },
                        enabled = !uiState.isClearingQueue,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FailedRed
                        )
                    ) {
                        Text(stringResource(R.string.clear_queue_button))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.clearHistory() },
                        enabled = !uiState.isClearingHistory,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = FailedRed
                        )
                    ) {
                        Text(stringResource(R.string.clear_history_button))
                    }
                }
            }
        }
    }
}
