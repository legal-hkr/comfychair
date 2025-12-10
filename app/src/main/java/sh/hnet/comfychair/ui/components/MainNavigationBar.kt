package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute

/**
 * Main navigation bar for the app with 4 destinations:
 * - Text-to-Image (icon button)
 * - Text-to-Video (icon button)
 * - Inpainting (icon button)
 * - Gallery (FAB) - toggles back to previous screen when tapped again
 *
 * Uses BottomAppBar with navigation buttons on left and FAB on right.
 */
@Composable
fun MainNavigationBar(
    navController: NavController
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Track the previous route before navigating to Gallery
    var previousRoute by rememberSaveable { mutableStateOf(MainRoute.TextToImage.route) }

    // Update previous route when we're not in Gallery
    if (currentRoute != MainRoute.Gallery.route && currentRoute != null) {
        previousRoute = currentRoute
    }

    BottomAppBar(
        actions = {
            // Add left padding to align with screen content (16dp - default 4dp = 12dp extra)
            Spacer(modifier = Modifier.width(12.dp))

            // Text to Image
            if (currentRoute == MainRoute.TextToImage.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = stringResource(R.string.nav_text_to_image)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TextToImage.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = stringResource(R.string.nav_text_to_image),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Text to Video
            if (currentRoute == MainRoute.TextToVideo.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = stringResource(R.string.nav_text_to_video)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.TextToVideo.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = stringResource(R.string.nav_text_to_video),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Inpainting
            if (currentRoute == MainRoute.Inpainting.route) {
                FilledIconButton(
                    onClick = { },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Filled.InvertColors,
                        contentDescription = stringResource(R.string.nav_inpainting)
                    )
                }
            } else {
                IconButton(onClick = {
                    navController.navigate(MainRoute.Inpainting.route) {
                        popUpTo(MainRoute.TextToImage.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(
                        Icons.Filled.InvertColors,
                        contentDescription = stringResource(R.string.nav_inpainting),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (currentRoute == MainRoute.Gallery.route) {
                        // Toggle back to previous screen
                        navController.navigate(previousRoute) {
                            popUpTo(MainRoute.TextToImage.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    } else {
                        // Navigate to Gallery
                        navController.navigate(MainRoute.Gallery.route) {
                            popUpTo(MainRoute.TextToImage.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                containerColor = if (currentRoute == MainRoute.Gallery.route)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    Icons.Filled.Collections,
                    contentDescription = stringResource(R.string.nav_gallery),
                    tint = if (currentRoute == MainRoute.Gallery.route)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    )
}
