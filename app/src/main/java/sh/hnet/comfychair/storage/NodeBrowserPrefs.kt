package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray

/**
 * The node browser's own preferences (spec §22): the node types last added from it, per server, newest first (class
 * names only: node types the server offers, nothing of the user's), and the view chosen (tree or list). Deleted
 * servers' lists go at startup ([PreferencesMaintenance]).
 */
object NodeBrowserPrefs {
    const val PREFS_NAME = "NodeBrowser"
    const val RECENT_PREFIX = "recent_"
    private const val KEY_LIST_VIEW = "list_view"
    const val MAX_RECENT = 6

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recent(context: Context, serverId: String?): List<String> {
        serverId ?: return emptyList()
        val array = runCatching { JSONArray(prefs(context).getString(RECENT_PREFIX + serverId, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
    }

    fun addRecent(context: Context, serverId: String?, classType: String) {
        serverId ?: return
        val list = (listOf(classType) + recent(context, serverId).filter { it != classType }).take(MAX_RECENT)
        prefs(context).edit().putString(RECENT_PREFIX + serverId, JSONArray(list).toString()).apply()
    }

    /** The list view (all nodes by name) rather than the tree. */
    fun isListView(context: Context): Boolean = prefs(context).getBoolean(KEY_LIST_VIEW, false)

    fun setListView(context: Context, list: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIST_VIEW, list).apply()
    }
}
