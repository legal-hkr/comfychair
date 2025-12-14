package sh.hnet.comfychair

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import sh.hnet.comfychair.ui.screens.MediaViewerScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode
import java.io.File

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
        const val EXTRA_BITMAP_PATH = "bitmap_path"
        const val EXTRA_VIDEO_URI = "video_uri"

        // Single mode file info (for metadata extraction)
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_SUBFOLDER = "subfolder"
        const val EXTRA_TYPE = "type"

        // Result
        const val RESULT_ITEM_DELETED = "item_deleted"

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
            type: String? = null
        ): Intent {
            // Save bitmap to temp file for passing to activity
            val tempFile = File(context.cacheDir, "viewer_temp_${System.currentTimeMillis()}.png")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SINGLE)
                putExtra(EXTRA_IS_VIDEO, false)
                putExtra(EXTRA_BITMAP_PATH, tempFile.absolutePath)
                // Add server and file info for metadata extraction
                hostname?.let { putExtra(EXTRA_HOSTNAME, it) }
                port?.let { putExtra(EXTRA_PORT, it) }
                filename?.let { putExtra(EXTRA_FILENAME, it) }
                subfolder?.let { putExtra(EXTRA_SUBFOLDER, it) }
                type?.let { putExtra(EXTRA_TYPE, it) }
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
        super.onCreate(savedInstanceState)

        // Setup edge-to-edge and system bars control
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SINGLE

        when (mode) {
            MODE_GALLERY -> initializeGalleryMode()
            MODE_SINGLE -> initializeSingleMode()
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()

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
                    MediaViewerScreen(
                        viewModel = viewModel,
                        onClose = {
                            // Set result if any items were deleted
                            setResult(Activity.RESULT_OK, Intent().apply {
                                putExtra(RESULT_ITEM_DELETED, true)
                            })
                            finish()
                        }
                    )
                }
            }
        }
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
                promptId = "",
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
            val bitmapPath = intent.getStringExtra(EXTRA_BITMAP_PATH)
            val bitmap = bitmapPath?.let {
                BitmapFactory.decodeFile(it)
            }

            // Create a single item for the image with file info
            val item = MediaViewerItem(
                promptId = "",
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

            // Clean up temp file
            bitmapPath?.let { File(it).delete() }
        }
    }
}
