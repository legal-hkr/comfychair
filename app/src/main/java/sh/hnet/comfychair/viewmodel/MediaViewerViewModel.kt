package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.MetadataParser
import sh.hnet.comfychair.util.Mp4MetadataExtractor
import sh.hnet.comfychair.util.PngMetadataExtractor
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
 * UI state for the media viewer
 */
data class MediaViewerUiState(
    val mode: ViewerMode = ViewerMode.GALLERY,
    val items: List<MediaViewerItem> = emptyList(),
    val currentIndex: Int = 0,
    val isUiVisible: Boolean = true,
    val isLoading: Boolean = false,
    val currentBitmap: Bitmap? = null,
    val currentVideoUri: Uri? = null,
    val cachedBitmaps: Map<Int, Bitmap> = emptyMap(),
    val cachedVideoUris: Map<Int, Uri> = emptyMap()
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

    private var comfyUIClient: ComfyUIClient? = null
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

        // Create client when hostname is provided (for gallery mode or single mode with server info)
        if (hostname.isNotEmpty()) {
            val client = ComfyUIClient(context.applicationContext, hostname, port)
            comfyUIClient = client

            // Test connection to establish protocol
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    kotlin.coroutines.suspendCoroutine { continuation ->
                        client.testConnection { success, _, _ ->
                            continuation.resumeWith(Result.success(success))
                        }
                    }
                }

                // For gallery mode, load current item after connection
                if (mode == ViewerMode.GALLERY && items.isNotEmpty()) {
                    loadCurrentItem()
                }
            }
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

        _uiState.value = state.copy(
            currentIndex = index,
            currentBitmap = state.cachedBitmaps[index],
            currentVideoUri = state.cachedVideoUris[index]
        )

        loadCurrentItem()
    }

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
        val client = comfyUIClient ?: return
        val context = applicationContext ?: return

        // Check if already cached
        if (item.isVideo && state.cachedVideoUris.containsKey(state.currentIndex)) {
            _uiState.value = state.copy(currentVideoUri = state.cachedVideoUris[state.currentIndex])
            return
        }
        if (!item.isVideo && state.cachedBitmaps.containsKey(state.currentIndex)) {
            _uiState.value = state.copy(currentBitmap = state.cachedBitmaps[state.currentIndex])
            return
        }

        _uiState.value = state.copy(isLoading = true)

        viewModelScope.launch {
            if (item.isVideo) {
                loadVideo(item, state.currentIndex, context, client)
            } else {
                loadImage(item, state.currentIndex, client)
            }

            // Prefetch adjacent items
            prefetchAdjacentItems()
        }
    }

    private suspend fun loadImage(item: MediaViewerItem, index: Int, client: ComfyUIClient) {
        val bitmap = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                    continuation.resumeWith(Result.success(bmp))
                }
            }
        }

        val state = _uiState.value
        val newCachedBitmaps = state.cachedBitmaps.toMutableMap()
        bitmap?.let { newCachedBitmaps[index] = it }

        _uiState.value = state.copy(
            isLoading = false,
            currentBitmap = if (state.currentIndex == index) bitmap else state.currentBitmap,
            cachedBitmaps = newCachedBitmaps
        )
    }

    private suspend fun loadVideo(item: MediaViewerItem, index: Int, context: Context, client: ComfyUIClient) {
        val videoBytes = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                    continuation.resumeWith(Result.success(bytes))
                }
            }
        }

        if (videoBytes == null) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            return
        }

        val uri = withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "viewer_video_${index}_${System.currentTimeMillis()}.mp4")
            tempFile.writeBytes(videoBytes)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
        }

        val state = _uiState.value
        val newCachedUris = state.cachedVideoUris.toMutableMap()
        newCachedUris[index] = uri

        _uiState.value = state.copy(
            isLoading = false,
            currentVideoUri = if (state.currentIndex == index) uri else state.currentVideoUri,
            cachedVideoUris = newCachedUris
        )
    }

    /**
     * Load metadata for the current item.
     * Extracts generation parameters from PNG/MP4 metadata.
     *
     * For SINGLE mode videos, reads metadata from local file.
     * For items with server file info, fetches from server.
     */
    fun loadMetadata() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val index = state.currentIndex
        val context = applicationContext ?: return

        // Check cache first
        if (cachedMetadata.containsKey(index)) {
            _currentMetadata.value = cachedMetadata[index]
            return
        }

        _isLoadingMetadata.value = true

        viewModelScope.launch {
            val metadata = withContext(Dispatchers.IO) {
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
                    item.filename.isNotEmpty() && comfyUIClient != null -> {
                        kotlin.coroutines.suspendCoroutine { continuation ->
                            comfyUIClient!!.fetchRawBytes(item.filename, item.subfolder, item.type) { rawBytes ->
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

    private suspend fun prefetchAdjacentItems() {
        val state = _uiState.value
        val client = comfyUIClient ?: return
        val context = applicationContext ?: return

        // Prefetch next item
        if (state.currentIndex + 1 < state.items.size) {
            val nextIndex = state.currentIndex + 1
            val nextItem = state.items[nextIndex]
            if (!state.cachedBitmaps.containsKey(nextIndex) && !state.cachedVideoUris.containsKey(nextIndex)) {
                if (nextItem.isVideo) {
                    loadVideo(nextItem, nextIndex, context, client)
                } else {
                    loadImage(nextItem, nextIndex, client)
                }
            }
        }

        // Prefetch previous item
        if (state.currentIndex - 1 >= 0) {
            val prevIndex = state.currentIndex - 1
            val prevItem = state.items[prevIndex]
            if (!state.cachedBitmaps.containsKey(prevIndex) && !state.cachedVideoUris.containsKey(prevIndex)) {
                if (prevItem.isVideo) {
                    loadVideo(prevItem, prevIndex, context, client)
                } else {
                    loadImage(prevItem, prevIndex, client)
                }
            }
        }
    }

    fun deleteCurrentItem() {
        val state = _uiState.value
        val item = state.currentItem ?: return
        val client = comfyUIClient ?: return
        val indexToDelete = state.currentIndex

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.deleteHistoryItem(item.promptId) { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }

            if (success) {
                _events.emit(MediaViewerEvent.ShowToast(R.string.history_item_deleted_success))

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

                    // IMPORTANT: Clear all caches because indices have shifted
                    // The cache uses integer indices as keys, so after deletion
                    // all indices >= deletedIndex are now wrong
                    cachedMetadata.clear()
                    _currentMetadata.value = null
                    _uiState.value = currentState.copy(
                        items = currentItems,
                        currentIndex = newIndex,
                        currentBitmap = null,
                        currentVideoUri = null,
                        cachedBitmaps = emptyMap(),
                        cachedVideoUris = emptyMap(),
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
                val inputStream = context.contentResolver.openInputStream(videoUri)
                    ?: throw Exception("Cannot open video URI")
                val videoBytes = inputStream.readBytes()
                inputStream.close()

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
        val client = comfyUIClient ?: return

        withContext(Dispatchers.IO) {
            val bitmap = kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                    continuation.resumeWith(Result.success(bmp))
                }
            }

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
        val client = comfyUIClient ?: return

        withContext(Dispatchers.IO) {
            val videoBytes = kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                    continuation.resumeWith(Result.success(bytes))
                }
            }

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
        val client = comfyUIClient ?: return

        val bitmap = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                    continuation.resumeWith(Result.success(bmp))
                }
            }
        }

        if (bitmap == null) {
            _events.emit(MediaViewerEvent.ShowToast(R.string.failed_share_image))
            return
        }

        shareBitmap(context, bitmap)
    }

    private suspend fun shareVideoFromServer(context: Context, item: MediaViewerItem) {
        val client = comfyUIClient ?: return

        val videoBytes = withContext(Dispatchers.IO) {
            kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                    continuation.resumeWith(Result.success(bytes))
                }
            }
        }

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

    override fun onCleared() {
        super.onCleared()
        comfyUIClient?.closeWebSocket()
    }
}
