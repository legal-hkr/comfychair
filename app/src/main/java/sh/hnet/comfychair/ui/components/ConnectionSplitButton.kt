package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.screens.ConnectionState

private val ButtonHeight = 56.dp

/**
 * Connect, at the bottom of the login screen, in the form of the Generate button: a filled split button whose icon
 * becomes a loading indicator while connecting. Its menu holds the offline choice: connecting from the offline cache,
 * or, in offline mode, going back online. Without an offline choice it's a single button.
 *
 * @param canConnectOffline Media are stored on the device and the selected server has an offline cache
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionSplitButton(
    connectionState: ConnectionState,
    hasSelectedServer: Boolean,
    isOfflineMode: Boolean,
    canConnectOffline: Boolean,
    onConnect: () -> Unit,
    onConnectOffline: () -> Unit,
    onGoOnline: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val idle = connectionState == ConnectionState.IDLE
    val failed = connectionState == ConnectionState.FAILED
    val colors = if (failed) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    } else ButtonDefaults.buttonColors()
    val label = when (connectionState) {
        ConnectionState.IDLE -> stringResource(if (isOfflineMode) R.string.button_connect_offline else R.string.button_connect)
        ConnectionState.CONNECTING -> stringResource(R.string.button_connecting)
        ConnectionState.FAILED -> stringResource(R.string.button_failed)
        ConnectionState.CONNECTED -> stringResource(R.string.button_connected)
    }
    val hasMenu = isOfflineMode || canConnectOffline

    val content: @Composable () -> Unit = {
        val iconSize = SplitButtonDefaults.leadingButtonIconSizeFor(ButtonHeight)
        if (connectionState == ConnectionState.CONNECTING) {
            // Drawn in the button's content colour, so it shows on the filled container
            LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(iconSize))
        } else {
            Icon(
                if (isOfflineMode) Icons.Rounded.CloudOff else Icons.Rounded.Link,
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(
            text = label,
            maxLines = 1,
            // Long translations shrink rather than wrap or cut
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 18.sp),
            style = LocalTextStyle.current.copy(fontSize = 18.sp)
        )
    }

    Row(modifier = modifier) {
        if (hasMenu) {
            SplitButtonDefaults.LeadingButton(
                onClick = onConnect,
                enabled = idle && hasSelectedServer,
                colors = colors,
                shapes = SplitButtonDefaults.leadingButtonShapesFor(ButtonHeight),
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(ButtonHeight),
                modifier = Modifier
                    .weight(1f)
                    .height(ButtonHeight)
            ) { content() }
        } else {
            // Nothing to choose: one whole button
            Button(
                onClick = onConnect,
                enabled = idle && hasSelectedServer,
                colors = colors,
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(ButtonHeight),
                modifier = Modifier
                    .weight(1f)
                    .height(ButtonHeight)
            ) { content() }
        }

        if (hasMenu) {
            Spacer(modifier = Modifier.width(SplitButtonDefaults.Spacing))
            Box(modifier = Modifier.wrapContentWidth(unbounded = true)) {
                val description = stringResource(R.string.content_description_connection_options)
                SplitButtonDefaults.TrailingButton(
                    checked = showMenu,
                    onCheckedChange = { showMenu = it },
                    enabled = idle,
                    colors = colors,
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(ButtonHeight),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(ButtonHeight),
                    modifier = Modifier.height(ButtonHeight)
                ) {
                    val rotation = if (showMenu) 180f else 0f
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = description,
                        modifier = Modifier
                            .size(SplitButtonDefaults.trailingButtonIconSizeFor(ButtonHeight))
                            .graphicsLayer { rotationZ = rotation }
                    )
                }
                DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (isOfflineMode) R.string.button_go_online else R.string.button_connect_offline))
                            },
                            leadingIcon = {
                                Icon(if (isOfflineMode) Icons.Rounded.Cloud else Icons.Rounded.CloudOff, contentDescription = null)
                            },
                            shape = MenuDefaults.itemShape(0, 1).shape,
                            enabled = isOfflineMode || hasSelectedServer,
                            onClick = {
                                showMenu = false
                                if (isOfflineMode) onGoOnline() else onConnectOffline()
                            }
                        )
                    }
                }
            }
        }
    }
}
