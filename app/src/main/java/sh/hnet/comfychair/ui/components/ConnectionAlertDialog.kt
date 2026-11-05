@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.ui.components.dialogs.ActionDialog
import sh.hnet.comfychair.ui.components.dialogs.DialogAction

/**
 * Alert dialog shown when WebSocket connection fails after max retry attempts.
 *
 * Shows different options based on failure type:
 * - Network failure: Reconnect / Go Offline (if cache exists) / Return to Login
 * - Auth failure: Return to Login only (no point retrying with invalid credentials)
 * - Stall failure: Dismiss only (informative)
 *
 * In the app's dialog pattern: text buttons, the confirming one (Reconnect, with a loading indicator while it tries)
 * last.
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

    val title = stringResource(
        when {
            isCertificateFailure -> R.string.title_certificate_changed
            isAuthFailure -> R.string.title_session_expired
            isStallFailure -> R.string.title_transfer_stalled
            else -> R.string.title_connection_lost
        }
    )
    val text = stringResource(
        when {
            isCertificateFailure -> R.string.msg_certificate_rejected_session
            isAuthFailure -> R.string.msg_session_expired
            isStallFailure -> R.string.msg_transfer_stalled
            else -> R.string.msg_connection_lost
        }
    )
    val actions = when {
        // Informative only
        isStallFailure -> listOf(DialogAction(stringResource(R.string.button_dismiss), onDismiss))
        // No point retrying with invalid credentials or a rejected certificate: resolved on the login screen
        isAuthFailure -> listOf(DialogAction(stringResource(R.string.action_return_to_login), onReturnToLogin))
        // Network: leave, browse the cache, or try again (last, the confirming one)
        else -> listOfNotNull(
            DialogAction(stringResource(R.string.action_return_to_login), onReturnToLogin),
            if (hasOfflineCache) DialogAction(stringResource(R.string.action_go_offline), onGoOffline) else null,
            DialogAction(stringResource(R.string.action_reconnect), onReconnect, busy = isReconnecting)
        )
    }
    ActionDialog(
        icon = when {
            isCertificateFailure -> Icons.Rounded.GppMaybe
            isAuthFailure -> Icons.Rounded.LockClock
            isStallFailure -> Icons.Rounded.HourglassEmpty
            else -> Icons.Rounded.CloudOff
        },
        title = title,
        text = text,
        actions = actions,
        onDismissRequest = onDismiss,
        error = isCertificateFailure
    )
}
