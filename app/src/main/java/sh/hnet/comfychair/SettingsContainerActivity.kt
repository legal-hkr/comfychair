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
import sh.hnet.comfychair.connection.ConnectionManager
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

    // Constants
    companion object {
        const val RESULT_CONNECTION_CHANGED = 101
    }

    // ViewModels
    private val workflowManagementViewModel: WorkflowManagementViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    // Lifecycle methods
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Guard check - redirect to login if not connected
        if (!ConnectionManager.isConnected) {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }

        // Initialize ViewModel (uses ConnectionManager internally)
        settingsViewModel.initialize(applicationContext)

        setContent {
            ComfyChairTheme {
                // Listen for RefreshNeeded and NavigateToLogin events
                LaunchedEffect(Unit) {
                    settingsViewModel.events.collect { event ->
                        when (event) {
                            is SettingsEvent.RefreshNeeded -> {
                                setResult(MainContainerActivity.RESULT_REFRESH_NEEDED)
                            }
                            is SettingsEvent.NavigateToLogin -> {
                                // Navigate directly to MainActivity with flags to clear entire back stack
                                // This ensures Gallery and other activities are also cleared
                                setResult(RESULT_CONNECTION_CHANGED)
                                val intent = Intent(this@SettingsContainerActivity, MainActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(intent)
                                finish()
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
                        onNavigateToGeneration = { finish() },
                        onLogout = { logout() }
                    )
                }
            }
        }
    }

    // Navigation helpers
    /**
     * Log out from the server and return to MainActivity
     */
    private fun logout() {
        ConnectionManager.logout()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
