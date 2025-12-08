package sh.hnet.comfychair

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.widget.Toast
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Container activity that hosts fragments with persistent bottom navigation.
 * Manages WebSocket connection and generation state that persists across fragment switches.
 */
class MainContainerActivity : AppCompatActivity() {

    // Connection information
    private var hostname: String = ""
    private var port: Int = 8188

    // ComfyUI client - persists across fragment switches
    private lateinit var comfyUIClient: ComfyUIClient

    // Connection state
    private var isConnectionReady = false
    private val connectionReadyListeners = mutableListOf<() -> Unit>()

    // WebSocket state tracking
    private var isWebSocketConnected = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val keepaliveHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    // Generation state
    private var isGenerating = false
    private var currentPromptId: String? = null
    private var currentProgress = 0
    private var maxProgress = 100

    // Track if image generation completed while no listener was active
    private var pendingImagePromptId: String? = null

    // Listener interface for fragments to receive updates
    interface GenerationStateListener {
        fun onGenerationStateChanged(isGenerating: Boolean, promptId: String?)
        fun onProgressUpdate(current: Int, max: Int)
        fun onImageGenerated(promptId: String)
        fun onGenerationError(message: String)
    }

    private var generationStateListener: GenerationStateListener? = null

    companion object {
        private const val PREFS_NAME = "MainContainerPrefs"
        private const val PREF_IS_GENERATING = "isGenerating"
        private const val PREF_CURRENT_PROMPT_ID = "currentPromptId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_container)

        // Apply window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // Get connection parameters from intent
        hostname = intent.getStringExtra("hostname") ?: ""
        port = intent.getIntExtra("port", 8188)

        // Initialize ComfyUI client
        comfyUIClient = ComfyUIClient(hostname, port)

        // Restore generation state from preferences
        restoreGenerationState()

        // Connect to server and open WebSocket
        connectToServer()

        // Setup bottom navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_text_to_image -> {
                    switchFragment(TextToImageFragment.newInstance(hostname, port))
                    true
                }
                R.id.nav_inpainting -> {
                    switchFragment(InpaintingFragment.newInstance(hostname, port))
                    true
                }
                R.id.nav_gallery -> {
                    switchFragment(GalleryFragment.newInstance(hostname, port))
                    true
                }
                R.id.nav_configuration -> {
                    switchFragment(ConfigurationFragment.newInstance(hostname, port))
                    true
                }
                else -> false
            }
        }

        // Load initial fragment (TextToImage) AFTER connection is ready
        if (savedInstanceState == null) {
            // Wait for connection before loading fragment
            onConnectionReady {
                runOnUiThread {
                    bottomNav.selectedItemId = R.id.nav_text_to_image
                }
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun connectToServer() {
        comfyUIClient.testConnection { success, errorMessage, certIssue ->
            if (success) {
                println("MainContainerActivity: Connection successful!")
                runOnUiThread {
                    isConnectionReady = true
                    openWebSocketConnection()
                    // Notify all waiting listeners
                    notifyConnectionReady()
                }
            } else {
                println("MainContainerActivity: Failed to connect to server: $errorMessage")
                runOnUiThread {
                    Toast.makeText(this@MainContainerActivity, "Failed to connect: $errorMessage", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Register a callback to be notified when connection is ready
     * If already connected, callback is invoked immediately
     */
    fun onConnectionReady(callback: () -> Unit) {
        if (isConnectionReady) {
            callback()
        } else {
            connectionReadyListeners.add(callback)
        }
    }

    /**
     * Notify all registered listeners that connection is ready
     */
    private fun notifyConnectionReady() {
        connectionReadyListeners.forEach { it() }
        connectionReadyListeners.clear()
    }

    /**
     * Check if connection is ready
     */
    fun isConnected(): Boolean = isConnectionReady

    private fun openWebSocketConnection() {
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("MainContainerActivity: WebSocket connected")
                isWebSocketConnected = true
                reconnectAttempts = 0
                // Start keepalive ping mechanism
                startKeepalive()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    val messageType = message.optString("type")

                    when (messageType) {
                        "executing" -> {
                            val data = message.optJSONObject("data")
                            val promptId = data?.optString("prompt_id")
                            val isComplete = data?.isNull("node") == true

                            println("MainContainerActivity: Executing: node=${if (isComplete) "null (complete)" else data?.optString("node")}, promptId=$promptId, currentPromptId=$currentPromptId")

                            if (isComplete && promptId == currentPromptId && promptId != null) {
                                println("MainContainerActivity: Generation complete for prompt: $promptId")
                                runOnUiThread {
                                    if (generationStateListener != null) {
                                        // Notify listener that image is ready
                                        generationStateListener?.onImageGenerated(promptId)
                                    } else {
                                        // No listener active - store for later
                                        println("MainContainerActivity: No listener active, storing pending image prompt: $promptId")
                                        pendingImagePromptId = promptId
                                    }
                                }
                            }
                        }
                        "progress" -> {
                            val data = message.optJSONObject("data")
                            val value = data?.optInt("value", 0) ?: 0
                            val max = data?.optInt("max", 0) ?: 0

                            if (max > 0) {
                                currentProgress = value
                                maxProgress = max
                                runOnUiThread {
                                    generationStateListener?.onProgressUpdate(value, max)
                                }
                            }
                        }
                        "execution_error" -> {
                            println("MainContainerActivity: Execution error: $text")
                            runOnUiThread {
                                resetGenerationState()
                                generationStateListener?.onGenerationError("Image generation failed")
                                Toast.makeText(this@MainContainerActivity, "Image generation failed", Toast.LENGTH_LONG).show()
                            }
                        }
                        "status" -> {
                            val data = message.optJSONObject("data")
                            val status = data?.optJSONObject("status")
                            val execInfo = status?.optJSONObject("exec_info")
                            val queueRemaining = execInfo?.optInt("queue_remaining", -1)

                            if (queueRemaining == 0 && currentPromptId != null && isGenerating) {
                                println("MainContainerActivity: Queue empty, generation might be complete")
                                // The "executing" message with null node will handle completion
                            }
                        }
                        "previewing", "execution_cached", "execution_start", "execution_success", "progress_state", "executed" -> {
                            // Known message types that we don't need to handle
                        }
                        else -> {
                            println("MainContainerActivity: Unknown WebSocket message type: $messageType")
                        }
                    }
                } catch (e: Exception) {
                    println("MainContainerActivity: Failed to parse WebSocket message: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("MainContainerActivity: WebSocket failed: ${t.message}")
                isWebSocketConnected = false
                stopKeepalive()

                runOnUiThread {
                    if (isGenerating) {
                        resetGenerationState()
                        generationStateListener?.onGenerationError("Connection lost during generation")
                        Toast.makeText(this@MainContainerActivity, "Connection lost during generation", Toast.LENGTH_LONG).show()
                    }

                    // Attempt to reconnect
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("MainContainerActivity: WebSocket closed: $code - $reason")
                isWebSocketConnected = false
                stopKeepalive()

                // Attempt to reconnect if not a normal closure
                if (code != 1000) {
                    runOnUiThread {
                        scheduleReconnect()
                    }
                }
            }
        }

        comfyUIClient.openWebSocket(webSocketListener)
    }

    /**
     * Start image generation with the given workflow JSON
     * Called by TextToImageFragment
     */
    fun startGeneration(workflowJson: String, callback: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit) {
        // Verify WebSocket is connected before starting generation
        if (!isWebSocketConnected) {
            println("MainContainerActivity: WebSocket not connected, attempting to reconnect...")
            Toast.makeText(this, "Reconnecting to server...", Toast.LENGTH_SHORT).show()

            // Try to reconnect immediately
            reconnectWebSocket()

            // Wait a bit for reconnection, then try submission
            Handler(Looper.getMainLooper()).postDelayed({
                if (isWebSocketConnected) {
                    submitWorkflow(workflowJson, callback)
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainContainerActivity, "Connection lost. Please try again.", Toast.LENGTH_LONG).show()
                        callback(false, null, "WebSocket not connected")
                    }
                }
            }, 2000)
        } else {
            submitWorkflow(workflowJson, callback)
        }
    }

    /**
     * Submit workflow to server (internal method)
     */
    private fun submitWorkflow(workflowJson: String, callback: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit) {
        comfyUIClient.submitPrompt(workflowJson) { success, promptId, errorMessage ->
            runOnUiThread {
                if (success && promptId != null) {
                    println("MainContainerActivity: Workflow submitted successfully. Prompt ID: $promptId")
                    isGenerating = true
                    currentPromptId = promptId
                    saveGenerationState()
                    generationStateListener?.onGenerationStateChanged(true, promptId)
                    callback(true, promptId, null)
                } else {
                    println("MainContainerActivity: Failed to submit workflow: $errorMessage")
                    Toast.makeText(this@MainContainerActivity, "Failed to start generation: $errorMessage", Toast.LENGTH_LONG).show()
                    callback(false, null, errorMessage)
                }
            }
        }
    }

    /**
     * Cancel the currently running generation
     * Called by TextToImageFragment
     */
    fun cancelGeneration(callback: (success: Boolean) -> Unit) {
        comfyUIClient.interruptExecution { success ->
            runOnUiThread {
                if (success) {
                    println("MainContainerActivity: Generation canceled successfully")
                } else {
                    println("MainContainerActivity: Failed to cancel generation")
                }
                resetGenerationState()
                generationStateListener?.onGenerationStateChanged(false, null)
                callback(success)
            }
        }
    }

    /**
     * Reset generation state after completion or cancellation
     */
    private fun resetGenerationState() {
        isGenerating = false
        currentPromptId = null
        currentProgress = 0
        maxProgress = 100
        pendingImagePromptId = null
        saveGenerationState()
    }

    /**
     * Complete generation after image is fetched
     * Called by TextToImageFragment after it successfully fetches the image
     */
    fun completeGeneration() {
        resetGenerationState()
        generationStateListener?.onGenerationStateChanged(false, null)
    }

    /**
     * Save generation state to SharedPreferences
     */
    private fun saveGenerationState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_IS_GENERATING, isGenerating)
            putString(PREF_CURRENT_PROMPT_ID, currentPromptId)
            apply()
        }
    }

    /**
     * Restore generation state from SharedPreferences
     */
    private fun restoreGenerationState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isGenerating = prefs.getBoolean(PREF_IS_GENERATING, false)
        currentPromptId = prefs.getString(PREF_CURRENT_PROMPT_ID, null)
        println("MainContainerActivity: Restored generation state: isGenerating=$isGenerating, promptId=$currentPromptId")
    }

    /**
     * Get the ComfyUI client
     */
    fun getComfyUIClient(): ComfyUIClient = comfyUIClient

    /**
     * Get current generation state
     */
    fun getGenerationState(): GenerationState {
        return GenerationState(isGenerating, currentPromptId, currentProgress, maxProgress)
    }

    /**
     * Set the generation state listener
     * Called by TextToImageFragment when it becomes visible
     */
    fun setGenerationStateListener(listener: GenerationStateListener?) {
        generationStateListener = listener

        if (listener != null) {
            // Check if there's a pending completed image
            val pending = pendingImagePromptId
            if (pending != null) {
                println("MainContainerActivity: Notifying listener of pending image: $pending")
                pendingImagePromptId = null
                listener.onImageGenerated(pending)
            } else {
                // Immediately notify the listener of current state
                listener.onGenerationStateChanged(isGenerating, currentPromptId)
                if (isGenerating) {
                    listener.onProgressUpdate(currentProgress, maxProgress)
                }
            }
        }
    }

    /**
     * Start keepalive ping mechanism
     * Sends a ping every 30 seconds to keep WebSocket connection alive
     */
    private fun startKeepalive() {
        stopKeepalive() // Clear any existing keepalive

        val keepaliveRunnable = object : Runnable {
            override fun run() {
                if (isWebSocketConnected) {
                    // Send ping to keep connection alive
                    val pingSuccess = comfyUIClient.sendWebSocketMessage("{\"type\":\"ping\"}")
                    if (!pingSuccess) {
                        println("MainContainerActivity: Failed to send keepalive ping")
                    }
                    // Schedule next ping in 30 seconds
                    keepaliveHandler.postDelayed(this, 30000)
                }
            }
        }

        // Start first ping after 30 seconds
        keepaliveHandler.postDelayed(keepaliveRunnable, 30000)
    }

    /**
     * Stop keepalive ping mechanism
     */
    private fun stopKeepalive() {
        keepaliveHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Schedule WebSocket reconnection with exponential backoff
     */
    private fun scheduleReconnect() {
        // Don't reconnect if we've exceeded max attempts
        if (reconnectAttempts >= maxReconnectAttempts) {
            println("MainContainerActivity: Max reconnection attempts reached")
            Toast.makeText(this, "Connection lost. Please restart the app.", Toast.LENGTH_LONG).show()
            return
        }

        // Cancel any pending reconnection attempts
        reconnectHandler.removeCallbacksAndMessages(null)

        // Calculate delay with exponential backoff: 2^attempts seconds
        val delayMs = (Math.pow(2.0, reconnectAttempts.toDouble()) * 1000).toLong()
        reconnectAttempts++

        println("MainContainerActivity: Scheduling reconnection attempt $reconnectAttempts in ${delayMs}ms")

        reconnectHandler.postDelayed({
            reconnectWebSocket()
        }, delayMs)
    }

    /**
     * Reconnect WebSocket connection
     */
    private fun reconnectWebSocket() {
        println("MainContainerActivity: Attempting to reconnect WebSocket...")

        // Close existing connection if any
        comfyUIClient.closeWebSocket()

        // Test connection and reopen WebSocket
        comfyUIClient.testConnection { success, errorMessage, _ ->
            if (success) {
                runOnUiThread {
                    openWebSocketConnection()
                    println("MainContainerActivity: WebSocket reconnected successfully")
                }
            } else {
                println("MainContainerActivity: Reconnection failed: $errorMessage")
                // Will retry via scheduleReconnect if needed
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeepalive()
        reconnectHandler.removeCallbacksAndMessages(null)
        comfyUIClient.shutdown()
    }

    /**
     * Data class to hold generation state
     */
    data class GenerationState(
        val isGenerating: Boolean,
        val promptId: String?,
        val currentProgress: Int,
        val maxProgress: Int
    )
}
