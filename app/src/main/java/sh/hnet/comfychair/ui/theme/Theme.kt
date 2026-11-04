package sh.hnet.comfychair.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Default Material 3 color schemes
// These will be overridden by dynamic colors on Android 12+ if available
private val DarkColorScheme = darkColorScheme()

private val LightColorScheme = lightColorScheme()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComfyChairTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // Override status bar appearance (null = auto based on theme, true = light icons, false = dark icons)
    forceDarkStatusBar: Boolean? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Update status bar icon colors based on theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Light status bar = dark icons (for light theme)
            // Dark status bar = light icons (for dark theme)
            // forceDarkStatusBar overrides automatic behavior
            insetsController.isAppearanceLightStatusBars = when (forceDarkStatusBar) {
                true -> false  // Force light icons (dark status bar)
                false -> true  // Force dark icons (light status bar)
                null -> !darkTheme  // Auto based on theme
            }
        }
    }

    // Expressive theme with the standard (not bouncy) motion scheme
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.standard(),
        typography = Typography,
        content = content
    )
}
