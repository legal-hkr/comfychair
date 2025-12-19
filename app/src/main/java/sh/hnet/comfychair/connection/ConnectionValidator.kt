package sh.hnet.comfychair.connection

import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.repository.GalleryRepository

/**
 * Validates and tracks the current server connection.
 *
 * This singleton ensures that when the connection changes (different hostname or port),
 * all cached data and client references are properly invalidated. This prevents
 * singletons like GalleryRepository and MediaCache from holding stale connections.
 *
 * Call [setConnection] in MainContainerActivity.onCreate() after getting connection
 * parameters from the intent.
 */
object ConnectionValidator {

    private var currentHostname: String = ""
    private var currentPort: Int = 0
    private var isInitialized: Boolean = false

    /**
     * Set the current connection parameters.
     * If the connection has changed from a previous session, all cached data
     * and client references are invalidated.
     *
     * @param hostname The server hostname
     * @param port The server port
     * @return true if this is a new/changed connection that triggered invalidation
     */
    @Synchronized
    fun setConnection(hostname: String, port: Int): Boolean {
        val changed = isInitialized && (hostname != currentHostname || port != currentPort)

        if (changed) {
            invalidateAll()
        }

        currentHostname = hostname
        currentPort = port
        isInitialized = true

        return changed
    }

    /**
     * Check if the given connection matches the current active connection.
     */
    fun isCurrentConnection(hostname: String, port: Int): Boolean {
        return isInitialized && hostname == currentHostname && port == currentPort
    }

    /**
     * Get the current hostname.
     */
    fun getHostname(): String = currentHostname

    /**
     * Get the current port.
     */
    fun getPort(): Int = currentPort

    /**
     * Invalidate all connection-dependent caches and singletons.
     * Called automatically when connection changes.
     */
    private fun invalidateAll() {
        // Reset GalleryRepository (also resets MediaCache internally)
        GalleryRepository.getInstance().reset()

        // Clear in-memory media state
        MediaStateHolder.clearAll()
    }

    /**
     * Force invalidation of all caches.
     * Use when manually triggering a connection reset (e.g., logout).
     */
    fun forceInvalidate() {
        invalidateAll()
        currentHostname = ""
        currentPort = 0
        isInitialized = false
    }
}
