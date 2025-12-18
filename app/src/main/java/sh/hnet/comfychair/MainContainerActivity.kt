package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.navigation.MainNavHost
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

/**
 * Container activity that hosts the main navigation graph.
 * Uses Jetpack Compose for UI with bottom navigation.
 */
class MainContainerActivity : ComponentActivity() {

    companion object {
        const val RESULT_REFRESH_NEEDED = 100
    }

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    // ViewModel for generation state management
    private val generationViewModel: GenerationViewModel by viewModels()

    // Settings activity launcher
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_REFRESH_NEEDED) {
            // Settings cleared/restored - restart activity fresh to reload all ViewModels
            // Using finish + start instead of recreate() to clear ViewModels
            val intent = Intent(this, MainContainerActivity::class.java)
            intent.putExtra("hostname", hostname)
            intent.putExtra("port", port)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get connection parameters from intent
        hostname = intent.getStringExtra("hostname") ?: ""
        port = intent.getIntExtra("port", 8188)

        // Initialize the ViewModel with connection parameters
        generationViewModel.initialize(this, hostname, port)

        // Determine start destination based on active generation owner
        val startDestination = when (generationViewModel.generationState.value.ownerId) {
            TextToImageViewModel.OWNER_ID -> MainRoute.TextToImage.route
            ImageToImageViewModel.OWNER_ID -> MainRoute.ImageToImage.route
            TextToVideoViewModel.OWNER_ID -> MainRoute.TextToVideo.route
            ImageToVideoViewModel.OWNER_ID -> MainRoute.ImageToVideo.route
            else -> MainRoute.TextToImage.route
        }

        setContent {
            ComfyChairTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavHost(
                        generationViewModel = generationViewModel,
                        onNavigateToSettings = { openSettings() },
                        onNavigateToGallery = { openGallery() },
                        onLogout = { logout() },
                        startDestination = startDestination
                    )
                }
            }
        }
    }

    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        generationViewModel.logout()
        finish()
    }

    /**
     * Open the Settings activity
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsContainerActivity::class.java)
        intent.putExtra("hostname", hostname)
        intent.putExtra("port", port)
        settingsLauncher.launch(intent)
    }

    /**
     * Open the Gallery activity
     */
    private fun openGallery() {
        val intent = Intent(this, GalleryContainerActivity::class.java)
        intent.putExtra("hostname", hostname)
        intent.putExtra("port", port)
        startActivity(intent)
    }

    override fun onStop() {
        super.onStop()
        // Save generation state when going to background
        generationViewModel.saveGenerationState(this)
    }

    override fun onResume() {
        super.onResume()
        // Check if there's a pending generation that may have completed while in background
        if (generationViewModel.generationState.value.isGenerating) {
            generationViewModel.checkServerForCompletion()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel handles cleanup automatically via onCleared()
    }
}
