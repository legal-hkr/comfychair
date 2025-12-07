package sh.hnet.comfychair

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * MainActivity - The login/connection screen
 *
 * This activity handles the initial connection to the ComfyUI server.
 * Users enter the hostname and port, then click Connect to test the connection.
 */
class MainActivity : AppCompatActivity() {

    // UI element references - we'll connect these to the views in the layout
    // TextInputLayout is the wrapper that can display error messages
    private lateinit var hostnameInputLayout: TextInputLayout
    private lateinit var portInputLayout: TextInputLayout
    // TextInputEditText is the actual text input field
    private lateinit var hostnameInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var connectButton: Button
    // Warning text for self-signed certificates
    private lateinit var warningText: TextView

    // Handler for posting delayed actions on the main UI thread
    private val handler = Handler(Looper.getMainLooper())

    // ComfyUI client for API communication
    // Initialized when user attempts to connect
    private var comfyUIClient: ComfyUIClient? = null

    /**
     * Connection states enum
     * This defines all possible states the connection can be in
     */
    enum class ConnectionState {
        IDLE,        // Initial state - ready to connect
        CONNECTING,  // Currently testing connection
        FAILED,      // Connection test failed
        CONNECTED    // Connection successful
    }

    // Track the current connection state
    private var currentState = ConnectionState.IDLE

    // Track if this is the first resume (to allow auto-connect)
    private var isFirstResume = true

    // SharedPreferences for saving connection info
    private val PREFS_NAME = "ComfyChairPrefs"
    private val PREF_HOSTNAME = "hostname"
    private val PREF_PORT = "port"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Handle system bars padding for edge-to-edge display and keyboard (IME) insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Calculate bottom padding based on keyboard state
            val bottomPadding = if (imeInsets.bottom > 0) {
                // Keyboard is showing - use keyboard height
                imeInsets.bottom
            } else {
                // Keyboard is hidden - use system bars
                systemBars.bottom
            }

            // Convert 24dp to pixels for consistent padding
            val horizontalPadding = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP,
                24f,
                resources.displayMetrics
            ).toInt()

            v.setPadding(
                systemBars.left + horizontalPadding,
                systemBars.top,
                systemBars.right + horizontalPadding,
                bottomPadding
            )

            insets
        }

        // Initialize UI elements by finding them in the layout
        initializeViews()

        // Set up button click listener
        setupConnectButton()

        // Load saved connection info and auto-connect
        loadSavedConnectionAndAutoConnect()
    }

    /**
     * Initialize all UI element references
     * findViewById connects our Kotlin variables to the XML layout views
     */
    private fun initializeViews() {
        // Get references to the TextInputLayout wrappers (for showing errors)
        hostnameInputLayout = findViewById(R.id.hostnameInputLayout)
        portInputLayout = findViewById(R.id.portInputLayout)
        // Get references to the actual input fields (for reading values)
        hostnameInput = findViewById(R.id.hostnameInput)
        portInput = findViewById(R.id.portInput)
        connectButton = findViewById(R.id.connectButton)
        warningText = findViewById(R.id.warningText)
    }

    /**
     * Set up the connect button click listener
     * This defines what happens when the user taps the Connect button
     */
    private fun setupConnectButton() {
        connectButton.setOnClickListener {
            // Only allow connection if we're not already connecting
            if (currentState != ConnectionState.CONNECTING) {
                attemptConnection()
            }
        }
    }

    /**
     * Load saved connection info from SharedPreferences and auto-connect
     */
    private fun loadSavedConnectionAndAutoConnect() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedHostname = prefs.getString(PREF_HOSTNAME, "")
        val savedPort = prefs.getInt(PREF_PORT, 8188)

        if (!savedHostname.isNullOrEmpty()) {
            // Restore saved values to UI
            hostnameInput.setText(savedHostname)
            portInput.setText(savedPort.toString())

            // Auto-connect
            handler.postDelayed({
                if (currentState == ConnectionState.IDLE) {
                    attemptConnection()
                }
            }, 500) // Small delay to let UI settle
        }
    }

    /**
     * Save connection info to SharedPreferences
     */
    private fun saveConnectionInfo(hostname: String, port: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            putString(PREF_HOSTNAME, hostname)
            putInt(PREF_PORT, port)
            apply()
        }
    }

    /**
     * Attempt to connect to the ComfyUI server
     * This method orchestrates the connection test process
     */
    private fun attemptConnection() {
        // Clear any previous error messages before validation
        // This is important so old errors don't persist after user fixes the input
        clearErrors()

        // Get the hostname and port from input fields
        val hostname = hostnameInput.text.toString().trim()
        val portString = portInput.text.toString().trim()

        // Track if validation passed
        var isValid = true

        // Validate hostname field
        if (hostname.isEmpty()) {
            // Set error on the TextInputLayout
            // This will:
            // 1. Show "Required" text in red below the field
            // 2. Change the border color to red
            // 3. Show an error icon
            hostnameInputLayout.error = getString(R.string.error_required)
            isValid = false
        }

        // Validate port number field
        if (portString.isEmpty()) {
            // Port field is empty - show "Required" error
            portInputLayout.error = getString(R.string.error_required)
            isValid = false
        } else {
            // Port field has a value - check if it's a valid number
            val port = portString.toIntOrNull()
            if (port == null || port !in 1..65535) {
                // Invalid port number (not a number or out of valid range 1-65535)
                portInputLayout.error = getString(R.string.error_invalid_port)
                isValid = false
            }
        }

        // Only proceed with connection if all fields are valid
        if (!isValid) {
            return
        }

        // All validation passed - start the connection test
        updateConnectionState(ConnectionState.CONNECTING)

        // Simulate connection test (replace with actual API call later)
        // We can safely use !! here because we validated the port above
        testConnection(hostname, portString.toInt())
    }

    /**
     * Clear all error messages from input fields
     * Called before validation to remove old error messages
     */
    private fun clearErrors() {
        // Setting error to null removes the error message and red border
        hostnameInputLayout.error = null
        portInputLayout.error = null
        // Also hide the warning message
        warningText.visibility = View.GONE
    }

    /**
     * Test the connection to the ComfyUI server
     * This method:
     * 1. Creates a ComfyUIClient instance
     * 2. Tests HTTP/HTTPS connection (auto-detects which works)
     * 3. Opens a WebSocket connection if successful
     *
     * @param hostname The server hostname or IP address
     * @param port The server port number
     */
    private fun testConnection(hostname: String, port: Int) {
        // Create a new ComfyUI client for this connection
        comfyUIClient = ComfyUIClient(hostname, port)

        // Test the connection
        // This will try HTTPS first, then fall back to HTTP if needed
        // The callback runs on a background thread, so we need to use runOnUiThread
        comfyUIClient?.testConnection { success, errorMessage, certIssue ->
            // runOnUiThread ensures UI updates happen on the main thread
            // Android requires all UI operations to be on the main thread
            runOnUiThread {
                if (success) {
                    // Connection test succeeded! Now open WebSocket
                    openWebSocketConnection(certIssue)
                } else {
                    // Connection test failed
                    // Show error state
                    updateConnectionState(ConnectionState.FAILED)

                    // Log the error for debugging
                    println("Connection failed: $errorMessage")

                    // Reset to idle state after 2 seconds so user can retry
                    handler.postDelayed({
                        updateConnectionState(ConnectionState.IDLE)
                    }, 2000)
                }
            }
        }
    }

    /**
     * Open WebSocket connection to ComfyUI server
     * WebSocket provides real-time bidirectional communication
     * ComfyUI uses it to send progress updates and receive workflow commands
     *
     * @param certIssue The type of certificate issue detected (NONE, SELF_SIGNED, UNKNOWN_CA)
     */
    private fun openWebSocketConnection(certIssue: CertificateIssue) {
        // Create WebSocket listener to handle events
        val webSocketListener = object : WebSocketListener() {
            /**
             * Called when WebSocket connection is established
             */
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Connection successful!
                runOnUiThread {
                    updateConnectionState(ConnectionState.CONNECTED)

                    // Save connection info for future use
                    val hostname = hostnameInput.text.toString().trim()
                    val port = portInput.text.toString().toIntOrNull() ?: 8188
                    saveConnectionInfo(hostname, port)

                    // Handle certificate issues - show appropriate warning
                    when (certIssue) {
                        CertificateIssue.SELF_SIGNED -> {
                            // Self-signed certificate detected
                            warningText.text = getString(R.string.warning_self_signed_cert)
                            warningText.visibility = View.VISIBLE
                            // Wait 1 second before navigating (to show the warning)
                            handler.postDelayed({
                                navigateToQueryActivity()
                            }, 1000)
                        }
                        CertificateIssue.UNKNOWN_CA -> {
                            // Unknown CA certificate detected
                            warningText.text = getString(R.string.warning_unknown_ca)
                            warningText.visibility = View.VISIBLE
                            // Wait 1 second before navigating (to show the warning)
                            handler.postDelayed({
                                navigateToQueryActivity()
                            }, 1000)
                        }
                        CertificateIssue.NONE -> {
                            // No certificate issue - valid certificate
                            // Navigate after 0.5 second
                            handler.postDelayed({
                                navigateToQueryActivity()
                            }, 500)
                        }
                    }
                }
                println("WebSocket connected!")
            }

            /**
             * Called when a text message is received from the server
             * ComfyUI sends JSON messages with progress updates and events
             */
            override fun onMessage(webSocket: WebSocket, text: String) {
                println("WebSocket message received: $text")
                // TODO: Parse and handle ComfyUI messages
                // Messages include: execution progress, queue updates, etc.
            }

            /**
             * Called when the WebSocket connection fails
             */
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    updateConnectionState(ConnectionState.FAILED)
                    println("WebSocket failed: ${t.message}")

                    // Reset to idle after 2 seconds
                    handler.postDelayed({
                        updateConnectionState(ConnectionState.IDLE)
                    }, 2000)
                }
            }

            /**
             * Called when WebSocket connection is closed
             */
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("WebSocket closed: $code - $reason")
            }
        }

        // Attempt to open WebSocket with our listener
        val opened = comfyUIClient?.openWebSocket(webSocketListener) ?: false

        if (!opened) {
            // Failed to open WebSocket (no working protocol found)
            updateConnectionState(ConnectionState.FAILED)
            handler.postDelayed({
                updateConnectionState(ConnectionState.IDLE)
            }, 2000)
        }
    }

    /**
     * Update the UI to reflect the current connection state
     * This changes the button text, color, and enabled state
     *
     * @param newState The new connection state
     */
    private fun updateConnectionState(newState: ConnectionState) {
        currentState = newState

        // Get the default primary color from theme
        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        val defaultColor = typedValue.data

        when (newState) {
            ConnectionState.IDLE -> {
                // Initial state: "Connect" button with default accent color
                connectButton.text = getString(R.string.button_connect)
                connectButton.isEnabled = true
                // Reset to default button color
                connectButton.backgroundTintList = ColorStateList.valueOf(defaultColor)
            }

            ConnectionState.CONNECTING -> {
                // Connecting state: "Connecting..." with yellow color
                connectButton.text = getString(R.string.button_connecting)
                connectButton.isEnabled = false // Disable button during connection
                connectButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.connecting_yellow)
                )
            }

            ConnectionState.FAILED -> {
                // Failed state: "Failed" with red color
                connectButton.text = getString(R.string.button_failed)
                connectButton.isEnabled = true
                connectButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.failed_red)
                )
            }

            ConnectionState.CONNECTED -> {
                // Connected state: "Connected" with green color
                connectButton.text = getString(R.string.button_connected)
                connectButton.isEnabled = false
                connectButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.connected_green)
                )
            }
        }
    }

    /**
     * Navigate to MainContainerActivity after successful connection
     * This is the main screen where users interact with ComfyUI
     */
    private fun navigateToQueryActivity() {
        // Close the WebSocket connection from MainActivity
        // MainContainerActivity will create its own connection
        comfyUIClient?.closeWebSocket()

        // Create an Intent to start MainContainerActivity
        val intent = Intent(this, MainContainerActivity::class.java)

        // Pass connection information to MainContainerActivity
        val hostname = hostnameInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull() ?: 8188
        intent.putExtra("hostname", hostname)
        intent.putExtra("port", port)

        // Start the new activity
        startActivity(intent)
        // Optionally finish this activity so user can't go back to login screen
        // finish()
    }

    /**
     * Reset connection state when activity resumes
     * This allows users to reconnect when they navigate back from other activities
     */
    override fun onResume() {
        super.onResume()

        // Skip reset on first resume (to allow auto-connect)
        if (isFirstResume) {
            isFirstResume = false
            return
        }

        // Reset to IDLE state to allow reconnection
        if (currentState == ConnectionState.CONNECTED) {
            // Close any existing connection
            comfyUIClient?.shutdown()
            comfyUIClient = null
            // Reset UI to allow new connection
            updateConnectionState(ConnectionState.IDLE)
        }
    }

    /**
     * Clean up when activity is destroyed
     * Important to prevent memory leaks from Handler callbacks and WebSocket connections
     */
    override fun onDestroy() {
        super.onDestroy()
        // Remove any pending callbacks to prevent memory leaks
        handler.removeCallbacksAndMessages(null)
        // Clean up ComfyUI client and close WebSocket connection
        comfyUIClient?.shutdown()
        comfyUIClient = null
    }
}