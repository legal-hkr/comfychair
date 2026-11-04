package sh.hnet.comfychair.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

/**
 * Main navigation host that contains all the generation screens.
 * Uses a Scaffold with bottom navigation bar.
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onLogout: () -> Unit,
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

    Scaffold(
        bottomBar = {
            MainNavigationBar(
                navController = navController,
                onNavigateToGallery = onNavigateToGallery,
                onNavigateToSettings = onNavigateToSettings
            )
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
                        onUseAsSource = useAsSource,
                        onNavigateToSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                }

                composable(MainRoute.ImageToImage.route) {
                    ImageToImageScreen(
                        generationViewModel = generationViewModel,
                        imageToImageViewModel = imageToImageViewModel,
                        onUseAsSource = useAsSource,
                        onNavigateToSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                }

                composable(MainRoute.TextToVideo.route) {
                    val textToVideoViewModel: TextToVideoViewModel = viewModel()
                    TextToVideoScreen(
                        generationViewModel = generationViewModel,
                        textToVideoViewModel = textToVideoViewModel,
                        onUseAsSource = useAsSource,
                        onNavigateToSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                }

                composable(MainRoute.ImageToVideo.route) {
                    ImageToVideoScreen(
                        generationViewModel = generationViewModel,
                        imageToVideoViewModel = imageToVideoViewModel,
                        onUseAsSource = useAsSource,
                        onNavigateToSettings = onNavigateToSettings,
                        onLogout = onLogout
                    )
                }
            }
        }
    }
}
