package sh.hnet.comfychair.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionFailure

/**
 * Alert dialog shown when WebSocket connection fails after max retry attempts.
 *
 * Shows different options based on failure type:
 * - Network failure: Retry / Go Offline (if cache exists) / Return to Login
 * - Auth failure: Return to Login only (no point retrying with invalid credentials)
 */
@Composable
fun ConnectionAlertDialog(
    failureType: ConnectionFailure,
    hasOfflineCache: Boolean,
    onRetry: () -> Unit,
    onGoOffline: () -> Unit,
    onReturnToLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    val isAuthFailure = failureType == ConnectionFailure.AUTHENTICATION

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isAuthFailure) R.string.session_expired_title
                    else R.string.connection_lost_title
                )
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (isAuthFailure) R.string.session_expired_message
                    else R.string.connection_lost_message
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onReturnToLogin) {
                Text(stringResource(R.string.action_return_to_login))
            }
        },
        dismissButton = {
            if (!isAuthFailure) {
                // Network failure - show additional options
                if (hasOfflineCache) {
                    TextButton(onClick = onGoOffline) {
                        Text(stringResource(R.string.action_go_offline))
                    }
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    )
}

/**
 * State holder for connection alert dialog.
 */
data class ConnectionAlertState(
    val failureType: ConnectionFailure,
    val hasOfflineCache: Boolean
)
