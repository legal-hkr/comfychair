package sh.hnet.comfychair.ui.components

import androidx.compose.runtime.rememberUpdatedState
import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize

/**
 * Where a medium is shown on a screen (a gallery tile, a generation preview, a result page), so the media viewer can
 * fly it out of there on open and back into it on close.
 *
 * @param bounds Full bounds in root coordinates, also the parts scrolled out of view
 * @param visible Whether any of it is on screen; a hidden origin gets a fade instead of a flight
 * @param fit The medium is fitted inside [bounds] (preview); otherwise it fills them, cropped (tile)
 * @param still The image the origin shows, to fly when the viewer has none at hand yet
 */
class MediaOrigin(val bounds: Rect, val visible: Boolean, val fit: Boolean, val cornerPx: Float, val still: Bitmap?) {
    /** Where the medium itself is drawn, for an image of [aspect] (width / height). */
    fun mediaRect(aspect: Float): Rect = if (fit) bounds.fitted(aspect) else bounds
}

/** The origins on screen by key, and the one hidden while its medium is in the viewer. */
class MediaOrigins {
    // Read only when the viewer opens or closes, so a plain map: no recomposition while scrolling
    private val origins = HashMap<String, MediaOrigin>()

    /** The origin whose medium is lifted into the viewer: it shows as an empty place meanwhile. */
    var hiddenKey by mutableStateOf<String?>(null)

    operator fun get(key: String): MediaOrigin? = origins[key]

    internal fun put(key: String, origin: MediaOrigin) {
        origins[key] = origin
    }

    internal fun remove(key: String) {
        origins.remove(key)
    }
}

val LocalMediaOrigins = staticCompositionLocalOf { MediaOrigins() }

/**
 * Marks this element as the origin of the medium with [key] for the viewer's flight, and hides it while the medium
 * is in the viewer.
 *
 * @param fit Whether the medium is fitted inside (preview) rather than cropped to fill (tile)
 * @param corner Corner radius of the element
 * @param still The image shown there, if the element holds it (a tile keeps its bitmap after the shared cache drops it)
 */
fun Modifier.mediaOrigin(key: String?, fit: Boolean, corner: Dp, still: Bitmap? = null): Modifier = if (key == null) this else composed {
    val currentStill by rememberUpdatedState(still)
    val origins = LocalMediaOrigins.current
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    DisposableEffect(origins, key) { onDispose { origins.remove(key) } }
    onGloballyPositioned { coordinates ->
        val bounds = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        origins.put(key, MediaOrigin(bounds, !coordinates.boundsInRoot().isEmpty, fit, cornerPx, currentStill))
    }.graphicsLayer { alpha = if (origins.hiddenKey == key) 0f else 1f }
}

/** This rect's largest centred sub-rect of [aspect] (width / height). */
internal fun Rect.fitted(aspect: Float): Rect {
    if (aspect <= 0f || width <= 0f || height <= 0f) return this
    val size = if (width / height > aspect) Size(height * aspect, height) else Size(width, width / aspect)
    return Rect(Offset(center.x - size.width / 2f, center.y - size.height / 2f), size)
}

/** Origin key of a server output (gallery tile, result page): the same item has the same key everywhere. */
fun mediaOriginKey(promptId: String, filename: String): String = "output:${promptId}_$filename"
