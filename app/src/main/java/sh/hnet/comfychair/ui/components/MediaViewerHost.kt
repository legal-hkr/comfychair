package sh.hnet.comfychair.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import sh.hnet.comfychair.ui.screens.MediaViewerScreen
import sh.hnet.comfychair.viewmodel.MediaViewerItem
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.ViewerMode

/**
 * Opens the full-screen media viewer, shown over the whole main activity (navigation bar included). The viewer's
 * state belongs to the activity; nothing is passed through intents.
 */
class MediaViewerOpener(private val context: Context, private val viewModel: MediaViewerViewModel) {

    /**
     * Several items, swiped through (gallery, a job's results).
     *
     * @param onClosed Gets the file name of the item shown last
     */
    fun openGallery(items: List<MediaViewerItem>, index: Int, onClosed: (String?) -> Unit = {}) {
        viewModel.open(context, ViewerMode.GALLERY, items, index, onClosed = onClosed)
    }

    /** One image held in memory: a result (file details for its metadata) or a source image (none). */
    fun openImage(bitmap: Bitmap, filename: String? = null, subfolder: String? = null, type: String? = null) {
        val item = MediaViewerItem("", filename ?: "", subfolder ?: "", type ?: "output", isVideo = false, index = 0)
        viewModel.open(context, ViewerMode.SINGLE, listOf(item), 0, singleBitmap = bitmap)
    }

    /** One video result. */
    fun openVideo(uri: Uri, filename: String? = null, subfolder: String? = null, type: String? = null) {
        val item = MediaViewerItem("", filename ?: "", subfolder ?: "", type ?: "output", isVideo = true, index = 0)
        viewModel.open(context, ViewerMode.SINGLE, listOf(item), 0, singleVideoUri = uri)
    }
}

val LocalMediaViewer = staticCompositionLocalOf<MediaViewerOpener> { error("No media viewer provided") }

/**
 * True for content under an open viewer. Video players there step aside (show their thumbnail): the player is shared
 * and draws on one surface at a time, so the viewer gets it while open and the screen gets it back after.
 */
val LocalCoveredByMediaViewer = compositionLocalOf { false }

/**
 * The viewer over the main activity, while open. It hides the system bars with its overlays and uses light bar icons
 * on its dark backdrop, restoring both when it closes.
 *
 * @param onUseAsSource Sends the image shown to a generation screen as its source
 */
@Composable
fun MediaViewerOverlay(
    viewModel: MediaViewerViewModel,
    onUseAsSource: (SourceTarget, Bitmap) -> Unit
) {
    val isOpen by viewModel.isOpen.collectAsState()
    if (!isOpen) return
    val uiState by viewModel.uiState.collectAsState()
    val view = LocalView.current
    val window = view.context.findActivity()?.window

    if (window != null) {
        val controller = WindowCompat.getInsetsController(window, view)
        DisposableEffect(controller) {
            val lightStatusBars = controller.isAppearanceLightStatusBars
            val lightNavigationBars = controller.isAppearanceLightNavigationBars
            val behavior = controller.systemBarsBehavior
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = lightStatusBars
                controller.isAppearanceLightNavigationBars = lightNavigationBars
                controller.systemBarsBehavior = behavior
            }
        }
        LaunchedEffect(controller, uiState.isUiVisible) {
            if (uiState.isUiVisible) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    MediaViewerScreen(
        viewModel = viewModel,
        onClose = { viewModel.close() },
        onUseAsSource = onUseAsSource
    )
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
