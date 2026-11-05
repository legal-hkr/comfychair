package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Dialog for adding or editing a server configuration.
 *
 * @param server The server to edit, or null for adding a new server
 * @param existingCredentials The existing credentials for the server (when editing)
 * @param isNameTaken Function to check if a name is already taken (excluding current server)
 * @param onDismiss Called when the dialog should be dismissed
 * @param onSave Called with the server data and credentials when save is confirmed
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerDialog(
    server: Server?,
    existingCredentials: AuthCredentials = AuthCredentials.None,
    isNameTaken: (name: String, excludeServerId: String?) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, hostname: String, port: Int, authType: AuthType, credentials: AuthCredentials) -> Unit
) {
    val isEditMode = server != null

    // Form state
    var name by remember { mutableStateOf(server?.name ?: "") }
    var hostname by remember { mutableStateOf(server?.hostname ?: "") }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "8188") }

    // Authentication state
    var authType by remember { mutableStateOf(server?.authType ?: AuthType.NONE) }
    var username by remember {
        mutableStateOf(
            (existingCredentials as? AuthCredentials.Basic)?.username ?: ""
        )
    }
    // Secure fields: no keyboard suggestions or learning, no copy or cut
    val passwordState = rememberTextFieldState((existingCredentials as? AuthCredentials.Basic)?.password ?: "")
    val tokenState = rememberTextFieldState((existingCredentials as? AuthCredentials.Bearer)?.token ?: "")

    // Error states
    var nameError by remember { mutableStateOf<String?>(null) }
    var hostnameError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    // Editing a secure field clears its error, as the other fields do in onValueChange
    LaunchedEffect(passwordState) { snapshotFlow { passwordState.text.toString() }.drop(1).collect { passwordError = null } }
    LaunchedEffect(tokenState) { snapshotFlow { tokenState.text.toString() }.drop(1).collect { tokenError = null } }

    // Focus requester for name field
    val nameFocusRequester = remember { FocusRequester() }

    // Regex patterns for validation
    val ipAddressPattern = remember {
        Regex("^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$")
    }
    val hostnamePattern = remember {
        Regex("^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$")
    }

    fun isValidHostname(value: String): Boolean {
        return ipAddressPattern.matches(value) || hostnamePattern.matches(value)
    }

    // String resources
    val errorRequired = stringResource(R.string.error_required)
    val errorInvalidHostname = stringResource(R.string.error_invalid_hostname)
    val errorInvalidPort = stringResource(R.string.error_invalid_port)
    val errorNameTaken = stringResource(R.string.error_server_name_taken)

    // Validation function
    fun validate(): Boolean {
        var isValid = true
        nameError = null
        hostnameError = null
        portError = null
        usernameError = null
        passwordError = null
        tokenError = null

        val trimmedName = name.trim()
        val trimmedHostname = hostname.trim()
        val trimmedPort = port.trim()

        // Validate name
        if (trimmedName.isEmpty()) {
            nameError = errorRequired
            isValid = false
        } else if (isNameTaken(trimmedName, server?.id)) {
            nameError = errorNameTaken
            isValid = false
        }

        // Validate hostname
        if (trimmedHostname.isEmpty()) {
            hostnameError = errorRequired
            isValid = false
        } else if (!isValidHostname(trimmedHostname)) {
            hostnameError = errorInvalidHostname
            isValid = false
        }

        // Validate port
        if (trimmedPort.isEmpty()) {
            portError = errorRequired
            isValid = false
        } else {
            val portNum = trimmedPort.toIntOrNull()
            if (portNum == null || portNum !in 1..65535) {
                portError = errorInvalidPort
                isValid = false
            }
        }

        // Validate auth credentials
        when (authType) {
            AuthType.BASIC -> {
                if (username.trim().isEmpty()) {
                    usernameError = errorRequired
                    isValid = false
                }
                if (passwordState.text.isEmpty()) {
                    passwordError = errorRequired
                    isValid = false
                }
            }
            AuthType.BEARER -> {
                if (tokenState.text.isBlank()) {
                    tokenError = errorRequired
                    isValid = false
                }
            }
            AuthType.NONE, AuthType.BROWSER -> { /* No credential validation needed */ }
        }

        return isValid
    }

    // Build credentials from current state
    fun buildCredentials(): AuthCredentials {
        return when (authType) {
            AuthType.NONE -> AuthCredentials.None
            AuthType.BASIC -> AuthCredentials.Basic(username.trim(), passwordState.text.toString())
            AuthType.BEARER -> AuthCredentials.Bearer(tokenState.text.toString().trim())
            // Browser cookies are captured via BrowserAuthActivity at connection time
            AuthType.BROWSER -> AuthCredentials.None
        }
    }

    // Focus the name field when dialog opens
    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    // What was there when the form opened, to ask before throwing edits away
    val initial = remember {
        listOf(server?.name ?: "", server?.hostname ?: "", server?.port?.toString() ?: "8188", server?.authType ?: AuthType.NONE,
            (existingCredentials as? AuthCredentials.Basic)?.username ?: "")
    }
    val initialPassword = remember { passwordState.text.toString() }
    val initialToken = remember { tokenState.text.toString() }
    val hasChanges = listOf(name, hostname, port, authType, username) != initial ||
        passwordState.text.toString() != initialPassword || tokenState.text.toString() != initialToken

    FullScreenFormDialog(
        title = stringResource(if (isEditMode) R.string.title_server_edit else R.string.title_server_add),
        confirmLabel = stringResource(R.string.button_save),
        hasChanges = hasChanges,
        onDismiss = onDismiss,
        onConfirm = {
            if (validate()) {
                onSave(name.trim(), hostname.trim(), port.trim().toInt(), authType, buildCredentials())
            }
        }
    ) {
        DialogTextField(
            value = name,
            onValueChange = {
                name = it
                nameError = null
            },
            label = stringResource(R.string.label_server_name),
            placeholder = stringResource(R.string.placeholder_server_name),
            error = nameError,
            modifier = Modifier.focusRequester(nameFocusRequester)
        )

        // Host and port belong together, in one row
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            DialogTextField(
                value = hostname,
                onValueChange = { newValue ->
                    hostname = newValue
                    // Live validation
                    val trimmed = newValue.trim()
                    hostnameError = if (trimmed.isNotEmpty() && !isValidHostname(trimmed)) errorInvalidHostname else null
                },
                label = stringResource(R.string.hint_hostname),
                placeholder = stringResource(R.string.placeholder_server_hostname),
                error = hostnameError,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.weight(2f)
            )
            DialogTextField(
                value = port,
                onValueChange = { newValue ->
                    port = newValue
                    val trimmed = newValue.trim()
                    portError = if (trimmed.isNotEmpty() && trimmed.toIntOrNull()?.let { it in 1..65535 } != true) errorInvalidPort else null
                },
                label = stringResource(R.string.hint_port),
                error = portError,
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
            selected = authType,
            onSelect = {
                authType = it
                usernameError = null
                passwordError = null
                tokenError = null
            }
        )

        val storedEncrypted = stringResource(R.string.label_stored_encrypted)
        when (authType) {
            AuthType.BASIC -> {
                DialogTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        usernameError = null
                    },
                    label = stringResource(R.string.label_auth_username),
                    error = usernameError
                )
                DialogSecureField(passwordState, stringResource(R.string.label_auth_password), error = passwordError, supporting = storedEncrypted)
            }
            AuthType.BEARER -> {
                DialogSecureField(tokenState, stringResource(R.string.label_auth_token), error = tokenError, supporting = storedEncrypted)
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
}
