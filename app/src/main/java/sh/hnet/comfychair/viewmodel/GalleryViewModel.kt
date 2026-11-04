package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.MediaExport

/**
 * Represents a gallery item (image or video).
 * Bitmaps are stored in MediaCache, not in this data class.
 */
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0 // For sorting
) {
    /** Create a cache key for this item */
    fun toCacheKey() = MediaCacheKey(promptId, filename)
}

/**
 * UI state for the Gallery screen
 */
data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedItems: Set<String> = emptySet(), // Set of "${promptId}_${filename}" keys
    val isSelectionMode: Boolean = false
)

/**
 * Events emitted by gallery operations
 */
sealed class GalleryEvent {
    data class ShowToast(val messageResId: Int) : GalleryEvent()
    data class ShowMedia(val item: GalleryItem, val bitmap: Bitmap?, val videoUri: Uri?) : GalleryEvent()
}

/**
 * ViewModel for the Gallery screen.
 * Uses GalleryRepository for data management and background loading.
 */
class GalleryViewModel : ViewModel() {

    // Constants
    companion object {
        private const val TAG = "Gallery"
    }

    // State
    private val repository = GalleryRepository.getInstance()

    // Selection state (local to this ViewModel)
    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    // Combine repository state with local selection state
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    init {
        // Observe repository state and combine with local selection state
        viewModelScope.launch {
            combine(
                repository.galleryItems,
                repository.isLoading,
                repository.isManualRefreshing,
                _selectedItems,
                _isSelectionMode
            ) { items, isLoading, isManualRefreshing, selectedItems, isSelectionMode ->
                GalleryUiState(
                    items = items,
                    isLoading = isLoading,
                    isRefreshing = isManualRefreshing, // Only show indicator for manual refresh
                    selectedItems = selectedItems,
                    isSelectionMode = isSelectionMode
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun initialize() {
        // MediaCache is used for all fetch operations
        // Repository is already initialized by GenerationViewModel
    }

    /**
     * Load gallery - delegates to repository.
     * If repository already has data, this will return immediately with cached data.
     * The repository handles background refreshing.
     */
    fun loadGallery() {
        DebugLogger.d(TAG, "Loading gallery")
        // Repository already has data from background preload, no need to reload
        // unless explicitly refreshed by user
        if (!repository.hasData()) {
            repository.refresh()
        }
    }

    /**
     * Manual refresh triggered by user (pull-to-refresh).
     * Shows the refresh indicator and displays a Toast on completion.
     */
    fun manualRefresh() {
        DebugLogger.i(TAG, "Manual refresh")
        repository.manualRefresh { success ->
            viewModelScope.launch {
                if (success) {
                    DebugLogger.d(TAG, "Refresh successful")
                    _events.emit(GalleryEvent.ShowToast(R.string.msg_gallery_refresh_success))
                } else {
                    DebugLogger.w(TAG, "Refresh failed")
                    _events.emit(GalleryEvent.ShowToast(R.string.error_gallery_refresh))
                }
            }
        }
    }

    /**
     * Background refresh (silent, no indicator).
     * Used when returning from other screens or after external changes.
     */
    fun refresh() {
        DebugLogger.d(TAG, "Background refresh")
        repository.refresh()
    }

    fun deleteItem(item: GalleryItem) {
        DebugLogger.i(TAG, "Deleting item: ${item.promptId}")
        viewModelScope.launch {
            val success = repository.deleteItem(item)
            if (success) {
                DebugLogger.d(TAG, "Delete successful")
                _events.emit(GalleryEvent.ShowToast(R.string.msg_history_item_deleted_success))
            } else {
                DebugLogger.w(TAG, "Delete failed")
                _events.emit(GalleryEvent.ShowToast(R.string.error_history_item_delete))
            }
        }
    }

    // Selection mode functions

    private fun getItemKey(item: GalleryItem): String = "${item.promptId}_${item.filename}"

    fun toggleSelection(item: GalleryItem) {
        val key = getItemKey(item)
        val currentSelected = _selectedItems.value.toMutableSet()

        if (currentSelected.contains(key)) {
            currentSelected.remove(key)
        } else {
            currentSelected.add(key)
        }

        _selectedItems.value = currentSelected
        _isSelectionMode.value = currentSelected.isNotEmpty()
    }

    fun isItemSelected(item: GalleryItem): Boolean {
        return _selectedItems.value.contains(getItemKey(item))
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
        _isSelectionMode.value = false
    }

    fun enterSelectionMode() {
        _isSelectionMode.value = true
    }

    fun deleteSelected() {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            // Get unique prompt IDs (multiple items can share the same promptId)
            val promptIds = selectedItems.map { it.promptId }.distinct().toSet()
            val successCount = repository.deleteItems(promptIds)
            val failCount = promptIds.size - successCount

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_items_deleted_success))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.msg_some_items_failed_to_delete))
            }

            // Clear selection after delete
            clearSelection()
        }
    }

    fun getSelectedItems(): List<GalleryItem> {
        val selectedKeys = _selectedItems.value
        return _uiState.value.items.filter { getItemKey(it) in selectedKeys }
    }

    fun saveSelectedToGallery(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            val failCount = selectedItems.count { item ->
                val key = MediaCacheKey(item.promptId, item.filename)
                val saved = if (item.isVideo) {
                    MediaCache.fetchVideoBytes(key, item.subfolder, item.type)?.let { MediaExport.saveVideo(context, it) }
                } else {
                    MediaCache.fetchImage(key, item.subfolder, item.type)?.let { MediaExport.saveImage(context, it) }
                }
                saved != true
            }
            _events.emit(GalleryEvent.ShowToast(
                if (failCount == 0) R.string.msg_items_saved_to_gallery else R.string.msg_some_items_failed_to_save
            ))
            clearSelection()
        }
    }

    fun shareSelected(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            val uris = selectedItems.mapNotNull { item ->
                val key = MediaCacheKey(item.promptId, item.filename)
                if (item.isVideo) {
                    MediaCache.fetchVideoBytes(key, item.subfolder, item.type)?.let { MediaExport.shareFile(context, it) }
                } else {
                    MediaCache.fetchImage(key, item.subfolder, item.type)?.let { MediaExport.shareFile(context, it) }
                }
            }
            if (uris.isEmpty()) {
                _events.emit(GalleryEvent.ShowToast(R.string.error_share_items))
                return@launch
            }
            val mimeType = when {
                selectedItems.all { it.isVideo } -> "video/*"
                selectedItems.none { it.isVideo } -> "image/*"
                else -> "*/*"
            }
            MediaExport.startShare(context, uris, mimeType, context.getString(R.string.button_share))
            clearSelection()
        }
    }

}
