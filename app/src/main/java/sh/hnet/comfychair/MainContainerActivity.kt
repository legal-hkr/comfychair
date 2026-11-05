package sh.hnet.comfychair

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.repository.GalleryRepository
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.GalleryMetadataCache
import sh.hnet.comfychair.storage.MediaStorageMode
import sh.hnet.comfychair.ui.components.ConnectionAlertDialog
import sh.hnet.comfychair.ui.components.dialogs.ActionDialog
import sh.hnet.comfychair.ui.components.dialogs.DialogAction
import sh.hnet.comfychair.ui.navigation.MainNavHost
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.PrivacyUtils
import sh.hnet.comfychair.util.ServerUrlUtils
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Container activity that hosts the main navigation graph.
 * Uses Jetpack Compose for UI with bottom navigation.
 */
class MainContainerActivity : ComponentActivity() {

    companion object {
        /** Route to show after a restart, e.g. the screen the user went to when leaving Settings. */
        private const val EXTRA_START_ROUTE = "start_route"
    }

    // ViewModels
    private val generationViewModel: GenerationViewModel by viewModels()
    // Screen view models belong to the activity, so a screen visited twice in the history shares its state
    private val textToImageViewModel: TextToImageViewModel by viewModels()
    private val imageToImageViewModel: ImageToImageViewModel by viewModels()
    private val textToVideoViewModel: TextToVideoViewModel by viewModels()
    private val imageToVideoViewModel: ImageToVideoViewModel by viewModels()
    private val galleryViewModel: GalleryViewModel by viewModels()
    private val mediaViewerViewModel: MediaViewerViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val workflowManagementViewModel: WorkflowManagementViewModel by viewModels()

    // Set by a change in Settings that needs the generation screens reloaded (restored backup, cleared data,
    // changed workflows); applied when the user leaves the Settings tab
    private var reloadPending = false

    // Credential storage — used to persist refreshed browser-session cookies
    private val credentialStorage by lazy { CredentialStorage(applicationContext) }

    // Launcher for BrowserAuthActivity — handles mid-session cookie refresh
    private val browserAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data
                ?.getStringExtra(BrowserAuthActivity.RESULT_COOKIES)
                .orEmpty()
            if (cookies.isNotEmpty()) {
                val serverId = ConnectionManager.currentServerId ?: return@registerForActivityResult
                val newCredentials = AuthCredentials.Cookie(cookies)
                credentialStorage.saveCredentials(serverId, newCredentials)
                ConnectionManager.updateCredentials(newCredentials)
                ConnectionManager.retrySingleAttempt(this@MainContainerActivity)
            }
        }
        // If cancelled or no cookies: leave the user in the current state;
        // the session-expired dialog remains dismissible via Return to Login.
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

        // Set media storage based on user preference
        val storageMode = AppSettings.getMediaStorageMode(this)
        val isMemoryFirst = storageMode != MediaStorageMode.DEVICE
        DebugLogger.d("MainContainer", "media storage: $storageMode")
        // The gallery list is kept only for offline mode, i.e. with storage on the device
        if (isMemoryFirst) GalleryMetadataCache.clearAllCaches(applicationContext)
        if (storageMode == MediaStorageMode.MEMORY_ONLY) {
            MediaCache.setMemoryFirstMode(true)
            MediaCache.deleteDiskCache(applicationContext)
        } else {
            MediaCache.setMemoryFirstMode(isMemoryFirst)
        }

        // Initialize MediaCache with context for image/video fetching
        MediaCache.ensureInitialized(applicationContext)

        // Cleanup and loading of stored media run in the background; the screens restore their
        // previews once MediaStateHolder announces the media are ready
        MediaStateHolder.prepareForStorageMode(applicationContext, storageMode)

        settingsViewModel.initialize(applicationContext)

        // Determine start destination: the screen chosen before a reload, else the active generation's owner
        val startDestination = intent.getStringExtra(EXTRA_START_ROUTE) ?: when (generationViewModel.generationState.value.ownerId) {
            TextToImageViewModel.OWNER_ID -> MainRoute.TextToImage.route
            ImageToImageViewModel.OWNER_ID -> MainRoute.ImageToImage.route
            TextToVideoViewModel.OWNER_ID -> MainRoute.TextToVideo.route
            ImageToVideoViewModel.OWNER_ID -> MainRoute.ImageToVideo.route
            else -> MainRoute.TextToImage.route
        }

        val activity = this
        setContent {
            ComfyChairTheme {
                // Observe connection alert state from ConnectionManager (single source of truth)
                val connectionAlertState by ConnectionManager.connectionAlertState.collectAsState()
                val isReconnecting by ConnectionManager.isReconnecting.collectAsState()
                val sessionExpired by ConnectionManager.sessionExpired.collectAsState()

                // Settings changes that need a reload, or a return to login
                LaunchedEffect(Unit) {
                    settingsViewModel.events.collect { event ->
                        when (event) {
                            is SettingsEvent.RefreshNeeded -> reloadPending = true
                            is SettingsEvent.NavigateToLogin -> returnToLogin()
                            else -> Unit // Toasts and dialogs are handled by the settings pages
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    workflowManagementViewModel.events.collect { event ->
                        if (event is WorkflowManagementEvent.WorkflowsChanged) reloadPending = true
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    MainNavHost(
                        generationViewModel = generationViewModel,
                        textToImageViewModel = textToImageViewModel,
                        imageToImageViewModel = imageToImageViewModel,
                        textToVideoViewModel = textToVideoViewModel,
                        imageToVideoViewModel = imageToVideoViewModel,
                        galleryViewModel = galleryViewModel,
                        mediaViewerViewModel = mediaViewerViewModel,
                        settingsViewModel = settingsViewModel,
                        workflowManagementViewModel = workflowManagementViewModel,
                        onLogout = { logout() },
                        onLeftSettings = { route -> if (reloadPending) reloadAt(route) },
                        startDestination = startDestination
                    )
                }

                // Session-expired dialog for browser-based (Cookie) authentication.
                // Shown when the AuthInterceptor detects a 401/403 or SSO redirect.
                if (sessionExpired) {
                    ActionDialog(
                        icon = Icons.Rounded.LockClock,
                        title = stringResource(R.string.title_session_expired),
                        text = stringResource(R.string.msg_session_expired),
                        onDismissRequest = { ConnectionManager.resetSessionExpired() },
                        actions = listOf(
                            DialogAction(stringResource(R.string.action_return_to_login), {
                                ConnectionManager.resetSessionExpired()
                                generationViewModel.logout()
                                finish()
                            }),
                            DialogAction(stringResource(R.string.button_reauthenticate), {
                                ConnectionManager.resetSessionExpired()
                                val serverUrl = ServerUrlUtils.buildServerUrl(
                                    ConnectionManager.protocol,
                                    ConnectionManager.hostname,
                                    ConnectionManager.port
                                )
                                browserAuthLauncher.launch(
                                    BrowserAuthActivity.createIntent(activity, serverUrl)
                                )
                            })
                        )
                    )
                }

                // Show connection alert dialog when connection fails
                connectionAlertState?.let { state ->
                    ConnectionAlertDialog(
                        failureType = state.failureType,
                        hasOfflineCache = state.hasOfflineCache,
                        isReconnecting = isReconnecting,
                        onReconnect = {
                            ConnectionManager.retrySingleAttempt(this@MainContainerActivity)
                        },
                        onGoOffline = {
                            ConnectionManager.clearConnectionAlert()
                            AppSettings.setOfflineMode(this@MainContainerActivity, true)
                        },
                        onReturnToLogin = {
                            ConnectionManager.clearConnectionAlert()
                            generationViewModel.logout()
                            finish()
                        },
                        onDismiss = { ConnectionManager.clearConnectionAlert() }
                    )
                }
            }
        }
    }

    // Navigation helpers
    /**
     * Start this activity fresh at [route], so the generation screens load workflows and settings again. Until an
     * in-place reload exists, this is how changes made in Settings take effect, as when Settings was its own activity.
     */
    private fun reloadAt(route: String) {
        val intent = Intent(this, MainContainerActivity::class.java)
            .putExtra(EXTRA_START_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    /** Back to the login screen, e.g. after the connection settings changed. */
    private fun returnToLogin() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
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

        // "Memory, saved on exit": write dirty media in the background (atomic writes, serialized with
        // loads and cleanups). Memory-only never writes; device storage writes when media arrives.
        val storageMode = AppSettings.getMediaStorageMode(this)
        DebugLogger.d("MainContainer", "onStop - media storage: $storageMode")
        if (storageMode == MediaStorageMode.MEMORY_PERSISTED) {
            DebugLogger.d("MainContainer", "onStop - persisting media to disk")
            MediaStateHolder.persistToDiskInBackground(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        PrivacyUtils.applyRecentsScreenshotPolicy(this)
        val isGenerating = generationViewModel.generationState.value.isGenerating

        // Only attempt reconnection if generating (need the connection)
        // Otherwise, connection will be established on-demand when user taps Generate
        if (!AppSettings.isOfflineMode(this) && isGenerating) {
            ConnectionManager.attemptSilentReconnect()
        }

        // Check if there's a pending generation that may have completed while in background
        if (isGenerating) {
            generationViewModel.checkServerForCompletion()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel handles cleanup automatically via onCleared()
    }
}
