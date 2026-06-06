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
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.LocalGalleryStorage
import sh.hnet.comfychair.util.DebugLogger
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

        private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")
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
     * Load gallery data in background with incremental sync.
     *
     * Sync strategy:
     * 1. Always load local index first (fast, works offline)
     * 2. If online, fetch /history from server and identify new items
     * 3. For new server items: fetch full history + download images, save locally
     * 4. For server-deleted items (not server restart): remove locally
     * 5. If server returns empty (restart scenario): preserve all local data
     * 6. Merge: local items + newly synced items
     *
     * @return true if load succeeded, false otherwise
     */
    private suspend fun loadGalleryInternal(isRefresh: Boolean): Boolean {
        val context = applicationContext
        val serverId = ConnectionManager.currentServerId

        if (isRefresh) {
            _isRefreshing.value = true
        } else {
            _isLoading.value = true
        }

        try {
            // Step 1: Always load local data first (works in both online and offline modes)
            val localItems = if (context != null) {
                withContext(Dispatchers.IO) {
                    LocalGalleryStorage.loadIndex(context)
                }
            } else {
                emptyList()
            }

            // Get pending deletions snapshot
            val deletionsSnapshot: Set<String>
            synchronized(pendingDeletions) {
                deletionsSnapshot = pendingDeletions.toSet()
            }

            // Start with local items as the base
            val mergedItems = mutableListOf<GalleryItem>()
            val localPromptIds = mutableSetOf<String>()

            // Add local items, filtering out pending deletions
            for (item in localItems) {
                if (item.promptId !in deletionsSnapshot) {
                    mergedItems.add(item)
                    localPromptIds.add(item.promptId)
                }
            }

            // Check if in offline mode - just use local data
            if (context != null && AppSettings.isOfflineMode(context)) {
                _galleryItems.value = mergedItems
                _lastRefreshTime.value = System.currentTimeMillis()
                hasLoadedOnce = true
                DebugLogger.d(TAG, "Gallery loaded from local storage: ${mergedItems.size} items (offline mode)")
                return true
            }

            val client = comfyUIClient
            if (client == null) {
                // No client available - use local data if we have it
                if (mergedItems.isNotEmpty()) {
                    _galleryItems.value = mergedItems
                    _lastRefreshTime.value = System.currentTimeMillis()
                    hasLoadedOnce = true
                    DebugLogger.d(TAG, "Gallery loaded from local storage: ${mergedItems.size} items (no server)")
                    return true
                }
                return false
            }

            // Step 2: Fetch all server prompt IDs from /history
            val allServerHistory = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchAllHistory { history ->
                        continuation.resumeWith(Result.success(history))
                    }
                }
            }

            if (allServerHistory == null) {
                // Server error - use local data if available
                if (mergedItems.isNotEmpty()) {
                    _galleryItems.value = mergedItems
                    _lastRefreshTime.value = System.currentTimeMillis()
                    hasLoadedOnce = true
                    DebugLogger.d(TAG, "Gallery loaded from local storage: ${mergedItems.size} items (server error)")
                    return true
                }
                return false
            }

            // Step 3: Parse server history to get all items and prompt IDs
            val serverItems = parseHistoryToGalleryItems(allServerHistory)
            val serverPromptIds = serverItems.map { it.promptId }.toSet()

            // Step 4: Determine which server items are new (not in local storage)
            val newPromptIds = serverPromptIds - localPromptIds
            val newItemsFromServer = serverItems.filter { it.promptId in newPromptIds }

            // Step 5: For new items, fetch full history JSON and download images
            if (newItemsFromServer.isNotEmpty() && context != null) {
                DebugLogger.i(TAG, "Found ${newItemsFromServer.size} new items to sync from server")

                // Collect unique filenames per prompt to download
                val newItemsToSync = newItemsFromServer
                    .filter { !LocalGalleryStorage.checkImageExists(context, it.filename) }

                if (newItemsToSync.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        // Download images for new items
                        for (item in newItemsToSync) {
                            try {
                                if (item.isVideo) {
                                    kotlin.coroutines.suspendCoroutine { continuation ->
                                        client.fetchVideo(item.filename, item.subfolder, item.type) { bytes, _ ->
                                            if (bytes != null) {
                                                LocalGalleryStorage.saveImage(context, item.filename, bytes)
                                            }
                                            continuation.resumeWith(kotlin.Result.success(Unit))
                                        }
                                    }
                                } else {
                                    kotlin.coroutines.suspendCoroutine { continuation ->
                                        client.fetchRawBytes(item.filename, item.subfolder, item.type) { bytes, _ ->
                                            if (bytes != null) {
                                                LocalGalleryStorage.saveImage(context, item.filename, bytes)
                                            }
                                            continuation.resumeWith(kotlin.Result.success(Unit))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLogger.w(TAG, "Failed to download image for ${item.promptId}/${item.filename}: ${e.message}")
                            }
                        }
                    }
                }

                // Save full history JSON for each new prompt
                val singleHistoryPrompts = newPromptIds.filter { promptId ->
                    allServerHistory.has(promptId)
                }
                for (promptId in singleHistoryPrompts) {
                    withContext(Dispatchers.IO) {
                        val historyEntry = allServerHistory.optJSONObject(promptId)
                        if (historyEntry != null && context != null) {
                            LocalGalleryStorage.saveHistory(context, promptId, historyEntry)
                        }
                    }
                }

                // Add new items to merged list, setting localCacheExists=true for successfully downloaded ones
                for (newItem in newItemsFromServer) {
                    val hasLocalImage = context != null && LocalGalleryStorage.checkImageExists(context, newItem.filename)
                    mergedItems.add(newItem.copy(localCacheExists = hasLocalImage))
                }
            } else {
                // No new items - just add server items with their local cache status
                for (serverItem in serverItems) {
                    if (serverItem.promptId !in localPromptIds) {
                        // Should not happen since we checked newPromptIds, but handle defensively
                        val hasLocalImage = context != null && LocalGalleryStorage.checkImageExists(context, serverItem.filename)
                        mergedItems.add(serverItem.copy(localCacheExists = hasLocalImage))
                    }
                }
            }

            // Step 6: Update local cache status for existing items
            if (context != null) {
                mergedItems.replaceAll { item ->
                    if (item.promptId in localPromptIds) {
                        item.copy(localCacheExists = LocalGalleryStorage.checkImageExists(context, item.filename))
                    } else {
                        item
                    }
                }
            }

            // Step 7: Sort merged list (newest first by timestamp, then by index)
            val sortedItems = mergedItems.sortedWith(
                compareByDescending<GalleryItem> { it.timestamp }
                    .thenByDescending { it.index }
            )

            val previousCount = _galleryItems.value.size
            _galleryItems.value = sortedItems
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery sync complete: ${sortedItems.size} items (was $previousCount, ${newPromptIds.size} new from server)")

            // Save updated index to local storage
            if (context != null) {
                withContext(Dispatchers.IO) {
                    LocalGalleryStorage.saveIndex(context, sortedItems)
                }
            }

            return true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Gallery refresh failed: ${e.message}")
            return false
        } finally {
            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    /**
     * Load gallery data from offline cache (LocalGalleryStorage).
     * Falls back to legacy GalleryMetadataCache if LocalGalleryStorage is empty.
     * @return true if cache was loaded successfully, false otherwise
     */
    private fun loadFromOfflineCache(): Boolean {
        val context = applicationContext ?: return false
        val serverId = ConnectionManager.currentServerId ?: return false

        // First try new LocalGalleryStorage (persistent gallery on external storage)
        val localItems = LocalGalleryStorage.loadIndex(context)
        if (localItems.isNotEmpty()) {
            _galleryItems.value = localItems
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery loaded from local storage: ${localItems.size} items")
            return true
        }

        // Fallback to legacy GalleryMetadataCache for backward compatibility
        val cachedItems = GalleryMetadataCache.loadMetadata(context, serverId)
        if (cachedItems != null) {
            _galleryItems.value = cachedItems
            _lastRefreshTime.value = GalleryMetadataCache.getCacheTimestamp(context, serverId)
            hasLoadedOnce = true
            DebugLogger.d(TAG, "Gallery loaded from legacy cache: ${cachedItems.size} items")
            return true
        }

        DebugLogger.w(TAG, "No offline cache available for gallery")
        return false
    }

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    /**
     * Remove an item from the local gallery list and local storage.
     * Called by MediaViewerViewModel after it deletes from server.
     *
     * @param promptId The prompt ID of the item to remove
     * @param filename Optional filename to also delete from local image storage
     */
    fun removeItemLocally(promptId: String, filename: String? = null) {
        // Remove from in-memory list
        val currentItems = _galleryItems.value.toMutableList()
        currentItems.removeAll { it.promptId == promptId }
        _galleryItems.value = currentItems

        // Clean local storage
        val context = applicationContext
        if (context != null) {
            LocalGalleryStorage.deleteHistory(context, promptId)
            if (filename != null) {
                LocalGalleryStorage.deleteImage(context, filename)
            }
            // Update index
            val updatedIndex = LocalGalleryStorage.loadIndex(context)
                .filter { it.promptId != promptId }
            if (updatedIndex.size < LocalGalleryStorage.loadIndex(context).size) {
                LocalGalleryStorage.saveIndex(context, updatedIndex)
            }
        }
    }

    /**
     * Delete an item from the gallery and local storage.
     */
    suspend fun deleteItem(item: GalleryItem): Boolean {
        val client = comfyUIClient
        val context = applicationContext

        // Add to pending deletions to prevent reappearing during concurrent refresh
        synchronized(pendingDeletions) {
            pendingDeletions.add(item.promptId)
        }

        // Remove from local list immediately for responsive UI
        val currentItems = _galleryItems.value.toMutableList()
        currentItems.removeAll { it.promptId == item.promptId }
        _galleryItems.value = currentItems

        try {
            // Delete from server if connected
            var serverSuccess = true
            if (client != null) {
                serverSuccess = withContext(Dispatchers.IO) {
                    kotlin.coroutines.suspendCoroutine { continuation ->
                        client.deleteHistoryItem(item.promptId) { success ->
                            continuation.resumeWith(Result.success(success))
                        }
                    }
                }
            }

            // Always delete from local storage
            if (context != null) {
                withContext(Dispatchers.IO) {
                    LocalGalleryStorage.deleteHistory(context, item.promptId)
                    LocalGalleryStorage.deleteImage(context, item.filename)
                    // Update index after deletion
                    val updatedIndex = LocalGalleryStorage.loadIndex(context)
                        .filter { it.promptId != item.promptId }
                    LocalGalleryStorage.saveIndex(context, updatedIndex)
                }
            }

            // Evict from media cache
            MediaCache.evict(MediaCacheKey(item.promptId, item.filename))

            return serverSuccess && context != null // Consider success if we at least cleaned local storage
        } finally {
            // Always remove from pending deletions to prevent memory leak
            synchronized(pendingDeletions) {
                pendingDeletions.remove(item.promptId)
            }
        }
    }

    /**
     * Delete multiple items by prompt IDs.
     * Removes from server, local storage, and media cache.
     */
    suspend fun deleteItems(promptIds: Set<String>): Int {
        val client = comfyUIClient
        val context = applicationContext

        // Find items to be deleted for cache eviction
        val itemsToDelete = _galleryItems.value.filter { it.promptId in promptIds }

        // Add to pending deletions to prevent reappearing during concurrent refresh
        synchronized(pendingDeletions) {
            pendingDeletions.addAll(promptIds)
        }

        // Remove from local list immediately for responsive UI
        val currentItems = _galleryItems.value.toMutableList()
        currentItems.removeAll { it.promptId in promptIds }
        _galleryItems.value = currentItems

        try {
            var successCount = 0
            for (promptId in promptIds) {
                var serverSuccess = true
                if (client != null) {
                    serverSuccess = withContext(Dispatchers.IO) {
                        kotlin.coroutines.suspendCoroutine { continuation ->
                            client.deleteHistoryItem(promptId) { success ->
                                continuation.resumeWith(Result.success(success))
                            }
                        }
                    }
                }

                // Delete from local storage regardless of server result
                if (context != null) {
                    withContext(Dispatchers.IO) {
                        LocalGalleryStorage.deleteHistory(context, promptId)
                    }
                }

                // Evict from media cache
                itemsToDelete.filter { it.promptId == promptId }.forEach { item ->
                    if (context != null) {
                        LocalGalleryStorage.deleteImage(context, item.filename)
                    }
                    MediaCache.evict(MediaCacheKey(item.promptId, item.filename))
                }

                if (serverSuccess || context != null) {
                    successCount++
                }
            }

            // Update local index after deletions
            if (context != null) {
                withContext(Dispatchers.IO) {
                    val updatedIndex = LocalGalleryStorage.loadIndex(context)
                        .filter { it.promptId !in promptIds }
                    LocalGalleryStorage.saveIndex(context, updatedIndex)
                }
            }

            return successCount
        } finally {
            // Always remove from pending deletions to prevent memory leak
            synchronized(pendingDeletions) {
                pendingDeletions.removeAll(promptIds)
            }
        }
    }

    /**
     * Clear cached data (for logout/disconnect).
     * Clears in-memory state and MediaCache.
     * Does NOT clear LocalGalleryStorage persistent data (images/metadata survive).
     * Call [clearLocalStorage] to also remove persistent gallery data.
     */
    fun clearCache() {
        DebugLogger.i(TAG, "Clearing cache")
        _galleryItems.value = emptyList()
        _lastRefreshTime.value = 0L
        hasLoadedOnce = false
        synchronized(pendingDeletions) {
            pendingDeletions.clear()
        }
        stopPeriodicRefresh()
        // Clear media cache (preserves disk cache for offline mode)
        MediaCache.reset()
    }

    /**
     * Reset repository state (for logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     * Does NOT clear LocalGalleryStorage persistent data.
     */
    fun reset() {
        DebugLogger.i(TAG, "Resetting repository")
        clearCache()
    }

    /**
     * Clear both in-memory state AND persistent local gallery storage.
     * This removes all locally cached metadata and images from
     * /sdcard/Android/data/<package>/gallery/
     * Use with caution - this cannot be undone without re-downloading from server.
     */
    fun clearLocalStorage() {
        clearCache()
        val context = applicationContext ?: return
        LocalGalleryStorage.clearAll(context)
        // Also clear legacy cache
        val serverId = ConnectionManager.currentServerId
        if (serverId != null) {
            GalleryMetadataCache.clearCache(context, serverId)
        }
        DebugLogger.i(TAG, "Local gallery storage cleared")
    }

    /**
     * Parse history JSON to gallery items.
     * Extracts timestamp from history entry for sorting.
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

            // Extract timestamp from history entry.
            // ComfyUI does NOT put a timestamp directly on the status object.
            // Instead, timestamps are inside status.messages array, e.g.:
            //   "messages": [["execution_start", {..., "timestamp": 1780716272859}],
            //                ["execution_success", {..., "timestamp": 1780716371499}]]
            // These timestamps are already in milliseconds.
            val status = promptHistory.optJSONObject("status")
            val messages = status?.optJSONArray("messages")
            val timestamp = if (messages != null && messages.length() > 0) {
                // Look for execution_success message, fallback to last message's timestamp
                var extractedTimestamp = 0L
                for (i in 0 until messages.length()) {
                    val msg = messages.optJSONArray(i) ?: continue
                    if (msg.length() >= 2) {
                        val msgType = msg.optString(0, "")
                        val msgData = msg.optJSONObject(1)
                        if (msgData != null) {
                            val t = msgData.optLong("timestamp", 0L)
                            if (t > 0L) {
                                extractedTimestamp = t
                                // Prefer execution_success as it's the completion time
                                if (msgType == "execution_success") {
                                    break
                                }
                            }
                        }
                    }
                }
                extractedTimestamp
            } else {
                0L
            }

            val nodeIds = outputs.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()
                val nodeOutput = outputs.optJSONObject(nodeId) ?: continue

                // Check for videos first
                val videos = nodeOutput.optJSONArray("videos")
                    ?: nodeOutput.optJSONArray("gifs")

                if (videos != null && videos.length() > 0) {
                    for (i in 0 until videos.length()) {
                        val videoInfo = videos.optJSONObject(i) ?: continue
                        val filename = videoInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        val subfolder = videoInfo.optString("subfolder", "")
                        val type = videoInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = true,
                            index = index++,
                            timestamp = timestamp
                        ))
                    }
                }

                // Check for images
                val images = nodeOutput.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    for (i in 0 until images.length()) {
                        val imageInfo = images.optJSONObject(i) ?: continue
                        val filename = imageInfo.optString("filename", "")
                        if (filename.isEmpty()) continue

                        // Skip if it's actually a video
                        if (VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }) {
                            val subfolder = imageInfo.optString("subfolder", "")
                            val type = imageInfo.optString("type", "output")

                            items.add(GalleryItem(
                                promptId = promptId,
                                filename = filename,
                                subfolder = subfolder,
                                type = type,
                                isVideo = true,
                                index = index++,
                                timestamp = timestamp
                            ))
                            continue
                        }

                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = false,
                            index = index++,
                            timestamp = timestamp
                        ))
                    }
                }
            }
        }

        // Sort by timestamp descending (newest first), fallback to index
        return items.sortedWith(
            compareByDescending<GalleryItem> { it.timestamp }
                .thenByDescending { it.index }
        )
    }
}
