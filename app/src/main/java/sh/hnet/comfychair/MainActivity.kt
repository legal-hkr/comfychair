package sh.hnet.comfychair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.storage.PreferencesMaintenance
import sh.hnet.comfychair.ui.components.ErrorSnackbars
import sh.hnet.comfychair.ui.components.WhatsNew
import sh.hnet.comfychair.ui.screens.LoginScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme

/**
 * MainActivity - The login/connection screen
 *
 * This activity handles the initial connection to the ComfyUI server.
 * Users enter the hostname and port, then click Connect to test the connection.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Before anything is shown: after an update, the main screens offer What's new once
        WhatsNew.recordStart(this)

        // Run preferences maintenance in background to clean up stale entries, and remove
        // media files left in the cache directory (share files, pre-in-memory playback files)
        lifecycleScope.launch(Dispatchers.IO) {
            PreferencesMaintenance.performMaintenance(applicationContext)
            InMemoryVideo.deleteShareFiles(applicationContext)
        }

        setContent {
            ComfyChairTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoginScreen()
                }
                // The login screen's errors, above the keyboard and the navigation bar
                val snackbarHostState = remember { SnackbarHostState() }
                ErrorSnackbars(snackbarHostState)
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    SnackbarHost(snackbarHostState) { data ->
                        Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}
