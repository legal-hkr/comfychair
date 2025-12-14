package sh.hnet.comfychair.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * Repository for managing gallery data with background preloading and caching.
 * This is a singleton that persists across activities.
 */
class GalleryRepository private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var comfyUIClient: ComfyUIClient? = null
    private var applicationContext: Context? = null

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

    companion object {
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
     * Initialize the repository with context and client.
     * Should be called after connection is established.
     */
    fun initialize(context: Context, client: ComfyUIClient) {
        applicationContext = context.applicationContext
        comfyUIClient = client
    }

    /**
     * Start background preloading of gallery data.
     * Called after WebSocket connection is established.
     */
    fun startBackgroundPreload() {
        if (comfyUIClient == null || applicationContext == null) {
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

        // Start periodic refresh
        startPeriodicRefresh()
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
     * Shows loading indicator.
     */
    fun manualRefresh() {
        if (_isLoading.value || _isRefreshing.value) {
            return
        }

        scope.launch {
            _isManualRefreshing.value = true
            loadGalleryInternal(isRefresh = true)
            _isManualRefreshing.value = false
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Called after generation completes, periodically, or when returning from other screens.
     */
    fun refresh() {
        if (_isLoading.value || _isRefreshing.value) {
            return
        }

        scope.launch {
            loadGalleryInternal(isRefresh = true)
        }
    }

    /**
     * Load gallery data in background
     */
    private suspend fun loadGalleryInternal(isRefresh: Boolean) {
        val client = comfyUIClient ?: return
        val context = applicationContext ?: return

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
                return
            }

            val items = parseHistoryToGalleryItems(historyJson, context)
            _galleryItems.value = items
            _lastRefreshTime.value = System.currentTimeMillis()
            hasLoadedOnce = true
        } catch (e: Exception) {
            // Failed to load gallery
        } finally {
            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    /**
     * Check if gallery data is available (has been loaded at least once)
     */
    fun hasData(): Boolean = hasLoadedOnce && _galleryItems.value.isNotEmpty()

    /**
     * Delete an item from the gallery
     */
    suspend fun deleteItem(item: GalleryItem): Boolean {
        val client = comfyUIClient ?: return false

        val success = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.deleteHistoryItem(item.promptId) { success ->
                    continuation.resumeWith(Result.success(success))
                }
            }
        }

        if (success) {
            // Remove from local list
            val currentItems = _galleryItems.value.toMutableList()
            currentItems.removeAll { it.promptId == item.promptId }
            _galleryItems.value = currentItems
        }

        return success
    }

    /**
     * Delete multiple items by prompt IDs
     */
    suspend fun deleteItems(promptIds: Set<String>): Int {
        val client = comfyUIClient ?: return 0

        var successCount = 0
        for (promptId in promptIds) {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.deleteHistoryItem(promptId) { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }
            if (success) successCount++
        }

        // Remove deleted items from local list
        if (successCount > 0) {
            val currentItems = _galleryItems.value.toMutableList()
            currentItems.removeAll { it.promptId in promptIds }
            _galleryItems.value = currentItems
        }

        return successCount
    }

    /**
     * Clear cached data (for logout/disconnect)
     */
    fun clearCache() {
        _galleryItems.value = emptyList()
        _lastRefreshTime.value = 0L
        hasLoadedOnce = false
        stopPeriodicRefresh()
    }

    /**
     * Reset repository state (for logout)
     */
    fun reset() {
        clearCache()
        comfyUIClient = null
        applicationContext = null
    }

    private suspend fun parseHistoryToGalleryItems(
        historyJson: JSONObject,
        context: Context
    ): List<GalleryItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<GalleryItem>()
        var index = 0

        val promptIds = historyJson.keys()
        while (promptIds.hasNext()) {
            val promptId = promptIds.next()
            val promptHistory = historyJson.optJSONObject(promptId) ?: continue
            val outputs = promptHistory.optJSONObject("outputs") ?: continue

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

                        // Get thumbnail for video
                        val thumbnail = getVideoThumbnail(context, filename, subfolder, type)

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = true,
                            thumbnail = thumbnail,
                            index = index++
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
                            val thumbnail = getVideoThumbnail(context, filename, subfolder, type)

                            items.add(GalleryItem(
                                promptId = promptId,
                                filename = filename,
                                subfolder = subfolder,
                                type = type,
                                isVideo = true,
                                thumbnail = thumbnail,
                                index = index++
                            ))
                            continue
                        }

                        val subfolder = imageInfo.optString("subfolder", "")
                        val type = imageInfo.optString("type", "output")

                        // Get thumbnail for image
                        val thumbnail = getImageThumbnail(filename, subfolder, type)

                        items.add(GalleryItem(
                            promptId = promptId,
                            filename = filename,
                            subfolder = subfolder,
                            type = type,
                            isVideo = false,
                            thumbnail = thumbnail,
                            index = index++
                        ))
                    }
                }
            }
        }

        // Sort by index descending (newest first)
        items.sortedByDescending { it.index }
    }

    private suspend fun getImageThumbnail(
        filename: String,
        subfolder: String,
        type: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        kotlin.coroutines.suspendCoroutine { continuation ->
            comfyUIClient?.fetchImage(filename, subfolder, type) { bitmap ->
                continuation.resumeWith(Result.success(bitmap))
            } ?: continuation.resumeWith(Result.success(null))
        }
    }

    private suspend fun getVideoThumbnail(
        context: Context,
        filename: String,
        subfolder: String,
        type: String
    ): Bitmap? = withContext(Dispatchers.IO) {
        // Fetch video and extract thumbnail
        val videoBytes: ByteArray? = kotlin.coroutines.suspendCoroutine { continuation ->
            comfyUIClient?.fetchVideo(filename, subfolder, type) { bytes ->
                continuation.resumeWith(Result.success(bytes))
            } ?: continuation.resumeWith(Result.success(null))
        }
        if (videoBytes == null) return@withContext null

        // Save to temp file
        val tempFile = File(context.cacheDir, "temp_video_thumb_${System.currentTimeMillis()}.mp4")
        try {
            tempFile.writeBytes(videoBytes)

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0)
            retriever.release()

            tempFile.delete()
            bitmap
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }
}
