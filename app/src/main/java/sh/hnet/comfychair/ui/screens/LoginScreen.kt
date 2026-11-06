@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.BrowserAuthActivity
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.MainContainerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.PendingCertificate
import sh.hnet.comfychair.connection.ServerTrustStore
import sh.hnet.comfychair.connection.connectionError
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.MediaStorageMode
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.AppLogo
import sh.hnet.comfychair.ui.components.CertificateTrustDialog
import sh.hnet.comfychair.ui.components.ConnectionSplitButton
import sh.hnet.comfychair.ui.components.InsecureFallbackDialog
import sh.hnet.comfychair.ui.components.ServerDialog
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.ui.components.settings.ConfirmDestructiveDialog
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsRadioRow
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.ServerUrlUtils

/**
 * Connection states for the login screen
 */
enum class ConnectionState {
    IDLE,
    CONNECTING,
    FAILED,
    CONNECTED
}

/**
 * Login screen composable - handles connection to ComfyUI server
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Server and credential storage
    val serverStorage = remember { ServerStorage(context.applicationContext) }
    val credentialStorage = remember { CredentialStorage(context.applicationContext) }

    // Check offline mode
    var isOfflineMode by remember { mutableStateOf(AppSettings.isOfflineMode(context)) }

    // State
    var servers by remember { mutableStateOf(serverStorage.getServers()) }
    var selectedServer by remember {
        mutableStateOf(
            serverStorage.getSelectedServerId()?.let { id -> servers.find { it.id == id } }
                ?: servers.firstOrNull()
        )
    }
    var connectionState by remember { mutableStateOf(ConnectionState.IDLE) }
    var comfyUIClient by remember { mutableStateOf<ComfyUIClient?>(null) }
    var hasAutoConnected by remember { mutableStateOf(false) }

    // Dialog state
    var showServerDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<Server?>(null) }
    var serverToDelete by remember { mutableStateOf<Server?>(null) }
    var showOfflinePrompt by remember { mutableStateOf(false) }
    var offlinePromptServer by remember { mutableStateOf<Server?>(null) }

    // Trust decisions waiting for the user: an untrusted or changed certificate, or HTTP for a server that used HTTPS
    var pendingCertificateTrust by remember { mutableStateOf<Pair<Server, PendingCertificate>?>(null) }
    var pendingHttpFallbackServer by remember { mutableStateOf<Server?>(null) }

    // Browser auth state:
    // - pendingBrowserAuthServer: the server whose cookies are being captured
    // - pendingBrowserAuthUrl: non-null triggers BrowserAuthActivity launch (cleared after launch)
    // - retryAfterAuthServer: non-null triggers a retry connect after successful auth
    var pendingBrowserAuthServer by remember { mutableStateOf<Server?>(null) }
    var pendingBrowserAuthUrl by remember { mutableStateOf<String?>(null) }
    var retryAfterAuthServer by remember { mutableStateOf<Server?>(null) }

    // Launcher for BrowserAuthActivity.
    // Declared before attemptConnection so that the lambda captures only state vars
    // (all declared above), not the local function. attemptConnection sets state to
    // trigger the launch; LaunchedEffect below calls the launcher.
    val browserAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val server = pendingBrowserAuthServer ?: return@rememberLauncherForActivityResult
        pendingBrowserAuthServer = null

        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data
                ?.getStringExtra(BrowserAuthActivity.RESULT_COOKIES)
                .orEmpty()
            if (cookies.isNotEmpty()) {
                credentialStorage.saveCredentials(server.id, AuthCredentials.Cookie(cookies))
                retryAfterAuthServer = server  // Triggers retry via LaunchedEffect below
            } else {
                // User tapped Done before completing sign-in
                AppErrors.report(context, R.string.error_browser_auth_cancelled)
                connectionState = ConnectionState.IDLE
            }
        } else {
            // User closed the WebView without authenticating
            connectionState = ConnectionState.IDLE
        }
    }

    // Connection function.
    // isRetryAfterAuth: true when called after a successful WebView auth — prevents a
    // second WebView launch if the connection still fails (e.g. wrong cookies).
    // When browser auth is needed, sets pendingBrowserAuth* state instead of calling
    // the launcher directly (avoids a forward-reference to browserAuthLauncher).
    fun attemptConnection(server: Server, isRetryAfterAuth: Boolean = false) {
        connectionState = ConnectionState.CONNECTING

        scope.launch {
            // Load credentials for the server
            val credentials = credentialStorage.getCredentials(server.id, server.authType)

            // BROWSER auth without saved cookies also runs the connection test first: the test settles certificate trust
            // and the protocol before the WebView opens. Without cookies it fails and the WebView branch below takes over.

            val client = ComfyUIClient(
                context.applicationContext,
                server.hostname,
                server.port,
                credentials
            )
            comfyUIClient = client

            // Test connection using suspendCoroutine
            val result = suspendCoroutine { continuation ->
                client.testConnection { success, errorMessage, _, failureType ->
                    continuation.resume(Triple(success, errorMessage, failureType))
                }
            }

            val (success, errorMessage, failureType) = result
            val pendingCertificate = client.pendingCertificate

            if (!success && failureType == ConnectionFailure.CERTIFICATE && pendingCertificate != null) {
                // Ask the user; trusting the certificate retries the connection
                pendingCertificateTrust = server to pendingCertificate
                connectionState = ConnectionState.IDLE
            } else if (!success && failureType == ConnectionFailure.INSECURE_FALLBACK) {
                pendingHttpFallbackServer = server
                connectionState = ConnectionState.IDLE
            } else if (success) {
                connectionState = ConnectionState.CONNECTED

                delay(500L)

                // Establish connection via ConnectionManager
                val detectedProtocol = client.getWorkingProtocol() ?: "http"

                // Save selected server
                serverStorage.setSelectedServerId(server.id)

                ConnectionManager.connect(
                    context = context.applicationContext,
                    serverId = server.id,
                    hostname = server.hostname,
                    port = server.port,
                    protocol = detectedProtocol,
                    credentials = credentials
                )

                // Navigate to main activity
                val intent = Intent(context, MainContainerActivity::class.java)
                context.startActivity(intent)
            } else if (server.authType == AuthType.BROWSER && !isRetryAfterAuth) {
                // Connection failed with browser auth and WebView hasn't been tried yet.
                // The server may be reachable but behind an SSO that blocked the request.
                val protocol = client.getWorkingProtocol() ?: "https"
                pendingBrowserAuthServer = server
                pendingBrowserAuthUrl =
                    ServerUrlUtils.buildServerUrl(protocol, server.hostname, server.port)
                connectionState = ConnectionState.IDLE
            } else {
                connectionState = ConnectionState.FAILED

                // A server that doesn't answer can wait: offer what's kept on this device. One that answered (refused
                // the credentials, or isn't ComfyUI) needs fixing first, so say why.
                val unreachable = failureType == ConnectionFailure.NETWORK || failureType == ConnectionFailure.STALLED
                if (unreachable && ConnectionManager.hasOfflineCache(context, server.id)) {
                    offlinePromptServer = server
                    showOfflinePrompt = true
                } else {
                    AppErrors.report(connectionError(context, failureType, errorMessage))
                }

                delay(2000)
                connectionState = ConnectionState.IDLE
            }
        }
    }

    // Launch BrowserAuthActivity when attemptConnection signals it via state
    LaunchedEffect(pendingBrowserAuthUrl) {
        val url = pendingBrowserAuthUrl ?: return@LaunchedEffect
        pendingBrowserAuthUrl = null  // Clear to prevent re-trigger on recomposition
        browserAuthLauncher.launch(BrowserAuthActivity.createIntent(context, url))
    }

    // Retry connection after successful browser auth (triggered by launcher result handler)
    LaunchedEffect(retryAfterAuthServer) {
        val server = retryAfterAuthServer ?: return@LaunchedEffect
        retryAfterAuthServer = null
        attemptConnection(server, isRetryAfterAuth = true)
    }

    // Offline connection function - loads from cache instead of connecting to server
    fun attemptOfflineConnection(server: Server) {
        // Check if cache exists for this server
        if (!ConnectionManager.hasOfflineCache(context, server.id)) {
            AppErrors.report(context, R.string.error_no_offline_cache)
            return
        }

        connectionState = ConnectionState.CONNECTING

        scope.launch {
            // Load data from cache
            val success = ConnectionManager.loadFromOfflineCache(context, server.id)

            if (success) {
                connectionState = ConnectionState.CONNECTED

                delay(500)

                // Save selected server
                serverStorage.setSelectedServerId(server.id)

                // Navigate to main activity (no actual connection established)
                val intent = Intent(context, MainContainerActivity::class.java)
                context.startActivity(intent)
            } else {
                connectionState = ConnectionState.FAILED
                AppErrors.report(context, R.string.error_no_offline_cache)
                delay(2000)
                connectionState = ConnectionState.IDLE
            }
        }
    }

    // Reset state when activity resumes (e.g., after logout or back navigation)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Reload servers in case they were modified
                servers = serverStorage.getServers()
                selectedServer = serverStorage.getSelectedServerId()?.let { id -> servers.find { it.id == id } }
                    ?: servers.firstOrNull()

                // Reload offline mode setting in case it was changed in settings
                isOfflineMode = AppSettings.isOfflineMode(context)

                // Reset connection state when screen becomes visible again
                if (connectionState == ConnectionState.CONNECTED) {
                    connectionState = ConnectionState.IDLE
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-connect on fresh app launch (if enabled in settings, we have a selected server, user didn't explicitly logout, and not in offline mode)
    LaunchedEffect(Unit) {
        val isAutoConnectEnabled = AppSettings.isAutoConnectEnabled(context)
        val shouldAutoConnect = isAutoConnectEnabled && !ConnectionManager.isUserInitiatedLogout && !isOfflineMode
        ConnectionManager.clearLogoutFlag()

        if (!hasAutoConnected && shouldAutoConnect && selectedServer != null) {
            hasAutoConnected = true
            delay(500)
            if (connectionState == ConnectionState.IDLE) {
                attemptConnection(selectedServer!!)
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            comfyUIClient?.shutdown()
        }
    }

    // Server dialog
    if (showServerDialog) {
        // Load existing credentials for editing
        val existingCredentials = remember(serverToEdit) {
            serverToEdit?.let { server ->
                credentialStorage.getCredentials(server.id, server.authType)
            } ?: AuthCredentials.None
        }

        ServerDialog(
            server = serverToEdit,
            existingCredentials = existingCredentials,
            isNameTaken = { name, excludeServerId ->
                serverStorage.isServerNameTaken(name, excludeServerId)
            },
            onDismiss = {
                showServerDialog = false
                serverToEdit = null
            },
            onSave = { name, hostname, port, authType, credentials ->
                if (serverToEdit != null) {
                    // Update existing server
                    val updatedServer = serverToEdit!!.copy(
                        name = name,
                        hostname = hostname,
                        port = port,
                        authType = authType
                    )
                    serverStorage.updateServer(updatedServer)

                    // Save credentials
                    credentialStorage.saveCredentials(updatedServer.id, credentials)

                    servers = serverStorage.getServers()

                    // If editing the selected server, update selection
                    if (selectedServer?.id == updatedServer.id) {
                        selectedServer = updatedServer
                    }
                } else {
                    // Add new server
                    val newServer = Server.create(name, hostname, port, authType)
                    serverStorage.addServer(newServer)

                    // Save credentials
                    credentialStorage.saveCredentials(newServer.id, credentials)

                    servers = serverStorage.getServers()

                    // Select the new server
                    selectedServer = newServer
                    serverStorage.setSelectedServerId(newServer.id)
                }
                showServerDialog = false
                serverToEdit = null
            }
        )
    }

    // Delete confirmation, for the server whose row menu asked
    serverToDelete?.let { server ->
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_delete_named, server.name),
            text = stringResource(R.string.msg_server_delete),
            confirmLabel = stringResource(R.string.button_delete),
            icon = Icons.Rounded.Delete,
            onConfirm = {
                serverStorage.deleteServer(server.id)
                // Also delete credentials for the server
                credentialStorage.deleteCredentials(server.id)
                servers = serverStorage.getServers()
                // Keep the selection unless it was this server
                if (selectedServer?.id == server.id) {
                    selectedServer = servers.firstOrNull()
                    selectedServer?.let { serverStorage.setSelectedServerId(it.id) }
                }
                Toast.makeText(context, R.string.msg_server_deleted_success, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { serverToDelete = null }
        )
    }

    pendingCertificateTrust?.let { (server, pending) ->
        CertificateTrustDialog(
            pending = pending,
            onTrust = {
                pendingCertificateTrust = null
                ServerTrustStore(context).pinFingerprint(server.hostname, server.port, pending.fingerprint)
                attemptConnection(server)
            },
            onCancel = { pendingCertificateTrust = null }
        )
    }

    pendingHttpFallbackServer?.let { server ->
        InsecureFallbackDialog(
            onConnectOverHttp = {
                pendingHttpFallbackServer = null
                ServerTrustStore(context).setProtocol(server.hostname, server.port, "http")
                attemptConnection(server)
            },
            onCancel = { pendingHttpFallbackServer = null }
        )
    }

    // Offline prompt dialog - shown when connection fails but cache exists
    if (showOfflinePrompt && offlinePromptServer != null) {
        ConfirmDialog(
            title = stringResource(R.string.title_offline_prompt),
            text = stringResource(R.string.msg_offline_prompt),
            confirmLabel = stringResource(R.string.button_offline_prompt_confirm),
            icon = Icons.Rounded.CloudOff,
            onDismiss = { showOfflinePrompt = false },
            onConfirm = {
                showOfflinePrompt = false
                // Enable offline mode and attempt offline connection
                AppSettings.setOfflineMode(context, true)
                isOfflineMode = true
                offlinePromptServer?.let { server ->
                    attemptOfflineConnection(server)
                }
            }
        )
    }

    // Offline: from the cache kept with media stored on the device, for the selected server
    val canConnectOffline = remember(selectedServer, isOfflineMode) {
        AppSettings.getMediaStorageMode(context) == MediaStorageMode.DEVICE &&
            selectedServer?.let { ConnectionManager.hasOfflineCache(context, it.id) } == true
    }
    val busy = connectionState != ConnectionState.IDLE

    // UI: the app and the servers above, Connect at the bottom in thumb reach
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))
            AppLogo(titleInset = TITLE_INSET)
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(horizontal = TITLE_INSET)
            )
            Text(
                text = stringResource(R.string.label_choose_server),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(16.dp))

            if (servers.isEmpty()) {
                Text(
                    text = stringResource(R.string.msg_no_servers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
            // One choice among the saved servers; each row has its own menu
            Column(Modifier.selectableGroup()) {
                SettingsGroup {
                    servers.forEach { server ->
                        row { shapes ->
                            SettingsRadioRow(
                                shapes = shapes,
                                title = server.name,
                                supporting = "${server.hostname}:${server.port}",
                                selected = server.id == selectedServer?.id,
                                enabled = !busy,
                                onSelect = {
                                    selectedServer = server
                                    serverStorage.setSelectedServerId(server.id)
                                },
                                trailing = {
                                    ServerRowMenu(
                                        enabled = !busy,
                                        onEdit = {
                                            serverToEdit = server
                                            showServerDialog = true
                                        },
                                        onDelete = { serverToDelete = server }
                                    )
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = {
                    serverToEdit = null
                    showServerDialog = true
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.button_server_add))
            }
        }

        Spacer(Modifier.height(16.dp))
        ConnectionSplitButton(
            connectionState = connectionState,
            hasSelectedServer = selectedServer != null,
            isOfflineMode = isOfflineMode,
            canConnectOffline = canConnectOffline,
            onConnect = {
                selectedServer?.let { server ->
                    if (isOfflineMode) attemptOfflineConnection(server) else attemptConnection(server)
                }
            },
            onConnectOffline = {
                selectedServer?.let { server ->
                    // Offline mode applies to the whole session (gallery from the cache)
                    AppSettings.setOfflineMode(context, true)
                    isOfflineMode = true
                    attemptOfflineConnection(server)
                }
            },
            onGoOnline = {
                AppSettings.setOfflineMode(context, false)
                isOfflineMode = false
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** A server row's menu: Edit, and Delete (confirmed) apart, in the error colour. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerRowMenu(enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Rounded.MoreVert, stringResource(R.string.content_description_server_menu), { expanded = true }, enabled = enabled)
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.button_server_edit)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    shape = MenuDefaults.itemShape(0, 1).shape,
                    onClick = {
                        expanded = false
                        onEdit()
                    }
                )
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                val error = MaterialTheme.colorScheme.error
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.button_server_delete), color = error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = error) },
                    shape = MenuDefaults.itemShape(0, 1).shape,
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** Start inset of the title and subtitle, which the mark lines up with. */
private val TITLE_INSET = 8.dp
