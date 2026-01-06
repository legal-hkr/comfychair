package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionFailure

/**
 * Alert dialog shown when WebSocket connection fails after max retry attempts.
 *
 * Shows different options based on failure type:
 * - Network failure: Retry / Go Offline (if cache exists) / Return to Login
 * - Auth failure: Return to Login only (no point retrying with invalid credentials)
 *
 * Buttons are stacked vertically with thumb-friendly ordering (primary action at bottom).
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
            Column {
                Text(
                    text = stringResource(
                        if (isAuthFailure) R.string.session_expired_message
                        else R.string.connection_lost_message
                    )
                )

                if (!isAuthFailure) {
                    // Network failure - show vertically stacked buttons
                    // Order: tertiary (top) → secondary → primary (bottom, easiest thumb reach)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Tertiary: Return to Login (outlined button, top)
                        OutlinedButton(
                            onClick = onReturnToLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_return_to_login))
                        }

                        // Secondary: Go Offline (outlined button, middle) - only if cache exists
                        if (hasOfflineCache) {
                            OutlinedButton(
                                onClick = onGoOffline,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.action_go_offline))
                            }
                        }

                        // Primary: Retry (filled button, bottom - easiest thumb reach)
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        },
        confirmButton = {
            // For auth failure, show single button
            if (isAuthFailure) {
                Button(onClick = onReturnToLogin) {
                    Text(stringResource(R.string.action_return_to_login))
                }
            }
            // For network failure, buttons are in the text content above
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
