package sh.hnet.comfychair.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.NoEncryption
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.CertificateIssue
import sh.hnet.comfychair.connection.PendingCertificate
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog

/**
 * Asks whether to trust a certificate the system does not accept (trust on first use),
 * or one that differs from the certificate accepted earlier for this server.
 */
@Composable
fun CertificateTrustDialog(
    pending: PendingCertificate,
    onTrust: () -> Unit,
    onCancel: () -> Unit
) {
    val isChanged = pending.issue == CertificateIssue.CHANGED
    val validUntil = remember(pending) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(pending.certificate.notAfter)
    }

    val context = LocalContext.current
    // Lowers security if wrong: icon, Trust in the error colour; the fingerprint to compare, selectable and copyable
    ConfirmDialog(
        title = stringResource(if (isChanged) R.string.title_certificate_changed else R.string.title_certificate_untrusted),
        text = stringResource(if (isChanged) R.string.msg_certificate_changed else R.string.msg_certificate_untrusted),
        confirmLabel = stringResource(R.string.button_trust_certificate),
        onConfirm = onTrust,
        onDismiss = onCancel,
        icon = Icons.Rounded.GppMaybe,
        destructive = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CertificateField(stringResource(R.string.label_certificate_subject), pending.subjectName)
            CertificateField(stringResource(R.string.label_certificate_issuer), pending.issuerName)
            CertificateField(stringResource(R.string.label_certificate_valid_until), validUntil)
            Text(
                text = stringResource(R.string.label_certificate_fingerprint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    SelectionContainer(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(
                            pending.fingerprint,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("fingerprint", pending.fingerprint))
                        Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.button_copy_to_clipboard))
                    }
                }
            }
        }
    }
}

@Composable
private fun CertificateField(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            }
        )
    }
}

/**
 * Asks before connecting over HTTP to a server that used HTTPS before.
 */
@Composable
fun InsecureFallbackDialog(
    onConnectOverHttp: () -> Unit,
    onCancel: () -> Unit
) {
    ConfirmDialog(
        title = stringResource(R.string.title_insecure_fallback),
        text = stringResource(R.string.msg_insecure_fallback),
        confirmLabel = stringResource(R.string.button_connect_over_http),
        onConfirm = onConnectOverHttp,
        onDismiss = onCancel,
        icon = Icons.Rounded.NoEncryption,
        destructive = true
    )
}
