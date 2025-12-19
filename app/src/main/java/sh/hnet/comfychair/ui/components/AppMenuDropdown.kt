package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R

/**
 * Reusable menu dropdown for navigation and Logout actions.
 * Used in TopAppBar across all screens.
 *
 * @param onNavigate First action (Settings or Generation)
 * @param onLogout Logout action
 * @param navigateLabel Label for the first action (defaults to Settings)
 * @param navigateIcon Icon for the first action (defaults to Settings icon)
 */
@Composable
fun AppMenuDropdown(
    onNavigate: () -> Unit,
    onLogout: () -> Unit,
    navigateLabel: String = stringResource(R.string.action_settings),
    navigateIcon: ImageVector = Icons.Default.Settings
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_description_menu))
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(navigateLabel) },
                onClick = {
                    showMenu = false
                    onNavigate()
                },
                leadingIcon = {
                    Icon(navigateIcon, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_logout)) },
                onClick = {
                    showMenu = false
                    onLogout()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                }
            )
        }
    }
}

/**
 * Menu dropdown for generation screens (Settings + Logout).
 */
@Composable
fun AppMenuDropdown(
    onSettings: () -> Unit,
    onLogout: () -> Unit
) {
    AppMenuDropdown(
        onNavigate = onSettings,
        onLogout = onLogout,
        navigateLabel = stringResource(R.string.action_settings),
        navigateIcon = Icons.Default.Settings
    )
}

/**
 * Menu dropdown for settings screens (Generation + Logout).
 */
@Composable
fun SettingsMenuDropdown(
    onGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    AppMenuDropdown(
        onNavigate = onGeneration,
        onLogout = onLogout,
        navigateLabel = stringResource(R.string.menu_generation),
        navigateIcon = Icons.Default.AutoAwesome
    )
}
