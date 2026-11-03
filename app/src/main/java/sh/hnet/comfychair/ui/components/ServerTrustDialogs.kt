package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.CertificateIssue
import sh.hnet.comfychair.connection.PendingCertificate
import java.text.DateFormat

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

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    if (isChanged) R.string.title_certificate_changed else R.string.title_certificate_untrusted
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(
                        if (isChanged) R.string.msg_certificate_changed else R.string.msg_certificate_untrusted
                    )
                )
                CertificateField(stringResource(R.string.label_certificate_subject), pending.subjectName)
                CertificateField(stringResource(R.string.label_certificate_issuer), pending.issuerName)
                CertificateField(stringResource(R.string.label_certificate_valid_until), validUntil)
                CertificateField(
                    label = stringResource(R.string.label_certificate_fingerprint),
                    value = pending.fingerprint,
                    monospace = true
                )
            }
        },
        confirmButton = {
            Button(onClick = onTrust) {
                Text(stringResource(R.string.button_trust_certificate))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
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
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.title_insecure_fallback)) },
        text = { Text(stringResource(R.string.msg_insecure_fallback)) },
        confirmButton = {
            Button(onClick = onConnectOverHttp) {
                Text(stringResource(R.string.button_connect_over_http))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
