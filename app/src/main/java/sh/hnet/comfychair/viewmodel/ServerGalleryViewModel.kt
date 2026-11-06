package sh.hnet.comfychair.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.*


/**
 * Browses the connected server's Input/Output images with the shared [ServerImagePicker]: an original viewer over the
 * picker, plus full-image download with bounds. Used by the Settings gallery page and the generation screens' picker.
 */
class ServerGalleryViewModel : ViewModel() {
    data class FullImage(val owner: ServerImageIdentity, val image: ServerImage, val bitmap: Bitmap, val encodedSize: Int)
    private val full = MutableStateFlow<FullImage?>(null)
    val viewer = full.asStateFlow()
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow(false)
    private var download: Job? = null
    private val previews = ServerImagePreviewCache<String, Bitmap>(12L * 1024 * 1024, 64)
    val picker = ServerImagePicker<Unit>(viewModelScope, ::currentMediaIdentity, { it == 1 },
        list = { owner, source, offset -> (owner.client as ComfyUIClient).listSourceImagePage(source, offset) },
        download = { _, _ -> }, accept = { _, _, _ -> },
        browse = { owner, source, query -> (owner.client as ComfyUIClient).browseSourceImages(source, query) },
        cached = { owner, source, query -> withContext(Dispatchers.IO) { (owner.client as ComfyUIClient).cachedSourceImages(source, query, owner.epoch) } },
        store = { owner, source, query, page, generation -> withContext(Dispatchers.IO) {
            if (currentMediaIdentity() == owner) (owner.client as ComfyUIClient).cacheSourceImages(source, query, page, owner.epoch, generation)
        } }, cacheGeneration = { 0L })
    fun open(source: ServerImageSource) { closeViewer(); picker.open(1, source) }
    fun closeViewer() { download?.cancel(); full.value = null; loading.value = false; error.value = false }
    fun stop() { closeViewer(); picker.close(); previews.clear() }
    fun view(image: ServerImage) {
        val owner = currentMediaIdentity() ?: return
        closeViewer(); loading.value = true
        download = viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) {
                    val bytes = (owner.client as ComfyUIClient).downloadSourceImage(image)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid image")
                    FullImage(owner, image, bitmap, bytes.size)
                }
                if (currentMediaIdentity() == owner) full.value = value
            } catch (e: CancellationException) { throw e } catch (_: Exception) { error.value = true }
            finally { loading.value = false }
        }
    }
    suspend fun preview(image: ServerImage): Bitmap = picker.preview(image) { owner -> withContext(Dispatchers.IO) {
        val key = "${owner.hashCode()}:${image.stableKey}"
        previews.get(key) ?: run {
            val client = owner.client as ComfyUIClient
            val storage = client.imageCacheStorage(owner.epoch)
            val id = "thumb:" + image.stableKey
            val cached = storage?.let { (disk, namespace) -> disk.get(namespace, id, 86_400_000) }
            var bitmap = cached?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.takeIf { it.width <= 256 && it.height <= 256 }
            if (bitmap == null) {
                val bytes = client.downloadSourcePreview(image)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 268_435_456)
                options.inJustDecodeBounds = false; options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 256 || options.outHeight / options.inSampleSize > 256) options.inSampleSize *= 2
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid preview")
                ensureActive(); check(currentMediaIdentity() == owner)
                val output = java.io.ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                storage?.let { (disk, namespace) -> disk.put(namespace, id, output.toByteArray()) }
            }
            previews.put(key, bitmap, bitmap.allocationByteCount.toLong()); bitmap
        }
    } }
    override fun onCleared() { stop() }

    /** The original of an image, for the generation screens' picker: bounded, checked, identity-guarded. */
    suspend fun loadOriginal(image: ServerImage): Bitmap {
        val owner = currentMediaIdentity() ?: throw CancellationException("Not connected")
        val bitmap = withContext(Dispatchers.IO) {
            val bytes = (owner.client as ComfyUIClient).downloadSourceImage(image)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_777_216)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid image")
        }
        check(currentMediaIdentity() == owner)
        return bitmap
    }

    /** Loads the original and hands it to [onPick]; failures surface as a toast. */
    fun loadOriginalIn(image: ServerImage, onPick: (Bitmap) -> Unit) {
        viewModelScope.launch {
            try {
                onPick(loadOriginal(image))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.emit(System.currentTimeMillis())
            }
        }
    }

    /** One-shot error signal for the sheet UI (toast). */
    private val _events = MutableStateFlow(0L)
    val eventsFlow = _events.asStateFlow()
}

/** The connected client and its epoch, or null when offline; identity changes close stale picker state. */
fun currentMediaIdentity(): ServerImageIdentity? {
    val client = ConnectionManager.clientOrNull ?: return null
    val server = ConnectionManager.currentServerId ?: return null
    return ServerImageIdentity(server, client, client.imageUploadIdentity())
}
