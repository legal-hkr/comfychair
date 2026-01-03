package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import sh.hnet.comfychair.util.UuidUtils

/**
 * Represents a ComfyUI server configuration.
 */
@Immutable
data class Server(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int
) {
    companion object {
        /**
         * Create a new server with auto-generated UUID.
         */
        fun create(name: String, hostname: String, port: Int): Server {
            return Server(
                id = UuidUtils.generateRandomId(),
                name = name,
                hostname = hostname,
                port = port
            )
        }

        /**
         * Parse server from JSON object.
         * Returns null if required fields are missing.
         */
        fun fromJson(json: JSONObject): Server? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null
            val hostname = json.optString("hostname").takeIf { it.isNotEmpty() } ?: return null
            val port = json.optInt("port", -1).takeIf { it > 0 } ?: return null

            return Server(id, name, hostname, port)
        }
    }

    /**
     * Convert server to JSON object.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("hostname", hostname)
            put("port", port)
        }
    }

    /**
     * Get display string for connection (hostname:port).
     */
    fun getConnectionString(): String = "$hostname:$port"
}
