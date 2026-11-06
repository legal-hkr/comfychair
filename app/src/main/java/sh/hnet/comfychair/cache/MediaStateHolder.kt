package sh.hnet.comfychair.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.storage.MediaStorageMode
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.util.GenerationOutputs
import sh.hnet.comfychair.util.VideoUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton that holds media state for generation screens.
 *
 * Supports two caching modes:
 * - Memory-first (default): Media stored in RAM, persisted to disk on app background.
 *   This eliminates frequent disk I/O during active generation.
 * - Disk-first: Media written directly to disk, read from disk when needed.
 *   This minimizes RAM usage for low-end devices with slow networks.
 *
 * Media files are scoped per-server using the format: {serverId}_{filename}
 */
object MediaStateHolder {

    private const val TAG = "MediaState"
    private const val OUTPUTS_FILE_SUFFIX = "_last_outputs.json"
    private const val TEMP_FILE_SUFFIX = ".tmp"

    // Current server ID for per-server file scoping
    @Volatile
    private var currentServerId: String? = null

    /**
     * Keys for different media types stored in the holder.
     * Base filenames are used - the server ID prefix is added dynamically.
     */
    sealed class MediaKey(val baseFilename: String) {
        // Image previews (final generated images displayed on screens)
        data object TtiPreview : MediaKey("tti_last_preview.png")
        data object ItiPreview : MediaKey("iti_last_preview.png")
        data object TtvPreview : MediaKey("ttv_last_preview.png")
        data object ItvPreview : MediaKey("itv_last_preview.png")
        data object V2vPreview : MediaKey("v2v_last_preview.png")

        // Source images (user-selected images for Image-to-X workflows)
        data object ItiSource : MediaKey("iti_last_source.png")
        data object ItvSource : MediaKey("itv_last_source.png")

        // Source video of Video to Video (video bytes, stored like the generated videos)
        data object V2vSource : MediaKey("v2v_last_source.mp4")

        // Reference images for ITE (Image Editing) workflow
        data object IteReferenceImage1 : MediaKey("ite_reference_1.png")
        data object IteReferenceImage2 : MediaKey("ite_reference_2.png")

        // Generated videos (with prompt ID for uniqueness)
        data class TtvVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.TEXT_TO_VIDEO}$promptId.mp4")
        data class ItvVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}$promptId.mp4")
        data class V2vVideo(val promptId: String) : MediaKey("${VideoUtils.FilePrefix.VIDEO_TO_VIDEO}$promptId.mp4")

        /**
         * Get the full filename including server ID prefix.
         */
        fun filename(serverId: String): String = "${serverId}_$baseFilename"

        /** Name for the debug log: video keys carry a prompt ID, which is shortened. */
        val logName: String
            get() = when (this) {
                is TtvVideo -> "${VideoUtils.FilePrefix.TEXT_TO_VIDEO}${Obfuscator.promptId(promptId)}"
                is ItvVideo -> "${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}${Obfuscator.promptId(promptId)}"
                is V2vVideo -> "${VideoUtils.FilePrefix.VIDEO_TO_VIDEO}${Obfuscator.promptId(promptId)}"
                else -> baseFilename
            }
    }

    /**
     * Set the current server ID for per-server file scoping.
     * Should be called when connecting to a server.
     */
    fun setCurrentServerId(serverId: String?) {
        if (currentServerId != serverId) {
            DebugLogger.i(TAG, "Server ID changed: ${currentServerId?.take(8)} -> ${serverId?.take(8)}")
            currentServerId = serverId
        }
    }

    /**
     * Get the current server ID.
     */
    fun getCurrentServerId(): String? = currentServerId

    // Caching mode - true for memory-first (default), false for disk-first
    @Volatile
    private var isMemoryFirstMode = true

    // In-memory storage (used in memory-first mode)
    private val bitmaps = ConcurrentHashMap<MediaKey, Bitmap>()
    private val videoBytes = ConcurrentHashMap<MediaKey, ByteArray>()

    // Dirty flags - tracks what needs to be persisted to disk (memory-first mode only)
    private val dirtyBitmaps = ConcurrentHashMap.newKeySet<MediaKey>()
    private val dirtyVideos = ConcurrentHashMap.newKeySet<MediaKey>()

    // Track current video prompt IDs for persistence
    @Volatile
    private var currentTtvPromptId: String? = null
    @Volatile
    private var currentItvPromptId: String? = null
    @Volatile
    private var currentV2vPromptId: String? = null

    // Serializes everything that reads, writes or deletes media files, so a background persist
    // never overlaps a load, a mode switch or the memory-only cleanup
    private val diskMutex = Mutex()

    // Outlives activities: persisting on onStop must not block the main thread or be cancelled with the activity
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _storedMediaVersion = MutableStateFlow(0L)

    /**
     * Increases each time media stored on the device has been brought into reach for the current
     * server (loaded into RAM, or found on disk). Screens restore their previews when it changes,
     * filling only what they don't show yet.
     */
    val storedMediaVersion: StateFlow<Long> = _storedMediaVersion.asStateFlow()

    /**
     * Apply the media storage mode at startup without blocking the caller: the flags take effect now,
     * the disk work (memory-only cleanup, loading saved media, mode migration) runs in the background,
     * and [storedMediaVersion] announces when it is done.
     */
    fun prepareForStorageMode(context: Context, mode: MediaStorageMode) {
        val appContext = context.applicationContext
        isMemoryOnly = mode == MediaStorageMode.MEMORY_ONLY
        backgroundScope.launch {
            when (mode) {
                // Nothing may stay on the device, including files left by other modes or older versions;
                // the cleanup moves the stored media into RAM first
                MediaStorageMode.MEMORY_ONLY -> enforceMemoryOnly(appContext)
                MediaStorageMode.MEMORY_PERSISTED -> {
                    setMemoryFirstMode(true, appContext)
                    loadFromDisk(appContext)
                }
                MediaStorageMode.DEVICE -> {
                    setMemoryFirstMode(false, appContext)
                    // Disk-first: just discover video promptIds (bytes read on demand)
                    discoverVideoPromptIds(appContext)
                }
            }
            DebugLogger.d(TAG, "Stored media ready ($mode)")
            _storedMediaVersion.update { it + 1 }
        }
    }

    /**
     * Set the caching mode.
     * @param enabled true for memory-first (default), false for disk-first
     * @param context Required for content migration when switching modes
     */
    suspend fun setMemoryFirstMode(enabled: Boolean, context: Context? = null) =
        diskMutex.withLock { setMemoryFirstModeLocked(enabled, context) }

    private suspend fun setMemoryFirstModeLocked(enabled: Boolean, context: Context?) {
        if (isMemoryFirstMode == enabled) return  // No change

        DebugLogger.i(TAG, "Switching to ${if (enabled) "memory-first" else "disk-first"} mode")
        if (enabled) {
            // Switching TO memory-first: load disk content into memory
            context?.let { ctx ->
                loadFromDiskLocked(ctx)
                // Disk files remain as backup - don't clear them
            }
        } else {
            // Switching TO disk-first: persist memory content to disk first
            context?.let { ctx ->
                persistToDiskLocked(ctx)
            }
            // Now safe to clear in-memory caches
            bitmaps.clear()
            videoBytes.clear()
            dirtyBitmaps.clear()
            dirtyVideos.clear()
        }

        isMemoryFirstMode = enabled
    }

    /**
     * Memory-only storage (the default): media must never be written to the device. Set from the
     * user's MediaStorageMode together with [setMemoryFirstMode].
     */
    @Volatile
    var isMemoryOnly: Boolean = true

    /** Media files of all servers in filesDir: previews, videos and result lists. */
    private fun diskMediaFiles(context: Context): List<File> =
        context.filesDir.listFiles()?.filter { file ->
            val name = file.name
            val serverId = name.substringBefore('_', missingDelimiterValue = "")
            // Server-scoped files start with the server UUID
            serverId.length == 36 && serverId.count { it == '-' } == 4 &&
                    (name.endsWith(".png") || name.endsWith(".mp4") || name.endsWith(OUTPUTS_FILE_SUFFIX) ||
                            name.endsWith(TEMP_FILE_SUFFIX))
        }.orEmpty()

    /** Whether any generated media of any server is stored on the device. */
    fun hasDiskMedia(context: Context): Boolean = diskMediaFiles(context).isNotEmpty()

    /**
     * Enforce memory-only storage: load the current server's stored media into RAM, so the
     * results on screen stay available, then delete all media files of all servers. Also removes
     * files left by other modes or by older versions that kept disk copies.
     */
    suspend fun enforceMemoryOnly(context: Context) = diskMutex.withLock {
        isMemoryOnly = true
        setMemoryFirstModeLocked(true, context)
        val files = withContext(Dispatchers.IO) { diskMediaFiles(context) }
        if (files.isEmpty()) return@withLock
        loadFromDiskLocked(context)
        withContext(Dispatchers.IO) {
            files.forEach { it.delete() }
        }
        DebugLogger.i(TAG, "Memory-only storage: deleted ${files.size} media files from the device")
    }

    /**
     * File with the result list of a screen's last job: filenames only, no media.
     */
    private fun outputsFile(context: Context, serverId: String, owner: String): File =
        File(context.filesDir, "${serverId}_${owner.lowercase()}$OUTPUTS_FILE_SUFFIX")

    /**
     * Keep the result list of a screen's last job, so the results strip survives process death.
     * Like the previews, it's written to the device unless storage is memory-only; then any file
     * left from another mode is removed, so an outdated list can't come back later.
     *
     * @param owner Screen owner ID, e.g. TextToImageViewModel.OWNER_ID
     */
    fun saveGenerationOutputs(context: Context?, owner: String, outputs: List<GenerationOutput>, currentIndex: Int) {
        val ctx = context ?: return
        val serverId = currentServerId ?: return
        val file = outputsFile(ctx, serverId, owner)
        try {
            if (isMemoryOnly || outputs.isEmpty()) {
                file.delete()
                return
            }
            file.writeText(
                JSONObject()
                    .put("currentIndex", currentIndex)
                    .put("outputs", GenerationOutputs.toJson(outputs))
                    .toString()
            )
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to save result list: ${e.message}")
        }
    }

    /**
     * Read the result list kept by [saveGenerationOutputs] (not in memory-only storage).
     * @return The results and the current index, or null if nothing is stored
     */
    fun loadGenerationOutputs(context: Context?, owner: String): Pair<List<GenerationOutput>, Int>? {
        if (isMemoryOnly) return null
        val ctx = context ?: return null
        val serverId = currentServerId ?: return null
        val file = outputsFile(ctx, serverId, owner)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val outputs = GenerationOutputs.fromJson(json.optJSONArray("outputs"))
            if (outputs.isEmpty()) null
            else outputs to json.optInt("currentIndex").coerceIn(0, outputs.size - 1)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to load result list: ${e.message}")
            null
        }
    }

    /**
     * Clear all disk cache files for the current server.
     */
    fun clearDiskCache(context: Context) {
        val serverId = currentServerId ?: return

        val allKeys = listOf(
            MediaKey.TtiPreview,
            MediaKey.ItiPreview,
            MediaKey.ItiSource,
            MediaKey.TtvPreview,
            MediaKey.ItvPreview,
            MediaKey.ItvSource,
            MediaKey.V2vPreview,
            MediaKey.V2vSource,
            MediaKey.IteReferenceImage1,
            MediaKey.IteReferenceImage2
        )

        for (key in allKeys) {
            try {
                val file = context.getFileStreamPath(key.filename(serverId))
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }

        // Also delete any video files for this server
        val prefix = "${serverId}_"
        try {
            context.filesDir.listFiles()?.filter { file ->
                file.name.startsWith(prefix) && (
                    file.name.contains(VideoUtils.FilePrefix.TEXT_TO_VIDEO) ||
                    file.name.contains(VideoUtils.FilePrefix.IMAGE_TO_VIDEO) ||
                    file.name.contains(VideoUtils.FilePrefix.VIDEO_TO_VIDEO) ||
                    file.name.endsWith(OUTPUTS_FILE_SUFFIX)
                )
            }?.forEach { file ->
                file.delete()
            }
        } catch (_: Exception) {
            // Ignore deletion failures
        }
    }

    /**
     * Check if memory-first mode is enabled.
     */
    fun isMemoryFirstMode(): Boolean = isMemoryFirstMode

    /**
     * Store a bitmap. In memory-first mode, stores in RAM. In disk-first mode, writes to disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun putBitmap(key: MediaKey, bitmap: Bitmap, context: Context? = null) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "putBitmap SKIPPED - currentServerId is null (key: ${key.logName})")
            return
        }

        DebugLogger.d(TAG, "putBitmap: ${key.logName} (server: ${serverId.take(8)}..., mode: ${if (isMemoryFirstMode) "memory" else "disk"})")

        if (isMemoryFirstMode) {
            // Memory-first: store in memory, mark as dirty for later persistence
            bitmaps[key] = bitmap
            dirtyBitmaps.add(key)
            DebugLogger.d(TAG, "putBitmap: stored in memory, marked dirty (dirty count: ${dirtyBitmaps.size})")
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            context?.let { ctx ->
                try {
                    ctx.openFileOutput(key.filename(serverId), Context.MODE_PRIVATE).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    DebugLogger.d(TAG, "putBitmap: written to disk")
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "putBitmap: disk write FAILED - ${e.message}")
                }
            } ?: DebugLogger.w(TAG, "putBitmap: context is null, cannot write to disk")
        }
    }

    /**
     * Get a bitmap. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getBitmap(key: MediaKey, context: Context? = null): Bitmap? {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "getBitmap SKIPPED - currentServerId is null (key: ${key.logName})")
            return null
        }

        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            val bitmap = bitmaps[key]
            DebugLogger.d(TAG, "getBitmap: ${key.logName} from memory -> ${if (bitmap != null) "FOUND" else "NOT FOUND"}")
            bitmap
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = ctx.getFileStreamPath(key.filename(serverId))
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        DebugLogger.d(TAG, "getBitmap: ${key.logName} from disk -> ${if (bitmap != null) "FOUND" else "DECODE FAILED"}")
                        bitmap
                    } else {
                        DebugLogger.d(TAG, "getBitmap: ${key.logName} from disk -> FILE NOT FOUND")
                        null
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "getBitmap: ${key.logName} disk read FAILED - ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * Store video bytes. In memory-first mode, stores in RAM. In disk-first mode, writes to disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun putVideoBytes(key: MediaKey, bytes: ByteArray, context: Context? = null) {
        val serverId = currentServerId ?: return

        // Track prompt ID for persistence
        when (key) {
            is MediaKey.TtvVideo -> currentTtvPromptId = key.promptId
            is MediaKey.ItvVideo -> currentItvPromptId = key.promptId
            is MediaKey.V2vVideo -> currentV2vPromptId = key.promptId
            else -> { }
        }

        // Each screen shows only its latest video; earlier ones would otherwise pile up in RAM
        // for the whole session, or on the device for good
        val prefix = when (key) {
            is MediaKey.TtvVideo -> VideoUtils.FilePrefix.TEXT_TO_VIDEO
            is MediaKey.ItvVideo -> VideoUtils.FilePrefix.IMAGE_TO_VIDEO
            is MediaKey.V2vVideo -> VideoUtils.FilePrefix.VIDEO_TO_VIDEO
            else -> null
        }
        if (prefix != null) {
            videoBytes.keys.filter { it::class == key::class && it != key }.forEach {
                videoBytes.remove(it)
                dirtyVideos.remove(it)
            }
            val ownFile = key.filename(serverId)
            context?.filesDir?.listFiles()?.forEach { file ->
                if (file.name.startsWith("${serverId}_$prefix") && file.name != ownFile) file.delete()
            }
        }

        if (isMemoryFirstMode) {
            // Memory-first: store in memory, mark as dirty for later persistence
            videoBytes[key] = bytes
            dirtyVideos.add(key)
        } else {
            // Disk-first: write to disk immediately, don't store in memory
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).writeBytes(bytes)
                } catch (_: Exception) {
                    // Ignore write failures
                }
            }
        }
    }

    /**
     * Get video bytes. In memory-first mode, returns from RAM. In disk-first mode, reads from disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun getVideoBytes(key: MediaKey, context: Context? = null): ByteArray? {
        val serverId = currentServerId ?: return null

        return if (isMemoryFirstMode) {
            // Memory-first: return from RAM
            videoBytes[key]
        } else {
            // Disk-first: read from disk each time
            context?.let { ctx ->
                try {
                    val file = File(ctx.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        file.readBytes()
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Get a video URI for ExoPlayer playback.
     * In memory-first mode, returns an in-memory URI (see InMemoryVideo), nothing is written to disk.
     * In disk-first mode, returns URI directly to the stored file.
     */
    suspend fun getVideoUri(context: Context, key: MediaKey): Uri? {
        val serverId = currentServerId ?: return null

        return withContext(Dispatchers.IO) {
            try {
                if (isMemoryFirstMode) {
                    // Memory-first: play straight from RAM
                    if (!videoBytes.containsKey(key)) return@withContext null
                    InMemoryVideo.uriFor("state_${key.filename(serverId)}") { videoBytes[key] }
                } else {
                    // Disk-first: return URI to the stored file directly
                    val file = File(context.filesDir, key.filename(serverId))
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Persist all dirty items to disk using parallel writes for speed.
     */
    suspend fun persistToDisk(context: Context) = diskMutex.withLock { persistToDiskLocked(context) }

    /**
     * Persist dirty items without blocking the caller (MainContainerActivity.onStop()).
     */
    fun persistToDiskInBackground(context: Context) {
        val appContext = context.applicationContext
        backgroundScope.launch { persistToDisk(appContext) }
    }

    /**
     * Write a file atomically: a process killed mid-write leaves the previous version, not a truncated file.
     */
    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val temp = File(target.parentFile, target.name + TEMP_FILE_SUFFIX)
        temp.outputStream().use(write)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("Cannot replace ${Obfuscator.filename(target.name)}")
        }
    }

    private suspend fun persistToDiskLocked(context: Context) {
        // The mode may have changed to memory-only while this persist was queued
        if (isMemoryOnly) {
            DebugLogger.d(TAG, "persistToDisk SKIPPED - memory-only storage")
            return
        }
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "persistToDisk SKIPPED - currentServerId is null! (dirty: ${dirtyBitmaps.size} bitmaps, ${dirtyVideos.size} videos)")
            return
        }

        val bitmapCount = dirtyBitmaps.size
        val videoCount = dirtyVideos.size
        DebugLogger.i(TAG, "persistToDisk START (server: ${serverId.take(8)}..., dirty: $bitmapCount bitmaps, $videoCount videos)")

        if (bitmapCount == 0 && videoCount == 0) {
            DebugLogger.d(TAG, "persistToDisk: nothing to persist")
            return
        }

        withContext(Dispatchers.IO) {
            // Launch all writes in parallel for maximum speed
            val bitmapsToPersist = dirtyBitmaps.toList()
            val videosToPersist = dirtyVideos.toList()

            // Create parallel jobs for all items
            val bitmapJobs = bitmapsToPersist.map { key ->
                async {
                    val bitmap = bitmaps[key]
                    if (bitmap == null) {
                        DebugLogger.w(TAG, "persistToDisk: bitmap ${key.logName} is dirty but not in memory!")
                        dirtyBitmaps.remove(key)
                        return@async false
                    }
                    try {
                        writeAtomically(File(context.filesDir, key.filename(serverId))) { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        // A newer bitmap stored meanwhile stays dirty (putBitmap stores before marking)
                        if (bitmaps[key] === bitmap) dirtyBitmaps.remove(key)
                        DebugLogger.d(TAG, "persistToDisk: ${key.logName} -> OK")
                        true
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "persistToDisk: ${key.logName} -> FAILED: ${e.message}")
                        false
                    }
                }
            }

            val videoJobs = videosToPersist.map { key ->
                async {
                    val bytes = videoBytes[key]
                    if (bytes == null) {
                        DebugLogger.w(TAG, "persistToDisk: video ${key.logName} is dirty but not in memory!")
                        dirtyVideos.remove(key)
                        return@async false
                    }
                    try {
                        val file = File(context.filesDir, key.filename(serverId))
                        writeAtomically(file) { out -> out.write(bytes) }
                        if (videoBytes[key] === bytes) {
                            dirtyVideos.remove(key)
                        } else if (!videoBytes.containsKey(key)) {
                            // Replaced by a newer video meanwhile: don't leave the old one on the device
                            file.delete()
                        }
                        DebugLogger.d(TAG, "persistToDisk: ${key.logName} -> OK")
                        true
                    } catch (e: Exception) {
                        DebugLogger.e(TAG, "persistToDisk: ${key.logName} -> FAILED: ${e.message}")
                        false
                    }
                }
            }

            // Wait for all writes to complete
            val bitmapResults = bitmapJobs.awaitAll()
            val videoResults = videoJobs.awaitAll()

            val bitmapSuccess = bitmapResults.count { it }
            val bitmapFailed = bitmapResults.count { !it }
            val videoSuccess = videoResults.count { it }
            val videoFailed = videoResults.count { !it }

            DebugLogger.i(TAG, "persistToDisk DONE (bitmaps: $bitmapSuccess OK, $bitmapFailed failed; videos: $videoSuccess OK, $videoFailed failed)")
        }
    }

    /**
     * Load persisted media from disk into memory for the current server.
     * Called from MainContainerActivity.onCreate().
     */
    suspend fun loadFromDisk(context: Context) = diskMutex.withLock { loadFromDiskLocked(context) }

    private suspend fun loadFromDiskLocked(context: Context) {
        val serverId = currentServerId
        if (serverId == null) {
            DebugLogger.w(TAG, "loadFromDisk SKIPPED - currentServerId is null!")
            return
        }

        DebugLogger.i(TAG, "loadFromDisk START (server: ${serverId.take(8)}...)")

        withContext(Dispatchers.IO) {
            // Load bitmap files
            val bitmapKeys = listOf(
                MediaKey.TtiPreview,
                MediaKey.ItiPreview,
                MediaKey.ItiSource,
                MediaKey.TtvPreview,
                MediaKey.ItvPreview,
                MediaKey.ItvSource,
                MediaKey.V2vPreview,
                MediaKey.IteReferenceImage1,
                MediaKey.IteReferenceImage2
            )

            var bitmapsLoaded = 0
            var bitmapsNotFound = 0
            for (key in bitmapKeys) {
                // Never replace a newer in-memory result that has not been saved yet
                if (key in dirtyBitmaps) continue
                try {
                    val file = context.getFileStreamPath(key.filename(serverId))
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            bitmaps[key] = bitmap
                            bitmapsLoaded++
                            DebugLogger.d(TAG, "loadFromDisk: ${key.logName} -> LOADED")
                            // NOT dirty - already on disk
                        } else {
                            DebugLogger.w(TAG, "loadFromDisk: ${key.logName} -> DECODE FAILED")
                        }
                    } else {
                        bitmapsNotFound++
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "loadFromDisk: ${key.logName} -> ERROR: ${e.message}")
                }
            }

            // Load latest video files for this server
            val serverPrefix = "${serverId}_"
            // As with bitmaps, an unsaved newer video in memory wins over the file
            if (dirtyVideos.none { it is MediaKey.TtvVideo }) loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.TEXT_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.TtvVideo(promptId)
                videoBytes[key] = bytes
                currentTtvPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: TTV video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            if (dirtyVideos.none { it is MediaKey.ItvVideo }) loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.IMAGE_TO_VIDEO) { promptId, bytes ->
                val key = MediaKey.ItvVideo(promptId)
                videoBytes[key] = bytes
                currentItvPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: ITV video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            if (dirtyVideos.none { it is MediaKey.V2vVideo }) loadLatestVideoFile(context, serverPrefix, VideoUtils.FilePrefix.VIDEO_TO_VIDEO) { promptId, bytes ->
                videoBytes[MediaKey.V2vVideo(promptId)] = bytes
                currentV2vPromptId = promptId
                DebugLogger.d(TAG, "loadFromDisk: V2V video -> LOADED (promptId: ${promptId.take(8)}...)")
            }

            // Video to Video's source video
            if (MediaKey.V2vSource !in dirtyVideos) {
                try {
                    val file = context.getFileStreamPath(MediaKey.V2vSource.filename(serverId))
                    if (file.exists()) videoBytes[MediaKey.V2vSource] = file.readBytes()
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "loadFromDisk: V2V source -> ERROR: ${e.message}")
                }
            }

            DebugLogger.i(TAG, "loadFromDisk DONE (loaded: $bitmapsLoaded bitmaps, not found: $bitmapsNotFound)")
        }
    }

    private fun loadLatestVideoFile(
        context: Context,
        serverPrefix: String,
        videoPrefix: String,
        onLoaded: (promptId: String, bytes: ByteArray) -> Unit
    ) {
        try {
            val fullPrefix = "$serverPrefix$videoPrefix"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    val promptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                    val bytes = file.readBytes()
                    onLoaded(promptId, bytes)
                }
        } catch (_: Exception) {
            // Ignore load failures
        }
    }

    /**
     * Remove a specific media item from memory.
     */
    fun evict(key: MediaKey) {
        bitmaps.remove(key)
        videoBytes.remove(key)
        dirtyBitmaps.remove(key)
        dirtyVideos.remove(key)

        // Clear prompt ID tracking
        when (key) {
            is MediaKey.TtvVideo -> if (key.promptId == currentTtvPromptId) currentTtvPromptId = null
            is MediaKey.ItvVideo -> if (key.promptId == currentItvPromptId) currentItvPromptId = null
            is MediaKey.V2vVideo -> if (key.promptId == currentV2vPromptId) currentV2vPromptId = null
            else -> { }
        }
    }

    /**
     * Remove a specific media item from memory AND delete from disk.
     * Use this when the item should not be restored on app restart.
     */
    suspend fun evictAndDeleteFromDisk(context: Context, key: MediaKey) {
        val serverId = currentServerId ?: return
        evict(key)
        withContext(Dispatchers.IO) {
            try {
                val file = context.getFileStreamPath(key.filename(serverId))
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
                // Ignore deletion failures
            }
        }
    }

    /**
     * Clear all media from memory.
     * Called during cache clearing.
     */
    fun clearAll() {
        DebugLogger.i(TAG, "Clearing all media from memory")
        bitmaps.clear()
        videoBytes.clear()
        dirtyBitmaps.clear()
        dirtyVideos.clear()
        currentTtvPromptId = null
        currentItvPromptId = null
        currentV2vPromptId = null
    }

    /**
     * Get the current Text-to-Video prompt ID (if any video is loaded).
     */
    fun getCurrentTtvPromptId(): String? = currentTtvPromptId

    /**
     * Get the current Image-to-Video prompt ID (if any video is loaded).
     */
    fun getCurrentItvPromptId(): String? = currentItvPromptId

    /** The current Video-to-Video prompt ID (if any video is loaded). */
    fun getCurrentV2vPromptId(): String? = currentV2vPromptId

    /**
     * Clear the current Text-to-Video prompt ID tracking.
     * This prevents video restoration on subsequent screen initializations.
     */
    fun clearCurrentTtvPromptId() {
        currentTtvPromptId = null
    }

    /**
     * Clear the current Image-to-Video prompt ID tracking.
     * This prevents video restoration on subsequent screen initializations.
     */
    fun clearCurrentItvPromptId() {
        currentItvPromptId = null
    }

    /** Clear the current Video-to-Video prompt ID tracking. */
    fun clearCurrentV2vPromptId() {
        currentV2vPromptId = null
    }

    /**
     * Discover video prompt IDs from disk without loading bytes into memory.
     * Used in disk-first mode to enable video restoration on app restart.
     * In disk-first mode, videos are read on-demand, but we still need to know
     * which promptIds have saved videos.
     */
    fun discoverVideoPromptIds(context: Context) {
        val serverId = currentServerId ?: return
        val serverPrefix = "${serverId}_"

        // Discover TTV video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.TEXT_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentTtvPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }

        // Discover ITV video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.IMAGE_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentItvPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }

        // Discover V2V video
        try {
            val fullPrefix = "$serverPrefix${VideoUtils.FilePrefix.VIDEO_TO_VIDEO}"
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith(fullPrefix) && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { file ->
                    currentV2vPromptId = file.name
                        .removePrefix(fullPrefix)
                        .removeSuffix(".mp4")
                }
        } catch (_: Exception) {
            // Ignore discovery failures
        }
    }

    /**
     * Check if a bitmap exists. In memory-first mode, checks RAM. In disk-first mode, checks disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun hasBitmap(key: MediaKey, context: Context? = null): Boolean {
        val serverId = currentServerId ?: return false

        return if (isMemoryFirstMode) {
            bitmaps.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    ctx.getFileStreamPath(key.filename(serverId)).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }

    /**
     * Check if video bytes exist. In memory-first mode, checks RAM. In disk-first mode, checks disk.
     * @param context Required for disk-first mode, optional for memory-first mode.
     */
    fun hasVideoBytes(key: MediaKey, context: Context? = null): Boolean {
        val serverId = currentServerId ?: return false

        return if (isMemoryFirstMode) {
            videoBytes.containsKey(key)
        } else {
            context?.let { ctx ->
                try {
                    File(ctx.filesDir, key.filename(serverId)).exists()
                } catch (e: Exception) {
                    false
                }
            } ?: false
        }
    }

    /**
     * Cleanup orphaned media files that don't belong to any registered server.
     * Call this after deleting a server or during app cleanup.
     *
     * @param context Application context
     * @param validServerIds List of server IDs that should be kept
     */
    fun cleanupOrphanedMediaFiles(context: Context, validServerIds: Set<String>) {
        DebugLogger.i(TAG, "Cleaning up orphaned media files")
        try {
            context.filesDir.listFiles()?.forEach { file ->
                // Check if file starts with a UUID pattern (server-scoped file)
                val name = file.name
                val underscoreIndex = name.indexOf('_')
                if (underscoreIndex > 0) {
                    val potentialServerId = name.substring(0, underscoreIndex)
                    // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (36 chars)
                    if (potentialServerId.length == 36 && potentialServerId.contains('-')) {
                        if (potentialServerId !in validServerIds) {
                            DebugLogger.d(TAG, "Deleting orphaned file: ${Obfuscator.filename(name)}")
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to cleanup orphaned files: ${e.message}")
        }
    }
}
