package sh.hnet.comfychair.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute

/**
 * Navigation by what is made: an Image tab and a Video tab, each holding the screens for its sources. The screens stay
 * separate routes with their own state; a tab only groups them, and the source switch picks one.
 */
enum class OutputTab { IMAGE, VIDEO }

/** One source of a tab: its label on the source switch and the screen it shows. */
class ScreenSource(val labelRes: Int, val route: MainRoute)

val OutputTab.sources: List<ScreenSource>
    get() = when (this) {
        OutputTab.IMAGE -> listOf(
            ScreenSource(R.string.label_source_text, MainRoute.TextToImage),
            ScreenSource(R.string.label_source_image, MainRoute.ImageToImage)
        )
        OutputTab.VIDEO -> listOf(
            ScreenSource(R.string.label_source_text, MainRoute.TextToVideo),
            ScreenSource(R.string.label_source_image, MainRoute.ImageToVideo)
        )
    }

/** The tab a generation screen belongs to; null for other routes. */
val MainRoute.outputTab: OutputTab?
    get() = OutputTab.entries.firstOrNull { tab -> tab.sources.any { it.route == this } }

fun mainRouteOf(route: String?): MainRoute? = when (route) {
    MainRoute.TextToImage.route -> MainRoute.TextToImage
    MainRoute.ImageToImage.route -> MainRoute.ImageToImage
    MainRoute.TextToVideo.route -> MainRoute.TextToVideo
    MainRoute.ImageToVideo.route -> MainRoute.ImageToVideo
    MainRoute.Gallery.route -> MainRoute.Gallery
    else -> null
}

/**
 * Show a screen the way tabs do: the previous screens' state is saved and restored, and switching never piles up
 * entries for Back.
 */
fun NavController.navigateToScreen(route: MainRoute) {
    navigate(route.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Shows another generation screen; provided by the main navigation host for the source switch. */
val LocalScreenNavigator = staticCompositionLocalOf<(MainRoute) -> Unit> { {} }
