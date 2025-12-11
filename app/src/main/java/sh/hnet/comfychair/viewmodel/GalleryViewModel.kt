package sh.hnet.comfychair.viewmodel

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import java.io.File

/**
 * Represents a gallery item (image or video)
 */
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val thumbnail: Bitmap? = null,
    val index: Int = 0 // For sorting
)

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
 * ViewModel for the Gallery screen
 */
class GalleryViewModel : ViewModel() {

    private var comfyUIClient: ComfyUIClient? = null
    private var applicationContext: Context? = null

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events.asSharedFlow()

    companion object {
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov")
    }

    fun initialize(context: Context, client: ComfyUIClient) {
        applicationContext = context.applicationContext
        comfyUIClient = client
    }

    fun loadGallery() {
        val client = comfyUIClient ?: return
        val context = applicationContext ?: return

        _uiState.value = _uiState.value.copy(isLoading = true)

        viewModelScope.launch {
            val historyJson = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchAllHistory { history ->
                        continuation.resumeWith(Result.success(history))
                    }
                }
            }

            if (historyJson == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false)
                _events.emit(GalleryEvent.ShowToast(R.string.error_failed_fetch_history))
                return@launch
            }

            val items = parseHistoryToGalleryItems(historyJson, context)
            _uiState.value = _uiState.value.copy(
                items = items,
                isLoading = false,
                isRefreshing = false
            )
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true)
        loadGallery()
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
            println("Failed to extract video thumbnail: ${e.message}")
            tempFile.delete()
            null
        }
    }

    fun deleteItem(item: GalleryItem) {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.deleteHistoryItem(item.promptId) { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }

            if (success) {
                // Remove from local list
                val currentItems = _uiState.value.items.toMutableList()
                currentItems.removeAll { it.promptId == item.promptId }
                _uiState.value = _uiState.value.copy(items = currentItems)
                _events.emit(GalleryEvent.ShowToast(R.string.history_item_deleted_success))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.history_item_deleted_failed))
            }
        }
    }

    fun saveImageToGallery(context: Context, item: GalleryItem) {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val bitmap = kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                        continuation.resumeWith(Result.success(bmp))
                    }
                }

                if (bitmap == null) {
                    _events.emit(GalleryEvent.ShowToast(R.string.failed_save_image))
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

                    _events.emit(GalleryEvent.ShowToast(R.string.image_saved_to_gallery))
                } catch (e: Exception) {
                    _events.emit(GalleryEvent.ShowToast(R.string.failed_save_image))
                }
            }
        }
    }

    fun saveVideoToGallery(context: Context, item: GalleryItem) {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val videoBytes = kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                        continuation.resumeWith(Result.success(bytes))
                    }
                }

                if (videoBytes == null) {
                    _events.emit(GalleryEvent.ShowToast(R.string.failed_save_video))
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

                    _events.emit(GalleryEvent.ShowToast(R.string.video_saved_to_gallery))
                } catch (e: Exception) {
                    _events.emit(GalleryEvent.ShowToast(R.string.failed_save_video))
                }
            }
        }
    }

    fun fetchFullImage(item: GalleryItem, onResult: (Bitmap?) -> Unit) {
        val client = comfyUIClient ?: run {
            onResult(null)
            return
        }

        client.fetchImage(item.filename, item.subfolder, item.type) { bitmap ->
            onResult(bitmap)
        }
    }

    fun fetchVideoUri(context: Context, item: GalleryItem, onResult: (Uri?) -> Unit) {
        val client = comfyUIClient ?: run {
            onResult(null)
            return
        }

        viewModelScope.launch {
            val videoBytes = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                        continuation.resumeWith(Result.success(bytes))
                    }
                }
            }

            if (videoBytes == null) {
                onResult(null)
                return@launch
            }

            // Save to temp file
            val tempFile = File(context.cacheDir, "gallery_video_${System.currentTimeMillis()}.mp4")
            withContext(Dispatchers.IO) {
                tempFile.writeBytes(videoBytes)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )

            onResult(uri)
        }
    }

    fun shareImage(context: Context, item: GalleryItem) {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                        continuation.resumeWith(Result.success(bmp))
                    }
                }
            }

            if (bitmap == null) {
                _events.emit(GalleryEvent.ShowToast(R.string.failed_share_image))
                return@launch
            }

            try {
                // Save to cache for sharing
                val shareFile = File(context.cacheDir, "share_image.png")
                withContext(Dispatchers.IO) {
                    shareFile.outputStream().use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "image/png"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_image)
                    )
                )
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.failed_share_image))
            }
        }
    }

    fun shareVideo(context: Context, item: GalleryItem) {
        val client = comfyUIClient ?: return

        viewModelScope.launch {
            val videoBytes = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                        continuation.resumeWith(Result.success(bytes))
                    }
                }
            }

            if (videoBytes == null) {
                _events.emit(GalleryEvent.ShowToast(R.string.failed_share_video))
                return@launch
            }

            try {
                val shareFile = File(context.cacheDir, "share_video.mp4")
                withContext(Dispatchers.IO) {
                    shareFile.writeBytes(videoBytes)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    type = "video/mp4"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_video)
                    )
                )
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.failed_share_video))
            }
        }
    }

    // Selection mode functions

    private fun getItemKey(item: GalleryItem): String = "${item.promptId}_${item.filename}"

    fun toggleSelection(item: GalleryItem) {
        val key = getItemKey(item)
        val currentSelected = _uiState.value.selectedItems.toMutableSet()

        if (currentSelected.contains(key)) {
            currentSelected.remove(key)
        } else {
            currentSelected.add(key)
        }

        _uiState.value = _uiState.value.copy(
            selectedItems = currentSelected,
            isSelectionMode = currentSelected.isNotEmpty()
        )
    }

    fun isItemSelected(item: GalleryItem): Boolean {
        return _uiState.value.selectedItems.contains(getItemKey(item))
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedItems = emptySet(),
            isSelectionMode = false
        )
    }

    fun deleteSelected() {
        val client = comfyUIClient ?: return
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var failCount = 0

            // Get unique prompt IDs (multiple items can share the same promptId)
            val promptIds = selectedItems.map { it.promptId }.distinct()

            for (promptId in promptIds) {
                val success = withContext(Dispatchers.IO) {
                    kotlin.coroutines.suspendCoroutine { continuation ->
                        client.deleteHistoryItem(promptId) { success ->
                            continuation.resumeWith(Result.success(success))
                        }
                    }
                }
                if (success) successCount++ else failCount++
            }

            // Remove deleted items from local list
            if (successCount > 0) {
                val deletedPromptIds = promptIds.toSet()
                val currentItems = _uiState.value.items.toMutableList()
                currentItems.removeAll { it.promptId in deletedPromptIds }
                _uiState.value = _uiState.value.copy(items = currentItems)
            }

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.items_deleted_success))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.some_items_failed_to_delete))
            }

            // Clear selection after delete
            clearSelection()
        }
    }

    fun getSelectedItems(): List<GalleryItem> {
        val selectedKeys = _uiState.value.selectedItems
        return _uiState.value.items.filter { getItemKey(it) in selectedKeys }
    }

    fun saveSelectedToGallery(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var failCount = 0

            for (item in selectedItems) {
                val success = if (item.isVideo) {
                    saveVideoToGalleryInternal(context, item)
                } else {
                    saveImageToGalleryInternal(context, item)
                }
                if (success) successCount++ else failCount++
            }

            // Show result toast
            if (failCount == 0) {
                _events.emit(GalleryEvent.ShowToast(R.string.items_saved_to_gallery))
            } else {
                _events.emit(GalleryEvent.ShowToast(R.string.some_items_failed_to_save))
            }

            // Clear selection after save
            clearSelection()
        }
    }

    private suspend fun saveImageToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        val client = comfyUIClient ?: return false

        return withContext(Dispatchers.IO) {
            val bitmap = kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                    continuation.resumeWith(Result.success(bmp))
                }
            }

            if (bitmap == null) return@withContext false

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
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun saveVideoToGalleryInternal(context: Context, item: GalleryItem): Boolean {
        val client = comfyUIClient ?: return false

        return withContext(Dispatchers.IO) {
            val videoBytes = kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                    continuation.resumeWith(Result.success(bytes))
                }
            }

            if (videoBytes == null) return@withContext false

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
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun shareSelected(context: Context) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            try {
                val uris = mutableListOf<Uri>()

                for ((index, item) in selectedItems.withIndex()) {
                    val uri = if (item.isVideo) {
                        getVideoShareUri(context, item, index)
                    } else {
                        getImageShareUri(context, item, index)
                    }
                    uri?.let { uris.add(it) }
                }

                if (uris.isEmpty()) {
                    _events.emit(GalleryEvent.ShowToast(R.string.failed_share_items))
                    return@launch
                }

                val shareIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                    type = if (selectedItems.all { it.isVideo }) "video/*"
                           else if (selectedItems.none { it.isVideo }) "image/*"
                           else "*/*"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(
                    android.content.Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share)
                    )
                )

                // Clear selection after share
                clearSelection()
            } catch (e: Exception) {
                _events.emit(GalleryEvent.ShowToast(R.string.failed_share_items))
            }
        }
    }

    private suspend fun getImageShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        val client = comfyUIClient ?: return null

        return withContext(Dispatchers.IO) {
            val bitmap = kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchImage(item.filename, item.subfolder, item.type) { bmp ->
                    continuation.resumeWith(Result.success(bmp))
                }
            }

            if (bitmap == null) return@withContext null

            try {
                val shareFile = File(context.cacheDir, "share_image_$index.png")
                shareFile.outputStream().use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getVideoShareUri(context: Context, item: GalleryItem, index: Int): Uri? {
        val client = comfyUIClient ?: return null

        return withContext(Dispatchers.IO) {
            val videoBytes = kotlin.coroutines.suspendCoroutine { continuation ->
                client.fetchVideo(item.filename, item.subfolder, item.type) { bytes ->
                    continuation.resumeWith(Result.success(bytes))
                }
            }

            if (videoBytes == null) return@withContext null

            try {
                val shareFile = File(context.cacheDir, "share_video_$index.mp4")
                shareFile.writeBytes(videoBytes)

                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    shareFile
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
