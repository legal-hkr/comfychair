package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import sh.hnet.comfychair.model.Server

/**
 * Persistent storage for server configurations.
 */
class ServerStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ServerPrefs"
        private const val KEY_SERVERS_JSON = "servers_json"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
    }

    /**
     * Get all saved servers.
     */
    fun getServers(): List<Server> {
        val json = prefs.getString(KEY_SERVERS_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                Server.fromJson(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Save all servers (replaces existing list).
     */
    fun saveServers(servers: List<Server>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS_JSON, array.toString()).apply()
    }

    /**
     * Add a new server.
     */
    fun addServer(server: Server) {
        val servers = getServers().toMutableList()
        servers.add(server)
        saveServers(servers)
    }

    /**
     * Update an existing server.
     */
    fun updateServer(server: Server) {
        val servers = getServers().map {
            if (it.id == server.id) server else it
        }
        saveServers(servers)
    }

    /**
     * Delete a server by ID.
     */
    fun deleteServer(serverId: String) {
        val servers = getServers().filter { it.id != serverId }
        saveServers(servers)

        // Clear selection if deleted server was selected
        if (getSelectedServerId() == serverId) {
            setSelectedServerId(null)
        }
    }

    /**
     * Get a server by ID.
     */
    fun getServer(serverId: String): Server? {
        return getServers().find { it.id == serverId }
    }

    /**
     * Get the currently selected server ID.
     */
    fun getSelectedServerId(): String? {
        return prefs.getString(KEY_SELECTED_SERVER_ID, null)
    }

    /**
     * Set the currently selected server ID.
     */
    fun setSelectedServerId(serverId: String?) {
        if (serverId != null) {
            prefs.edit().putString(KEY_SELECTED_SERVER_ID, serverId).apply()
        } else {
            prefs.edit().remove(KEY_SELECTED_SERVER_ID).apply()
        }
    }

    /**
     * Get the currently selected server.
     */
    fun getSelectedServer(): Server? {
        val id = getSelectedServerId() ?: return null
        return getServer(id)
    }

    /**
     * Check if a server name is already taken (case-insensitive).
     * @param excludeServerId Server ID to exclude from check (for editing)
     */
    fun isServerNameTaken(name: String, excludeServerId: String? = null): Boolean {
        return getServers().any {
            it.name.equals(name, ignoreCase = true) &&
            (excludeServerId == null || it.id != excludeServerId)
        }
    }
}
