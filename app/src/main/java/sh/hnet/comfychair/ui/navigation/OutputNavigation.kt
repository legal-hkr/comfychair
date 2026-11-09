package sh.hnet.comfychair.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.VideoToVideoViewModel

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
            ScreenSource(R.string.label_source_image, MainRoute.ImageToVideo),
            ScreenSource(R.string.label_source_video, MainRoute.VideoToVideo)
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
    MainRoute.VideoToVideo.route -> MainRoute.VideoToVideo
    MainRoute.Gallery.route -> MainRoute.Gallery
    MainRoute.Settings.route -> MainRoute.Settings
    else -> null
}

/** The screen that submitted a job (its owner id), e.g. to show its result. */
fun routeForOwner(ownerId: String?): MainRoute? = when (ownerId) {
    TextToImageViewModel.OWNER_ID -> MainRoute.TextToImage
    ImageToImageViewModel.OWNER_ID -> MainRoute.ImageToImage
    TextToVideoViewModel.OWNER_ID -> MainRoute.TextToVideo
    ImageToVideoViewModel.OWNER_ID -> MainRoute.ImageToVideo
    VideoToVideoViewModel.OWNER_ID -> MainRoute.VideoToVideo
    else -> null
}

/** The Settings tab: its list and the pages opened from it. */
fun isSettingsRoute(route: String?): Boolean =
    route == MainRoute.Settings.route || SettingsRoute.all.any { it.route == route }

/**
 * Show a screen. Every switch (tab, source, Gallery, Settings) is a step in the history, and Back retraces them; from
 * the first one it leaves the main activity for the login screen, which then leaves the app. A screen visited twice
 * shares its state, since the screens' view models belong to the activity, not to the history entry.
 */
fun NavController.navigateToScreen(route: MainRoute) {
    navigate(route.route) { launchSingleTop = true }
}

/**
 * Show [route] as when it was first opened, as tapping its selected tab does: it leaves the history with the pages
 * opened from it (the Settings pages) and comes back fresh, at its top.
 */
fun NavController.reopenScreen(route: MainRoute) {
    navigate(route.route) {
        popUpTo(route.route) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Tabs tapped again while their own screen (not one of its pages) is shown: the screen scrolls back to its top, with
 * its top app bar expanded, as when first opened. Provided by the main navigation host.
 */
val LocalTabReselects = staticCompositionLocalOf<Flow<MainRoute>> { emptyFlow() }

/** A collapsing top app bar opened out again, as a page shows it at its top (a scroll by code doesn't reach it). */
@OptIn(ExperimentalMaterial3Api::class)
suspend fun TopAppBarScrollBehavior.expandAnimated() {
    animate(state.heightOffset, 0f) { value, _ -> state.heightOffset = value }
    state.contentOffset = 0f
}

/** Shows another generation screen; provided by the main navigation host for the source switch. */
val LocalScreenNavigator = staticCompositionLocalOf<(MainRoute) -> Unit> { {} }
