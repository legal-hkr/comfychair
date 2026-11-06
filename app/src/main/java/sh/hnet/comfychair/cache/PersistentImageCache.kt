package sh.hnet.comfychair.cache

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.storage.AppSettings

/** Only the remote picker/upload metadata cache. Never touches media state or materials. */
object PersistentImageCache {
    @Volatile private var policy = ImageCachePolicy()
    @Volatile private var disk: PrivateImageDiskCache? = null
    private val changed = MutableStateFlow(0L)
    val changes = changed.asStateFlow()
    @Synchronized fun storage(context: Context): PrivateImageDiskCache = disk ?: run {
        policy = AppSettings.getImageCachePolicy(context)
        PrivateImageDiskCache(File(context.noBackupFilesDir, "server-image-cache-v1"), policy = { policy })
            .also { disk = it }
    }
    @Synchronized fun apply(context: Context, value: ImageCachePolicy) {
        val cache = storage(context)
        AppSettings.setImageCachePolicy(context, value)
        policy = value
        cache.cleanup()
    }
    fun generation(): Long = disk?.generation() ?: 0L
    @Synchronized fun clear(context: Context) {
        try { storage(context).clear() }
        finally { changed.value = changed.value + 1 }
    }
}
