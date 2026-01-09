package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.MetadataParser
import sh.hnet.comfychair.util.Mp4MetadataExtractor
import sh.hnet.comfychair.util.PngMetadataExtractor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.io.File

/**
 * Viewer mode
 */
enum class ViewerMode {
    GALLERY,  // Launched from gallery, supports navigation
    SINGLE    // Launched from generation screen, single item only
}

/**
 * Represents an item in the media viewer
 */
@Immutable
data class MediaViewerItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("promptId", promptId)
            put("filename", filename)
            put("subfolder", subfolder)
            put("type", type)
            put("isVideo", isVideo)
            put("index", index)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MediaViewerItem {
            return MediaViewerItem(
                promptId = json.optString("promptId", ""),
                filename = json.optString("filename", ""),
                subfolder = json.optString("subfolder", ""),
                type = json.optString("type", "output"),
                isVideo = json.optBoolean("isVideo", false),
                index = json.optInt("index", 0)
            )
        }

        fun listToJson(items: List<MediaViewerItem>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(json: String): List<MediaViewerItem> {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    fromJson(array.getJSONObject(i))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * UI state for the media viewer.
 * Caching is handled by MediaCache singleton - no local cache maps needed.
 */
@Stable
data class MediaViewerUiState(
    val mode: ViewerMode = ViewerMode.GALLERY,
    val items: List<MediaViewerItem> = emptyList(),
    val currentIndex: Int = 0,
    val isUiVisible: Boolean = true,
    val isLoading: Boolean = false,
    val currentBitmap: Bitmap? = null,
    val currentVideoUri: Uri? = null
) {
    val currentItem: MediaViewerItem?
        get() = items.getOrNull(currentIndex)

    val totalCount: Int
        get() = items.size

    val canNavigateBack: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex > 0

    val canNavigateForward: Boolean
        get() = mode == ViewerMode.GALLERY && currentIndex < items.size - 1
}

/**
 * Events emitted by media viewer operations
 */
sealed class MediaViewerEvent {
    data class ShowToast(val messageResId: Int) : MediaViewerEvent()
    data object ItemDeleted : MediaViewerEvent()
    data object Close : MediaViewerEvent()
}

/**
 * ViewModel for the MediaViewer screen
 */
class MediaViewerViewModel : ViewModel() {

    private var applicationContext: Context? = null

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MediaViewerEvent>()
    val events: SharedFlow<MediaViewerEvent> = _events.asSharedFlow()

    // Metadata state
    private val cachedMetadata = mutableMapOf<Int, GenerationMetadata?>()
    private val _currentMetadata = MutableStateFlow<GenerationMetadata?>(null)
    val currentMetadata: StateFlow<GenerationMetadata?> = _currentMetadata.asStateFlow()

    private val _isLoadingMetadata = MutableStateFlow(false)
    val isLoadingMetadata: StateFlow<Boolean> = _isLoadingMetadata.asStateFlow()

    // Track whether any items were deleted during this session
    private val _hasDeletedItems = MutableStateFlow(false)
    val hasDeletedItems: StateFlow<Boolean> = _hasDeletedItems.asStateFlow()

    fun initialize(
        context: Context,
        hostname: String,
        port: Int,
        mode: ViewerMode,
        items: List<MediaViewerItem>,
        initialIndex: Int,
        singleBitmap: Bitmap? = null,
        singleVideoUri: Uri? = null
    ) {
        applicationContext = context.applicationContext

        _uiState.value = MediaViewerUiState(
            mode = mode,
            items = items,
            currentIndex = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            currentBitmap = singleBitmap,
            currentVideoUri = singleVideoUri,
            isLoading = mode == ViewerMode.GALLERY && items.isNotEmpty()
        )

        // For gallery mode, set up priorities and load current item
        if (mode == ViewerMode.GALLERY && items.isNotEmpty()) {
            val currentIdx = _uiState.value.currentIndex
            updateCachePrioritiesForIndex(currentIdx)
            triggerPrefetchForIndex(currentIdx)
            preloadMetadataForIndices()
            loadCurrentItem()
        }
    }

    fun toggleUiVisibility() {
        _uiState.value = _uiState.value.copy(isUiVisible = !_uiState.value.isUiVisible)
    }

    fun setCurrentIndex(index: Int) {
        val state = _uiState.value
        if (index < 0 || index >= state.items.size) return

        // Clear metadata when navigating
        clearCurrentMetadata()

        val item = state.items[index]
        val key = item.toCacheKey()

        // Update image cache priorities based on new position
        updateCachePrioritiesForIndex(index)

        // Trigger prefetch IMMEDIATELY for adjacent items (before loading current)
        triggerPrefetchForIndex(index)

        // Pre-load metadata for current + adjacent items
        preloadMetadataForIndices()

        // Try to get from cache immediately (same approach for images and videos)
        val cachedBitmap = if (!item.isVideo) MediaCache.getBitmap(key) else null
        val cachedVideoUri = if (item.isVideo) MediaCache.getCachedVideoUri(key) else null

        if (!item.isVideo && cachedBitmap != null) {
            // Image is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = cachedBitmap,
                currentVideoUri = null,
                isLoading = false
            )
        } else if (item.isVideo && cachedVideoUri != null) {
            // Video is cached - show immediately
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = cachedVideoUri,
                isLoading = false
            )
        } else if (MediaCache.isPrefetchInProgress(key)) {
            // Prefetch is in progress - wait for it instead of starting new fetch
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            viewModelScope.launch {
                MediaCache.awaitPrefetchCompletion(key)
                showFromCache(item, key)
            }
        } else {
            // Content not cached - load from server
            _uiState.value = state.copy(
                currentIndex = index,
                currentBitmap = null,
                currentVideoUri = null,
                isLoading = true
            )
            loadCurrentItem()
        }
    }

    /**
     * Show item from cache after prefetch completes.
     */
    private fun showFromCache(item: MediaViewerItem, key: MediaCacheKey) {
        if (item.isVideo) {
            val uri = MediaCache.getCachedVideoUri(key)
            _uiState.value = _uiState.value.copy(
                currentVideoUri = uri,
                isLoading = false
            )
        } else {
            val bitmap = MediaCache.getBitmap(key)
            _uiState.value = _uiState.value.copy(
                currentBitmap = bitmap,
                isLoading = false
            )
        }
    }

    /**
     * Update cache priorities based on current viewing position.
     * Ensures adjacent items have higher priority than distant items.
     */
    private fun updateCachePrioritiesForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val allKeys = state.items.map { it.toCacheKey() }
        MediaCache.updateNavigationPriorities(index, allKeys)
    }

    /**
     * Trigger prefetch for items around the given index.
     */
    private fun triggerPrefetchForIndex(index: Int) {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val prefetchItems = state.items.map { item ->
            MediaCache.PrefetchItem(
                key = item.toCacheKey(),
                isVideo = item.isVideo,
                subfolder = item.subfolder,
                type = item.type
            )
        }

        MediaCache.prefetchAround(index, prefetchItems)
    }

    /** Convert MediaViewerItem to MediaCacheKey */
    private fun MediaViewerItem.toCacheKey() = MediaCacheKey(promptId, filename)

    fun navigateNext() {
        val state = _uiState.value
        if (state.canNavigateForward) {
            setCurrentIndex(state.currentIndex + 1)
        }
    }

    fun navigatePrevious() {
        val state = _uiState.value
        if (state.canNavigateBack) {
            setCurrentIndex(state.currentIndex - 1)
        }
    }

    private fun loadCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val context = applicationContext ?: return

        val key = item.toCacheKey()

        // Check if already cached
        if (item.isVideo) {
            MediaCache.getCachedVideoUri(key)?.let { uri ->
                _uiState.value = state.copy(currentVideoUri = uri, isLoading = false)
                return
            }
        } else {
            MediaCache.getBitmap(key)?.let { bitmap ->
                _uiState.value = state.copy(currentBitmap = bitmap, isLoading = false)
                return
            }
        }

        _uiState.value = state.copy(isLoading = true)

        viewModelScope.launch {
            if (item.isVideo) {
                // Fetch video and create URI in one step
                val uri = MediaCache.fetchVideoUri(key, item.subfolder, item.type, context)
                _uiState.value = _uiState.value.copy(
                    currentVideoUri = uri,
                    isLoading = false
                )
            } else {
                val bitmap = MediaCache.fetchImage(key, item.subfolder, item.type)
                _uiState.value = _uiState.value.copy(
                    currentBitmap = bitmap,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Load metadata for the current item.
     * Uses cached metadata if available from pre-loading, otherwise fetches on demand.
     */
    fun loadMetadata() {
        val index = _uiState.value.currentIndex

        // Check cache first - metadata may have been pre-loaded
        if (cachedMetadata.containsKey(index)) {
            _currentMetadata.value = cachedMetadata[index]
            return
        }

        // Not cached - load now (fallback for edge cases like rapid swiping)
        _isLoadingMetadata.value = true

        viewModelScope.launch {
            val metadata = fetchMetadataForIndex(index)
            cachedMetadata[index] = metadata
            _currentMetadata.value = metadata
            _isLoadingMetadata.value = false
        }
    }

    /**
     * Clear metadata when navigating to a different item.
     */
    private fun clearCurrentMetadata() {
        _currentMetadata.value = null
    }

    /**
     * Fetches metadata for a specific item by index.
     * Handles both images (PNG) and videos (MP4).
     * Returns null if metadata cannot be extracted.
     */
    private suspend fun fetchMetadataForIndex(index: Int): GenerationMetadata? {
        val state = _uiState.value
        val item = state.items.getOrNull(index) ?: return null
        val context = applicationContext ?: return null

        return withContext(Dispatchers.IO) {
            val bytes: ByteArray? = when {
                // For SINGLE mode videos, try to read from local file first
                state.mode == ViewerMode.SINGLE && item.isVideo && state.currentVideoUri != null -> {
                    try {
                        context.contentResolver.openInputStream(state.currentVideoUri)?.use {
                            it.readBytes()
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                // For items with server file info and a client, fetch from server
                item.filename.isNotEmpty() && ConnectionManager.clientOrNull != null -> {
                    kotlin.coroutines.suspendCoroutine { continuation ->
                        ConnectionManager.clientOrNull!!.fetchRawBytes(item.filename, item.subfolder, item.type) { rawBytes, _ ->
                            continuation.resumeWith(Result.success(rawBytes))
                        }
                    }
                }
                // No source available
                else -> null
            }

            if (bytes == null) return@withContext null

            // Extract metadata based on file type
            val jsonString = if (item.isVideo) {
                Mp4MetadataExtractor.extractPromptMetadata(bytes)
            } else {
                PngMetadataExtractor.extractPromptMetadata(bytes)
            }

            // Parse the workflow JSON
            jsonString?.let { MetadataParser.parseWorkflowJson(it) }
        }
    }

    /**
     * Pre-loads metadata for current and adjacent items in the background.
     * Called when navigating to have metadata ready before user opens sheet.
     * Mirrors the same indices as MediaCache.prefetchAround() for consistency.
     */
    private fun preloadMetadataForIndices() {
        val state = _uiState.value
        if (state.mode != ViewerMode.GALLERY || state.items.isEmpty()) return

        val currentIndex = state.currentIndex
        val itemCount = state.items.size

        // Same pattern as MediaCache.prefetchAround(): current ±1, ±2
        val indicesToPreload = listOf(
            currentIndex,      // Current item (highest priority)
            currentIndex - 1,  // Adjacent
            currentIndex + 1,
            currentIndex - 2,  // Nearby
            currentIndex + 2
        ).filter { it in 0 until itemCount }
         .filter { !cachedMetadata.containsKey(it) }

        indicesToPreload.forEach { idx ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val metadata = fetchMetadataForIndex(idx)
                    cachedMetadata[idx] = metadata
                } catch (e: Exception) {
                    // Silently fail - metadata will load on-demand if needed
                }
            }
        }
    }

    fun deleteCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val client = ConnectionManager.clientOrNull ?: return

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine<Boolean> { continuation ->
                    client.deleteHistoryItem(item.promptId) { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }

            if (success) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.history_item_deleted_success))

                // Mark that items were deleted (for result reporting)
                _hasDeletedItems.value = true

                // Evict from MediaCache (uses stable key, not index)
                MediaCache.evict(item.toCacheKey())

                // Sync deletion to GalleryRepository so Gallery UI updates immediately
                GalleryRepository.getInstance().removeItemLocally(item.promptId)

                // Get fresh state after async operation
                val currentState = _uiState.value
                val currentItems = currentState.items.toMutableList()

                // Make sure we're removing the correct item
                val actualIndexToRemove = currentItems.indexOfFirst {
                    it.promptId == item.promptId && it.filename == item.filename
                }

                if (actualIndexToRemove >= 0) {
                    currentItems.removeAt(actualIndexToRemove)
                }

                if (currentItems.isEmpty()) {
                    // No more items, close viewer
                    _events.emit(MediaViewerEvent.ItemDeleted)
                    _events.emit(MediaViewerEvent.Close)
                } else {
                    // Adjust index and show next/previous item
                    val newIndex = actualIndexToRemove.coerceIn(0, currentItems.size - 1)

                    // Clear metadata cache (still uses indices)
                    cachedMetadata.clear()
                    _currentMetadata.value = null

                    _uiState.value = currentState.copy(
                        items = currentItems,
                        currentIndex = newIndex,
                        currentBitmap = null,
                        currentVideoUri = null,
                        isLoading = true
                    )
                    _events.emit(MediaViewerEvent.ItemDeleted)
                    loadCurrentItem()
                }
            } else {
                _events.emit(MediaViewerEvent.ShowToast(R.string.history_item_deleted_failed))
            }
        }
    }

    fun saveCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value

        viewModelScope.launch {
            if (state.mode == ViewerMode.SINGLE) {
                // Save from current bitmap/video
                if (state.currentItem?.isVideo == true || state.currentVideoUri != null) {
                    saveVideoFromUri(context, state.currentVideoUri)
                } else {
                    saveBitmapToGallery(context, state.currentBitmap)
                }
            } else {
                // Gallery mode - fetch and save
                val item = state.currentItem ?: return@launch
                if (item.isVideo) {
                    saveVideoFromServer(context, item)
                } else {
                    saveImageFromServer(context, item)
                }
            }
        }
    }

    private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_image))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.image_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    private suspend fun saveVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val videoBytes = context.contentResolver.openInputStream(videoUri)?.use {
                    it.readBytes()
                } ?: run {
                    _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_video))
                    return@withContext
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.video_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_video))
            }
        }
    }

    private suspend fun saveImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val bitmap = MediaCache.getBitmap(key)
                ?: MediaCache.fetchImage(key, item.subfolder, item.type)

            if (bitmap == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_image))
                return@withContext
            }

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.image_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_image))
            }
        }
    }

    private suspend fun saveVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        withContext(Dispatchers.IO) {
            // Try cache first, then fetch if needed
            val videoBytes = MediaCache.getVideoBytes(key)
                ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

            if (videoBytes == null) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_video))
                return@withContext
            }

            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let { outputUri ->
                    resolver.openOutputStream(outputUri)?.use { outputStream ->
                        outputStream.write(videoBytes)
                    }
                }

                _events.emit(MediaViewerEvent.ShowToast(R.string.video_saved_to_gallery))
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_save_video))
            }
        }
    }

    fun shareCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value

        viewModelScope.launch {
            if (state.mode == ViewerMode.SINGLE) {
                if (state.currentItem?.isVideo == true || state.currentVideoUri != null) {
                    shareVideoFromUri(context, state.currentVideoUri)
                } else {
                    shareBitmap(context, state.currentBitmap)
                }
            } else {
                val item = state.currentItem ?: return@launch
                if (item.isVideo) {
                    shareVideoFromServer(context, item)
                } else {
                    shareImageFromServer(context, item)
                }
            }
        }
    }

    private suspend fun shareBitmap(context: Context, bitmap: Bitmap?) {
        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_image))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val shareFile = File(context.cacheDir, "share_image.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "image/png"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.share_image))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_image))
            }
        }
    }

    private suspend fun shareVideoFromUri(context: Context, videoUri: Uri?) {
        if (videoUri == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_video))
            return
        }

        withContext(Dispatchers.Main) {
            try {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    type = "video/mp4"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.share_video))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_video))
            }
        }
    }

    private suspend fun shareImageFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed
        val bitmap = MediaCache.getBitmap(key)
            ?: MediaCache.fetchImage(key, item.subfolder, item.type)

        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_image))
            return
        }

        shareBitmap(context, bitmap)
    }

    private suspend fun shareVideoFromServer(context: Context, item: MediaViewerItem) {
        val key = item.toCacheKey()

        // Try cache first, then fetch if needed
        val videoBytes = MediaCache.getVideoBytes(key)
            ?: MediaCache.fetchVideoBytes(key, item.subfolder, item.type)

        if (videoBytes == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_video))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val shareFile = File(context.cacheDir, "share_video.mp4")
                shareFile.writeBytes(videoBytes)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "video/mp4"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(
                        Intent.createChooser(shareIntent, context.getString(R.string.share_video))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_video))
            }
        }
    }
}
