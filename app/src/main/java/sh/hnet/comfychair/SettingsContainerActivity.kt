package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import sh.hnet.comfychair.ui.navigation.SettingsNavHost
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel

/**
 * Container activity that hosts settings screens with Compose Navigation.
 */
class SettingsContainerActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    // Connection information (passed from MainContainerActivity)
    private var hostname: String = ""
    private var port: Int = 8188

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get connection parameters from intent
        hostname = intent.getStringExtra("hostname") ?: ""
        port = intent.getIntExtra("port", 8188)

        // Initialize ViewModel with connection info
        settingsViewModel.initialize(hostname, port)

        setContent {
            ComfyChairTheme {
                // Listen for RefreshNeeded event to set activity result
                LaunchedEffect(Unit) {
                    settingsViewModel.events.collect { event ->
                        when (event) {
                            is SettingsEvent.RefreshNeeded -> {
                                setResult(MainContainerActivity.RESULT_REFRESH_NEEDED)
                            }
                            else -> {} // Toast events handled in screens
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsNavHost(
                        settingsViewModel = settingsViewModel,
                        onNavigateToGeneration = { finish() },
                        onLogout = { logout() }
                    )
                }
            }
        }
    }

    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
