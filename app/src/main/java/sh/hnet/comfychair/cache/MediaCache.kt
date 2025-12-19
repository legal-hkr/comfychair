package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.coroutines.resume

/**
 * Stable cache key for media items.
 * Uses promptId + filename to survive index changes during deletions.
 */
data class MediaCacheKey(
    val promptId: String,
    val filename: String
) {
    val keyString: String get() = "${promptId}_$filename"
    override fun toString(): String = keyString
}

/**
 * Cached video dimensions.
 * Used to avoid repeated MediaMetadataRetriever calls in VideoPlayer.
 */
data class VideoDimensions(
    val width: Int,
    val height: Int
)

/**
 * Prefetch priority levels - lower value = higher priority
 */
enum class PrefetchPriority(val value: Int) {
    ADJACENT(1),    // N±1 from current
    NEARBY(2),      // N±2 from current
    DISTANT(3)      // N±3 from current
}

/**
 * Identifies which screen is currently controlling cache priorities.
 * MediaViewer takes precedence when open.
 */
enum class ActiveView {
    GALLERY,
    MEDIA_VIEWER
}

/**
 * Prefetch request with priority ordering
 */
data class PrefetchRequest(
    val key: MediaCacheKey,
    val isVideo: Boolean,
    val subfolder: String,
    val type: String,
    val priority: PrefetchPriority,
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<PrefetchRequest> {
    override fun compareTo(other: PrefetchRequest): Int {
        val priorityCompare = priority.value.compareTo(other.priority.value)
        return if (priorityCompare != 0) priorityCompare
        else timestamp.compareTo(other.timestamp)
    }
}

/**
 * Centralized in-memory media cache singleton.
 *
 * Provides shared caching between Gallery and MediaViewer with:
 * - Single bitmap cache for all images (thumbnails and full-size are the same)
 * - Priority-based eviction to protect items near current view position
 * - Smart prefetching with priority queue
 * - Session-only storage (cleared when app closes)
 *
 * All data is stored in RAM only. Videos are cached as ByteArray and
 * converted to temp file URIs on-demand for ExoPlayer playback.
 */
object MediaCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Context for temp file operations (set once when first needed)
    private var applicationContext: Context? = null
    private var prefetchWorkersStarted = false

    // Memory budget: 1/3 of max heap for bitmaps (full-res images), 1/8 for video bytes
    // Bitmaps are large (4MB+ per 1024x1024 image), so we need generous cache for smooth scrolling
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val bitmapCacheSize = maxMemoryKb / 3
    private val videoCacheSize = maxMemoryKb / 8

    // Single bitmap cache for all images (full-size for both gallery and viewer)
    private val bitmapCache = PriorityLruCache<String, Bitmap>(
        maxSize = bitmapCacheSize,
        sizeOf = { _, bitmap -> bitmap.byteCount / 1024 }
    )

    // Video bytes cache (separate data type)
    private val videoCache = PriorityLruCache<String, ByteArray>(
        maxSize = videoCacheSize,
        sizeOf = { _, bytes -> bytes.size / 1024 }
    )

    // Cache of video URIs (lightweight - just stores Uri references)
    private val videoUriCache = ConcurrentHashMap<String, Uri>()

    // Cache of video dimensions for VideoPlayer
    private val videoDimensionsCache = ConcurrentHashMap<String, VideoDimensions>()


    // Prefetching infrastructure
    private val prefetchQueue = PriorityBlockingQueue<PrefetchRequest>()
    private val inProgressKeys = ConcurrentHashMap.newKeySet<String>()

    private const val MAX_CONCURRENT_PREFETCH = 3

    // Active view tracking - MediaViewer takes precedence when open
    private var activeView = ActiveView.GALLERY

    /**
     * Set which view is currently active for priority management.
     * MediaViewer takes precedence when open.
     */
    fun setActiveView(view: ActiveView) {
        activeView = view
    }

    /**
     * Check if a specific view is currently active.
     */
    fun isActiveView(view: ActiveView): Boolean = activeView == view

    /**
     * Ensure prefetch workers are running.
     * Called when cache operations start that may need prefetching.
     */
    fun ensureInitialized(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
        if (!prefetchWorkersStarted) {
            startPrefetchWorkers()
            prefetchWorkersStarted = true
        }
    }

    // ==================== BITMAP OPERATIONS ====================

    /**
     * Get bitmap from cache (sync).
     */
    fun getBitmap(key: MediaCacheKey): Bitmap? {
        return bitmapCache.get(key.keyString)
    }

    /**
     * Put bitmap into cache with optional priority.
     */
    fun putBitmap(key: MediaCacheKey, bitmap: Bitmap, priority: Int = PriorityLruCache.PRIORITY_DEFAULT) {
        bitmapCache.put(key.keyString, bitmap, priority)
    }

    // ==================== NAVIGATION PRIORITIES ====================

    /**
     * Update bitmap priorities based on current navigation position.
     * Call this when user swipes to a new page in MediaViewer.
     *
     * @param currentIndex Index of the currently viewed item
     * @param allKeys All keys in viewing order
     */
    fun updateNavigationPriorities(currentIndex: Int, allKeys: List<MediaCacheKey>) {
        val priorityMap = mutableMapOf<String, Int>()

        for ((index, key) in allKeys.withIndex()) {
            val distance = kotlin.math.abs(index - currentIndex)
            val priority = when (distance) {
                0 -> PriorityLruCache.PRIORITY_CURRENT
                1 -> PriorityLruCache.PRIORITY_ADJACENT
                2 -> PriorityLruCache.PRIORITY_NEARBY
                3 -> PriorityLruCache.PRIORITY_DISTANT
                else -> PriorityLruCache.PRIORITY_DEFAULT
            }
            priorityMap[key.keyString] = priority
        }

        bitmapCache.updateAllPriorities(priorityMap)
    }

    /**
     * Update priorities based on gallery scroll position.
     * Uses a wider window than MediaViewer since more items are visible at once.
     *
     * @param firstVisibleIndex Index of first visible item in gallery grid
     * @param visibleItemCount Number of items currently visible on screen
     * @param allItems All gallery items for priority calculation
     * @param columnsInGrid Number of columns in the grid (for row-based calculations)
     */
    fun updateGalleryPosition(
        firstVisibleIndex: Int,
        visibleItemCount: Int,
        allItems: List<PrefetchItem>,
        columnsInGrid: Int = 2
    ) {
        if (allItems.isEmpty()) return

        val lastVisibleIndex = (firstVisibleIndex + visibleItemCount - 1).coerceAtMost(allItems.size - 1)
        val centerIndex = firstVisibleIndex + (visibleItemCount / 2)

        val priorityMap = mutableMapOf<String, Int>()

        for ((index, item) in allItems.withIndex()) {
            val distanceFromCenter = kotlin.math.abs(index - centerIndex)
            val rowDistance = distanceFromCenter / columnsInGrid

            val priority = when {
                // Visible items get highest priority
                index in firstVisibleIndex..lastVisibleIndex ->
                    PriorityLruCache.PRIORITY_CURRENT
                // ±1 row from visible
                rowDistance <= 1 -> PriorityLruCache.PRIORITY_ADJACENT
                // ±2 rows from visible
                rowDistance <= 2 -> PriorityLruCache.PRIORITY_NEARBY
                // ±3 rows from visible
                rowDistance <= 3 -> PriorityLruCache.PRIORITY_DISTANT
                else -> PriorityLruCache.PRIORITY_DEFAULT
            }
            priorityMap[item.key.keyString] = priority
        }

        bitmapCache.updateAllPriorities(priorityMap)

        // Trigger prefetch for items in buffer zone
        prefetchGalleryItems(firstVisibleIndex, visibleItemCount, columnsInGrid, allItems)
    }

    /**
     * Prefetch gallery items around visible area.
     */
    private fun prefetchGalleryItems(
        firstVisibleIndex: Int,
        visibleItemCount: Int,
        columnsInGrid: Int,
        allItems: List<PrefetchItem>
    ) {
        val bufferRows = 3
        val bufferItems = bufferRows * columnsInGrid

        val startIndex = (firstVisibleIndex - bufferItems).coerceAtLeast(0)
        val endIndex = (firstVisibleIndex + visibleItemCount + bufferItems).coerceAtMost(allItems.size)

        // Build set of keys that should be prefetched with their priorities
        val desiredPrefetches = mutableMapOf<String, Pair<PrefetchItem, PrefetchPriority>>()

        for (i in startIndex until endIndex) {
            val item = allItems[i]
            val keyStr = item.key.keyString

            // Skip if already cached
            if (isCached(item.key, item.isVideo)) continue
            // Skip if already in progress
            if (inProgressKeys.contains(keyStr)) continue

            val distanceFromFirst = kotlin.math.abs(i - firstVisibleIndex)
            val priority = when {
                i in firstVisibleIndex until (firstVisibleIndex + visibleItemCount) ->
                    PrefetchPriority.ADJACENT
                distanceFromFirst <= columnsInGrid * 2 -> PrefetchPriority.NEARBY
                else -> PrefetchPriority.DISTANT
            }

            desiredPrefetches[keyStr] = item to priority
        }

        // Merge approach: remove outdated requests, keep existing ones in range
        val iterator = prefetchQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            val keyStr = request.key.keyString
            if (!desiredPrefetches.containsKey(keyStr)) {
                // Remove item no longer in desired range
                iterator.remove()
            } else {
                // Item already queued - remove from desired to avoid re-adding
                desiredPrefetches.remove(keyStr)
            }
        }

        // Add only new prefetch requests
        for ((_, itemAndPriority) in desiredPrefetches) {
            val (item, priority) = itemAndPriority
            prefetchQueue.offer(PrefetchRequest(
                key = item.key,
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type,
                priority = priority
            ))
        }
    }

    /**
     * Initial prefetch for gallery startup.
     * Fetches first N items with high priority.
     */
    fun initialPrefetch(items: List<PrefetchItem>) {
        for (item in items) {
            val keyStr = item.key.keyString
            if (!isCached(item.key, item.isVideo) && !inProgressKeys.contains(keyStr)) {
                prefetchQueue.offer(PrefetchRequest(
                    key = item.key,
                    isVideo = item.isVideo,
                    subfolder = item.subfolder,
                    type = item.type,
                    priority = PrefetchPriority.ADJACENT
                ))
            }
        }
    }

    /**
     * Fetch bitmap with caching.
     * Returns cached version immediately if available.
     */
    suspend fun fetchBitmap(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String,
        priority: Int = PriorityLruCache.PRIORITY_DEFAULT
    ): Bitmap? {
        val keyStr = key.keyString

        // Check cache first
        bitmapCache.get(keyStr)?.let { return it }

        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        return withContext(Dispatchers.IO) {
            if (isVideo) {
                fetchVideoBitmap(key, subfolder, type, client, context, priority)
            } else {
                fetchImageBitmap(key, subfolder, type, client, priority)
            }
        }
    }

    private suspend fun fetchImageBitmap(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        priority: Int
    ): Bitmap? {
        val bitmap = suspendCancellableCoroutine { continuation ->
            client.fetchImage(key.filename, subfolder, type) { bmp ->
                continuation.resume(bmp)
            }
        }

        bitmap?.let {
            bitmapCache.put(key.keyString, it, priority)
        }

        return bitmap
    }

    private suspend fun fetchVideoBitmap(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context,
        priority: Int
    ): Bitmap? {
        val keyStr = key.keyString

        // Check if thumbnail is already cached - avoid redundant extraction
        bitmapCache.get(keyStr)?.let { return it }

        // Check if we have video bytes cached
        var videoBytes = videoCache.get(keyStr)

        if (videoBytes == null) {
            // Fetch video bytes
            videoBytes = suspendCancellableCoroutine { continuation ->
                client.fetchVideo(key.filename, subfolder, type) { bytes ->
                    continuation.resume(bytes)
                }
            }

            // Cache the video bytes
            videoBytes?.let { videoCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT) }
        }

        if (videoBytes == null) return null

        // Double-check bitmap cache (another thread might have extracted while we fetched)
        bitmapCache.get(keyStr)?.let { return it }

        // Extract and cache thumbnail and dimensions
        val (dimensions, thumbnail) = extractVideoData(keyStr, videoBytes, context)
        dimensions?.let { videoDimensionsCache[keyStr] = it }
        thumbnail?.let { bitmapCache.put(keyStr, it, priority) }

        return thumbnail
    }

    // ==================== FULL-SIZE IMAGE OPERATIONS ====================

    /**
     * Fetch full-size image with caching.
     *
     * @param priority Cache priority level (default: PRIORITY_CURRENT for direct requests)
     */
    suspend fun fetchImage(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        priority: Int = PriorityLruCache.PRIORITY_CURRENT
    ): Bitmap? {
        val keyStr = key.keyString

        // Check cache first
        bitmapCache.get(keyStr)?.let { return it }

        val client = comfyUIClient ?: return null

        return withContext(Dispatchers.IO) {
            val bitmap = suspendCancellableCoroutine { continuation ->
                client.fetchImage(key.filename, subfolder, type) { bmp ->
                    continuation.resume(bmp)
                }
            }

            bitmap?.let { bitmapCache.put(keyStr, it, priority) }
            bitmap
        }
    }

    // ==================== VIDEO OPERATIONS ====================

    /**
     * Get video bytes from memory cache (sync).
     */
    fun getVideoBytes(key: MediaCacheKey): ByteArray? {
        return videoCache.get(key.keyString)
    }

    /**
     * Fetch video bytes with caching.
     */
    suspend fun fetchVideoBytes(
        key: MediaCacheKey,
        subfolder: String,
        type: String
    ): ByteArray? {
        val keyStr = key.keyString

        // Check cache first
        videoCache.get(keyStr)?.let { return it }

        val client = comfyUIClient ?: return null

        return withContext(Dispatchers.IO) {
            val bytes = suspendCancellableCoroutine { continuation ->
                client.fetchVideo(key.filename, subfolder, type) { videoBytes ->
                    continuation.resume(videoBytes)
                }
            }

            bytes?.let { videoCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT) }
            bytes
        }
    }

    /**
     * Get cached video URI if available (sync).
     * Returns null if URI not yet created - call getVideoUri to create it.
     */
    fun getCachedVideoUri(key: MediaCacheKey): Uri? {
        return videoUriCache[key.keyString]
    }

    /**
     * Get cached video dimensions if available (sync).
     * Used by VideoPlayer to avoid repeated MediaMetadataRetriever calls.
     */
    fun getVideoDimensions(key: MediaCacheKey): VideoDimensions? {
        return videoDimensionsCache[key.keyString]
    }

    /**
     * Get video URI for ExoPlayer playback.
     * Creates a temp file from cached bytes if available, or returns cached URI.
     * Returns null if bytes not cached - call fetchVideoBytes first.
     */
    suspend fun getVideoUri(key: MediaCacheKey, context: Context): Uri? {
        val keyStr = key.keyString

        // Return cached URI if available
        videoUriCache[keyStr]?.let { return it }

        val bytes = videoCache.get(keyStr) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                // Create temp file for playback
                val tempFile = File(context.cacheDir, "playback_${keyStr.hashCode()}.mp4")
                tempFile.writeBytes(bytes)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                )

                // Cache the URI for future use
                videoUriCache[keyStr] = uri
                uri
            } catch (e: Exception) {
                Logger.e("MediaCache", "Failed to create video URI for $keyStr", e)
                null
            }
        }
    }

    /**
     * Fetch video and return URI for playback.
     * Caches bytes, extracts metadata, and creates temp file.
     */
    suspend fun fetchVideoUri(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        context: Context
    ): Uri? {
        val keyStr = key.keyString

        // Ensure bytes are cached
        val bytes = fetchVideoBytes(key, subfolder, type) ?: return null

        // Extract and cache dimensions/thumbnail if not already cached
        if (!videoDimensionsCache.containsKey(keyStr)) {
            withContext(Dispatchers.IO) {
                val (dimensions, thumbnail) = extractVideoData(keyStr, bytes, context)
                dimensions?.let { videoDimensionsCache[keyStr] = it }
                thumbnail?.let { bitmapCache.put(keyStr, it, PriorityLruCache.PRIORITY_DEFAULT) }
            }
        }

        return getVideoUri(key, context)
    }

    // ==================== PREFETCHING ====================

    // Track completion callbacks for in-progress fetches
    private val completionCallbacks = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    /**
     * Request prefetching of adjacent items.
     * Call this when the user navigates to a new item.
     * Does not clear the queue - instead reprioritizes existing requests.
     */
    fun prefetchAround(
        currentIndex: Int,
        allItems: List<PrefetchItem>
    ) {
        // Build set of keys that should be prefetched with their priorities
        val desiredPrefetches = mutableMapOf<String, Pair<PrefetchItem, PrefetchPriority>>()

        val indicesToPrefetch = listOf(
            currentIndex - 1 to PrefetchPriority.ADJACENT,
            currentIndex + 1 to PrefetchPriority.ADJACENT,
            currentIndex - 2 to PrefetchPriority.NEARBY,
            currentIndex + 2 to PrefetchPriority.NEARBY,
            currentIndex - 3 to PrefetchPriority.DISTANT,
            currentIndex + 3 to PrefetchPriority.DISTANT
        )

        for ((index, priority) in indicesToPrefetch) {
            if (index in allItems.indices) {
                val item = allItems[index]
                val keyStr = item.key.keyString
                // Skip if already cached
                if (!isCached(item.key, item.isVideo)) {
                    desiredPrefetches[keyStr] = item to priority
                }
            }
        }

        // Remove outdated requests from queue (items no longer in desired range)
        val iterator = prefetchQueue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            val keyStr = request.key.keyString
            if (!desiredPrefetches.containsKey(keyStr)) {
                iterator.remove()
            } else {
                // Item already queued - remove from desired to avoid re-adding
                desiredPrefetches.remove(keyStr)
            }
        }

        // Add new prefetch requests
        for ((keyStr, itemAndPriority) in desiredPrefetches) {
            val (item, priority) = itemAndPriority
            // Skip if already in progress
            if (inProgressKeys.contains(keyStr)) {
                continue
            }

            prefetchQueue.offer(PrefetchRequest(
                key = item.key,
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type,
                priority = priority
            ))
        }
    }

    /**
     * Check if a prefetch is currently in progress for the given key.
     */
    fun isPrefetchInProgress(key: MediaCacheKey): Boolean {
        return inProgressKeys.contains(key.keyString)
    }

    /**
     * Wait for an in-progress prefetch to complete.
     * Returns immediately if no prefetch is in progress.
     */
    suspend fun awaitPrefetchCompletion(key: MediaCacheKey) {
        val keyStr = key.keyString
        if (!inProgressKeys.contains(keyStr)) return

        suspendCancellableCoroutine { continuation ->
            val callbacks = completionCallbacks.getOrPut(keyStr) { mutableListOf() }
            val callback: () -> Unit = { continuation.resume(Unit) }
            synchronized(callbacks) {
                // Double-check in case it completed while we were setting up
                if (!inProgressKeys.contains(keyStr)) {
                    continuation.resume(Unit)
                } else {
                    callbacks.add(callback)
                }
            }

            continuation.invokeOnCancellation {
                synchronized(callbacks) {
                    callbacks.remove(callback)
                }
            }
        }
    }

    /**
     * Data class for prefetch item info
     */
    data class PrefetchItem(
        val key: MediaCacheKey,
        val isVideo: Boolean,
        val subfolder: String,
        val type: String
    )

    private fun isCached(key: MediaCacheKey, isVideo: Boolean): Boolean {
        val keyStr = key.keyString
        return if (isVideo) {
            // Video is cached when bytes are downloaded (URI can be created on-demand)
            videoCache.get(keyStr) != null
        } else {
            bitmapCache.get(keyStr) != null
        }
    }

    private fun startPrefetchWorkers() {
        repeat(MAX_CONCURRENT_PREFETCH) {
            scope.launch {
                while (isActive) {
                    val request = prefetchQueue.poll()

                    if (request != null) {
                        val keyStr = request.key.keyString

                        if (!isCached(request.key, request.isVideo) &&
                            inProgressKeys.add(keyStr)) {

                            try {
                                // Convert PrefetchPriority to PriorityLruCache priority
                                val cachePriority = when (request.priority) {
                                    PrefetchPriority.ADJACENT -> PriorityLruCache.PRIORITY_ADJACENT
                                    PrefetchPriority.NEARBY -> PriorityLruCache.PRIORITY_NEARBY
                                    PrefetchPriority.DISTANT -> PriorityLruCache.PRIORITY_DISTANT
                                }

                                if (request.isVideo) {
                                    // Fetch bytes and create URI so video is ready to play
                                    val context = applicationContext
                                    if (context != null) {
                                        fetchVideoUri(request.key, request.subfolder, request.type, context)
                                    } else {
                                        fetchVideoBytes(request.key, request.subfolder, request.type)
                                    }
                                } else {
                                    fetchImage(request.key, request.subfolder, request.type, cachePriority)
                                }
                            } finally {
                                inProgressKeys.remove(keyStr)
                                notifyPrefetchComplete(keyStr)
                            }
                        }
                    } else {
                        delay(100) // Wait before checking again
                    }
                }
            }
        }
    }

    /**
     * Notify all waiting callbacks that a prefetch has completed.
     */
    private fun notifyPrefetchComplete(keyStr: String) {
        val callbacks = completionCallbacks.remove(keyStr) ?: return
        synchronized(callbacks) {
            callbacks.forEach { it.invoke() }
        }
    }

    // ==================== CACHE MANAGEMENT ====================

    /**
     * Remove item from all caches (after deletion).
     */
    fun evict(key: MediaCacheKey) {
        val keyStr = key.keyString
        bitmapCache.remove(keyStr)
        videoCache.remove(keyStr)
        videoUriCache.remove(keyStr)
        videoDimensionsCache.remove(keyStr)
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoUriCache.clear()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()
    }

    /**
     * Clear bitmap and video thumbnail caches for refresh.
     * This forces thumbnails to be re-fetched from the server.
     * Does not clear video URIs (temp files) as they may still be in use.
     */
    fun clearForRefresh() {
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()
    }

    /**
     * Reset cache (on logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     */
    fun reset() {
        clearAll()
        applicationContext = null
        prefetchWorkersStarted = false
    }

    // ==================== UTILITY ====================

    /**
     * Extract video dimensions and thumbnail from video bytes.
     * Uses MediaMetadataRetriever which requires a file, so creates a temp file.
     * Returns Pair of (dimensions, thumbnail) - either may be null on error.
     *
     * @param keyStr Unique key string used for temp file naming to prevent race conditions
     * @param videoBytes The video bytes to extract from
     * @param context Android context for cache directory access
     */
    private fun extractVideoData(keyStr: String, videoBytes: ByteArray, context: Context): Pair<VideoDimensions?, Bitmap?> {
        // Use key hash + thread ID for unique temp filename to prevent race conditions
        val uniqueId = "${keyStr.hashCode()}_${Thread.currentThread().id}"
        val tempFile = File(context.cacheDir, "temp_meta_$uniqueId.mp4")
        return try {
            tempFile.writeBytes(videoBytes)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val thumbnail = retriever.getFrameAtTime(0)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: thumbnail?.width ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: thumbnail?.height ?: 0
            retriever.release()
            Pair(VideoDimensions(width, height), thumbnail)
        } catch (e: Exception) {
            Logger.e("MediaCache", "Failed to extract video metadata/thumbnail for $keyStr", e)
            Pair(null, null)
        } finally {
            tempFile.delete()
        }
    }
}
