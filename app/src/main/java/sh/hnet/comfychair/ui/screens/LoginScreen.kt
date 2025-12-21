package sh.hnet.comfychair.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.CertificateIssue
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.MainContainerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
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
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var hostname by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8188") }
    var hostnameError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var connectionState by remember { mutableStateOf(ConnectionState.IDLE) }
    var warningMessage by remember { mutableStateOf<String?>(null) }
    var comfyUIClient by remember { mutableStateOf<ComfyUIClient?>(null) }
    var hasAutoConnected by remember { mutableStateOf(false) }

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
    val warningSelfSigned = stringResource(R.string.warning_self_signed_cert)
    val warningUnknownCa = stringResource(R.string.warning_unknown_ca)

    // Connection function - uses scope.launch so it's not tied to LaunchedEffect lifecycle
    fun attemptConnection() {
        // Validate inputs
        var isValid = true
        hostnameError = null
        portError = null
        warningMessage = null

        val trimmedHostname = hostname.trim()
        val trimmedPort = port.trim()

        if (trimmedHostname.isEmpty()) {
            hostnameError = errorRequired
            isValid = false
        } else if (!isValidHostname(trimmedHostname)) {
            hostnameError = errorInvalidHostname
            isValid = false
        }

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

        if (!isValid) {
            connectionState = ConnectionState.IDLE
            return
        }

        connectionState = ConnectionState.CONNECTING

        // Launch connection in scope (survives state changes)
        scope.launch {
            val portNum = trimmedPort.toInt()
            val client = ComfyUIClient(context.applicationContext, trimmedHostname, portNum)
            comfyUIClient = client

            // Test connection using suspendCoroutine
            val result = suspendCoroutine { continuation ->
                client.testConnection { success, errorMessage, certIssue ->
                    continuation.resume(Triple(success, errorMessage, certIssue))
                }
            }

            val (success, errorMessage, certIssue) = result

            if (success) {
                // Skip WebSocket test - HTTP test is sufficient
                // WebSocket will be established by GenerationViewModel
                connectionState = ConnectionState.CONNECTED

                // Save connection info
                val prefs = context.getSharedPreferences("ComfyChairPrefs", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putString("hostname", trimmedHostname)
                    putInt("port", portNum)
                    apply()
                }

                // Handle certificate warnings
                val navigateDelay = when (certIssue) {
                    CertificateIssue.SELF_SIGNED -> {
                        warningMessage = warningSelfSigned
                        1000L
                    }
                    CertificateIssue.UNKNOWN_CA -> {
                        warningMessage = warningUnknownCa
                        1000L
                    }
                    CertificateIssue.NONE -> 500L
                }

                delay(navigateDelay)

                // Establish connection via ConnectionManager
                val detectedProtocol = client.getWorkingProtocol() ?: "http"
                ConnectionManager.connect(
                    context = context.applicationContext,
                    hostname = trimmedHostname,
                    port = portNum,
                    protocol = detectedProtocol
                )

                // Navigate to main activity (no Intent extras needed)
                val intent = Intent(context, MainContainerActivity::class.java)
                context.startActivity(intent)
            } else {
                connectionState = ConnectionState.FAILED
                // Show error Toast
                errorMessage?.let { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
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

    // Load saved connection info and auto-connect
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ComfyChairPrefs", Context.MODE_PRIVATE)
        val savedHostname = prefs.getString("hostname", "") ?: ""
        val savedPort = prefs.getInt("port", 8188)

        // Check if user explicitly logged out - don't auto-connect in that case
        val shouldAutoConnect = !ConnectionManager.userInitiatedLogout
        ConnectionManager.clearLogoutFlag()

        if (savedHostname.isNotEmpty()) {
            hostname = savedHostname
            port = savedPort.toString()

            // Auto-connect after a small delay (only on fresh app launch, not after logout)
            if (!hasAutoConnected && shouldAutoConnect) {
                hasAutoConnected = true
                delay(500)
                if (connectionState == ConnectionState.IDLE) {
                    attemptConnection()
                }
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            comfyUIClient?.shutdown()
        }
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App logo and name
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(112.dp)
            )
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 44.sp,
                fontFamily = FontFamily.Cursive,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(x = (-16).dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Hostname input
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
            isError = hostnameError != null,
            supportingText = hostnameError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Port input
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

        Spacer(modifier = Modifier.height(32.dp))

        // Connect button
        val buttonColors = when (connectionState) {
            ConnectionState.IDLE -> ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            ConnectionState.CONNECTING -> ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
            ConnectionState.FAILED -> ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
            ConnectionState.CONNECTED -> ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        }

        val buttonText = when (connectionState) {
            ConnectionState.IDLE -> stringResource(R.string.button_connect)
            ConnectionState.CONNECTING -> stringResource(R.string.button_connecting)
            ConnectionState.FAILED -> stringResource(R.string.button_failed)
            ConnectionState.CONNECTED -> stringResource(R.string.button_connected)
        }

        ElevatedButton(
            onClick = {
                if (connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.CONNECTED) {
                    attemptConnection()
                }
            },
            enabled = connectionState != ConnectionState.CONNECTING && connectionState != ConnectionState.CONNECTED,
            colors = buttonColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = buttonText,
                fontSize = 18.sp
            )
        }

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
