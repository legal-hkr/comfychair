package sh.hnet.comfychair.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import sh.hnet.comfychair.storage.AppSettings

/**
 * Helpers that keep prompts and generated media out of system surfaces outside the app.
 */
object PrivacyUtils {

    /**
     * Copy text that may contain prompts. Marked as sensitive, so the system clipboard preview does not show its content.
     */
    fun copySensitiveText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    /**
     * In memory-only mode the system must not store a screenshot of the activity (which may show generated media) for the recents screen.
     * Call from onResume, so a mode change made in settings applies when the user returns.
     */
    fun applyRecentsScreenshotPolicy(activity: Activity) {
        activity.setRecentsScreenshotEnabled(!AppSettings.isMediaCacheDisabled(activity))
    }
}
