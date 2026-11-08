package sh.hnet.comfychair.storage

import android.content.Context

/**
 * The node browser's own preferences (spec §22): the view chosen (tree or list).
 */
object NodeBrowserPrefs {
    const val PREFS_NAME = "NodeBrowser"
    private const val KEY_LIST_VIEW = "list_view"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The list view (all nodes by name) rather than the tree. */
    fun isListView(context: Context): Boolean = prefs(context).getBoolean(KEY_LIST_VIEW, false)

    fun setListView(context: Context, list: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIST_VIEW, list).apply()
    }
}
