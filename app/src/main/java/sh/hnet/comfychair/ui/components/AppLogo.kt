package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import kotlin.math.roundToInt

/**
 * The app's logo above its title (login screen, welcome wizard). Decorative.
 *
 * The logo file is the launcher icon's foreground, with the safe zone around the mark: the mark is moved to start on
 * the title's line ([titleInset] from the start), and the empty band below it doesn't push the title down.
 */
@Composable
fun AppLogo(titleInset: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier
            .size(LOGO_SIZE)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val markStart = (placeable.width * LOGO_MARK_START).roundToInt()
                val emptyBelow = (placeable.height * LOGO_EMPTY_BELOW).roundToInt()
                layout(placeable.width, placeable.height - emptyBelow) {
                    placeable.place(titleInset.roundToPx() - markStart, 0)
                }
            }
    )
}

private val LOGO_SIZE = 144.dp

/** Where the mark starts in the logo file, as a share of its width (measured from the icon's artwork). */
private const val LOGO_MARK_START = 0.30f

/** The empty band below the mark in the logo file, as a share of its height. */
private const val LOGO_EMPTY_BELOW = 0.265f
