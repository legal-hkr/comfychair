package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ConnectionAlertDialog
import sh.hnet.comfychair.ui.screens.GalleryScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel

/**
 * Container activity that hosts the Gallery screen.
 * Uses a simple layout with FAB to navigate back to generation.
 */
class GalleryContainerActivity : ComponentActivity() {

    // ViewModels
    private val generationViewModel: GenerationViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()

    // Lifecycle methods
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Guard check - redirect to login if not connected (unless in offline mode)
        val isOfflineMode = AppSettings.isOfflineMode(this)
        if (!ConnectionManager.isConnected && !isOfflineMode) {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        // Initialize the GenerationViewModel (uses ConnectionManager internally)
        generationViewModel.initialize(this)

        // Initialize MediaCache with context for image/video fetching
        MediaCache.ensureInitialized(applicationContext)

        setContent {
            ComfyChairTheme {
                // Observe connection alert state from ConnectionManager (single source of truth)
                val connectionAlertState by ConnectionManager.connectionAlertState.collectAsState()
                val isReconnecting by ConnectionManager.isReconnecting.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        floatingActionButton = {
                            FloatingActionButton(
                                onClick = { finish() },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = stringResource(R.string.menu_generation),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
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

                // Show connection alert dialog when connection fails
                connectionAlertState?.let { state ->
                    ConnectionAlertDialog(
                        failureType = state.failureType,
                        hasOfflineCache = state.hasOfflineCache,
                        isReconnecting = isReconnecting,
                        onReconnect = {
                            ConnectionManager.retrySingleAttempt(this@GalleryContainerActivity)
                        },
                        onGoOffline = {
                            ConnectionManager.clearConnectionAlert()
                            AppSettings.setOfflineMode(this@GalleryContainerActivity, true)
                        },
                        onReturnToLogin = {
                            ConnectionManager.clearConnectionAlert()
                            logout()
                        },
                        onDismiss = { ConnectionManager.clearConnectionAlert() }
                    )
                }
            }
        }
    }

    // Navigation helpers

    /**
     * Open the Settings activity
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsContainerActivity::class.java)
        startActivity(intent)
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
}
