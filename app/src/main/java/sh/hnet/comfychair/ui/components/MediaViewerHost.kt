package sh.hnet.comfychair.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * One image held in memory: a result (file details for its metadata) or a source image (none).
     *
     * @param originKey The [mediaOrigin] it's shown in, to fly from and back to
     */
    fun openImage(
        bitmap: Bitmap,
        filename: String? = null,
        subfolder: String? = null,
        type: String? = null,
        originKey: String? = null
    ) {
        val item = MediaViewerItem("", filename ?: "", subfolder ?: "", type ?: "output", isVideo = false, index = 0)
        viewModel.open(context, ViewerMode.SINGLE, listOf(item), 0, singleBitmap = bitmap, originKey = originKey)
    }

    /** One video result. */
    fun openVideo(uri: Uri, originKey: String? = null) {
        val item = MediaViewerItem("", "", "", "output", isVideo = true, index = 0)
        viewModel.open(context, ViewerMode.SINGLE, listOf(item), 0, singleVideoUri = uri, originKey = originKey)
    }
}

val LocalMediaViewer = staticCompositionLocalOf<MediaViewerOpener> { error("No media viewer provided") }

/**
 * True for content under an open viewer. Video players there step aside (show their thumbnail): the player is shared
 * and draws on one surface at a time, so the viewer gets it while open and the screen gets it back after.
 */
val LocalCoveredByMediaViewer = compositionLocalOf { false }

/**
 * True inside the viewer while its medium is still flying in from its origin: a video waits on its first frame and
 * starts once the flight has landed, rather than being somewhere into it by then.
 */
val LocalMediaViewerLanding = compositionLocalOf { false }

/** The medium flying between its origin and the viewer: a still, cropped to a moving rect, over a fading backdrop. */
private class MediaFlight(
    val image: ImageBitmap,
    val from: Rect,
    val to: Rect,
    val fromCornerPx: Float,
    val toCornerPx: Float,
    val fromBackdrop: Float,
    val toBackdrop: Float
) {
    val progress = Animatable(0f)

    suspend fun run() {
        progress.animateTo(1f, tween(FLIGHT_MS, easing = StandardEasing))
    }
}

private const val FLIGHT_MS = 350

/** Longest wait, after landing, for the viewer to show the medium before it takes over from the still. */
private const val LANDING_WAIT_MS = 500L

/**
 * The viewer over the main activity, while open. It flies the medium out of its origin ([mediaOrigin]) on open and
 * back into it on close (a fade when the origin isn't on screen or there's no still), hides the system bars with its
 * overlays and uses light bar icons on its dark backdrop, restoring both when it closes.
 *
 * @param onUseAsSource Sends the image shown to a generation screen as its source
 */
@Composable
fun MediaViewerOverlay(
    viewModel: MediaViewerViewModel,
    onUseAsSource: (SourceTarget, Bitmap) -> Unit
) {
    val isOpen by viewModel.isOpen.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val origins = LocalMediaOrigins.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var flight by remember { mutableStateOf<MediaFlight?>(null) }
    // The viewer itself is visible (not while the still flies)
    var shown by remember { mutableStateOf(false) }

    // The medium's place stays empty while it's in the viewer or flying
    val currentKey = if (isOpen) remember(uiState.mode, uiState.items, uiState.currentIndex) { viewModel.currentOriginKey() } else null
    SideEffect { origins.hiddenKey = if (isOpen && (shown || flight != null)) currentKey else null }

    // Fly out of the origin on open
    LaunchedEffect(isOpen) {
        if (!isOpen) {
            shown = false
            flight = null
            return@LaunchedEffect
        }
        val origin = viewModel.currentOriginKey()?.let { origins[it] }
        // What the origin shows flies out; the shared cache may have dropped it meanwhile
        val still = origin?.still ?: viewModel.currentStill()
        if (origin != null && origin.visible && still != null && size != IntSize.Zero && still.height > 0) {
            val aspect = still.width.toFloat() / still.height
            val out = MediaFlight(
                image = still.asImageBitmap(),
                from = origin.bounds,
                to = Rect(Offset.Zero, size.toSize()).fitted(aspect),
                fromCornerPx = origin.cornerPx,
                toCornerPx = 0f,
                fromBackdrop = 0f,
                toBackdrop = 1f
            )
            flight = out
            out.run()
            // Closed meanwhile (Back during the flight): the flight back has taken over
            if (flight !== out) return@LaunchedEffect
            // Let the viewer show the medium under the still before it goes, so nothing blinks
            withTimeoutOrNull(LANDING_WAIT_MS) {
                snapshotFlow { viewModel.uiState.value.isLoading }.first { !it }
            }
            if (flight !== out) return@LaunchedEffect
            shown = true
            withFrameNanos { }
            if (flight === out) flight = null
        } else {
            shown = true
        }
    }

    // Fly back into the origin, from where the swipe or back gesture left the medium
    val requestClose: (ViewerCloseFrom) -> Boolean = requestClose@{ from ->
        val origin = viewModel.currentOriginKey()?.let { origins[it] }
        // The viewer's own (full) image flies back; the origin's if the viewer has none yet
        val still = viewModel.currentStill() ?: origin?.still
        if (origin == null || !origin.visible || still == null || still.height <= 0 || size == IntSize.Zero) {
            return@requestClose false
        }
        val aspect = still.width.toFloat() / still.height
        val screen = Rect(Offset.Zero, size.toSize())
        val back = MediaFlight(
            image = still.asImageBitmap(),
            from = screen.fitted(aspect).transformed(screen.center, from.scale, from.offsetY),
            to = origin.bounds,
            fromCornerPx = from.cornerPx,
            toCornerPx = origin.cornerPx,
            fromBackdrop = from.backdropAlpha,
            toBackdrop = 0f
        )
        flight = back
        shown = false
        scope.launch {
            back.run()
            // The flight stays until the viewer is closed, then the origin shows again in its place
            viewModel.close()
        }
        true
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
    ) {
        if (isOpen) {
            ViewerSystemBars(uiState.isUiVisible)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (shown) 1f else 0f }) {
                CompositionLocalProvider(LocalMediaViewerLanding provides !shown) {
                    MediaViewerScreen(
                        viewModel = viewModel,
                        onClose = { viewModel.close() },
                        onUseAsSource = onUseAsSource,
                        onRequestClose = requestClose
                    )
                }
            }
        }
        flight?.let { MediaFlightLayer(it) }
    }
}

@Composable
private fun MediaFlightLayer(flight: MediaFlight) {
    val progress = flight.progress.value
    val rect = lerp(flight.from, flight.to, progress)
    val corner = with(LocalDensity.current) { lerp(flight.fromCornerPx, flight.toCornerPx, progress).toDp() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = lerp(flight.fromBackdrop, flight.toBackdrop, progress)))
    )
    Image(
        bitmap = flight.image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0))
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            }
            .clip(RoundedCornerShape(corner))
    )
}

/** The rect after the swipe's transform of the viewer: scaled about [pivot], then moved down by [offsetY]. */
private fun Rect.transformed(pivot: Offset, scale: Float, offsetY: Float): Rect = Rect(
    left = pivot.x + (left - pivot.x) * scale,
    top = pivot.y + (top - pivot.y) * scale + offsetY,
    right = pivot.x + (right - pivot.x) * scale,
    bottom = pivot.y + (bottom - pivot.y) * scale + offsetY
)

/** Hides the system bars with the viewer's overlays; light bar icons on its dark backdrop, restored on close. */
@Composable
private fun ViewerSystemBars(barsVisible: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
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
    LaunchedEffect(controller, barsVisible) {
        if (barsVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
