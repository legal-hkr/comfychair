package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.runBlocking
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.ConnectionAlertDialog
import sh.hnet.comfychair.ui.components.ConnectionAlertState
import sh.hnet.comfychair.ui.navigation.MainNavHost
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GenerationEvent
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

/**
 * Container activity that hosts the main navigation graph.
 * Uses Jetpack Compose for UI with bottom navigation.
 */
class MainContainerActivity : ComponentActivity() {

    // Constants
    companion object {
        const val RESULT_REFRESH_NEEDED = 100
    }

    // ViewModels
    private val generationViewModel: GenerationViewModel by viewModels()

    // Activity result launchers
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_REFRESH_NEEDED -> {
                // Settings cleared/restored or workflows changed - restart activity fresh
                val intent = Intent(this, MainContainerActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
            SettingsContainerActivity.RESULT_CONNECTION_CHANGED -> {
                // Connection settings changed - return to login screen
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
        }
    }

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

        // Initialize GalleryRepository for offline cache access
        GalleryRepository.getInstance().initialize(this)

        // Enable debug logging based on saved preference (must be early to capture init logs)
        DebugLogger.setEnabled(AppSettings.isDebugLoggingEnabled(this))
        DebugLogger.i("MainContainer", "onCreate - debug logging enabled")

        // Initialize the ViewModel (uses ConnectionManager internally)
        generationViewModel.initialize(this)

        // Set current server ID for per-server media scoping
        val serverId = ConnectionManager.currentServerId
        DebugLogger.d("MainContainer", "setCurrentServerId: ${serverId?.take(8) ?: "NULL"}...")
        MediaStateHolder.setCurrentServerId(serverId)

        // Set caching mode based on user preference
        val isMemoryFirst = AppSettings.isMemoryFirstCache(this)
        DebugLogger.d("MainContainer", "caching mode: ${if (isMemoryFirst) "memory-first" else "disk-first"}")
        MediaStateHolder.setMemoryFirstMode(isMemoryFirst, applicationContext)
        MediaCache.setMemoryFirstMode(isMemoryFirst)

        // Initialize MediaCache with context for image/video fetching
        MediaCache.ensureInitialized(applicationContext)

        // Load saved media state before screens initialize
        if (isMemoryFirst) {
            // Memory-first: load everything from disk into memory
            DebugLogger.d("MainContainer", "loading media from disk (memory-first mode)")
            runBlocking {
                MediaStateHolder.loadFromDisk(applicationContext)
            }
        } else {
            // Disk-first: just discover video promptIds (bytes read on-demand)
            DebugLogger.d("MainContainer", "discovering video promptIds (disk-first mode)")
            MediaStateHolder.discoverVideoPromptIds(applicationContext)
        }

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
                // State for connection alert dialog
                var connectionAlertState by remember { mutableStateOf<ConnectionAlertState?>(null) }

                // Observe connection failure events
                LaunchedEffect(Unit) {
                    generationViewModel.events.collect { event ->
                        if (event is GenerationEvent.ConnectionFailed) {
                            connectionAlertState = ConnectionAlertState(
                                failureType = event.failureType,
                                hasOfflineCache = event.hasOfflineCache
                            )
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavHost(
                        generationViewModel = generationViewModel,
                        onNavigateToSettings = { openSettings() },
                        onNavigateToGallery = { openGallery() },
                        onLogout = { logout() },
                        startDestination = startDestination
                    )
                }

                // Show connection alert dialog when connection fails
                connectionAlertState?.let { state ->
                    ConnectionAlertDialog(
                        failureType = state.failureType,
                        hasOfflineCache = state.hasOfflineCache,
                        onRetry = {
                            connectionAlertState = null
                            ConnectionManager.resetReconnectAttempts()
                            ConnectionManager.openWebSocket()
                        },
                        onGoOffline = {
                            connectionAlertState = null
                            AppSettings.setOfflineMode(this@MainContainerActivity, true)
                            // Stay in app with offline mode enabled
                        },
                        onReturnToLogin = {
                            connectionAlertState = null
                            generationViewModel.logout()
                            finish()
                        },
                        onDismiss = { connectionAlertState = null }
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
        settingsLauncher.launch(intent)
    }

    /**
     * Open the Gallery activity
     */
    private fun openGallery() {
        val intent = Intent(this, GalleryContainerActivity::class.java)
        startActivity(intent)
    }

    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        generationViewModel.logout()
        finish()
    }

    override fun onStop() {
        super.onStop()
        DebugLogger.d("MainContainer", "onStop - saving generation state")
        // Save generation state when going to background
        generationViewModel.saveGenerationState(this)

        // Persist all dirty media to disk synchronously to ensure completion before process death
        // Only persist in memory-first mode (disk-first writes immediately, no persistence needed)
        // Also skip if media cache is disabled
        val isMemoryFirst = AppSettings.isMemoryFirstCache(this)
        val isCacheDisabled = AppSettings.isMediaCacheDisabled(this)
        DebugLogger.d("MainContainer", "onStop - memoryFirst=$isMemoryFirst, cacheDisabled=$isCacheDisabled")
        if (isMemoryFirst && !isCacheDisabled) {
            DebugLogger.d("MainContainer", "onStop - persisting media to disk")
            runBlocking {
                MediaStateHolder.persistToDisk(applicationContext)
            }
        }
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
