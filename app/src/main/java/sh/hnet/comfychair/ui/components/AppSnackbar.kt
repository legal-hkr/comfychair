package sh.hnet.comfychair.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The main activity's snackbar host, for pages that report the outcome of an action after leaving (e.g. "Workflow
 * deleted" on the list). Null outside the main activity.
 */
val LocalAppSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }
