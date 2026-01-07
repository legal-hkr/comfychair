package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
 * - Network failure: Reconnect / Go Offline (if cache exists) / Return to Login
 * - Auth failure: Return to Login only (no point retrying with invalid credentials)
 * - Stall failure: Dismiss only (informative)
 *
 * The Reconnect button shows a loading state with "Reconnecting..." while attempting.
 * Buttons are stacked vertically with thumb-friendly ordering (primary action at bottom).
 */
@Composable
fun ConnectionAlertDialog(
    failureType: ConnectionFailure,
    hasOfflineCache: Boolean,
    isReconnecting: Boolean = false,
    onReconnect: () -> Unit,
    onGoOffline: () -> Unit,
    onReturnToLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    val isAuthFailure = failureType == ConnectionFailure.AUTHENTICATION
    val isStallFailure = failureType == ConnectionFailure.STALLED

    AlertDialog(
        onDismissRequest = { if (!isReconnecting) onDismiss() },
        title = {
            Text(
                text = stringResource(
                    when {
                        isAuthFailure -> R.string.session_expired_title
                        isStallFailure -> R.string.transfer_stalled_title
                        else -> R.string.connection_lost_title
                    }
                )
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        when {
                            isAuthFailure -> R.string.session_expired_message
                            isStallFailure -> R.string.transfer_stalled_message
                            else -> R.string.connection_lost_message
                        }
                    )
                )

                if (isStallFailure) {
                    // Stall failure - informative only with single dismiss button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.button_dismiss))
                        }
                    }
                } else if (!isAuthFailure) {
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
                            enabled = !isReconnecting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_return_to_login))
                        }

                        // Secondary: Go Offline (outlined button, middle) - only if cache exists
                        if (hasOfflineCache) {
                            OutlinedButton(
                                onClick = onGoOffline,
                                enabled = !isReconnecting,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.action_go_offline))
                            }
                        }

                        // Primary: Reconnect (filled button, bottom - easiest thumb reach)
                        Button(
                            onClick = onReconnect,
                            enabled = !isReconnecting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isReconnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_reconnecting))
                            } else {
                                Text(stringResource(R.string.action_reconnect))
                            }
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
            // For network/stall failure, buttons are in the text content above
        }
    )
}
