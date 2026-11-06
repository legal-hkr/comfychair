package sh.hnet.comfychair.util

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * An error to tell the user about: a short [message] for the snackbar and, where there's more to it, what Show opens:
 * a sentence ([text]) and the full details as written by the server or the system (shown monospaced, selectable).
 */
data class AppError(
    val message: String,
    val text: String? = null,
    val details: List<String> = emptyList()
) {
    val hasDetails: Boolean get() = text != null || details.isNotEmpty()
}

/**
 * The app's errors on their way to the screen: reported from anywhere (view models, callbacks, composables), shown as a
 * snackbar by whichever screen collects them now ([sh.hnet.comfychair.ui.components.ErrorSnackbars]: the main screens,
 * the media viewer while open, the login screen). Each error is shown once; errors reported while no screen collects
 * them wait for the next one. The details are shown only, never logged: they may hold workflow contents.
 */
object AppErrors {
    private val channel = Channel<AppError>(capacity = 16)

    val errors: Flow<AppError> = channel.receiveAsFlow()

    fun report(error: AppError) {
        channel.trySend(error)
    }

    fun report(message: String, details: List<String> = emptyList()) = report(AppError(message, details = details))

    fun report(context: Context, messageRes: Int) = report(AppError(context.getString(messageRes)))

    /**
     * Tells the user [messageRes] where a screen's events carry both kinds: an error (an `error_` string, by the
     * strings' naming convention) as an error snackbar, any other message (`msg_`, `hint_`: saved, copied…) as a toast.
     * Call on the main thread.
     */
    fun notify(context: Context, messageRes: Int) {
        if (context.resources.getResourceEntryName(messageRes).startsWith("error_")) {
            report(context, messageRes)
        } else {
            Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        }
    }
}
