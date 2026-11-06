package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
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
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.ui.components.SourceMedia
import sh.hnet.comfychair.ui.components.mediaOriginKey
import sh.hnet.comfychair.ui.components.readShownVideo
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.GenerationMetadataLoader
import sh.hnet.comfychair.util.MediaExport

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
                DebugLogger.w("MediaViewer", "Item list could not be read: ${e.javaClass.simpleName}")
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
    /** Items left the viewer; the server deletes them unless [id] is undone (see [MediaViewerViewModel.undoDeletion]). */
    data class DeletionPending(val id: Long, val count: Int) : MediaViewerEvent()
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

    /** A deletion shown with Undo, sent to the server once the snackbar is gone or the viewer closes. */
    private class PendingDeletion(
        val id: Long,
        val promptId: String,
        val removed: List<IndexedValue<MediaViewerItem>>,
        val previousIndex: Int
    )
    private var pendingDeletion: PendingDeletion? = null
    private var nextDeletionId = 0L

    // Track whether any items were deleted during this session
    private val _hasDeletedItems = MutableStateFlow(false)
    val hasDeletedItems: StateFlow<Boolean> = _hasDeletedItems.asStateFlow()

    /** Whether the viewer is shown over the main activity. */
    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()
    private var onClosed: ((String?) -> Unit)? = null
    /** Where a single item was opened from (its media origin), for the flight back. */
    private var singleOriginKey: String? = null

    /**
     * Open the viewer over the main activity.
     *
     * @param onClosed Called on close with the file name of the item shown last (the list shrinks on deletion)
     */
    fun open(
        context: Context,
        mode: ViewerMode,
        items: List<MediaViewerItem>,
        initialIndex: Int,
        singleBitmap: Bitmap? = null,
        singleVideoUri: Uri? = null,
        originKey: String? = null,
        onClosed: (String?) -> Unit = {}
    ) {
        // A new session: nothing from the previous one carries over
        commitPendingDeletion()
        cachedMetadata.clear()
        _currentMetadata.value = null
        _isLoadingMetadata.value = false
        _hasDeletedItems.value = false
        this.onClosed = onClosed
        singleOriginKey = originKey
        initialize(context, mode, items, initialIndex, singleBitmap, singleVideoUri)
        _isOpen.value = true
    }

    /**
     * Close the viewer: a deletion waiting for Undo moves to the gallery, which offers the Undo instead, and the opener
     * learns what was shown last.
     */
    fun close() {
        if (!_isOpen.value) return
        handOverPendingDeletion()
        val lastShown = _uiState.value.currentItem?.filename
        val deleted = _hasDeletedItems.value
        _isOpen.value = false
        _uiState.value = MediaViewerUiState()
        cachedMetadata.clear()
        _currentMetadata.value = null
        onClosed?.invoke(lastShown)
        onClosed = null
        // The gallery already dropped the deleted jobs; refresh to match the server
        if (deleted) GalleryRepository.getInstance().refresh()
    }

    private fun initialize(
        context: Context,
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
        val videoUri = state.currentVideoUri
        return when {
            // A single video is read from where it plays (RAM or a stored file)
            state.mode == ViewerMode.SINGLE && item.isVideo && videoUri != null ->
                GenerationMetadataLoader.fromVideo(context, videoUri)
            else -> GenerationMetadataLoader.fromServerFile(item.filename, item.subfolder, item.type, item.isVideo)
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
                    // Metadata is loaded again when the sheet is opened
                    DebugLogger.w("MediaViewer", "Metadata preload failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Delete the item shown. The server deletes whole jobs, so every item of its job leaves the viewer; they come back
     * on Undo. Nothing reaches the server until [commitDeletion] (or the viewer closes). The last item is deleted at
     * once and closes the viewer, as there is nothing left to show.
     */
    fun deleteCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        if (ConnectionManager.clientOrNull == null) return
        // One Undo at a time
        commitPendingDeletion()

        val removed = state.items.withIndex().filter { it.value.promptId == item.promptId }
        val remaining = state.items.filter { it.promptId != item.promptId }
        val deletion = PendingDeletion(++nextDeletionId, item.promptId, removed, state.currentIndex)
        pendingDeletion = deletion

        if (remaining.isEmpty()) {
            // Nothing left to show: the viewer closes and the gallery offers the Undo
            handOverPendingDeletion()
            viewModelScope.launch { _events.emit(MediaViewerEvent.Close) }
            return
        }
        // The item that took the deleted one's place
        val removedBefore = removed.count { it.index < state.currentIndex }
        showItems(remaining, (state.currentIndex - removedBefore).coerceIn(0, remaining.size - 1))
        viewModelScope.launch { _events.emit(MediaViewerEvent.DeletionPending(deletion.id, removed.size)) }
    }

    /** Bring back the items of a deletion that hasn't reached the server yet. */
    fun undoDeletion(id: Long) {
        val deletion = pendingDeletion?.takeIf { it.id == id } ?: return
        pendingDeletion = null
        val items = _uiState.value.items.toMutableList()
        // In ascending order, each goes back to its old position
        deletion.removed.forEach { items.add(it.index.coerceAtMost(items.size), it.value) }
        showItems(items, deletion.previousIndex.coerceIn(0, items.size - 1))
    }

    /** Send a deletion to the server: its Undo is over. */
    fun commitDeletion(id: Long) {
        if (pendingDeletion?.id == id) commitPendingDeletion()
    }

    /** Send the waiting deletion, if any, to the server: its Undo is over (or a new session starts). */
    fun commitPendingDeletion() {
        val deletion = pendingDeletion ?: return
        pendingDeletion = null
        if (ConnectionManager.clientOrNull == null) return reportNoConnection()
        _hasDeletedItems.value = true
        GalleryRepository.getInstance().deleteNow(setOf(deletion.promptId))
    }

    /** The waiting deletion, if any, to the gallery: it leaves the gallery and its Undo is offered there. */
    private fun handOverPendingDeletion() {
        val deletion = pendingDeletion ?: return
        pendingDeletion = null
        if (!GalleryRepository.getInstance().deleteWithUndo(setOf(deletion.promptId))) reportNoConnection()
    }

    /** No connection: nothing reaches the server, so say so rather than let the items come back unannounced. */
    private fun reportNoConnection() {
        applicationContext?.let { AppErrors.report(it, R.string.error_history_item_delete) }
    }

    private fun showItems(items: List<MediaViewerItem>, index: Int) {
        // Metadata is cached by index
        cachedMetadata.clear()
        _currentMetadata.value = null
        _uiState.value = _uiState.value.copy(
            items = items,
            currentIndex = index,
            currentBitmap = null,
            currentVideoUri = null,
            isLoading = true
        )
        viewModelScope.launch { _events.emit(MediaViewerEvent.ItemDeleted) }
        loadCurrentItem()
    }

    /** Media origin of the item shown: where it flies back to on close. */
    fun currentOriginKey(): String? {
        val state = _uiState.value
        val item = state.currentItem ?: return null
        return if (state.mode == ViewerMode.SINGLE) singleOriginKey else mediaOriginKey(item.promptId, item.filename)
    }

    /** A still of the item shown, already in memory (a video's frame where cached), for the flight; null if none. */
    fun currentStill(): Bitmap? {
        val state = _uiState.value
        val item = state.currentItem ?: return null
        if (state.mode == ViewerMode.SINGLE) return state.currentBitmap
        return state.currentBitmap?.takeIf { !item.isVideo } ?: MediaCache.getBitmap(item.toCacheKey())
    }

    /** The image shown, for "Use as source", fetched if it hasn't loaded yet (RAM only); null for a video. */
    suspend fun currentImage(): Bitmap? {
        val state = _uiState.value
        val item = state.currentItem ?: return null
        if (state.showsVideo()) return null
        val key = item.toCacheKey()
        return state.currentBitmap
            ?: MediaCache.getBitmap(key)
            ?: if (state.mode == ViewerMode.GALLERY) MediaCache.fetchImage(key, item.subfolder, item.type) else null
    }

    /**
     * The medium shown, for "Use as source": its image, or its video read up to the source limit (RAM only in
     * memory-first mode). Null when it can't be loaded.
     */
    suspend fun currentSourceMedia(): SourceMedia? {
        val state = _uiState.value
        if (!state.showsVideo()) return currentImage()?.let { SourceMedia.Image(it) }
        val context = applicationContext ?: return null
        state.currentVideoUri?.let { return readShownVideo(context, it) }
        val item = state.currentItem ?: return null
        if (state.mode != ViewerMode.GALLERY) return null
        return MediaCache.fetchVideoBytes(item.toCacheKey(), item.subfolder, item.type)?.let { SourceMedia.Video(it) }
    }

    override fun onCleared() {
        commitPendingDeletion()
        super.onCleared()
    }

    /** Whether the item shown is a video (single mode: also a video passed as URI). */
    private fun MediaViewerUiState.showsVideo(): Boolean =
        if (mode == ViewerMode.SINGLE) currentItem?.isVideo == true || currentVideoUri != null
        else currentItem?.isVideo == true

    fun saveCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value
        val isVideo = state.showsVideo()

        viewModelScope.launch {
            val saved = if (state.mode == ViewerMode.SINGLE) {
                if (isVideo) state.currentVideoUri?.let { MediaExport.saveVideo(context, it) } ?: false
                else state.currentBitmap?.let { MediaExport.saveImage(context, it) } ?: false
            } else {
                // Gallery mode - from the cache, else fetched from the server
                val item = state.currentItem ?: return@launch
                val key = item.toCacheKey()
                if (isVideo) MediaCache.fetchVideoBytes(key, item.subfolder, item.type)?.let { MediaExport.saveVideo(context, it) } ?: false
                else MediaCache.fetchImage(key, item.subfolder, item.type)?.let { MediaExport.saveImage(context, it) } ?: false
            }
            _events.emit(MediaViewerEvent.ShowToast(
                when {
                    isVideo && saved -> R.string.msg_video_saved_to_gallery
                    isVideo -> R.string.error_save_video
                    saved -> R.string.msg_image_saved_to_gallery
                    else -> R.string.error_save_image
                }
            ))
        }
    }

    fun shareCurrentItem() {
        val context = applicationContext ?: return
        val state = _uiState.value
        val isVideo = state.showsVideo()

        viewModelScope.launch {
            val uri = if (state.mode == ViewerMode.SINGLE) {
                if (isVideo) state.currentVideoUri?.let { MediaExport.shareFile(context, it) }
                else state.currentBitmap?.let { MediaExport.shareFile(context, it) }
            } else {
                val item = state.currentItem ?: return@launch
                val key = item.toCacheKey()
                if (isVideo) MediaCache.fetchVideoBytes(key, item.subfolder, item.type)?.let { MediaExport.shareFile(context, it) }
                else MediaCache.fetchImage(key, item.subfolder, item.type)?.let { MediaExport.shareFile(context, it) }
            }
            if (uri == null) {
                _events.emit(MediaViewerEvent.ShowToast(if (isVideo) R.string.error_share_video else R.string.error_share_image))
                return@launch
            }
            val kind = if (isVideo) MediaExport.Kind.VIDEO else MediaExport.Kind.IMAGE
            MediaExport.startShare(
                context, listOf(uri), kind.mimeType,
                context.getString(if (isVideo) R.string.share_video else R.string.share_image)
            )
        }
    }

}
