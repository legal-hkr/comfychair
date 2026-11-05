package sh.hnet.comfychair.ui.components

import androidx.lifecycle.compose.LifecycleResumeEffect
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
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.R
import java.io.IOException

/**
 * The release notes of this version, shown once on the first start after an install or update. They come from the
 * F-Droid changelog written for the release (copied into the assets at build time, see app/build.gradle.kts), so
 * they're in English. Remembered in their own preferences, so Restore defaults doesn't show them again.
 */
object WhatsNew {
    private const val PREFS = "WhatsNewPrefs"
    private const val KEY_SHOWN = "shownVersionCode"
    private const val ASSET = "whats_new.txt"

    /** A part of the notes: its heading ("New features") and its lines, bulleted ("- …") or plain. */
    data class Section(val title: String?, val lines: List<Line>)
    data class Line(val text: String, val bullet: Boolean)

    /** This version's notes, or null when they were shown already or the build has none. */
    fun pending(context: Context): List<Section>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SHOWN, 0) == BuildConfig.VERSION_CODE) return null
        val text = try {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            return null
        }
        return parse(text).takeIf { it.isNotEmpty() }
    }

    fun markShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SHOWN, BuildConfig.VERSION_CODE).apply()
    }

    /**
     * The changelog's format: the version on the first line (the dialog's title has it already), then sections, each a
     * line ending in a colon followed by "- " items.
     */
    fun parse(text: String): List<Section> {
        val lines = text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.drop(1)
        val sections = mutableListOf<Section>()
        var title: String? = null
        var current = mutableListOf<Line>()
        fun close() {
            if (title != null || current.isNotEmpty()) sections += Section(title, current)
            title = null
            current = mutableListOf()
        }
        for (line in lines) {
            when {
                line.isEmpty() -> Unit
                line.startsWith("- ") || line.startsWith("* ") -> current += Line(line.drop(2).trim(), bullet = true)
                line.endsWith(":") -> {
                    close()
                    title = line.dropLast(1)
                }
                else -> current += Line(line, bullet = false)
            }
        }
        close()
        return sections
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
    var sections by remember { mutableStateOf<List<WhatsNew.Section>?>(null) }
    LifecycleResumeEffect(Unit) {
        sections = WhatsNew.pending(context)
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
                        section.lines.forEach { line ->
                            if (line.bullet) {
                                Row {
                                    Text("•", modifier = Modifier.padding(end = 8.dp))
                                    Text(line.text)
                                }
                            } else {
                                Text(line.text)
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
