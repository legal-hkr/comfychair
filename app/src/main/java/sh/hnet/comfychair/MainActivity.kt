package sh.hnet.comfychair

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.hnet.comfychair.notification.GenerationCheckWorker
import sh.hnet.comfychair.notification.GenerationForegroundService
import sh.hnet.comfychair.notification.NotificationHelper
import sh.hnet.comfychair.storage.PreferencesMaintenance
import sh.hnet.comfychair.ui.screens.LoginScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger

/**
 * MainActivity - The login/connection screen
 *
 * This activity handles the initial connection to the ComfyUI server.
 * Users enter the hostname and port, then click Connect to test the connection.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            DebugLogger.d(TAG, "Notification permission granted")
        } else {
            DebugLogger.w(TAG, "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Run preferences maintenance in background to clean up stale entries
        lifecycleScope.launch(Dispatchers.IO) {
            PreferencesMaintenance.performMaintenance(applicationContext)
        }

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Start foreground service and schedule WorkManager
        startGenerationServices()

        setContent {
            ComfyChairTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoginScreen()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    DebugLogger.d(TAG, "Notification permission already granted")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    DebugLogger.d(TAG, "Should show notification permission rationale")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            DebugLogger.d(TAG, "Notification permission not required (Android < 13)")
        }
    }

    private fun startGenerationServices() {
        // Schedule WorkManager periodic check (runs even if app is killed)
        GenerationCheckWorker.schedule(applicationContext)

        // Start foreground service if there's pending generation
        if (NotificationHelper.hasPendingGeneration(applicationContext)) {
            DebugLogger.d(TAG, "Pending generation found, starting foreground service")
            GenerationForegroundService.start(applicationContext)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
