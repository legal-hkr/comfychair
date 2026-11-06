package sh.hnet.comfychair.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.io.IOException
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.Changelog

/**
 * The release notes of this version, shown once on the first start after an install or update. They come from the
 * F-Droid changelogs written for the release (copied into the assets at build time, see app/build.gradle.kts): in the
 * phone's language where one was written, otherwise in English. Remembered in their own preferences, so Restore
 * defaults doesn't show them again.
 */
object WhatsNew {
    private const val PREFS = "WhatsNewPrefs"
    private const val KEY_SHOWN = "shownVersionCode"
    private const val ASSET_DIR = "whats_new"
    private const val DEFAULT_LOCALE = "en-US"

    /** This version's notes, or null when the build has none. */
    fun changelog(context: Context): Changelog? {
        val files = try {
            context.assets.list(ASSET_DIR)?.map { it.removeSuffix(".txt") }.orEmpty()
        } catch (_: IOException) {
            return null
        }
        if (DEFAULT_LOCALE !in files) return null
        val locale = context.resources.configuration.locales[0]
        // The exact language and region (pt-BR), else the language in any region (pl for pl-PL), else English
        val name = files.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
            ?: files.firstOrNull { it.substringBefore('-').equals(locale.language, ignoreCase = true) }
            ?: DEFAULT_LOCALE
        return try {
            context.assets.open("$ASSET_DIR/$name.txt").bufferedReader().use { Changelog.parse(it.readText()) }
        } catch (_: IOException) {
            null
        }
    }

    /** This version's notes, or null when they were shown already or the build has none. */
    fun pending(context: Context): Changelog? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SHOWN, 0) == BuildConfig.VERSION_CODE) return null
        return changelog(context)?.takeIf { it.sections.isNotEmpty() }
    }

    fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SHOWN, BuildConfig.VERSION_CODE).apply()
    }
}

/**
 * Shows this version's notes once (first start after an install or update); closing them marks them seen. Used by the
 * login screen and the main screens: each checks again when it comes to the front, so notes closed in one don't
 * reappear in the other.
 */
@Composable
fun WhatsNewDialog() {
    val context = LocalContext.current
    var sections by remember { mutableStateOf<List<Changelog.Section>?>(null) }
    LifecycleResumeEffect(Unit) {
        sections = WhatsNew.pending(context)?.sections
        onPauseOrDispose { }
    }
    val shown = sections ?: return
    val title = stringResource(R.string.title_whats_new, BuildConfig.VERSION_NAME)
    val close = {
        WhatsNew.markShown(context)
        sections = null
    }
    AlertDialog(
        onDismissRequest = close,
        icon = { Icon(Icons.Rounded.NewReleases, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                shown.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        section.title?.let {
                            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        section.blocks.forEach { block ->
                            when (block) {
                                is Changelog.Block.Item -> Row {
                                    Text("•", modifier = Modifier.padding(end = 8.dp))
                                    Text(block.text)
                                }
                                is Changelog.Block.Paragraph -> Text(block.text)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.button_dismiss)) } },
        modifier = Modifier.semantics { paneTitle = title }
    )
}
