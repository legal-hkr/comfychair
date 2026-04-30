package sh.hnet.comfychair

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.notification.NotificationHelper
import sh.hnet.comfychair.ui.screens.MediaViewerScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode
/**
 * Simple in-memory cache for passing bitmaps between activities.
 * Avoids expensive PNG compression/decompression when launching MediaViewer.
 */
object BitmapCache {
    private var cachedBitmap: Bitmap? = null

    fun put(bitmap: Bitmap) {
        cachedBitmap = bitmap
    }

    fun get(): Bitmap? {
        return cachedBitmap
    }

    fun clear() {
        cachedBitmap = null
    }
}

/**
 * Activity for fullscreen media viewing.
 * Supports two modes:
 * - Gallery mode: Swipe navigation between items from ComfyUI history
 * - Single mode: Single preview from generation screens
 */
class MediaViewerActivity : ComponentActivity() {

    private val viewModel: MediaViewerViewModel by viewModels()
    private lateinit var insetsController: WindowInsetsControllerCompat

    companion object {
        // Intent extras
        const val EXTRA_MODE = "mode"
        const val MODE_GALLERY = "gallery"
        const val MODE_SINGLE = "single"

        // Gallery mode extras
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_PORT = "port"
        const val EXTRA_GALLERY_ITEMS_JSON = "gallery_items_json"
        const val EXTRA_INITIAL_INDEX = "initial_index"

        // Single mode extras
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_VIDEO_URI = "video_uri"

        // Single mode file info (for metadata extraction)
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_SUBFOLDER = "subfolder"
        const val EXTRA_TYPE = "type"

        // Replace slot extra (source image replace feature)
        const val EXTRA_REPLACE_SLOT = "replace_slot"

        // Bypass slot extra — which source image slot is currently bypassed (so toolbar shows correct state)
        const val EXTRA_BYPASS_SLOT = "bypass_slot"
        const val EXTRA_IS_BYPASSED = "is_bypassed"

        // Result
        const val RESULT_ITEM_DELETED = "item_deleted"
        const val RESULT_REPLACE = "replace"
        const val RESULT_SLOT = "slot"

        // Static callback for bypass toggle (avoids threading callback through Activity→Screen→Toolbar)
        // Set by ImageToImageScreen before launching MediaViewer, cleared after result
        var onBypassToggleCallback: ((slot: Int) -> Unit)? = null

        // Static callback for "use as source" — passes (promptId, filename, subfolder, type, bitmap)
        // Set by ImageToImageScreen before launching MediaViewer, cleared after result
        var onUseAsSourceCallback: ((promptId: String, filename: String, subfolder: String, type: String, bitmap: android.graphics.Bitmap) -> Unit)? = null

        // SINGLE mode: bitmap stored here (not in BitmapCache which gets cleared during init)
        // Cleared by MediaViewerActivity when done
        var singleModeBitmap: android.graphics.Bitmap? = null
        fun clearSingleModeBitmap() {
            singleModeBitmap = null
        }

        /**
         * Create intent for gallery mode (swipe navigation between items)
         */
        fun createGalleryIntent(
            context: Context,
            hostname: String,
            port: Int,
            items: List<MediaViewerItem>,
            initialIndex: Int
        ): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_GALLERY)
                putExtra(EXTRA_HOSTNAME, hostname)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_GALLERY_ITEMS_JSON, MediaViewerItem.listToJson(items))
                putExtra(EXTRA_INITIAL_INDEX, initialIndex)
            }
        }

        /**
         * Create intent for single image mode (from generation screen preview)
         *
         * @param hostname Server hostname for metadata extraction (optional)
         * @param port Server port for metadata extraction (optional)
         * @param filename Server filename for metadata extraction (optional)
         * @param subfolder Server subfolder for metadata extraction (optional)
         * @param type Server type for metadata extraction (optional)
         */
        fun createSingleImageIntent(
            context: Context,
            bitmap: Bitmap,
            hostname: String? = null,
            port: Int? = null,
            filename: String? = null,
            subfolder: String? = null,
            type: String? = null,
            replaceSlot: Int? = null,
            bypassSlot: Int? = null,
            isSlotBypassed: Boolean = false
        ): Intent {
            // Store bitmap in companion object (survives activity init, unlike BitmapCache which gets cleared)
            singleModeBitmap = bitmap

            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SINGLE)
                putExtra(EXTRA_IS_VIDEO, false)
                // Add server and file info for metadata extraction
                hostname?.let { putExtra(EXTRA_HOSTNAME, it) }
                port?.let { putExtra(EXTRA_PORT, it) }
                filename?.let { putExtra(EXTRA_FILENAME, it) }
                subfolder?.let { putExtra(EXTRA_SUBFOLDER, it) }
                type?.let { putExtra(EXTRA_TYPE, it) }
                // Add replace slot if provided (enables replace button in viewer)
                replaceSlot?.let { putExtra(EXTRA_REPLACE_SLOT, it) }
                // Add bypass slot if provided (shows correct initial bypass state in toolbar)
                bypassSlot?.let { putExtra(EXTRA_BYPASS_SLOT, it) }
                putExtra(EXTRA_IS_BYPASSED, isSlotBypassed)
            }
        }

        /**
         * Create intent for single video mode (from generation screen preview)
         *
         * Note: For videos, metadata can be extracted directly from the video file,
         * so server info is optional but can be provided for consistency.
         */
        fun createSingleVideoIntent(
            context: Context,
            videoUri: Uri,
            hostname: String? = null,
            port: Int? = null,
            filename: String? = null,
            subfolder: String? = null,
            type: String? = null
        ): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SINGLE)
                putExtra(EXTRA_IS_VIDEO, true)
                putExtra(EXTRA_VIDEO_URI, videoUri.toString())
                // Add server and file info for metadata extraction
                hostname?.let { putExtra(EXTRA_HOSTNAME, it) }
                port?.let { putExtra(EXTRA_PORT, it) }
                filename?.let { putExtra(EXTRA_FILENAME, it) }
                subfolder?.let { putExtra(EXTRA_SUBFOLDER, it) }
                type?.let { putExtra(EXTRA_TYPE, it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Setup system bars control for hide/show behavior
        insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE

        when (mode) {
            MODE_GALLERY -> initializeGalleryMode()
            MODE_SINGLE -> initializeSingleMode()
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val hasDeletedItems by viewModel.hasDeletedItems.collectAsState()

            // Control system bars based on UI visibility
            LaunchedEffect(uiState.isUiVisible) {
                if (uiState.isUiVisible) {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            ComfyChairTheme(forceDarkStatusBar = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val replaceSlot = intent.getIntExtra(EXTRA_REPLACE_SLOT, -1).takeIf { it > 0 }
                    val bypassSlot = intent.getIntExtra(EXTRA_BYPASS_SLOT, -1).takeIf { it > 0 }
                    val isSlotBypassed = intent.getBooleanExtra(EXTRA_IS_BYPASSED, false)
                    android.util.Log.d("ComfyChair", "MediaViewer bypassSlot=$bypassSlot isSlotBypassed=$isSlotBypassed")
                    MediaViewerScreen(
                        viewModel = viewModel,
                        replaceSlot = replaceSlot,
                        bypassSlot = bypassSlot,
                        isSlotBypassed = isSlotBypassed,
                        onClose = { replaceRequestedSlot: Int? ->
                            // Set result based on what happened
                            val resultIntent = Intent()
                            var hasResult = false
                            if (hasDeletedItems) {
                                resultIntent.putExtra(RESULT_ITEM_DELETED, true)
                                hasResult = true
                            }
                            if (replaceRequestedSlot != null) {
                                resultIntent.putExtra(RESULT_REPLACE, true)
                                resultIntent.putExtra(RESULT_SLOT, replaceRequestedSlot)
                                hasResult = true
                            }
                            if (hasResult) {
                                setResult(Activity.RESULT_OK, resultIntent)
                            }
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always clear the SINGLE mode bitmap when activity finishes
        clearSingleModeBitmap()
    }

    private fun initializeGalleryMode() {
        val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 8188)
        val itemsJson = intent.getStringExtra(EXTRA_GALLERY_ITEMS_JSON) ?: "[]"
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)

        val items = MediaViewerItem.listFromJson(itemsJson)

        viewModel.initialize(
            context = this,
            hostname = hostname,
            port = port,
            mode = ViewerMode.GALLERY,
            items = items,
            initialIndex = initialIndex
        )
    }

    private fun initializeSingleMode() {
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        // Check if this intent came from a notification (has EXTRA_MEDIA_OWNER_ID)
        val notificationOwnerId = intent.getStringExtra(NotificationHelper.EXTRA_MEDIA_OWNER_ID)
        val notificationPromptId = intent.getStringExtra(NotificationHelper.EXTRA_MEDIA_PROMPT_ID)
        val isFromNotification = notificationOwnerId != null

        // Extract server and file info for metadata extraction
        val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: ""
        val subfolder = intent.getStringExtra(EXTRA_SUBFOLDER) ?: ""
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "output"

        if (isVideo) {
            val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
            val videoUri = videoUriString?.let { Uri.parse(it) }

            // Create a single item for the video with file info
            val item = MediaViewerItem(
                promptId = notificationPromptId ?: "",
                filename = filename,
                subfolder = subfolder,
                type = type,
                isVideo = true,
                index = 0
            )

            viewModel.initialize(
                context = this,
                hostname = hostname,
                port = port,
                mode = ViewerMode.SINGLE,
                items = listOf(item),
                initialIndex = 0,
                singleVideoUri = videoUri
            )
        } else {
            // Get bitmap from companion object (not BitmapCache which gets cleared during SINGLE mode init)
            var bitmap = singleModeBitmap
            // Don't clear here - keep for onUseAsSource to use later; cleared by onClose/onDestroy

            // If no cached bitmap and we have notification extras, retrieve from MediaStateHolder
            if (bitmap == null && isFromNotification && notificationOwnerId != null) {
                val mediaKey = ownerIdToMediaKey(notificationOwnerId)
                if (mediaKey != null) {
                    bitmap = MediaStateHolder.getBitmap(mediaKey, this)
                    DebugLogger.d("MediaViewer", "Retrieved bitmap from MediaStateHolder for $notificationOwnerId: ${bitmap != null}")
                }
            }

            // Create a single item for the image with file info
            val item = MediaViewerItem(
                promptId = notificationPromptId ?: "",
                filename = filename,
                subfolder = subfolder,
                type = type,
                isVideo = false,
                index = 0
            )

            viewModel.initialize(
                context = this,
                hostname = hostname,
                port = port,
                mode = ViewerMode.SINGLE,
                items = listOf(item),
                initialIndex = 0,
                singleBitmap = bitmap
            )
        }
    }

    /**
     * Map ownerId string (e.g. "TEXT_TO_IMAGE") to MediaStateHolder.MediaKey.
     */
    private fun ownerIdToMediaKey(ownerId: String): sh.hnet.comfychair.cache.MediaStateHolder.MediaKey? {
        return when (ownerId) {
            "TEXT_TO_IMAGE" -> sh.hnet.comfychair.cache.MediaStateHolder.MediaKey.TtiPreview
            "IMAGE_TO_IMAGE" -> sh.hnet.comfychair.cache.MediaStateHolder.MediaKey.ItiPreview
            "TEXT_TO_VIDEO" -> sh.hnet.comfychair.cache.MediaStateHolder.MediaKey.TtvPreview
            "IMAGE_TO_VIDEO" -> sh.hnet.comfychair.cache.MediaStateHolder.MediaKey.ItvPreview
            else -> null
        }
    }
}
