package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.util.LicenseText

/**
 * The app's licence, GPL-3.0, as the FSF publishes it (its standalone page in the assets, always in English: only the
 * original is binding, which a line in the app's language says). Laid out as What's new: headings, then the text
 * between them in cards; selectable. Example notices scroll sideways, never wrap.
 */
@Composable
fun LicenseSettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val blocks by produceState<List<LicenseText.Block>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(ASSET).bufferedReader().use { LicenseText.parse(it.readText()) } }.getOrNull()
        }
    }

    SettingsPage(
        title = stringResource(R.string.title_about_license),
        onNavigateBack = onNavigateBack,
        subtitle = stringResource(R.string.label_about_license_name)
    ) {
        Text(
            stringResource(R.string.msg_license_english_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        blocks?.let { list ->
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    groups(list).forEach { (heading, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            heading?.let { LicenseHeading(it) }
                            if (body.isNotEmpty()) LicenseCard(body)
                        }
                    }
                }
            }
        }
    }
}

/** Each heading with the text that follows it, up to the next heading. */
private fun groups(blocks: List<LicenseText.Block>): List<Pair<LicenseText.Block.Heading?, List<LicenseText.Block>>> {
    val result = mutableListOf<Pair<LicenseText.Block.Heading?, MutableList<LicenseText.Block>>>()
    blocks.forEach { block ->
        if (block is LicenseText.Block.Heading) result.add(block to mutableListOf())
        else (result.lastOrNull() ?: (null to mutableListOf<LicenseText.Block>()).also { result.add(it) }).second.add(block)
    }
    return result
}

@Composable
private fun LicenseHeading(heading: LicenseText.Block.Heading) {
    val (style, color) = when (heading.level) {
        1 -> MaterialTheme.typography.titleLarge to MaterialTheme.colorScheme.onSurface
        2 -> MaterialTheme.typography.titleMedium to MaterialTheme.colorScheme.primary
        else -> MaterialTheme.typography.titleSmall to MaterialTheme.colorScheme.primary
    }
    Text(
        heading.text,
        style = style,
        color = color,
        textAlign = if (heading.level == 1) TextAlign.Center else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 4.dp)
            .semantics { heading() }
    )
}

@Composable
private fun LicenseCard(blocks: List<LicenseText.Block>) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is LicenseText.Block.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    is LicenseText.Block.Item -> LicenseItem(block.text)
                    is LicenseText.Block.Preformatted -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                    is LicenseText.Block.Heading -> Unit
                }
            }
        }
    }
}

/**
 * A list item: the licence letters its items itself ("a)", "b)"…), so no dot; the letter hangs, the text wraps beside
 * it.
 */
@Composable
private fun LicenseItem(text: String) {
    val style = MaterialTheme.typography.bodyMedium
    val marker = ITEM_MARKER.find(text)
    if (marker == null) {
        Text(text, style = style, modifier = Modifier.padding(start = 12.dp))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
        Text(marker.groupValues[1], style = style, color = MaterialTheme.colorScheme.primary)
        Text(text.substring(marker.range.last + 1), style = style)
    }
}

private val ITEM_MARKER = Regex("""^([a-z]\))\s+""")
private const val ASSET = "licenses/gpl-3.0.html"
