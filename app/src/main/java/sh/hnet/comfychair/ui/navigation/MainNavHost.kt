package sh.hnet.comfychair.ui.navigation

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.screens.InpaintingScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.InpaintingViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

/**
 * Main navigation host that contains all the generation screens.
 * Uses a Scaffold with bottom navigation bar.
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    Scaffold(
        bottomBar = {
            MainNavigationBar(
                navController = navController,
                onNavigateToGallery = onNavigateToGallery
            )
        },
        modifier = modifier.imePadding()
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = MainRoute.TextToImage.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(MainRoute.TextToImage.route) {
                val textToImageViewModel: TextToImageViewModel = viewModel()
                TextToImageScreen(
                    generationViewModel = generationViewModel,
                    textToImageViewModel = textToImageViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.TextToVideo.route) {
                val textToVideoViewModel: TextToVideoViewModel = viewModel()
                TextToVideoScreen(
                    generationViewModel = generationViewModel,
                    textToVideoViewModel = textToVideoViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }

            composable(MainRoute.Inpainting.route) {
                val inpaintingViewModel: InpaintingViewModel = viewModel()
                InpaintingScreen(
                    generationViewModel = generationViewModel,
                    inpaintingViewModel = inpaintingViewModel,
                    onNavigateToSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }
        }
    }
}
