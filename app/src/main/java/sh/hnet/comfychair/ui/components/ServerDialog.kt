package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.ui.components.dialogs.DialogDropdownField
import sh.hnet.comfychair.ui.components.dialogs.DialogSecureField
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FullScreenFormDialog

/**
 * The state of a server form: its fields, their errors, validation and the credentials it builds. Kept by whoever
 * hosts the form, so a page can be left and come back to (the welcome wizard) or a dialog asks before discarding edits.
 *
 * @param server The server to edit, or null for adding a new one
 * @param existingCredentials The server's stored credentials (when editing)
 */
@Stable
class ServerFormState(private val server: Server?, existingCredentials: AuthCredentials) {
    val isEditMode: Boolean = server != null

    var name by mutableStateOf(server?.name ?: "")
    var hostname by mutableStateOf(server?.hostname ?: "")
    var port by mutableStateOf(server?.port?.toString() ?: "8188")
    var authType by mutableStateOf(server?.authType ?: AuthType.NONE)
    var username by mutableStateOf((existingCredentials as? AuthCredentials.Basic)?.username ?: "")
    // Secure fields: no keyboard suggestions or learning, no copy or cut
    val passwordState = TextFieldState((existingCredentials as? AuthCredentials.Basic)?.password ?: "")
    val tokenState = TextFieldState((existingCredentials as? AuthCredentials.Bearer)?.token ?: "")

    var nameError by mutableStateOf<String?>(null)
    var hostnameError by mutableStateOf<String?>(null)
    var portError by mutableStateOf<String?>(null)
    var usernameError by mutableStateOf<String?>(null)
    var passwordError by mutableStateOf<String?>(null)
    var tokenError by mutableStateOf<String?>(null)

    // What was there when the form opened, to ask before throwing edits away
    private val initial = listOf(name, hostname, port, authType, username)
    private val initialPassword = passwordState.text.toString()
    private val initialToken = tokenState.text.toString()
    val hasChanges: Boolean
        get() = listOf(name, hostname, port, authType, username) != initial ||
            passwordState.text.toString() != initialPassword || tokenState.text.toString() != initialToken

    /** Validates every field, showing the errors; true when the server can be saved. */
    fun validate(messages: ServerFormMessages, isNameTaken: (name: String, excludeServerId: String?) -> Boolean): Boolean {
        nameError = null
        hostnameError = null
        portError = null
        usernameError = null
        passwordError = null
        tokenError = null
        val trimmedName = name.trim()
        val trimmedHostname = hostname.trim()
        val trimmedPort = port.trim()
        nameError = when {
            trimmedName.isEmpty() -> messages.required
            isNameTaken(trimmedName, server?.id) -> messages.nameTaken
            else -> null
        }
        hostnameError = when {
            trimmedHostname.isEmpty() -> messages.required
            !isValidHostname(trimmedHostname) -> messages.invalidHostname
            else -> null
        }
        portError = when {
            trimmedPort.isEmpty() -> messages.required
            trimmedPort.toIntOrNull()?.let { it in 1..65535 } != true -> messages.invalidPort
            else -> null
        }
        when (authType) {
            AuthType.BASIC -> {
                if (username.trim().isEmpty()) usernameError = messages.required
                if (passwordState.text.isEmpty()) passwordError = messages.required
            }
            AuthType.BEARER -> if (tokenState.text.isBlank()) tokenError = messages.required
            AuthType.NONE, AuthType.BROWSER -> Unit
        }
        return listOf(nameError, hostnameError, portError, usernameError, passwordError, tokenError).all { it == null }
    }

    /** The credentials of the chosen authentication (browser cookies are captured when connecting). */
    fun buildCredentials(): AuthCredentials = when (authType) {
        AuthType.NONE -> AuthCredentials.None
        AuthType.BASIC -> AuthCredentials.Basic(username.trim(), passwordState.text.toString())
        AuthType.BEARER -> AuthCredentials.Bearer(tokenState.text.toString().trim())
        AuthType.BROWSER -> AuthCredentials.None
    }

    companion object {
        private val ipAddressPattern =
            Regex("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$")
        private val hostnamePattern =
            Regex("^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$")

        fun isValidHostname(value: String): Boolean = ipAddressPattern.matches(value) || hostnamePattern.matches(value)
    }
}

/** The form's validation messages. */
class ServerFormMessages(val required: String, val invalidHostname: String, val invalidPort: String, val nameTaken: String)

@Composable
fun rememberServerFormMessages() = ServerFormMessages(
    required = stringResource(R.string.error_required),
    invalidHostname = stringResource(R.string.error_invalid_hostname),
    invalidPort = stringResource(R.string.error_invalid_port),
    nameTaken = stringResource(R.string.error_server_name_taken)
)

/**
 * The server form's fields: name, hostname and port, the authentication and its fields. The same in the server dialog
 * and the welcome wizard.
 *
 * @param missingCredentials The server's credentials aren't on this device (restored from a backup): the cursor starts
 *     in the first credential field, which says why it's empty; otherwise in the name field
 */
@Composable
fun ColumnScope.ServerFormFields(state: ServerFormState, missingCredentials: Boolean = false) {
    val messages = rememberServerFormMessages()
    // Editing a secure field clears its error, as the other fields do in onValueChange
    LaunchedEffect(state.passwordState) { snapshotFlow { state.passwordState.text.toString() }.drop(1).collect { state.passwordError = null } }
    LaunchedEffect(state.tokenState) { snapshotFlow { state.tokenState.text.toString() }.drop(1).collect { state.tokenError = null } }
    val nameFocusRequester = remember { FocusRequester() }
    val credentialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (missingCredentials) credentialFocusRequester else nameFocusRequester).requestFocus() }
    val notInBackup = if (missingCredentials) stringResource(R.string.label_credentials_not_in_backup) else null

    DialogTextField(
        value = state.name,
        onValueChange = {
            state.name = it
            state.nameError = null
        },
        label = stringResource(R.string.label_server_name),
        placeholder = stringResource(R.string.placeholder_server_name),
        error = state.nameError,
        modifier = Modifier.focusRequester(nameFocusRequester)
    )

    // Host and port belong together, in one row
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        DialogTextField(
            value = state.hostname,
            onValueChange = { newValue ->
                state.hostname = newValue
                // Live validation
                val trimmed = newValue.trim()
                state.hostnameError = if (trimmed.isNotEmpty() && !ServerFormState.isValidHostname(trimmed)) messages.invalidHostname else null
            },
            label = stringResource(R.string.hint_hostname),
            placeholder = stringResource(R.string.placeholder_server_hostname),
            error = state.hostnameError,
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.weight(2f)
        )
        DialogTextField(
            value = state.port,
            onValueChange = { newValue ->
                state.port = newValue
                val trimmed = newValue.trim()
                state.portError = if (trimmed.isNotEmpty() && trimmed.toIntOrNull()?.let { it in 1..65535 } != true) messages.invalidPort else null
            },
            label = stringResource(R.string.hint_port),
            error = state.portError,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
    }

    // Authentication, by name; its fields follow
    DialogDropdownField(
        label = stringResource(R.string.label_auth_type),
        options = listOf(
            AuthType.NONE to stringResource(R.string.option_auth_type_none),
            AuthType.BASIC to stringResource(R.string.option_auth_type_basic),
            AuthType.BEARER to stringResource(R.string.option_auth_type_bearer),
            AuthType.BROWSER to stringResource(R.string.option_auth_type_browser)
        ),
        selected = state.authType,
        onSelect = {
            state.authType = it
            state.usernameError = null
            state.passwordError = null
            state.tokenError = null
        }
    )

    val storedEncrypted = stringResource(R.string.label_stored_encrypted)
    when (state.authType) {
        AuthType.BASIC -> {
            DialogTextField(
                value = state.username,
                onValueChange = {
                    state.username = it
                    state.usernameError = null
                },
                label = stringResource(R.string.label_auth_username),
                error = state.usernameError,
                supporting = notInBackup,
                modifier = Modifier.focusRequester(credentialFocusRequester)
            )
            DialogSecureField(state.passwordState, stringResource(R.string.label_auth_password), error = state.passwordError, supporting = storedEncrypted)
        }
        AuthType.BEARER -> {
            DialogSecureField(
                state.tokenState,
                stringResource(R.string.label_auth_token),
                error = state.tokenError,
                supporting = notInBackup ?: storedEncrypted,
                modifier = Modifier.focusRequester(credentialFocusRequester)
            )
        }
        AuthType.BROWSER -> {
            Text(
                text = stringResource(R.string.label_auth_browser_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AuthType.NONE -> Unit
    }
}

/**
 * Dialog for adding or editing a server: the server form in a full-screen form dialog.
 *
 * @param server The server to edit, or null for adding a new server
 * @param existingCredentials The existing credentials for the server (when editing)
 * @param isNameTaken Function to check if a name is already taken (excluding current server)
 * @param onDismiss Called when the dialog should be dismissed
 * @param onSave Called with the server data and credentials when save is confirmed
 * @param missingCredentials The server's credentials aren't on this device (see [ServerFormFields])
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerDialog(
    server: Server?,
    existingCredentials: AuthCredentials = AuthCredentials.None,
    missingCredentials: Boolean = false,
    isNameTaken: (name: String, excludeServerId: String?) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, hostname: String, port: Int, authType: AuthType, credentials: AuthCredentials) -> Unit
) {
    val state = remember { ServerFormState(server, existingCredentials) }
    val messages = rememberServerFormMessages()
    FullScreenFormDialog(
        title = stringResource(if (state.isEditMode) R.string.title_server_edit else R.string.title_server_add),
        confirmLabel = stringResource(R.string.button_save),
        hasChanges = state.hasChanges,
        onDismiss = onDismiss,
        onConfirm = {
            if (state.validate(messages, isNameTaken)) {
                onSave(state.name.trim(), state.hostname.trim(), state.port.trim().toInt(), state.authType, state.buildCredentials())
            }
        }
    ) {
        ServerFormFields(state, missingCredentials)
    }
}
