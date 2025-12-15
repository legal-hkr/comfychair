package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Prefetch priority levels - lower value = higher priority
 */
enum class PrefetchPriority(val value: Int) {
    ADJACENT(1),    // N±1 from current
    NEARBY(2),      // N±2 from current
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

    private val imageCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(imageCacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    }

    private val videoCache: LruCache<String, ByteArray> = object : LruCache<String, ByteArray>(videoCacheSize) {
        override fun sizeOf(key: String, bytes: ByteArray): Int = bytes.size
    }

    // Prefetching infrastructure
    private val prefetchQueue = PriorityBlockingQueue<PrefetchRequest>()
    private val inProgressKeys = ConcurrentHashMap.newKeySet<String>()
    private var prefetchJobs: List<Job> = emptyList()

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
        client: ComfyUIClient
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
            imageCache.put(keyStr, it)
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

        return extractVideoThumbnail(videoBytes, context)?.also {
            thumbnailCache.put(keyStr, it)
        }
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
     */
    suspend fun fetchImage(
        key: MediaCacheKey,
        subfolder: String,
        type: String
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

            bitmap?.let { imageCache.put(keyStr, it) }
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
     * Get video URI for ExoPlayer playback.
     * Creates a temp file from cached bytes if available.
     * Returns null if bytes not cached - call fetchVideoBytes first.
     */
    suspend fun getVideoUri(key: MediaCacheKey, context: Context): Uri? {
        val bytes = videoCache.get(key.keyString) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                // Create temp file for playback
                val tempFile = File(context.cacheDir, "playback_${key.keyString.hashCode()}.mp4")
                tempFile.writeBytes(bytes)

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    tempFile
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Fetch video and return URI for playback.
     * Caches bytes and creates temp file.
     */
    suspend fun fetchVideoUri(
        key: MediaCacheKey,
        subfolder: String,
        type: String,
        context: Context
    ): Uri? {
        // Ensure bytes are cached
        fetchVideoBytes(key, subfolder, type) ?: return null

        return getVideoUri(key, context)
    }

    // ==================== PREFETCHING ====================

    /**
     * Request prefetching of adjacent items.
     * Call this when the user navigates to a new item.
     */
    fun prefetchAround(
        currentIndex: Int,
        allItems: List<PrefetchItem>
    ) {
        // Clear existing queue to prioritize new location
        prefetchQueue.clear()

        // Queue adjacent items with priority
        val indicesToPrefetch = listOf(
            currentIndex - 1 to PrefetchPriority.ADJACENT,
            currentIndex + 1 to PrefetchPriority.ADJACENT,
            currentIndex - 2 to PrefetchPriority.NEARBY,
            currentIndex + 2 to PrefetchPriority.NEARBY
        )

        for ((index, priority) in indicesToPrefetch) {
            if (index in allItems.indices) {
                val item = allItems[index]
                val keyStr = item.key.keyString

                // Skip if already cached or in progress
                if (isCached(item.key, item.isVideo) || inProgressKeys.contains(keyStr)) {
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
            videoCache.get(keyStr) != null
        } else {
            imageCache.get(keyStr) != null
        }
    }

    private fun startPrefetchWorkers() {
        prefetchJobs = List(MAX_CONCURRENT_PREFETCH) {
            scope.launch {
                while (isActive) {
                    val request = prefetchQueue.poll()

                    if (request != null) {
                        val keyStr = request.key.keyString

                        if (!isCached(request.key, request.isVideo) &&
                            inProgressKeys.add(keyStr)) {

                            try {
                                if (request.isVideo) {
                                    fetchVideoBytes(request.key, request.subfolder, request.type)
                                } else {
                                    fetchImage(request.key, request.subfolder, request.type)
                                }
                            } finally {
                                inProgressKeys.remove(keyStr)
                            }
                        }
                    } else {
                        delay(100) // Wait before checking again
                    }
                }
            }
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
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        thumbnailCache.evictAll()
        imageCache.evictAll()
        videoCache.evictAll()
        prefetchQueue.clear()
        inProgressKeys.clear()
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

    private fun extractVideoThumbnail(videoBytes: ByteArray, context: Context): Bitmap? {
        val tempFile = File(context.cacheDir, "temp_thumb_${System.currentTimeMillis()}.mp4")
        return try {
            tempFile.writeBytes(videoBytes)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0)
            retriever.release()
            bitmap
        } catch (e: Exception) {
            null
        } finally {
            tempFile.delete()
        }
    }
}
