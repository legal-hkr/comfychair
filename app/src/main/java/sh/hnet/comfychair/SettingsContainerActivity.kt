package sh.hnet.comfychair

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import sh.hnet.comfychair.ui.navigation.SettingsNavHost
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.viewmodel.SettingsEvent
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Container activity that hosts settings screens with Compose Navigation.
 */
class SettingsContainerActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val workflowManagementViewModel: WorkflowManagementViewModel by viewModels()

    // Connection information (passed from MainContainerActivity)
    private var hostname: String = ""
    private var port: Int = 8188
    private lateinit var comfyUIClient: ComfyUIClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get connection parameters from intent
        hostname = intent.getStringExtra("hostname") ?: ""
        port = intent.getIntExtra("port", 8188)

        // Initialize ComfyUIClient and test connection to determine protocol
        comfyUIClient = ComfyUIClient(applicationContext, hostname, port)
        comfyUIClient.testConnection { _, _, _ ->
            // Protocol is now determined (http or https)
        }

        // Initialize ViewModel with connection info
        settingsViewModel.initialize(applicationContext, hostname, port)

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

                // Listen for WorkflowsChanged event to set activity result
                LaunchedEffect(Unit) {
                    workflowManagementViewModel.events.collect { event ->
                        when (event) {
                            is WorkflowManagementEvent.WorkflowsChanged -> {
                                setResult(MainContainerActivity.RESULT_REFRESH_NEEDED)
                            }
                            else -> {} // Other events handled in screens
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsNavHost(
                        settingsViewModel = settingsViewModel,
                        workflowManagementViewModel = workflowManagementViewModel,
                        comfyUIClient = comfyUIClient,
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
