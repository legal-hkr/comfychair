package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.ui.screens.GalleryScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel

/**
 * Container activity that hosts the Gallery screen.
 * Uses a simple layout with FAB to navigate back to generation.
 */
class GalleryContainerActivity : ComponentActivity() {

    // Connection information (passed from MainContainerActivity)
    private var hostname: String = ""
    private var port: Int = 8188

    // ViewModels
    private val generationViewModel: GenerationViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get connection parameters from intent
        hostname = intent.getStringExtra("hostname") ?: ""
        port = intent.getIntExtra("port", 8188)

        // Initialize the GenerationViewModel with connection parameters
        generationViewModel.initialize(this, hostname, port)

        setContent {
            ComfyChairTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = {
                            BottomAppBar(
                                actions = { },
                                floatingActionButton = {
                                    // Back to generation FAB
                                    FloatingActionButton(
                                        onClick = { finish() },
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.content_description_back),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            )
                        }
                    ) { paddingValues ->
                        GalleryScreen(
                            generationViewModel = generationViewModel,
                            galleryViewModel = galleryViewModel,
                            onNavigateToSettings = { openSettings() },
                            onLogout = { logout() },
                            modifier = Modifier.padding(paddingValues)
                        )
                    }
                }
            }
        }
    }

    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        generationViewModel.logout()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Open the Settings activity
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsContainerActivity::class.java)
        intent.putExtra("hostname", hostname)
        intent.putExtra("port", port)
        startActivity(intent)
    }
}
