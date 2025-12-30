package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.screens.ConnectionState

/**
 * Connect button that displays connection status with appropriate styling.
 *
 * @param connectionState Current connection state
 * @param enabled Whether the button is enabled
 * @param onClick Called when the button is clicked
 * @param modifier Modifier for the button
 */
@Composable
fun ServerConnectButton(
    connectionState: ConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonColors = when (connectionState) {
        ConnectionState.IDLE -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
        ConnectionState.CONNECTING -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.secondary
        )
        ConnectionState.FAILED -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
        ConnectionState.CONNECTED -> ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiary
        )
    }

    val buttonText = when (connectionState) {
        ConnectionState.IDLE -> stringResource(R.string.button_connect)
        ConnectionState.CONNECTING -> stringResource(R.string.button_connecting)
        ConnectionState.FAILED -> stringResource(R.string.button_failed)
        ConnectionState.CONNECTED -> stringResource(R.string.button_connected)
    }

    ElevatedButton(
        onClick = onClick,
        enabled = enabled,
        colors = buttonColors,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(
            text = buttonText,
            fontSize = 18.sp
        )
    }
}
