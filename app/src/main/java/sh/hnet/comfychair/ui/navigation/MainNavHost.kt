package sh.hnet.comfychair.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.screens.AboutSettingsScreen
import sh.hnet.comfychair.ui.screens.ApplicationSettingsScreen
import sh.hnet.comfychair.ui.screens.GalleryScreen
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.SettingsRootScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Main navigation host: the generation screens (Image and Video tabs), the Gallery tab and the Settings tab with its
 * pages, above the navigation bar.
 *
 * @param onLeftSettings Called with the route shown when the user leaves the Settings tab (a change made there may
 *   need the generation screens reloaded)
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    onLogout: () -> Unit,
    onLeftSettings: (route: String) -> Unit,
    startDestination: String = MainRoute.TextToImage.route,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Sends a generated image to Image to Image or Image to Video as its source and switches to
    // that screen the way the navigation bar does
    val useAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit = { target, loadBitmap ->
        scope.launch {
            val bitmap = loadBitmap() ?: return@launch
            val route = when (target) {
                SourceTarget.IMAGE_TO_IMAGE -> {
                    imageToImageViewModel.useAsSource(context, bitmap)
                    MainRoute.ImageToImage
                }
                SourceTarget.IMAGE_TO_VIDEO -> {
                    imageToVideoViewModel.useAsSource(context, bitmap)
                    MainRoute.ImageToVideo
                }
            }
            navController.navigateToScreen(route)
        }
    }

    // A change in Settings may need the generation screens reloaded: tell the activity when the user leaves the tab
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    var wasInSettings by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        val inSettings = isSettingsRoute(currentRoute)
        if (wasInSettings && !inSettings && currentRoute != null) onLeftSettings(currentRoute)
        wasInSettings = inSettings
    }

    Scaffold(
        bottomBar = {
            MainNavigationBar(navController = navController)
        },
        modifier = modifier.imePadding()
    ) { paddingValues ->
        // The source switch on each screen shows the other screens of its tab
        CompositionLocalProvider(LocalScreenNavigator provides { route: MainRoute -> navController.navigateToScreen(route) }) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(paddingValues)
            ) {
                composable(MainRoute.TextToImage.route) {
                    val textToImageViewModel: TextToImageViewModel = viewModel()
                    TextToImageScreen(
                        generationViewModel = generationViewModel,
                        textToImageViewModel = textToImageViewModel,
                        onUseAsSource = useAsSource
                    )
                }

                composable(MainRoute.ImageToImage.route) {
                    ImageToImageScreen(
                        generationViewModel = generationViewModel,
                        imageToImageViewModel = imageToImageViewModel,
                        onUseAsSource = useAsSource
                    )
                }

                composable(MainRoute.TextToVideo.route) {
                    val textToVideoViewModel: TextToVideoViewModel = viewModel()
                    TextToVideoScreen(
                        generationViewModel = generationViewModel,
                        textToVideoViewModel = textToVideoViewModel,
                        onUseAsSource = useAsSource
                    )
                }

                // Server history; the same GenerationViewModel as the generation screens
                composable(MainRoute.Gallery.route) {
                    val galleryViewModel: GalleryViewModel = viewModel()
                    GalleryScreen(
                        generationViewModel = generationViewModel,
                        galleryViewModel = galleryViewModel
                    )
                }

                // Settings tab: the list, and the pages it opens (Back returns to the list)
                composable(MainRoute.Settings.route) {
                    SettingsRootScreen(onOpenPage = { page -> navController.navigate(page.route) })
                }
                composable(SettingsRoute.Server.route) {
                    ServerSettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onLogout = onLogout
                    )
                }
                composable(SettingsRoute.Workflows.route) {
                    WorkflowsSettingsScreen(
                        viewModel = workflowManagementViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(SettingsRoute.Application.route) {
                    ApplicationSettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(SettingsRoute.About.route) {
                    AboutSettingsScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(MainRoute.ImageToVideo.route) {
                    ImageToVideoScreen(
                        generationViewModel = generationViewModel,
                        imageToVideoViewModel = imageToVideoViewModel,
                        onUseAsSource = useAsSource
                    )
                }
            }
        }
    }
}
