@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.WhatsNew
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsLinkRow
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.util.Changelog

/**
 * This version's release notes, as written for the release: each section's heading, then one card with its items and
 * paragraphs. Nothing to act on but the link to all releases. Opening the page marks the notes seen (the About row's
 * New badge goes).
 */
@Composable
fun WhatsNewSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val notes = remember { WhatsNew.notes(context) }
    LaunchedEffect(Unit) { WhatsNew.markSeen(context) }

    SettingsPage(
        title = stringResource(R.string.whats_new_title),
        onNavigateBack = onNavigateBack,
        subtitle = notes?.let { stringResource(R.string.label_about_version, it.version) }
    ) {
        if (notes != null) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val sections = notes.changelog.sections
                    if (sections.isEmpty()) {
                        // Nothing recognised: the file as it is
                        NotesCard(listOf(Changelog.Block.Paragraph(notes.text.trim())))
                    } else {
                        sections.forEach { section -> NotesSection(section) }
                    }
                }
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsLinkRow(
                    shapes = shapes,
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.whats_new_all_releases),
                    supporting = stringResource(R.string.whats_new_on_github),
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_ADDRESS))) }
                )
            }
        }
    }
}

@Composable
private fun NotesSection(section: Changelog.Section) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        section.title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 6.dp, top = 4.dp)
                    .semantics { heading() }
            )
        }
        NotesCard(section.blocks)
    }
}

/** A section's items (with a dot) and paragraphs, in one card. */
@Composable
private fun NotesCard(blocks: List<Changelog.Block>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics { collectionInfo = CollectionInfo(rowCount = blocks.size, columnCount = 1) }
        ) {
            blocks.forEach { block ->
                when (block) {
                    is Changelog.Block.Item -> NotesItem(block.text)
                    is Changelog.Block.Paragraph -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesItem(text: String) {
    val style = MaterialTheme.typography.bodyLarge
    // The dot sits in the middle of the first line
    val dotTop = with(LocalDensity.current) { (style.lineHeight.toDp() - DOT_SIZE) / 2 }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(top = dotTop)
                .size(DOT_SIZE)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Text(text = text, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

private val DOT_SIZE = 6.dp
private const val RELEASES_ADDRESS = "https://github.com/legal-hkr/comfychair/releases"
