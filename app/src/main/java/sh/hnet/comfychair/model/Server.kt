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
    val port: Int,
    val authType: AuthType = AuthType.NONE
) {
    companion object {
        /**
         * Create a new server with auto-generated UUID.
         */
        fun create(name: String, hostname: String, port: Int, authType: AuthType = AuthType.NONE): Server {
            return Server(
                id = UuidUtils.generateRandomId(),
                name = name,
                hostname = hostname,
                port = port,
                authType = authType
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
            val authType = try {
                AuthType.valueOf(json.optString("authType", "NONE"))
            } catch (e: IllegalArgumentException) {
                AuthType.NONE
            }

            return Server(id, name, hostname, port, authType)
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
            put("authType", authType.name)
        }
    }

    /**
     * Get display string for connection (hostname:port).
     */
    fun getConnectionString(): String = "$hostname:$port"
}
