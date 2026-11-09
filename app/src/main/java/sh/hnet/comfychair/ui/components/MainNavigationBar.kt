package sh.hnet.comfychair.ui.components

import sh.hnet.comfychair.ui.navigation.sources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.navigation.OutputTab
import sh.hnet.comfychair.ui.navigation.isSettingsRoute
import sh.hnet.comfychair.ui.navigation.mainRouteOf
import sh.hnet.comfychair.ui.navigation.navigateToScreen
import sh.hnet.comfychair.ui.navigation.outputTab

/**
 * Main navigation: Image, Video, Gallery and Settings, each with icon and label. Image and Video open the screen of
 * the source used last in that tab; the source switch on the screen picks another. Their badges count your jobs for
 * the tab, or show a dot while a result finished elsewhere waits to be seen. Gallery and Settings tapped again show
 * anew, as when first opened ([onReopen]: Settings' list from any of its pages, both at their top); Image and Video go
 * to their first source (Text), as their source switch does.
 *
 * @param lastImageRoute Screen the Image tab opens (the source used last)
 * @param lastVideoRoute Screen the Video tab opens
 * @param ownJobs Your waiting or running jobs per tab
 * @param unseenResults Tabs with a finished result not seen yet
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainNavigationBar(
    navController: NavController,
    lastImageRoute: MainRoute,
    lastVideoRoute: MainRoute,
    ownJobs: Map<OutputTab, Int>,
    unseenResults: Set<OutputTab>,
    /** Show a screen anew, as when first opened (its tab tapped again) */
    onReopen: (MainRoute) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = mainRouteOf(navBackStackEntry?.destination?.route)
    val currentTab = currentRoute?.outputTab

    ShortNavigationBar {
        TabItem(
            selected = currentTab == OutputTab.IMAGE,
            label = stringResource(R.string.nav_tab_image),
            selectedIcon = Icons.Filled.Image,
            icon = Icons.Outlined.Image,
            badgeCount = ownJobs[OutputTab.IMAGE] ?: 0,
            badgeDot = OutputTab.IMAGE in unseenResults,
            onClick = { navController.navigateToScreen(lastImageRoute) },
            onReselect = { showFirstSource(navController, OutputTab.IMAGE, currentRoute) }
        )
        TabItem(
            selected = currentTab == OutputTab.VIDEO,
            label = stringResource(R.string.nav_tab_video),
            selectedIcon = Icons.Filled.Movie,
            icon = Icons.Outlined.Movie,
            badgeCount = ownJobs[OutputTab.VIDEO] ?: 0,
            badgeDot = OutputTab.VIDEO in unseenResults,
            onClick = { navController.navigateToScreen(lastVideoRoute) },
            onReselect = { showFirstSource(navController, OutputTab.VIDEO, currentRoute) }
        )
        TabItem(
            selected = currentRoute == MainRoute.Gallery,
            label = stringResource(R.string.nav_gallery),
            selectedIcon = Icons.Filled.PhotoLibrary,
            icon = Icons.Outlined.PhotoLibrary,
            onClick = { navController.navigateToScreen(MainRoute.Gallery) },
            onReselect = { onReopen(MainRoute.Gallery) }
        )
        TabItem(
            selected = isSettingsRoute(navBackStackEntry?.destination?.route),
            label = stringResource(R.string.action_settings),
            selectedIcon = Icons.Filled.Settings,
            icon = Icons.Outlined.Settings,
            onClick = { navController.navigateToScreen(MainRoute.Settings) },
            onReselect = { onReopen(MainRoute.Settings) }
        )
    }
}

/** A tab tapped again: its first source (Text), as the source switch shows it; nothing when that's already shown. */
private fun showFirstSource(navController: NavController, tab: OutputTab, current: MainRoute?) {
    val first = tab.sources.first().route
    if (current != first) navController.navigateToScreen(first)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabItem(
    selected: Boolean,
    label: String,
    selectedIcon: ImageVector,
    icon: ImageVector,
    badgeCount: Int = 0,
    badgeDot: Boolean = false,
    /** Tapped while selected */
    onReselect: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val badgeDescription = when {
        badgeCount > 0 -> pluralStringResource(R.plurals.label_queue_own_jobs, badgeCount, badgeCount)
        badgeDot -> stringResource(R.string.content_description_new_result)
        else -> null
    }
    ShortNavigationBarItem(
        selected = selected,
        onClick = { if (!selected) onClick() else onReselect?.invoke() },
        icon = {
            BadgedBox(
                badge = {
                    when {
                        badgeCount > 0 -> Badge { Text(badgeCount.toString()) }
                        badgeDot -> Badge()
                    }
                }
            ) {
                Icon(if (selected) selectedIcon else icon, contentDescription = null)
            }
        },
        modifier = if (badgeDescription != null) Modifier.semantics { stateDescription = badgeDescription } else Modifier,
        // One line; a label that doesn't fit ends with an ellipsis, and TalkBack still reads it whole
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
