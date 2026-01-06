package sh.hnet.comfychair.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server

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
    var password by remember {
        mutableStateOf(
            (existingCredentials as? AuthCredentials.Basic)?.password ?: ""
        )
    }
    var token by remember {
        mutableStateOf(
            (existingCredentials as? AuthCredentials.Bearer)?.token ?: ""
        )
    }
    var passwordVisible by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }

    // Error states
    var nameError by remember { mutableStateOf<String?>(null) }
    var hostnameError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var tokenError by remember { mutableStateOf<String?>(null) }

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
    val errorNameTaken = stringResource(R.string.server_name_taken_error)

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
                if (password.isEmpty()) {
                    passwordError = errorRequired
                    isValid = false
                }
            }
            AuthType.BEARER -> {
                if (token.trim().isEmpty()) {
                    tokenError = errorRequired
                    isValid = false
                }
            }
            AuthType.NONE -> { /* No validation needed */ }
        }

        return isValid
    }

    // Build credentials from current state
    fun buildCredentials(): AuthCredentials {
        return when (authType) {
            AuthType.NONE -> AuthCredentials.None
            AuthType.BASIC -> AuthCredentials.Basic(username.trim(), password)
            AuthType.BEARER -> AuthCredentials.Bearer(token.trim())
        }
    }

    // Focus the name field when dialog opens
    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isEditMode) R.string.server_edit_title
                    else R.string.server_add_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Server name
                OutlinedTextField(
                    value = name,
                    onValueChange = { newValue ->
                        name = newValue
                        // Clear error on change
                        nameError = null
                    },
                    label = { Text(stringResource(R.string.server_name_label)) },
                    placeholder = { Text(stringResource(R.string.server_name_placeholder)) },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                )

                // Hostname
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { newValue ->
                        hostname = newValue
                        // Live validation
                        val trimmed = newValue.trim()
                        hostnameError = when {
                            trimmed.isEmpty() -> null
                            !isValidHostname(trimmed) -> errorInvalidHostname
                            else -> null
                        }
                    },
                    label = { Text(stringResource(R.string.hostname_hint)) },
                    placeholder = { Text(stringResource(R.string.server_hostname_placeholder)) },
                    isError = hostnameError != null,
                    supportingText = hostnameError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                // Port
                OutlinedTextField(
                    value = port,
                    onValueChange = { newValue ->
                        port = newValue
                        // Live validation
                        val trimmed = newValue.trim()
                        portError = when {
                            trimmed.isEmpty() -> null
                            else -> {
                                val portNum = trimmed.toIntOrNull()
                                if (portNum == null || portNum !in 1..65535) {
                                    errorInvalidPort
                                } else {
                                    null
                                }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.port_hint)) },
                    isError = portError != null,
                    supportingText = portError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Authentication section
                Text(
                    text = stringResource(R.string.auth_type_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Auth type selector using connected button group
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = authType == AuthType.NONE,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.NONE
                                usernameError = null
                                passwordError = null
                                tokenError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = stringResource(R.string.auth_type_none)
                        )
                    }
                    ToggleButton(
                        checked = authType == AuthType.BASIC,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.BASIC
                                tokenError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedMiddleButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Password,
                            contentDescription = stringResource(R.string.auth_type_basic)
                        )
                    }
                    ToggleButton(
                        checked = authType == AuthType.BEARER,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                authType = AuthType.BEARER
                                usernameError = null
                                passwordError = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = stringResource(R.string.auth_type_bearer)
                        )
                    }
                }

                // Basic auth fields
                AnimatedVisibility(visible = authType == AuthType.BASIC) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { newValue ->
                                username = newValue
                                usernameError = null
                            },
                            label = { Text(stringResource(R.string.auth_username_label)) },
                            isError = usernameError != null,
                            supportingText = usernameError?.let { { Text(it) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { newValue ->
                                password = newValue
                                passwordError = null
                            },
                            label = { Text(stringResource(R.string.auth_password_label)) },
                            isError = passwordError != null,
                            supportingText = passwordError?.let { { Text(it) } },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Filled.VisibilityOff
                                        } else {
                                            Icons.Filled.Visibility
                                        },
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Bearer token field
                AnimatedVisibility(visible = authType == AuthType.BEARER) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { newValue ->
                            token = newValue
                            tokenError = null
                        },
                        label = { Text(stringResource(R.string.auth_token_label)) },
                        isError = tokenError != null,
                        supportingText = tokenError?.let { { Text(it) } },
                        singleLine = true,
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (validate()) {
                        onSave(
                            name.trim(),
                            hostname.trim(),
                            port.trim().toInt(),
                            authType,
                            buildCredentials()
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}
