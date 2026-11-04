package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.navigation.SettingsRoute

/**
 * The Settings tab: a list of the settings pages. The pages keep their content; their own redesign (and splitting
 * Application into topic pages) follows later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(onOpenPage: (SettingsRoute) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.action_settings)) },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Address shown so it's clear which server these settings are about
            val server = ConnectionManager.hostname.takeIf { it.isNotEmpty() }?.let { "$it:${ConnectionManager.port}" }
            SettingsPageRow(Icons.Rounded.Storage, stringResource(R.string.nav_server_settings), supporting = server, first = true) {
                onOpenPage(SettingsRoute.Server)
            }
            SettingsPageRow(Icons.Rounded.AccountTree, stringResource(R.string.nav_workflows_settings)) {
                onOpenPage(SettingsRoute.Workflows)
            }
            SettingsPageRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.nav_application_settings)) {
                onOpenPage(SettingsRoute.Application)
            }
            SettingsPageRow(Icons.Rounded.Info, stringResource(R.string.nav_about_settings), last = true) {
                onOpenPage(SettingsRoute.About)
            }
        }
    }
}

/** A row of a group: rounded outer corners at the group's ends, small ones between rows. */
@Composable
private fun SettingsPageRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    first: Boolean = false,
    last: Boolean = false,
    onClick: () -> Unit
) {
    val outer = 20.dp
    val inner = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (first) outer else inner, topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner, bottomEnd = if (last) outer else inner
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick)
    )
}
