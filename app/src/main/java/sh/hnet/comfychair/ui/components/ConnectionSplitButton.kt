package sh.hnet.comfychair.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.screens.ConnectionState

/**
 * Split button for the connection screen with server management dropdown.
 *
 * Leading button: Connection state button (IDLE, CONNECTING, FAILED, CONNECTED)
 * Trailing button: Dropdown menu with server management actions (Add, Edit, Remove)
 *
 * @param connectionState Current connection state
 * @param hasSelectedServer Whether a server is currently selected
 * @param onConnect Callback when Connect button is clicked
 * @param onAddServer Callback to add a new server
 * @param onEditServer Callback to edit the selected server
 * @param onRemoveServer Callback to remove the selected server
 * @param modifier Modifier for the button layout
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionSplitButton(
    connectionState: ConnectionState,
    hasSelectedServer: Boolean,
    onConnect: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: () -> Unit,
    onRemoveServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    // Button colors based on connection state
    val containerColor = when (connectionState) {
        ConnectionState.IDLE -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
        ConnectionState.FAILED -> MaterialTheme.colorScheme.error
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
    }
    val contentColor = when (connectionState) {
        ConnectionState.IDLE -> MaterialTheme.colorScheme.onPrimary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.onSecondary
        ConnectionState.FAILED -> MaterialTheme.colorScheme.onError
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onTertiary
    }

    // Button text based on connection state
    val buttonText = when (connectionState) {
        ConnectionState.IDLE -> stringResource(R.string.button_connect)
        ConnectionState.CONNECTING -> stringResource(R.string.button_connecting)
        ConnectionState.FAILED -> stringResource(R.string.button_failed)
        ConnectionState.CONNECTED -> stringResource(R.string.button_connected)
    }

    // Leading button enabled when IDLE and has a selected server
    val leadingEnabled = connectionState == ConnectionState.IDLE && hasSelectedServer

    // Trailing button disabled when CONNECTED or FAILED
    val trailingEnabled = connectionState != ConnectionState.CONNECTED &&
                          connectionState != ConnectionState.FAILED

    Row(modifier = modifier) {
        // Leading button - connection action
        SplitButtonDefaults.ElevatedLeadingButton(
            onClick = onConnect,
            enabled = leadingEnabled,
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
        ) {
            Text(
                text = buttonText,
                fontSize = 18.sp
            )
        }

        // Spacing between buttons
        Spacer(modifier = Modifier.width(SplitButtonDefaults.Spacing))

        // Trailing button with dropdown
        val iconRotation by animateFloatAsState(
            targetValue = if (showMenu) 180f else 0f,
            label = "dropdown icon rotation"
        )

        Box(modifier = Modifier.wrapContentWidth(unbounded = true)) {
            SplitButtonDefaults.ElevatedTrailingButton(
                checked = showMenu,
                onCheckedChange = { if (trailingEnabled) showMenu = it },
                enabled = trailingEnabled,
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor
                ),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.content_description_server_menu),
                    modifier = Modifier.rotate(iconRotation)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // Add server
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.server_add_button)) },
                    onClick = {
                        showMenu = false
                        onAddServer()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                )

                // Edit server (only enabled if a server is selected)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.server_edit_button),
                            color = if (hasSelectedServer)
                                MenuDefaults.itemColors().textColor
                            else
                                MenuDefaults.itemColors().disabledTextColor
                        )
                    },
                    onClick = {
                        showMenu = false
                        onEditServer()
                    },
                    enabled = hasSelectedServer,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = if (hasSelectedServer)
                                MenuDefaults.itemColors().leadingIconColor
                            else
                                MenuDefaults.itemColors().disabledLeadingIconColor
                        )
                    }
                )

                // Divider before destructive action
                HorizontalDivider()

                // Remove server (destructive, error color)
                val removeColor = if (hasSelectedServer) {
                    MaterialTheme.colorScheme.error
                } else {
                    MenuDefaults.itemColors().disabledTextColor
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.server_delete_button), color = removeColor) },
                    onClick = {
                        showMenu = false
                        onRemoveServer()
                    },
                    enabled = hasSelectedServer,
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = removeColor)
                    }
                )
            }
        }
    }
}
