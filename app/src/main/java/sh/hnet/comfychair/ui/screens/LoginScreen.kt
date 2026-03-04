package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import sh.hnet.comfychair.WebViewAuthActivity
import sh.hnet.comfychair.connection.ConnectionFailure
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.CertificateIssue
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.MainContainerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.AuthType
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.ConnectionSplitButton
import sh.hnet.comfychair.ui.components.ServerDialog
import sh.hnet.comfychair.ui.components.ServerDropdown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
/**
 * Build a server URL with proper protocol detection.
 * Port 443 → HTTPS, port 80 → HTTP, port 8188 → HTTP (ComfyUI default).
 * Standard ports (80/443) omitted from URL. Others included.
 */
private fun buildServerUrl(hostname: String, port: Int): String {
    return when (port) {
        443 -> "https://$hostname"
        80 -> "http://$hostname"
        8188 -> "http://$hostname:8188"
        else -> "https://$hostname:$port"
    }
}

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
    var warningMessage by remember { mutableStateOf<String?>(null) }
    var showCertDialog by remember { mutableStateOf(false) }
    var pendingCertIssue by remember { mutableStateOf(CertificateIssue.NONE) }
    var pendingCertServer by remember { mutableStateOf<Server?>(null) }
    var pendingCertClient by remember { mutableStateOf<ComfyUIClient?>(null) }
    var pendingCertProtocol by remember { mutableStateOf<String?>(null) }
    var comfyUIClient by remember { mutableStateOf<ComfyUIClient?>(null) }
    var hasAutoConnected by remember { mutableStateOf(false) }

    // Dialog state
    var showServerDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<Server?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showOfflinePrompt by remember { mutableStateOf(false) }
    var offlinePromptServer by remember { mutableStateOf<Server?>(null) }
    var showBrowserReauthPrompt by remember { mutableStateOf(false) }
    var browserReauthServer by remember { mutableStateOf<Server?>(null) }
    // Set to a server to trigger attemptConnection via LaunchedEffect (avoids forward-ref)
    var pendingBrowserConnect by remember { mutableStateOf<Server?>(null) }

    // Browser auth WebView launcher — used when session expired / first connect
    val webViewAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val server = browserReauthServer ?: return@rememberLauncherForActivityResult
            val cookies = result.data?.getStringExtra(WebViewAuthActivity.EXTRA_COOKIES) ?: ""
            val authDomain = result.data?.getStringExtra(WebViewAuthActivity.EXTRA_AUTH_DOMAIN) ?: ""
            val authDomainCookies = result.data?.getStringExtra(WebViewAuthActivity.EXTRA_AUTH_DOMAIN_COOKIES) ?: ""
            if (cookies.isNotEmpty()) {
                credentialStorage.saveCredentials(
                    server.id,
                    sh.hnet.comfychair.model.AuthCredentials.Cookie(
                        cookies = cookies,
                        authDomain = authDomain,
                        authDomainCookies = authDomainCookies
                    )
                )
                showBrowserReauthPrompt = false
                browserReauthServer = null
                connectionState = ConnectionState.IDLE
                pendingBrowserConnect = server  // triggers LaunchedEffect below
            }
        } else {
            showBrowserReauthPrompt = false
            browserReauthServer = null
        }
    }

    // String resources
    val warningSelfSigned = stringResource(R.string.warning_self_signed_cert)
    val warningUnknownCa = stringResource(R.string.warning_unknown_ca)

    // Connection function
    fun attemptConnection(server: Server) {
        connectionState = ConnectionState.CONNECTING
        warningMessage = null

        scope.launch {
            // For browser auth with no stored cookies, open WebView immediately
            if (server.authType == sh.hnet.comfychair.model.AuthType.BROWSER &&
                !credentialStorage.hasCredentials(server.id, server.authType)) {
                connectionState = ConnectionState.IDLE
                val serverUrl = buildServerUrl(server.hostname, server.port)
                browserReauthServer = server
                val intent = WebViewAuthActivity.createIntent(context, serverUrl, server.hostname)
                webViewAuthLauncher.launch(intent)
                return@launch
            }

            // Load credentials for the server
            val credentials = credentialStorage.getCredentials(server.id, server.authType)

            val client = ComfyUIClient(
                context.applicationContext,
                server.hostname,
                server.port,
                credentials
            )
            comfyUIClient = client

            // Test connection using suspendCoroutine
            val result = suspendCoroutine { continuation ->
                client.testConnection { success, errorMessage, certIssue, _ ->
                    continuation.resume(Triple(success, errorMessage, certIssue))
                }
            }

            val (success, errorMessage, certIssue) = result

            if (success) {
                val detectedProtocol = client.getWorkingProtocol() ?: "http"

                // Handle certificate issues
                if (certIssue != CertificateIssue.NONE && !server.trustCert) {
                    // Cert issue and user hasn't trusted this server — ask
                    connectionState = ConnectionState.CONNECTED
                    pendingCertIssue = certIssue
                    pendingCertServer = server
                    pendingCertClient = client
                    pendingCertProtocol = detectedProtocol
                    showCertDialog = true
                    return@launch
                }

                connectionState = ConnectionState.CONNECTED
                delay(500L)

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
            } else {
                connectionState = ConnectionState.FAILED

                // For browser auth failures (expired session), offer re-auth via browser
                if (server.authType == sh.hnet.comfychair.model.AuthType.BROWSER) {
                    delay(500)
                    connectionState = ConnectionState.IDLE
                    val serverUrl = buildServerUrl(server.hostname, server.port)
                    browserReauthServer = server
                    val intent = WebViewAuthActivity.createIntent(context, serverUrl, server.hostname)
                    webViewAuthLauncher.launch(intent)
                } else if (ConnectionManager.hasOfflineCache(context, server.id)) {
                    // Check if offline cache exists - offer offline mode
                    offlinePromptServer = server
                    showOfflinePrompt = true
                    delay(2000)
                    connectionState = ConnectionState.IDLE
                } else {
                    // No cache available, just show error Toast
                    errorMessage?.let { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    delay(2000)
                    connectionState = ConnectionState.IDLE
                }
            }
        }
    }

    // Trigger attemptConnection after WebView auth completes (avoids forward-reference issue)
    LaunchedEffect(pendingBrowserConnect) {
        pendingBrowserConnect?.let { server ->
            pendingBrowserConnect = null
            delay(200)
            attemptConnection(server)
        }
    }

    // Offline connection function - loads from cache instead of connecting to server
    fun attemptOfflineConnection(server: Server) {
        // Check if cache exists for this server
        if (!ConnectionManager.hasOfflineCache(context, server.id)) {
            Toast.makeText(context, R.string.error_no_offline_cache, Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, R.string.error_no_offline_cache, Toast.LENGTH_LONG).show()
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
                    warningMessage = null
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

    // Delete confirmation dialog
    if (showDeleteConfirmation && selectedServer != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.title_server_delete)) },
            text = { Text(stringResource(R.string.msg_server_delete, selectedServer!!.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        val serverToDelete = selectedServer!!
                        serverStorage.deleteServer(serverToDelete.id)

                        // Also delete credentials for the server
                        credentialStorage.deleteCredentials(serverToDelete.id)

                        servers = serverStorage.getServers()

                        // Select another server or null
                        selectedServer = servers.firstOrNull()
                        selectedServer?.let { serverStorage.setSelectedServerId(it.id) }

                        showDeleteConfirmation = false
                        Toast.makeText(context, R.string.msg_server_deleted_success, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.button_server_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Offline prompt dialog - shown when connection fails but cache exists
    if (showOfflinePrompt && offlinePromptServer != null) {
        AlertDialog(
            onDismissRequest = { showOfflinePrompt = false },
            title = { Text(stringResource(R.string.title_offline_prompt)) },
            text = { Text(stringResource(R.string.msg_offline_prompt)) },
            confirmButton = {
                Button(
                    onClick = {
                        showOfflinePrompt = false
                        // Enable offline mode and attempt offline connection
                        AppSettings.setOfflineMode(context, true)
                        isOfflineMode = true
                        offlinePromptServer?.let { server ->
                            attemptOfflineConnection(server)
                        }
                    }
                ) {
                    Text(stringResource(R.string.button_offline_prompt_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showOfflinePrompt = false }) {
                    Text(stringResource(R.string.button_offline_prompt_dismiss))
                }
            }
        )
    }

    // Certificate warning dialog
    if (showCertDialog && pendingCertServer != null) {
        var rememberChoice by remember { mutableStateOf(false) }

        val certTitle = when (pendingCertIssue) {
            CertificateIssue.SELF_SIGNED -> "Self-Signed Certificate"
            CertificateIssue.UNKNOWN_CA -> "Unknown Certificate Authority"
            else -> "Certificate Warning"
        }
        val certMessage = when (pendingCertIssue) {
            CertificateIssue.SELF_SIGNED ->
                "The server is using a self-signed certificate. " +
                "This is common for local servers but means the connection " +
                "cannot be verified by a trusted authority.\n\n" +
                "Do you want to continue connecting?"
            CertificateIssue.UNKNOWN_CA ->
                "The server's certificate was issued by an unrecognized " +
                "certificate authority. This could mean the CA is not in " +
                "your device's trust store.\n\n" +
                "Do you want to continue connecting?"
            else -> "There is a certificate issue. Continue?"
        }

        AlertDialog(
            onDismissRequest = {
                showCertDialog = false
                connectionState = ConnectionState.IDLE
                pendingCertServer = null
                pendingCertClient = null
                pendingCertProtocol = null
            },
            title = { Text(certTitle) },
            text = {
                Column {
                    Text(certMessage)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { rememberChoice = !rememberChoice }
                    ) {
                        Checkbox(
                            checked = rememberChoice,
                            onCheckedChange = { rememberChoice = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Remember for this server",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCertDialog = false
                        val server = pendingCertServer!!
                        val protocol = pendingCertProtocol ?: "http"
                        pendingCertServer = null
                        pendingCertClient = null
                        pendingCertProtocol = null

                        scope.launch {
                            // Save trust preference if checked
                            if (rememberChoice) {
                                serverStorage.updateServer(server.copy(trustCert = true))
                            }

                            serverStorage.setSelectedServerId(server.id)
                            ConnectionManager.connect(
                                context = context.applicationContext,
                                serverId = server.id,
                                hostname = server.hostname,
                                port = server.port,
                                protocol = protocol,
                                credentials = credentialStorage.getCredentials(server.id, server.authType)
                            )

                            // Navigate to main activity
                            val intent = Intent(context, MainContainerActivity::class.java)
                            context.startActivity(intent)
                        }
                    }
                ) {
                    Text("Connect Anyway")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showCertDialog = false
                        connectionState = ConnectionState.IDLE
                        pendingCertServer = null
                        pendingCertClient = null
                        pendingCertProtocol = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App logo and name
        // The text has a -16dp offset to tuck it closer to the icon (which has built-in padding).
        // To keep the visual center aligned, we offset the entire Row by half that amount (-8dp).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = (-8).dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_comfychair_foreground),
                contentDescription = null,
                modifier = Modifier.size(112.dp)
            )
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 36.sp,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(x = (-16).dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Server dropdown
        ServerDropdown(
            servers = servers,
            selectedServer = selectedServer,
            onServerSelected = { server ->
                selectedServer = server
                serverStorage.setSelectedServerId(server.id)
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Connect split button with server management
        ConnectionSplitButton(
            connectionState = connectionState,
            hasSelectedServer = selectedServer != null,
            isOfflineMode = isOfflineMode,
            onConnect = {
                if (selectedServer != null) {
                    if (isOfflineMode) {
                        attemptOfflineConnection(selectedServer!!)
                    } else {
                        attemptConnection(selectedServer!!)
                    }
                }
            },
            onAddServer = {
                serverToEdit = null
                showServerDialog = true
            },
            onEditServer = {
                serverToEdit = selectedServer
                showServerDialog = true
            },
            onRemoveServer = {
                showDeleteConfirmation = true
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Warning message
        warningMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp
            )
        }
    }
}
