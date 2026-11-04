package sh.hnet.comfychair.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
 * the source used last in that tab; the source switch on the screen picks another.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainNavigationBar(
    navController: NavController
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = mainRouteOf(navBackStackEntry?.destination?.route)
    val currentTab = currentRoute?.outputTab

    // The source used last in each tab, so the tab opens where the user left it
    var lastImageRoute by rememberSaveable { mutableStateOf(MainRoute.TextToImage.route) }
    var lastVideoRoute by rememberSaveable { mutableStateOf(MainRoute.TextToVideo.route) }
    LaunchedEffect(currentRoute) {
        when (currentRoute?.outputTab) {
            OutputTab.IMAGE -> lastImageRoute = currentRoute.route
            OutputTab.VIDEO -> lastVideoRoute = currentRoute.route
            null -> Unit
        }
    }

    ShortNavigationBar {
        TabItem(
            selected = currentTab == OutputTab.IMAGE,
            label = stringResource(R.string.nav_tab_image),
            selectedIcon = Icons.Filled.Image,
            icon = Icons.Outlined.Image,
            onClick = { mainRouteOf(lastImageRoute)?.let(navController::navigateToScreen) }
        )
        TabItem(
            selected = currentTab == OutputTab.VIDEO,
            label = stringResource(R.string.nav_tab_video),
            selectedIcon = Icons.Filled.Movie,
            icon = Icons.Outlined.Movie,
            onClick = { mainRouteOf(lastVideoRoute)?.let(navController::navigateToScreen) }
        )
        TabItem(
            selected = currentRoute == MainRoute.Gallery,
            label = stringResource(R.string.nav_gallery),
            selectedIcon = Icons.Filled.PhotoLibrary,
            icon = Icons.Outlined.PhotoLibrary,
            onClick = { navController.navigateToScreen(MainRoute.Gallery) }
        )
        TabItem(
            selected = isSettingsRoute(navBackStackEntry?.destination?.route),
            label = stringResource(R.string.action_settings),
            selectedIcon = Icons.Filled.Settings,
            icon = Icons.Outlined.Settings,
            onClick = { navController.navigateToScreen(MainRoute.Settings) }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabItem(
    selected: Boolean,
    label: String,
    selectedIcon: ImageVector,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ShortNavigationBarItem(
        selected = selected,
        onClick = { if (!selected) onClick() },
        icon = { Icon(if (selected) selectedIcon else icon, contentDescription = null) },
        // One line; a label that doesn't fit ends with an ellipsis, and TalkBack still reads it whole
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}
