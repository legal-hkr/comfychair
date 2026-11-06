package sh.hnet.comfychair.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.MediaStorageMode
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationOutputs
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.viewmodel.GalleryItem

/**
 * Repository for managing gallery data with background preloading and caching.
 * This is a singleton that persists across activities.
 */
class GalleryRepository private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Application context for accessing settings and cache
    private var applicationContext: Context? = null

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    /**
     * Initialize with application context.
     * Called when needed to access settings/cache.
     */
    fun initialize(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    // Gallery data state
    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Manual refresh triggered by user (pull-to-refresh) - shows indicator
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    // Any refresh in progress (manual or background)
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefreshTime = MutableStateFlow(0L)
    val lastRefreshTime: StateFlow<Long> = _lastRefreshTime.asStateFlow()

    // Periodic refresh job
    private var periodicRefreshJob: Job? = null

    // Track if initial load has been done
    private var hasLoadedOnce = false

    // Track items being deleted to filter them from refresh results
    private val pendingDeletions = mutableSetOf<String>()

    companion object {
        private const val TAG = "GalleryRepo"

        @Volatile
        private var instance: GalleryRepository? = null

        private const val PERIODIC_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        fun getInstance(): GalleryRepository {
            return instance ?: synchronized(this) {
                instance ?: GalleryRepository().also { instance = it }
            }
        }
    }

    /**
     * Start background preloading of gallery data.
     * Called after WebSocket connection is established, or when entering offline mode.
     */
    fun startBackgroundPreload() {
        val context = applicationContext
        val isOffline = context != null && AppSettings.isOfflineMode(context)

        // In online mode, require a client
        if (!isOffline && comfyUIClient == null) {
            return
        }

        if (_isLoading.value || _isRefreshing.value) {
            return
        }

        scope.launch {
            // Small delay to let UI settle after connection
            delay(500)
            loadGalleryInternal(isRefresh = false)
        }

        // Start periodic refresh only in online mode
        if (!isOffline) {
            startPeriodicRefresh()
        }
    }

    /**
     * Start periodic background refresh
     */
    private fun startPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = scope.launch {
            while (true) {
                delay(PERIODIC_REFRESH_INTERVAL_MS)
                if (comfyUIClient != null && !_isLoading.value && !_isRefreshing.value) {
                    loadGalleryInternal(isRefresh = true)
                }
            }
        }
    }

    /**
     * Stop periodic refresh (call when disconnecting)
     */
    fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Clears the thumbnail cache and fetches fresh data from server.
     *
     * @param onComplete Callback with success status (true if refresh succeeded)
     */
    fun manualRefresh(onComplete: (Boolean) -> Unit = {}) {
        if (_isLoading.value || _isRefreshing.value) {
            onComplete(false)
            return
        }

        scope.launch {
            _isManualRefreshing.value = true

            // Clear thumbnail cache to force re-fetch
            MediaCache.clearForRefresh()

            val success = loadGalleryInternal(isRefresh = true)

            _isManualRefreshing.value = false
            onComplete(success)
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() {
        if (_isLoading.value || _isRefreshing.value) {
            DebugLogger.d(TAG, "Refresh skipped - already in progress (loading=${_isLoading.value}, refreshing=${_isRefreshing.value})")
            return
        }

        if (comfyUIClient == null) {
            DebugLogger.w(TAG, "Refresh skipped - no client available")
            return
        }

        DebugLogger.d(TAG, "Starting background refresh")
        scope.launch {
            loadGalleryInternal(isRefresh = true)
        }
    }

    /**
     * Load gallery data in background.
     * @return true if load succeeded, false otherwise
     */
    private suspend fun loadGalleryInternal(isRefresh: Boolean): Boolean {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId

        // Check if in offline mode - load from cache instead
        if (context != null && AppSettings.isOfflineMode(context)) {
            return loadFromOfflineCache()
        }

        val client = comfyUIClient ?: run {
            return false
        }

        if (isRefresh) {
            _isRefreshing.value = true
        } else {
            _isLoading.value = true
        }

        try {
            val historyJson = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchAllHistory { history ->
                        continuation.resumeWith(Result.success(history))
                    }
                }
            }

            if (historyJson == null) {
                _isLoading.value = false
                _isRefreshing.value = false
                return false
            }

            // Get pending deletions snapshot
            val deletionsSnapshot: Set<String>
            synchronized(pendingDeletions) {
                deletionsSnapshot = pendingDeletions.toSet()
            }

            var items = parseHistoryToGalleryItems(historyJson)

            // Filter out pending deletions to prevent reappearing
            if (deletionsSnapshot.isNotEmpty()) {
                items = items.filter { it.promptId !in deletionsSnapshot }
            }

            val previousCount = _galleryItems.value.size
            _galleryItems.value = items
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery refresh complete: ${items.size} items (was $previousCount)")

            // Cache gallery metadata for offline mode, which needs storage on the device; the
            // memory modes leave no trace of the generation history on the device
            if (context != null && serverId != null &&
                AppSettings.getMediaStorageMode(context) == MediaStorageMode.DEVICE) {
                withContext(Dispatchers.IO) {
                    GalleryMetadataCache.saveMetadata(context, serverId, items)
                }
            }

            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Gallery refresh failed: ${Obfuscator.error(e)}")
            return false
        } finally {
            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    /**
     * Load gallery data from offline cache.
     * @return true if cache was loaded successfully, false otherwise
     */
    private fun loadFromOfflineCache(): Boolean {
        val context = applicationContext ?: return false
        val serverId = ConnectionManager.currentServerId ?: return false

        val cachedItems = GalleryMetadataCache.loadMetadata(context, serverId)
        if (cachedItems != null) {
            _galleryItems.value = cachedItems
            _lastRefreshTime.value = GalleryMetadataCache.getCacheTimestamp(context, serverId)
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery loaded from offline cache: ${cachedItems.size} items")
            return true
        }

        DebugLogger.w(TAG, "No offline cache available for gallery")
        return false
    }

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    // Deletions: jobs leave the gallery at once; one deletion at a time waits for its Undo before reaching the server

    /** A deletion waiting for its Undo, for the gallery's snackbar. */
    data class UndoableDeletion(val id: Long, val itemCount: Int)

    private class PendingRemoval(val id: Long, val promptIds: Set<String>, val removed: List<IndexedValue<GalleryItem>>)

    private var pendingRemoval: PendingRemoval? = null
    private var nextRemovalId = 0L
    private val _undoableDeletion = MutableStateFlow<UndoableDeletion?>(null)
    val undoableDeletion: StateFlow<UndoableDeletion?> = _undoableDeletion.asStateFlow()

    /**
     * Take jobs out of the gallery, to be deleted on the server once their Undo is over ([commitDeletion], or the next
     * deletion). A deletion still waiting is sent first. False without a connection: nothing could reach the server.
     */
    @Synchronized
    fun deleteWithUndo(promptIds: Set<String>): Boolean {
        if (promptIds.isEmpty()) return true
        if (comfyUIClient == null) return false
        commitPendingDeletion()
        val removed = takeOut(promptIds)
        val removal = PendingRemoval(++nextRemovalId, promptIds, removed)
        pendingRemoval = removal
        _undoableDeletion.value = UndoableDeletion(removal.id, removed.size.coerceAtLeast(promptIds.size))
        return true
    }

    /** Bring back the items of a deletion that hasn't reached the server yet, where they were. */
    @Synchronized
    fun undoDeletion(id: Long) {
        val removal = pendingRemoval?.takeIf { it.id == id } ?: return
        pendingRemoval = null
        _undoableDeletion.value = null
        synchronized(pendingDeletions) { pendingDeletions.removeAll(removal.promptIds) }
        val items = _galleryItems.value.toMutableList()
        // In ascending order, each goes back to its old position
        removal.removed.forEach { items.add(it.index.coerceAtMost(items.size), it.value) }
        _galleryItems.value = items
    }

    /** A deletion's Undo is over: send it to the server. */
    @Synchronized
    fun commitDeletion(id: Long) {
        if (pendingRemoval?.id == id) commitPendingDeletion()
    }

    /** Send the deletion waiting for its Undo, if any, to the server. */
    @Synchronized
    fun commitPendingDeletion() {
        val removal = pendingRemoval ?: return
        pendingRemoval = null
        _undoableDeletion.value = null
        deleteOnServer(removal.promptIds, removal.removed.map { it.value })
    }

    /** Delete jobs on the server now (their Undo, if any, was offered elsewhere); they leave the gallery at once. */
    fun deleteNow(promptIds: Set<String>) {
        if (promptIds.isEmpty()) return
        deleteOnServer(promptIds, takeOut(promptIds).map { it.value })
    }

    /** Jobs out of the gallery, kept out of refreshes until their deletion is settled; returns what was removed. */
    private fun takeOut(promptIds: Set<String>): List<IndexedValue<GalleryItem>> {
        synchronized(pendingDeletions) { pendingDeletions.addAll(promptIds) }
        val items = _galleryItems.value
        _galleryItems.value = items.filter { it.promptId !in promptIds }
        return items.withIndex().filter { it.value.promptId in promptIds }
    }

    /**
     * Delete jobs on the server and drop their media from the cache. Jobs that couldn't be deleted are still on the
     * server: the user is told and the gallery is refreshed, so they come back.
     */
    private fun deleteOnServer(promptIds: Set<String>, items: List<GalleryItem>) {
        scope.launch {
            val client = comfyUIClient
            var failed = 0
            try {
                for (promptId in promptIds) {
                    val success = client != null && withContext(Dispatchers.IO) {
                        kotlin.coroutines.suspendCoroutine { continuation ->
                            client.deleteHistoryItem(promptId) { continuation.resumeWith(Result.success(it)) }
                        }
                    }
                    if (success) {
                        items.filter { it.promptId == promptId }.forEach { MediaCache.evict(MediaCacheKey(it.promptId, it.filename)) }
                    } else {
                        failed++
                    }
                }
            } finally {
                synchronized(pendingDeletions) { pendingDeletions.removeAll(promptIds) }
            }
            if (failed > 0) {
                DebugLogger.w(TAG, "$failed of ${promptIds.size} deletions failed")
                applicationContext?.let { context ->
                    withContext(Dispatchers.Main) {
                        val message = if (promptIds.size == 1) R.string.error_history_item_delete else R.string.error_some_items_failed_to_delete
                        AppErrors.report(context, message)
                    }
                }
                refresh()
            }
        }
    }

    /**
     * Clear cached data (for logout/disconnect)
     * Note: Uses MediaCache.reset() to preserve disk cache for offline mode.
     * User can still clear disk cache manually via Settings → Clear Cache.
     */
    fun clearCache() {
        DebugLogger.i(TAG, "Clearing cache")
        _galleryItems.value = emptyList()
        _lastRefreshTime.value = 0L
        hasLoadedOnce = false
        synchronized(pendingDeletions) {
            pendingDeletions.clear()
        }
        // A deletion waiting for Undo belongs to the session that ends: dropped, not sent
        synchronized(this) {
            pendingRemoval = null
            _undoableDeletion.value = null
        }
        stopPeriodicRefresh()
        // Clear media cache (preserves disk cache for offline mode)
        MediaCache.reset()
    }

    /**
     * Reset repository state (for logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     */
    fun reset() {
        DebugLogger.i(TAG, "Resetting repository")
        clearCache()
    }

    /**
     * Parse history JSON to gallery items.
     * Does NOT fetch bitmaps - those are loaded lazily via MediaCache.
     */
    private fun parseHistoryToGalleryItems(historyJson: JSONObject): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        var index = 0

        val promptIds = historyJson.keys()
        while (promptIds.hasNext()) {
            val promptId = promptIds.next()
            val promptHistory = historyJson.optJSONObject(promptId) ?: continue
            val outputs = promptHistory.optJSONObject("outputs") ?: continue

            // The gallery lists every file, temporary previews included
            for (output in GenerationOutputs.parse(outputs, promptId, preferSaved = false)) {
                items.add(GalleryItem(
                    promptId = promptId,
                    filename = output.filename,
                    subfolder = output.subfolder,
                    type = output.type,
                    isVideo = output.isVideo,
                    index = index++
                ))
            }
        }

        // Sort by index descending (newest first)
        return items.sortedByDescending { it.index }
    }
}
