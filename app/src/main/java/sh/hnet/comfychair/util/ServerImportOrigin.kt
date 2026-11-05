package sh.hnet.comfychair.util

/** Server imports must be confirmed against the same client instance and server ID. */
class ServerImportOrigin<T : Any>(private val client: T, private val serverId: String) {
    fun matches(currentClient: T?, currentServerId: String?): Boolean =
        currentClient === client && currentServerId == serverId
}
