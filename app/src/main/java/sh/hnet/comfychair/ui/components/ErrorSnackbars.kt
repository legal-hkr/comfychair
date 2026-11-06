package sh.hnet.comfychair.ui.components

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.dialogs.InfoDialog
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.PrivacyUtils

/**
 * Shows the app's errors ([AppErrors]) in [hostState] while [active], one after another: the message, and Show when
 * there's more to it, which opens the whole error (selectable, with Copy).
 *
 * @param active False while something covers this screen's snackbars (e.g. the media viewer, which shows them itself)
 */
@Composable
fun ErrorSnackbars(hostState: SnackbarHostState, active: Boolean = true) {
    val resources = LocalResources.current
    var shown by remember { mutableStateOf<AppError?>(null) }
    LaunchedEffect(hostState, active) {
        if (!active) return@LaunchedEffect
        AppErrors.errors.collect { error ->
            // One at a time: the next waits until this one is gone
            val result = hostState.showSnackbar(
                message = error.message,
                actionLabel = if (error.hasDetails) resources.getString(R.string.button_view) else null,
                duration = if (error.hasDetails) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) shown = error
        }
    }
    shown?.let { error -> ErrorDetailsDialog(error, onDismiss = { shown = null }) }
}

/** The whole error: its message, a sentence and the details, which Copy puts on the clipboard (marked sensitive). */
@Composable
fun ErrorDetailsDialog(error: AppError, onDismiss: () -> Unit) {
    val context = LocalContext.current
    InfoDialog(
        icon = Icons.Rounded.ErrorOutline,
        title = error.message,
        text = error.text.orEmpty(),
        details = error.details,
        error = true,
        onDismiss = onDismiss,
        helperLabel = if (error.details.isNotEmpty()) stringResource(R.string.button_copy) else null,
        onHelper = {
            val all = listOfNotNull(error.message, error.text).plus(error.details).joinToString("\n\n")
            PrivacyUtils.copySensitiveText(context, "error", all)
            Toast.makeText(context, R.string.msg_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    )
}
