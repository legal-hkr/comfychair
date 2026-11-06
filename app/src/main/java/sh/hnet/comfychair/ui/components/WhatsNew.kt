package sh.hnet.comfychair.ui.components

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.util.Changelog

/**
 * This version's release notes, from the F-Droid changelogs written for the release (copied into the assets at build
 * time, see app/build.gradle.kts): in the phone's language where one was written, otherwise in English. Shown on the
 * What's new page (Settings › About); after an update, a one-time snackbar on the first main screen offers it. Kept in
 * their own preferences, so Restore defaults doesn't bring the snackbar or the New badge back.
 */
object WhatsNew {
    private const val PREFS = "WhatsNewPrefs"
    // Version code of the previous start
    private const val KEY_LAST_RUN = "lastRunVersionCode"
    // Version code the What's new page was opened for (the About row's New badge until then)
    private const val KEY_SEEN = "seenVersionCode"
    // Version code whose update snackbar is still to be shown
    private const val KEY_NOTICE = "noticeVersionCode"
    // Written by versions up to 0.9.0 when their What's new dialog was closed: they ran before
    private const val KEY_LEGACY_SHOWN = "shownVersionCode"
    private const val ASSET_DIR = "whats_new"
    private const val DEFAULT_LOCALE = "en-US"

    /** Release notes as written ([text]) and as read ([changelog]). */
    class Notes(val text: String, val changelog: Changelog) {
        /** The version they name, else the app's. */
        val version: String get() = changelog.version ?: BuildConfig.VERSION_NAME.removePrefix("v")
    }

    /** This version's notes, or null when the build has none. */
    fun notes(context: Context): Notes? {
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
            val text = context.assets.open("$ASSET_DIR/$name.txt").bufferedReader().use { it.readText() }
            Notes(text, Changelog.parse(text))
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Notes this start of the app: after an update (from a version that ran before, or an install that was updated)
     * the snackbar is due, unless the page was opened already; a fresh install gets neither the snackbar nor the badge.
     * Called by both activities; only the first call after an install or update changes anything.
     */
    fun recordStart(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        val last = prefs.getInt(KEY_LAST_RUN, 0)
        if (last == current) return
        val updated = last != 0 || prefs.contains(KEY_LEGACY_SHOWN) || wasUpdated(context)
        prefs.edit().apply {
            putInt(KEY_LAST_RUN, current)
            if (!updated) {
                putInt(KEY_SEEN, current)
            } else if (prefs.getInt(KEY_SEEN, 0) != current && (notes(context)?.changelog?.itemCount ?: 0) > 0) {
                putInt(KEY_NOTICE, current)
            }
        }.apply()
    }

    /** The version to announce in the update snackbar, once: null when it's not due or was shown already. */
    fun takeUpdateNotice(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTICE, 0) != BuildConfig.VERSION_CODE) return null
        prefs.edit().remove(KEY_NOTICE).apply()
        if (isSeen(context)) return null
        return notes(context)?.version
    }

    /** Whether the What's new page was opened for this version (the About row's New badge until then). */
    fun isSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEEN, 0) == BuildConfig.VERSION_CODE

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SEEN, BuildConfig.VERSION_CODE).apply()
    }

    // An installed app that was updated since (for versions before the last-start record)
    private fun wasUpdated(context: Context): Boolean = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        info.lastUpdateTime != info.firstInstallTime
    } catch (_: Exception) {
        false
    }
}
