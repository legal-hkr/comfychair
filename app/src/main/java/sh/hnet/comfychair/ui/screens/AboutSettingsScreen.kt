@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.WhatsNew
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsInfoRow
import sh.hnet.comfychair.ui.components.settings.SettingsLinkRow
import sh.hnet.comfychair.ui.components.settings.SettingsNavigationRow
import sh.hnet.comfychair.ui.components.settings.SettingsPage

@Composable
fun AboutSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenWhatsNew: () -> Unit
) {
    val context = LocalContext.current
    // No row without this version's notes; the badge until the page was opened (checked again on coming back)
    val notes = remember { WhatsNew.notes(context) }
    var seen by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        seen = WhatsNew.isSeen(context)
        onPauseOrDispose { }
    }

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
            notes?.let { notes ->
                row { shapes ->
                    val count = notes.changelog.itemCount
                    SettingsNavigationRow(
                        shapes = shapes,
                        icon = Icons.Rounded.NewReleases,
                        title = stringResource(R.string.whats_new_title),
                        supporting = if (count > 0) {
                            pluralStringResource(R.plurals.whats_new_row_supporting, count, notes.version, count)
                        } else {
                            stringResource(R.string.label_about_version, notes.version)
                        },
                        badge = if (seen) null else stringResource(R.string.whats_new_badge),
                        onClick = onOpenWhatsNew
                    )
                }
            }
            row { shapes ->
                SettingsInfoRow(
                    shapes = shapes,
                    title = stringResource(R.string.title_about_license),
                    value = stringResource(R.string.label_about_license_name),
                    icon = Icons.Rounded.WorkspacePremium
                )
            }
            row { shapes ->
                SettingsLinkRow(
                    shapes = shapes,
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.title_about_source),
                    supporting = SOURCE_ADDRESS,
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$SOURCE_ADDRESS"))) }
                )
            }
        }
    }
}

private const val SOURCE_ADDRESS = "github.com/legal-hkr/comfychair"
