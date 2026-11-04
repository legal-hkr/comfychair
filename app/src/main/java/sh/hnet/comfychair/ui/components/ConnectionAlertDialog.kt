@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
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
    val isCertificateFailure = failureType == ConnectionFailure.CERTIFICATE
    // Certificate problems are resolved on the login screen, like expired sessions
    val isAuthFailure = failureType == ConnectionFailure.AUTHENTICATION || isCertificateFailure
    val isStallFailure = failureType == ConnectionFailure.STALLED

    AlertDialog(
        onDismissRequest = { if (!isReconnecting) onDismiss() },
        title = {
            Text(
                text = stringResource(
                    when {
                        isCertificateFailure -> R.string.title_certificate_changed
                        isAuthFailure -> R.string.title_session_expired
                        isStallFailure -> R.string.title_transfer_stalled
                        else -> R.string.title_connection_lost
                    }
                )
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        when {
                            isCertificateFailure -> R.string.msg_certificate_rejected_session
                            isAuthFailure -> R.string.msg_session_expired
                            isStallFailure -> R.string.msg_transfer_stalled
                            else -> R.string.msg_connection_lost
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
                                LoadingIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = LocalContentColor.current
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
