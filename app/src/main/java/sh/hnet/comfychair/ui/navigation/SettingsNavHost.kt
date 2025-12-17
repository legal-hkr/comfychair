package sh.hnet.comfychair.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.ui.screens.ApplicationSettingsScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@Composable
fun SettingsNavHost(
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    comfyUIClient: ComfyUIClient,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            BottomAppBar(
                actions = {
                    // Add left padding to align with screen content
                    Spacer(modifier = Modifier.width(12.dp))

                    // Workflows Settings (first)
                    if (currentRoute == SettingsRoute.Workflows.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = stringResource(R.string.nav_workflows_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Workflows.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.AccountTree,
                                contentDescription = stringResource(R.string.nav_workflows_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Application Settings
                    if (currentRoute == SettingsRoute.Application.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.nav_application_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Application.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.nav_application_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Server Settings
                    if (currentRoute == SettingsRoute.Server.route) {
                        FilledIconButton(
                            onClick = { },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = stringResource(R.string.nav_server_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            navController.navigate(SettingsRoute.Server.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }) {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = stringResource(R.string.nav_server_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                floatingActionButton = {
                    // Back to generation FAB
                    FloatingActionButton(
                        onClick = onNavigateToGeneration,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.menu_generation),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = SettingsRoute.Workflows.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(SettingsRoute.Application.route) {
                ApplicationSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = onNavigateToGeneration,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.Server.route) {
                ServerSettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = onNavigateToGeneration,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }

            composable(SettingsRoute.Workflows.route) {
                WorkflowsSettingsScreen(
                    viewModel = workflowManagementViewModel,
                    comfyUIClient = comfyUIClient,
                    onNavigateToGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }
        }
    }
}
