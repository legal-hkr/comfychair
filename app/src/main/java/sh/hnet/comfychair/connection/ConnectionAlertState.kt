package sh.hnet.comfychair.connection

/**
 * State holder for connection alert dialog.
 * Single source of truth owned by ConnectionManager.
 */
data class ConnectionAlertState(
    val failureType: ConnectionFailure,
    val hasOfflineCache: Boolean
)
