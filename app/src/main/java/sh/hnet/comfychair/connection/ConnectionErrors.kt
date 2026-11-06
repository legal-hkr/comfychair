package sh.hnet.comfychair.connection

import android.content.Context
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.AppError

/**
 * Why a connection to the server failed, for the user: refused credentials and a server that isn't ComfyUI by name,
 * anything else as a failed connection with the system's or the server's own words behind Show.
 */
fun connectionError(context: Context, failureType: ConnectionFailure, rawMessage: String?): AppError = when (failureType) {
    ConnectionFailure.AUTHENTICATION -> AppError(context.getString(R.string.error_auth_unauthorized))
    ConnectionFailure.INVALID_SERVER -> AppError(rawMessage ?: context.getString(R.string.error_not_comfyui_server))
    else -> AppError(
        message = context.getString(R.string.error_connection_failed),
        details = listOfNotNull(rawMessage?.trim()?.takeIf { it.isNotEmpty() })
    )
}
