package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
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
 * Thumbnails are stored separately in thumbnailCache.
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
 * - Stable key-based caching (survives deletions)
 * - LRU eviction for memory management
 * - Smart prefetching with priority queue
 * - Session-only storage (cleared when app closes)
 *
 * All data is stored in RAM only. Videos are cached as ByteArray and
 * converted to temp file URIs on-demand for ExoPlayer playback.
 */
object MediaCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Context and client references
    private var applicationContext: Context? = null
    private var comfyUIClient: ComfyUIClient? = null
    private var isInitialized = false

    // Memory budget calculation based on max heap
    private val maxMemory = Runtime.getRuntime().maxMemory()
    private val thumbnailCacheSize = (maxMemory / 16).toInt()  // ~32MB on 512MB heap
    private val imageCacheSize = (maxMemory / 8).toInt()       // ~64MB on 512MB heap
    private val videoCacheSize = (maxMemory / 10).toInt()      // ~48MB on 512MB heap

    // LRU Caches with byte-based sizing
    private val thumbnailCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(thumbnailCacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    }

    private val imageCache: PriorityLruCache<String, Bitmap> = PriorityLruCache(
        maxSize = imageCacheSize,
        sizeOf = { _, bitmap -> bitmap.byteCount }
    )

    private val videoCache: LruCache<String, ByteArray> = object : LruCache<String, ByteArray>(videoCacheSize) {
        override fun sizeOf(key: String, bytes: ByteArray): Int = bytes.size
    }

    // Cache of video URIs (lightweight - just stores Uri references)
    private val videoUriCache = ConcurrentHashMap<String, Uri>()

    // Cache of video dimensions for VideoPlayer
    private val videoDimensionsCache = ConcurrentHashMap<String, VideoDimensions>()

    // Prefetching infrastructure
    private val prefetchQueue = PriorityBlockingQueue<PrefetchRequest>()
    private val inProgressKeys = ConcurrentHashMap.newKeySet<String>()

    private const val MAX_CONCURRENT_PREFETCH = 3

    /**
     * Initialize the cache with context and client.
     * Called after connection is established.
     */
    fun initialize(context: Context, client: ComfyUIClient) {
        applicationContext = context.applicationContext
        comfyUIClient = client
        if (!isInitialized) {
            startPrefetchWorkers()
            isInitialized = true
        }
    }

    // ==================== THUMBNAIL OPERATIONS ====================

    /**
     * Get thumbnail from cache (sync).
     */
    fun getThumbnail(key: MediaCacheKey): Bitmap? {
        return thumbnailCache.get(key.keyString)
    }

    /**
     * Put thumbnail into cache.
     */
    fun putThumbnail(key: MediaCacheKey, bitmap: Bitmap) {
        thumbnailCache.put(key.keyString, bitmap)
    }

    /**
     * Fetch thumbnail with caching.
     * Returns cached version immediately if available.
     */
    suspend fun fetchThumbnail(
        key: MediaCacheKey,
        isVideo: Boolean,
        subfolder: String,
        type: String
    ): Bitmap? {
        val keyStr = key.keyString

        // Check cache first
        thumbnailCache.get(keyStr)?.let { return it }

        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        return withContext(Dispatchers.IO) {
            if (isVideo) {
                fetchVideoThumbnail(key, subfolder, type, client, context)
            } else {
                fetchImageAsThumbnail(key, subfolder, type, client)
            }
        }
    }

    private suspend fun fetchImageAsThumbnail(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        priority: Int = PriorityLruCache.PRIORITY_DEFAULT
    ): Bitmap? {
        val bitmap = suspendCancellableCoroutine { continuation ->
            client.fetchImage(key.filename, subfolder, type) { bmp ->
                continuation.resume(bmp)
            }
        }

        bitmap?.let {
            val keyStr = key.keyString
            thumbnailCache.put(keyStr, it)
            // Also cache in full-size cache since we have it
            imageCache.put(keyStr, it, priority)
        }

        return bitmap
    }

    private suspend fun fetchVideoThumbnail(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        client: ComfyUIClient,
        context: Context
    ): Bitmap? {
        val keyStr = key.keyString

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
            videoBytes?.let { videoCache.put(keyStr, it) }
        }

        if (videoBytes == null) return null

        // Extract and cache thumbnail and dimensions
        val (dimensions, thumbnail) = extractVideoData(videoBytes, context)
        dimensions?.let { videoDimensionsCache[keyStr] = it }
        thumbnail?.let { thumbnailCache.put(keyStr, it) }

        return thumbnail
    }

    // ==================== FULL-SIZE IMAGE OPERATIONS ====================

    /**
     * Get full-size image from cache (sync).
     */
    fun getImage(key: MediaCacheKey): Bitmap? {
        return imageCache.get(key.keyString)
    }

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
        imageCache.get(keyStr)?.let { return it }

        val client = comfyUIClient ?: return null

        return withContext(Dispatchers.IO) {
            val bitmap = suspendCancellableCoroutine { continuation ->
                client.fetchImage(key.filename, subfolder, type) { bmp ->
                    continuation.resume(bmp)
                }
            }

            bitmap?.let { imageCache.put(keyStr, it, priority) }
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

            bytes?.let { videoCache.put(keyStr, it) }
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
                val (dimensions, thumbnail) = extractVideoData(bytes, context)
                dimensions?.let { videoDimensionsCache[keyStr] = it }
                thumbnail?.let { thumbnailCache.put(keyStr, it) }
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
            // Video is fully cached when URI is ready (not just bytes)
            videoUriCache.containsKey(keyStr)
        } else {
            imageCache.get(keyStr) != null
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
        thumbnailCache.remove(keyStr)
        imageCache.remove(keyStr)
        videoCache.remove(keyStr)
        videoUriCache.remove(keyStr)
        videoDimensionsCache.remove(keyStr)
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        thumbnailCache.evictAll()
        imageCache.evictAll()
        videoCache.evictAll()
        videoUriCache.clear()
        videoDimensionsCache.clear()
        prefetchQueue.clear()
        inProgressKeys.clear()
        completionCallbacks.clear()
    }

    /**
     * Reset cache (on logout/disconnect).
     */
    fun reset() {
        clearAll()
        comfyUIClient = null
        applicationContext = null
    }

    // ==================== UTILITY ====================

    /**
     * Extract video dimensions and thumbnail from video bytes.
     * Uses MediaMetadataRetriever which requires a file, so creates a temp file.
     * Returns Pair of (dimensions, thumbnail) - either may be null on error.
     */
    private fun extractVideoData(videoBytes: ByteArray, context: Context): Pair<VideoDimensions?, Bitmap?> {
        val tempFile = File(context.cacheDir, "temp_meta_${System.currentTimeMillis()}.mp4")
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
            Pair(null, null)
        } finally {
            tempFile.delete()
        }
    }

    // ==================== PRIORITY MANAGEMENT ====================

    /**
     * Update image cache priorities based on current viewing position.
     * Called when user navigates to a new item.
     *
     * @param currentKey The key of the currently viewed item
     * @param allKeys All keys in viewing order
     * @param currentIndex Index of current item in allKeys
     */
    fun updateImagePriorities(currentKey: MediaCacheKey, allKeys: List<MediaCacheKey>, currentIndex: Int) {
        val priorityMap = mutableMapOf<String, Int>()

        // Set priority based on distance from current
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

        imageCache.updateAllPriorities(priorityMap)
    }
}
