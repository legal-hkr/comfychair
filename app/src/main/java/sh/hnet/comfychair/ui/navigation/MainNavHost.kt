package sh.hnet.comfychair.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
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
    // Shared by the generation screens, e.g. for "Source image replaced" with Undo
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sourceReplacedMessage = stringResource(R.string.msg_source_image_replaced)
    val undoLabel = stringResource(R.string.button_undo)

    // Sends a generated image to Image to Image or Image to Video as its source, switches to that
    // screen the way the navigation bar does and offers Undo
    val useAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit = { target, loadBitmap ->
        scope.launch {
            val bitmap = loadBitmap() ?: return@launch
            val route: String
            val undo: () -> Unit
            when (target) {
                SourceTarget.IMAGE_TO_IMAGE -> {
                    imageToImageViewModel.useAsSource(context, bitmap)
                    route = MainRoute.ImageToImage.route
                    undo = imageToImageViewModel::undoUseAsSource
                }
                SourceTarget.IMAGE_TO_VIDEO -> {
                    imageToVideoViewModel.useAsSource(context, bitmap)
                    route = MainRoute.ImageToVideo.route
                    undo = imageToVideoViewModel::undoUseAsSource
                }
            }
            navController.navigate(route) {
                popUpTo(MainRoute.TextToImage.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            val result = snackbarHostState.showSnackbar(sourceReplacedMessage, undoLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
