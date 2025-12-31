package sh.hnet.comfychair

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import sh.hnet.comfychair.cache.MaskEditorStateHolder
import sh.hnet.comfychair.ui.screens.MaskEditorScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger

/**
 * Fullscreen activity for editing inpainting masks.
 * Follows the MediaViewerActivity pattern for consistent transitions.
 */
class MaskEditorActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MaskEditor"

        /**
         * Create intent to launch the mask editor.
         * State must be initialized via MaskEditorStateHolder before launching.
         */
        fun createIntent(context: Context): Intent {
            return Intent(context, MaskEditorActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Verify state is initialized
        if (MaskEditorStateHolder.sourceImage == null) {
            DebugLogger.w(TAG, "MaskEditorStateHolder not initialized, finishing activity")
            finish()
            return
        }

        DebugLogger.i(TAG, "Opening mask editor")

        setContent {
            ComfyChairTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MaskEditorScreen(
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear state when activity is destroyed
        MaskEditorStateHolder.clear()
        DebugLogger.i(TAG, "Mask editor closed")
    }
}
