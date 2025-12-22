package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.io.FileOutputStream
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
 * Centralized media cache singleton for Gallery and MediaViewer.
 *
 * Supports two caching modes:
 * - Memory-first (default): In-memory caching with priority-based eviction and prefetching
 * - Disk-first: Write to disk cache, read from disk when needed (minimal RAM for low-end devices)
 *
 * Provides:
 * - Single bitmap cache for all images (thumbnails and full-size are the same)
 * - Priority-based eviction to protect items near current view position
 * - Smart prefetching with priority queue
 */
object MediaCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    // Context for temp file operations (set once when first needed)
    private var applicationContext: Context? = null
    private var prefetchWorkersStarted = false

    // Caching mode - true for memory-first (default), false for disk-first
    private var isMemoryFirstMode = true

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

    // Disk cache directory name
    private const val DISK_CACHE_DIR = "gallery_cache"

    /**
     * Set the caching mode.
     * @param enabled true for memory-first (default), false for disk-first
     */
    fun setMemoryFirstMode(enabled: Boolean) {
        if (isMemoryFirstMode == enabled) return  // No change

        if (!enabled) {
            // Switching TO disk-first: clear in-memory caches
            // Gallery content is fetched from server on-demand, no migration needed
            bitmapCache.evictAll()
            videoCache.evictAll()
            videoUriCache.clear()
            videoDimensionsCache.clear()
            prefetchQueue.clear()
            inProgressKeys.clear()
            completionCallbacks.clear()
        }
        // Switching TO memory-first: no action needed
        // Content will be fetched on-demand and cached in memory

        isMemoryFirstMode = enabled
    }

    /**
     * Check if memory-first mode is enabled.
     */
    fun isMemoryFirstMode(): Boolean = isMemoryFirstMode

    /**
     * Get the disk cache directory.
     */
    private fun getDiskCacheDir(context: Context): File {
        return File(context.cacheDir, DISK_CACHE_DIR).apply { mkdirs() }
    }

    /**
     * Get the disk cache file for a given key.
     */
    private fun getDiskCacheFile(context: Context, key: MediaCacheKey, isVideo: Boolean): File {
        val ext = if (isVideo) "mp4" else "png"
        return File(getDiskCacheDir(context), "${key.keyString}.$ext")
    }

    /**
     * Check if an item exists in disk cache.
     */
    private fun existsInDiskCache(context: Context, key: MediaCacheKey, isVideo: Boolean): Boolean {
        return getDiskCacheFile(context, key, isVideo).exists()
    }

    /**
     * Read a bitmap from disk cache.
     */
    private fun readBitmapFromDisk(context: Context, key: MediaCacheKey): Bitmap? {
        return try {
            val file = getDiskCacheFile(context, key, false)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Write a bitmap to disk cache.
     */
    private fun writeBitmapToDisk(context: Context, key: MediaCacheKey, bitmap: Bitmap) {
        try {
            val file = getDiskCacheFile(context, key, false)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
            // Ignore write failures
        }
    }

    /**
     * Read video bytes from disk cache.
     */
    private fun readVideoBytesFromDisk(context: Context, key: MediaCacheKey): ByteArray? {
        return try {
            val file = getDiskCacheFile(context, key, true)
            if (file.exists()) {
                file.readBytes()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Write video bytes to disk cache.
     */
    private fun writeVideoToDisk(context: Context, key: MediaCacheKey, bytes: ByteArray) {
        try {
            val file = getDiskCacheFile(context, key, true)
            file.writeBytes(bytes)
        } catch (_: Exception) {
            // Ignore write failures
        }
    }

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
     * In disk-first mode, checks disk cache first then fetches from server and saves to disk.
     */
    suspend fun fetchBitmap(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String,
        priority: Int = PriorityLruCache.PRIORITY_DEFAULT
    ): Bitmap? {
        val keyStr = key.keyString
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        if (isMemoryFirstMode) {
            // Memory-first: check memory cache first
            bitmapCache.get(keyStr)?.let { return it }

            return withContext(Dispatchers.IO) {
                if (isVideo) {
                    fetchVideoBitmap(key, subfolder, type, client, context, priority)
                } else {
                    fetchImageBitmap(key, subfolder, type, client, priority)
                }
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                // Check disk cache first
                if (!isVideo) {
                    readBitmapFromDisk(context, key)?.let { return@withContext it }
                } else {
                    // For video, check if we have cached video and extract thumbnail
                    if (existsInDiskCache(context, key, true)) {
                        val videoBytes = readVideoBytesFromDisk(context, key)
                        if (videoBytes != null) {
                            val (_, thumbnail) = extractVideoData(keyStr, videoBytes, context)
                            return@withContext thumbnail
                        }
                    }
                }

                // Fetch from server
                if (isVideo) {
                    fetchVideoBitmapDiskFirst(key, subfolder, type, client, context)
                } else {
                    fetchImageBitmapDiskFirst(key, subfolder, type, client, context)
                }
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

    // Disk-first versions of fetch methods

    private suspend fun fetchImageBitmapDiskFirst(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context
    ): Bitmap? {
        val bitmap = suspendCancellableCoroutine { continuation ->
            client.fetchImage(key.filename, subfolder, type) { bmp ->
                continuation.resume(bmp)
            }
        }

        // Write to disk cache (don't keep in memory)
        bitmap?.let { writeBitmapToDisk(context, key, it) }

        return bitmap
    }

    private suspend fun fetchVideoBitmapDiskFirst(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context
    ): Bitmap? {
        val keyStr = key.keyString

        // Fetch video bytes
        val videoBytes = suspendCancellableCoroutine<ByteArray?> { continuation ->
            client.fetchVideo(key.filename, subfolder, type) { bytes ->
                continuation.resume(bytes)
            }
        } ?: return null

        // Write video to disk cache
        writeVideoToDisk(context, key, videoBytes)

        // Extract and return thumbnail (don't cache dimensions in memory for disk-first)
        val (_, thumbnail) = extractVideoData(keyStr, videoBytes, context)
        return thumbnail
    }

    // ==================== FULL-SIZE IMAGE OPERATIONS ====================

    /**
     * Fetch full-size image with caching.
     * In disk-first mode, checks disk cache first then fetches from server and saves to disk.
     *
     * @param priority Cache priority level (default: PRIORITY_CURRENT for direct requests)
     */
    suspend fun fetchImage(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        priority: Int = PriorityLruCache.PRIORITY_CURRENT
    ): Bitmap? {
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        if (isMemoryFirstMode) {
            // Memory-first: check memory cache first
            bitmapCache.get(key.keyString)?.let { return it }

            return withContext(Dispatchers.IO) {
                val bitmap = suspendCancellableCoroutine { continuation ->
                    client.fetchImage(key.filename, subfolder, type) { bmp ->
                        continuation.resume(bmp)
                    }
                }

                bitmap?.let { bitmapCache.put(key.keyString, it, priority) }
                bitmap
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                readBitmapFromDisk(context, key)?.let { return@withContext it }

                val bitmap = suspendCancellableCoroutine { continuation ->
                    client.fetchImage(key.filename, subfolder, type) { bmp ->
                        continuation.resume(bmp)
                    }
                }

                bitmap?.let { writeBitmapToDisk(context, key, it) }
                bitmap
            }
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
     * In disk-first mode, checks disk cache first then fetches from server and saves to disk.
     */
    suspend fun fetchVideoBytes(
        key: MediaCacheKey,
        subfolder: String,
        type: String
    ): ByteArray? {
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        if (isMemoryFirstMode) {
            // Memory-first: check memory cache first
            videoCache.get(key.keyString)?.let { return it }

            return withContext(Dispatchers.IO) {
                val bytes = suspendCancellableCoroutine { continuation ->
                    client.fetchVideo(key.filename, subfolder, type) { videoBytes ->
                        continuation.resume(videoBytes)
                    }
                }

                bytes?.let { videoCache.put(key.keyString, it, PriorityLruCache.PRIORITY_DEFAULT) }
                bytes
            }
        } else {
            // Disk-first: check disk cache first, fetch and save to disk if not found
            return withContext(Dispatchers.IO) {
                readVideoBytesFromDisk(context, key)?.let { return@withContext it }

                val bytes = suspendCancellableCoroutine { continuation ->
                    client.fetchVideo(key.filename, subfolder, type) { videoBytes ->
                        continuation.resume(videoBytes)
                    }
                }

                bytes?.let { writeVideoToDisk(context, key, it) }
                bytes
            }
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
     * In memory-first mode, creates a temp file from cached bytes.
     * In disk-first mode, returns URI directly to the cached file.
     * Returns null if bytes not cached - call fetchVideoBytes first.
     */
    suspend fun getVideoUri(key: MediaCacheKey, context: Context): Uri? {
        val keyStr = key.keyString

        if (isMemoryFirstMode) {
            // Memory-first: return cached URI if available
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
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            // Disk-first: return URI directly to the cached file
            return withContext(Dispatchers.IO) {
                try {
                    val file = getDiskCacheFile(context, key, true)
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
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
        if (isMemoryFirstMode) {
            // Memory-first: check in-memory cache
            val keyStr = key.keyString
            return if (isVideo) {
                videoCache.get(keyStr) != null
            } else {
                bitmapCache.get(keyStr) != null
            }
        } else {
            // Disk-first: check disk cache
            val context = applicationContext ?: return false
            return existsInDiskCache(context, key, isVideo)
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
     * In disk-first mode, also removes from disk cache.
     */
    fun evict(key: MediaCacheKey) {
        val keyStr = key.keyString
        bitmapCache.remove(keyStr)
        videoCache.remove(keyStr)
        videoUriCache.remove(keyStr)
        videoDimensionsCache.remove(keyStr)

        // In disk-first mode, also remove from disk
        if (!isMemoryFirstMode) {
            applicationContext?.let { context ->
                try {
                    getDiskCacheFile(context, key, false).delete()
                    getDiskCacheFile(context, key, true).delete()
                } catch (_: Exception) {
                    // Ignore deletion failures
                }
            }
        }
    }

    /**
     * Clear all caches (memory and disk).
     */
    fun clearAll() {
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoUriCache.clear()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()

        // Also clear disk cache
        applicationContext?.let { context ->
            try {
                getDiskCacheDir(context).deleteRecursively()
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }
    }

    /**
     * Clear bitmap and video thumbnail caches for refresh.
     * This forces thumbnails to be re-fetched from the server.
     * In disk-first mode, also clears the disk cache.
     */
    fun clearForRefresh() {
        bitmapCache.evictAll()
        videoCache.evictAll()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()

        // In disk-first mode, also clear disk cache
        if (!isMemoryFirstMode) {
            applicationContext?.let { context ->
                try {
                    getDiskCacheDir(context).deleteRecursively()
                } catch (_: Exception) {
                    // Ignore deletion failures
                }
            }
        }
    }

    /**
     * Reset cache (on logout/disconnect).
     * Called by ConnectionManager when disconnecting.
     */
    fun reset() {
        clearAll()
        applicationContext = null
        prefetchWorkersStarted = false
        isMemoryFirstMode = true  // Reset to default
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
        val uniqueId = "${keyStr.hashCode()}_${Thread.currentThread().threadId()}"
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
        } catch (_: Exception) {
            Pair(null, null)
        } finally {
            tempFile.delete()
        }
    }
}
