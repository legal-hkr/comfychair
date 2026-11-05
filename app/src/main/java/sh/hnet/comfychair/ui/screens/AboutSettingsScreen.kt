@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.settings.SettingsActionRow
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsInfoRow
import sh.hnet.comfychair.ui.components.settings.SettingsPage

@Composable
fun AboutSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    SettingsPage(stringResource(R.string.title_about_settings), onNavigateBack) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(240.dp)
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = stringResource(R.string.label_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SettingsGroup {
            row { shapes ->
                SettingsInfoRow(shapes, stringResource(R.string.title_about_license), stringResource(R.string.label_about_license_name))
            }
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    title = stringResource(R.string.title_about_source),
                    supporting = SOURCE_ADDRESS,
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$SOURCE_ADDRESS"))) }
                )
            }
        }
    }
}

private const val SOURCE_ADDRESS = "github.com/legal-hkr/comfychair"
