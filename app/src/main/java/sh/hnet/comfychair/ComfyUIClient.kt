package sh.hnet.comfychair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ComfyUIClient - Handles communication with ComfyUI server
 *
 * This class is responsible for:
 * 1. Testing HTTP/HTTPS connection to the server
 * 2. Auto-detecting whether to use HTTP or HTTPS
 * 3. Opening WebSocket connection for real-time updates
 *
 * @param context Application context for string resources
 * @param hostname The server hostname or IP address (e.g., "192.168.1.100")
 * @param port The server port number (default: 8188)
 * @param credentials Authentication credentials for the server
 */
class ComfyUIClient(
    private val context: Context? = null,
    private val hostname: String,
    private val port: Int,
    credentials: AuthCredentials = AuthCredentials.None
) {
    companion object {
        private const val TAG = "API"
    }

    // Auth interceptor for adding Authorization headers
    private val authInterceptor = AuthInterceptor(credentials)

    // OkHttpClient for HTTP requests - short timeouts are fine
    private val httpClient = SelfSignedCertHelper.configureToAcceptSelfSigned(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)  // Max time to establish connection
            .readTimeout(10, TimeUnit.SECONDS)     // Max time to read response
            .writeTimeout(10, TimeUnit.SECONDS)    // Max time to write request
    ).build()

    // Separate OkHttpClient for WebSocket connections
    // WebSockets need longer timeouts since data may not arrive for extended periods
    // pingInterval keeps the connection alive during long operations
    private val webSocketClient = SelfSignedCertHelper.configureToAcceptSelfSigned(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)      // No read timeout for WebSocket
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)    // Keep connection alive with pings
    ).build()

    // Store which protocol worked (http or https)
    private var workingProtocol: String? = null

    /**
     * Set the working protocol externally.
     * Used by ConnectionManager when creating a shared client instance.
     *
     * @param protocol Either "http" or "https"
     */
    fun setWorkingProtocol(protocol: String) {
        workingProtocol = protocol
    }

    /**
     * Get the current working protocol.
     * @return "http", "https", or null if not yet determined
     */
    fun getWorkingProtocol(): String? = workingProtocol

    /**
     * Update the credentials used for authentication.
     * Thread-safe - can be called from any thread.
     *
     * @param credentials The new credentials to use
     */
    fun setCredentials(credentials: AuthCredentials) {
        authInterceptor.setCredentials(credentials)
    }

    /**
     * Get the current credentials.
     * @return The current authentication credentials
     */
    fun getCredentials(): AuthCredentials = authInterceptor.getCredentials()

    // Store the active WebSocket connection
    private var webSocket: WebSocket? = null

    // Client ID for tracking WebSocket messages
    // This must match the client_id used when submitting prompts
    // Can be overridden by ConnectionManager to ensure WebSocket and HTTP use the same ID
    private var clientId = "comfychair_android_${UUID.randomUUID()}"

    /**
     * Set the client ID externally.
     * Used by ConnectionManager to ensure WebSocket connection and prompt submission
     * use the same client ID.
     *
     * @param id The client ID to use
     */
    fun setClientId(id: String) {
        clientId = id
    }

    /**
     * Test connection to the ComfyUI server
     * Tries HTTPS first, then falls back to HTTP if HTTPS fails
     *
     * @param callback Called with the result:
     *                 - success: true if connected, false if failed
     *                 - errorMessage: error message if failed, null if success
     *                 - certIssue: the type of certificate issue detected (NONE, SELF_SIGNED, UNKNOWN_CA)
     *                 - failureType: the type of failure (NONE, AUTHENTICATION, NETWORK, INVALID_SERVER)
     */
    fun testConnection(callback: (success: Boolean, errorMessage: String?, certIssue: CertificateIssue, failureType: ConnectionFailure) -> Unit) {
        DebugLogger.i(TAG, "Testing connection to ${Obfuscator.hostname(hostname)}")
        // Reset the certificate issue detection
        SelfSignedCertHelper.reset()
        // Try HTTPS first (more secure)
        tryConnection("https", callback)
    }

    /**
     * Try connecting with a specific protocol (http or https)
     *
     * @param protocol Either "http" or "https"
     * @param callback Called with result
     */
    private fun tryConnection(
        protocol: String,
        callback: (success: Boolean, errorMessage: String?, certIssue: CertificateIssue, failureType: ConnectionFailure) -> Unit
    ) {
        // Build the URL to test
        // ComfyUI provides /system_stats endpoint that returns server info
        val url = "$protocol://$hostname:$port/system_stats"

        // Create an HTTP GET request
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        // Execute the request asynchronously (in background thread)
        // We use enqueue instead of execute to avoid blocking the UI thread
        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // Connection failed with this protocol
                if (protocol == "https") {
                    // HTTPS failed, try HTTP as fallback
                    tryConnection("http", callback)
                } else {
                    // HTTP also failed - both protocols don't work
                    callback(false, "Connection failed: ${e.message}", CertificateIssue.NONE, ConnectionFailure.NETWORK)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        // Validate this is actually a ComfyUI server by checking response content
                        try {
                            val body = response.body?.string() ?: "{}"
                            val json = JSONObject(body)
                            // ComfyUI's /system_stats returns a "system" object with "os" field
                            val system = json.optJSONObject("system")
                            if (system != null && system.has("os")) {
                                // Valid ComfyUI server
                                workingProtocol = protocol
                                val certIssue = SelfSignedCertHelper.certificateIssue
                                DebugLogger.i(TAG, "Connection successful (protocol: $protocol)")
                                callback(true, null, certIssue, ConnectionFailure.NONE)
                            } else {
                                // Response doesn't match ComfyUI format
                                DebugLogger.w(TAG, "Invalid response: not a ComfyUI server")
                                if (protocol == "https") {
                                    tryConnection("http", callback)
                                } else {
                                    callback(false, context?.getString(R.string.error_not_comfyui_server)
                                        ?: "Not a ComfyUI server", CertificateIssue.NONE, ConnectionFailure.INVALID_SERVER)
                                }
                            }
                        } catch (e: Exception) {
                            // Response is not valid JSON - not a ComfyUI server
                            DebugLogger.w(TAG, "Invalid response: ${e.message}")
                            if (protocol == "https") {
                                tryConnection("http", callback)
                            } else {
                                callback(false, context?.getString(R.string.error_invalid_server_response)
                                    ?: "Invalid server response", CertificateIssue.NONE, ConnectionFailure.INVALID_SERVER)
                            }
                        }
                    } else {
                        // Got response but status code indicates error
                        val code = response.code

                        // Check for authentication errors - these should not fallback to HTTP
                        // since authentication is protocol-independent
                        if (code == 401 || code == 403) {
                            workingProtocol = protocol  // Protocol works, auth doesn't
                            DebugLogger.w(TAG, "Authentication failed (protocol: $protocol, code: $code)")
                            callback(
                                false,
                                context?.getString(R.string.auth_error_unauthorized)
                                    ?: "Authentication failed. Please check your credentials.",
                                SelfSignedCertHelper.certificateIssue,
                                ConnectionFailure.AUTHENTICATION
                            )
                        } else if (protocol == "https") {
                            // Try HTTP as fallback for other errors
                            tryConnection("http", callback)
                        } else {
                            // Both protocols failed
                            DebugLogger.w(TAG, "Connection failed: server error $code")
                            callback(false, "Server returned error: $code", CertificateIssue.NONE, ConnectionFailure.NETWORK)
                        }
                    }
                }
            }
        })
    }

    /**
     * Open WebSocket connection to ComfyUI server
     * WebSocket allows real-time bi-directional communication
     * ComfyUI uses it to send progress updates and receive commands
     *
     * IMPORTANT: The client_id query parameter is required to receive
     * progress and preview events for this client's jobs
     *
     * @param listener WebSocket event listener
     * @return true if connection attempt started, false if no working protocol found
     */
    fun openWebSocket(listener: WebSocketListener): Boolean {
        return openWebSocket(clientId, listener)
    }

    /**
     * Open WebSocket connection with a specific client ID.
     * Used by ConnectionManager to provide a shared client ID across components.
     *
     * @param clientIdOverride The client ID to use for this WebSocket connection
     * @param listener WebSocket event listener
     * @return true if connection attempt started, false if no working protocol found
     */
    fun openWebSocket(clientIdOverride: String, listener: WebSocketListener): Boolean {
        // Make sure we have a working protocol from testConnection
        val protocol = workingProtocol ?: run {
            DebugLogger.w(TAG, "Cannot open WebSocket: no working protocol")
            return false
        }

        // Build WebSocket URL with clientId parameter
        // This is crucial - ComfyUI only sends progress/preview events to the matching client_id
        // ws:// for http, wss:// for https
        val wsProtocol = if (protocol == "https") "wss" else "ws"
        val wsUrl = "$wsProtocol://$hostname:$port/ws?clientId=$clientIdOverride"

        DebugLogger.i(TAG, "Opening WebSocket connection")

        // Create WebSocket request
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        // Open WebSocket connection using dedicated WebSocket client with longer timeouts
        webSocket = webSocketClient.newWebSocket(request, listener)

        return true
    }

    /**
     * Check if the WebSocket connection is currently active.
     * @return true if connected, false otherwise
     */
    fun isWebSocketConnected(): Boolean {
        return webSocket != null
    }

    /**
     * Close the WebSocket connection
     * Should be called when done with the connection to free resources
     *
     * @param code Close code (1000 = normal closure)
     * @param reason Optional reason for closing
     */
    fun closeWebSocket(code: Int = 1000, reason: String? = null) {
        DebugLogger.i(TAG, "Closing WebSocket (code: $code)")
        webSocket?.close(code, reason)
        webSocket = null
    }

    /**
     * Send a message through the WebSocket
     *
     * @param message The message to send (usually JSON)
     * @return true if sent successfully, false if no connection
     */
    fun sendWebSocketMessage(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    /**
     * Get the base URL for HTTP requests
     * Uses the protocol that was determined to work during testConnection
     *
     * @return The base URL (e.g., "https://192.168.1.100:8188")
     */
    fun getBaseUrl(): String? {
        return workingProtocol?.let { "$it://$hostname:$port" }
    }

    /**
     * Fetch all available node types from the ComfyUI server.
     * Uses the /object_info endpoint to get the complete node registry.
     *
     * @param callback Called with the result:
     *                 - nodeTypes: Set of available node class_type names, or null on error
     */
    fun fetchAllNodeTypes(callback: (nodeTypes: Set<String>?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/object_info"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // The keys of the object are the available node class_types
                            val nodeTypes = mutableSetOf<String>()
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                nodeTypes.add(keys.next())
                            }
                            callback(nodeTypes)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch the full /object_info response from the ComfyUI server.
     * Returns the complete node type information including inputs/outputs.
     * Used for resolving slot types for edge coloring in workflow editor.
     *
     * @param callback Called with the result:
     *                 - objectInfo: The full JSON object, or null on error
     */
    fun fetchFullObjectInfo(callback: (objectInfo: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/object_info"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.w(TAG, "Failed to fetch object_info: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            callback(json)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse object_info: ${e.message}")
                            callback(null)
                        }
                    } else {
                        DebugLogger.w(TAG, "object_info request failed: ${response.code}")
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch available checkpoints from the ComfyUI server
     * Retrieves the list of checkpoint models that can be used for generation
     *
     * @param callback Called with the result:
     *                 - checkpoints: List of checkpoint filenames, or empty list on error
     */
    fun fetchCheckpoints(callback: (checkpoints: List<String>) -> Unit) {
        DebugLogger.d(TAG, "Fetching checkpoints")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Cannot fetch checkpoints: no connection")
            callback(emptyList())
            return
        }

        val url = "$baseUrl/object_info/CheckpointLoaderSimple"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.w(TAG, "Failed to fetch checkpoints: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // First get the CheckpointLoaderSimple object
                            val checkpointLoaderJson = json.optJSONObject("CheckpointLoaderSimple")
                            val inputJson = checkpointLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val ckptNameArray = requiredJson?.optJSONArray("ckpt_name")

                            // The first element is the array of checkpoint names
                            val optionsArray = ckptNameArray?.optJSONArray(0)

                            val checkpoints = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val checkpoint = optionsArray.getString(i)
                                    checkpoints.add(checkpoint)
                                }
                            }
                            DebugLogger.d(TAG, "Fetched ${checkpoints.size} checkpoints")
                            callback(checkpoints)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse checkpoints: ${e.message}")
                            callback(emptyList())
                        }
                    } else {
                        DebugLogger.w(TAG, "Checkpoint fetch failed: ${response.code}")
                        callback(emptyList())
                    }
                }
            }
        })
    }

    /**
     * Fetch available diffusion models (UNET) from the ComfyUI server
     * Retrieves the list of diffusion models that can be used for generation
     *
     * @param callback Called with the result:
     *                 - unets: List of UNET model filenames, or empty list on error
     */
    fun fetchUNETs(callback: (unets: List<String>) -> Unit) {
        DebugLogger.d(TAG, "Fetching UNETs")
        val baseUrl = getBaseUrl() ?: run {
            callback(emptyList())
            return
        }

        val url = "$baseUrl/object_info/UNETLoader"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.w(TAG, "Failed to fetch UNETs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // Get the UNETLoader object
                            val unetLoaderJson = json.optJSONObject("UNETLoader")
                            val inputJson = unetLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val unetNameArray = requiredJson?.optJSONArray("unet_name")

                            // The first element is the array of UNET model names
                            val optionsArray = unetNameArray?.optJSONArray(0)

                            val unets = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val unet = optionsArray.getString(i)
                                    unets.add(unet)
                                }
                            }
                            DebugLogger.d(TAG, "Fetched ${unets.size} UNETs")
                            callback(unets)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse UNETs: ${e.message}")
                            callback(emptyList())
                        }
                    } else {
                        callback(emptyList())
                    }
                }
            }
        })
    }

    /**
     * Fetch available VAE models from the ComfyUI server
     * Retrieves the list of VAE models that can be used for generation
     *
     * @param callback Called with the result:
     *                 - vaes: List of VAE model filenames, or empty list on error
     */
    fun fetchVAEs(callback: (vaes: List<String>) -> Unit) {
        DebugLogger.d(TAG, "Fetching VAEs")
        val baseUrl = getBaseUrl() ?: run {
            callback(emptyList())
            return
        }

        val url = "$baseUrl/object_info/VAELoader"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.w(TAG, "Failed to fetch VAEs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // Get the VAELoader object
                            val vaeLoaderJson = json.optJSONObject("VAELoader")
                            val inputJson = vaeLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val vaeNameArray = requiredJson?.optJSONArray("vae_name")

                            // The first element is the array of VAE model names
                            val optionsArray = vaeNameArray?.optJSONArray(0)

                            val vaes = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val vae = optionsArray.getString(i)
                                    vaes.add(vae)
                                }
                            }
                            DebugLogger.d(TAG, "Fetched ${vaes.size} VAEs")
                            callback(vaes)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse VAEs: ${e.message}")
                            callback(emptyList())
                        }
                    } else {
                        DebugLogger.w(TAG, "VAE fetch failed: ${response.code}")
                        callback(emptyList())
                    }
                }
            }
        })
    }

    /**
     * Fetch available CLIP models from the ComfyUI server
     * Retrieves the list of CLIP models that can be used for generation
     *
     * @param callback Called with the result:
     *                 - clips: List of CLIP model filenames, or empty list on error
     */
    fun fetchCLIPs(callback: (clips: List<String>) -> Unit) {
        DebugLogger.d(TAG, "Fetching CLIPs")
        val baseUrl = getBaseUrl() ?: run {
            callback(emptyList())
            return
        }

        val url = "$baseUrl/object_info/CLIPLoader"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.w(TAG, "Failed to fetch CLIPs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // Get the CLIPLoader object
                            val clipLoaderJson = json.optJSONObject("CLIPLoader")
                            val inputJson = clipLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val clipNameArray = requiredJson?.optJSONArray("clip_name")

                            // The first element is the array of CLIP model names
                            val optionsArray = clipNameArray?.optJSONArray(0)

                            val clips = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val clip = optionsArray.getString(i)
                                    clips.add(clip)
                                }
                            }
                            DebugLogger.d(TAG, "Fetched ${clips.size} CLIPs")
                            callback(clips)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse CLIPs: ${e.message}")
                            callback(emptyList())
                        }
                    } else {
                        DebugLogger.w(TAG, "CLIP fetch failed: ${response.code}")
                        callback(emptyList())
                    }
                }
            }
        })
    }

    /**
     * Fetch available LoRA models from the ComfyUI server
     * Retrieves the list of LoRA models that can be used for generation
     *
     * @param callback Called with the result:
     *                 - loras: List of LoRA model filenames, or empty list on error
     */
    fun fetchLoRAs(callback: (loras: List<String>) -> Unit) {
        DebugLogger.d(TAG, "Fetching LoRAs")
        val baseUrl = getBaseUrl() ?: run {
            callback(emptyList())
            return
        }

        val url = "$baseUrl/object_info/LoraLoaderModelOnly"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.w(TAG, "Failed to fetch LoRAs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            val json = JSONObject(responseBody)
                            // Get the LoraLoaderModelOnly object
                            val loraLoaderJson = json.optJSONObject("LoraLoaderModelOnly")
                            val inputJson = loraLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val loraNameArray = requiredJson?.optJSONArray("lora_name")

                            // The first element is the array of LoRA model names
                            val optionsArray = loraNameArray?.optJSONArray(0)

                            val loras = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val lora = optionsArray.getString(i)
                                    loras.add(lora)
                                }
                            }
                            DebugLogger.d(TAG, "Fetched ${loras.size} LoRAs")
                            callback(loras)
                        } catch (e: Exception) {
                            DebugLogger.w(TAG, "Failed to parse LoRAs: ${e.message}")
                            callback(emptyList())
                        }
                    } else {
                        DebugLogger.w(TAG, "LoRA fetch failed: ${response.code}")
                        callback(emptyList())
                    }
                }
            }
        })
    }

    /**
     * Submit a workflow (prompt) to the ComfyUI server for execution
     * This queues the workflow for generation
     *
     * @param workflowJson The workflow JSON (from WorkflowManager)
     * @param callback Called with the result:
     *                 - success: true if submitted successfully
     *                 - promptId: The prompt ID assigned by the server (for tracking)
     *                 - errorMessage: error message if failed
     *                 - failureType: type of failure (NONE for success, AUTHENTICATION for 401/403, NETWORK for other errors)
     */
    fun submitPrompt(
        workflowJson: String,
        front: Boolean = false,
        callback: (success: Boolean, promptId: String?, errorMessage: String?, failureType: ConnectionFailure) -> Unit
    ) {
        DebugLogger.i(TAG, "Submitting prompt${if (front) " (front of queue)" else ""}")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Submit failed: no connection")
            callback(false, null, context?.getString(R.string.error_no_connection) ?: "No connection to server", ConnectionFailure.NETWORK)
            return
        }

        val url = "$baseUrl/prompt"

        // Parse the workflow JSON to extract just the nodes section
        val workflowObject = JSONObject(workflowJson)
        val nodesObject = workflowObject.optJSONObject("nodes")

        // Create the prompt request
        // The client_id here must match the clientId used in the WebSocket connection
        val promptRequest = JSONObject().apply {
            put("prompt", nodesObject)
            put("client_id", clientId)
            if (front) {
                put("front", true)
            }
        }

        val requestBody = promptRequest.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.e(TAG, "Submit failed: ${e.message}")
                callback(false, null, "Failed to submit prompt: ${e.message}", ConnectionFailure.NETWORK)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(responseBody)
                            val promptId = json.optString("prompt_id")
                            DebugLogger.i(TAG, "Prompt submitted (promptId: ${Obfuscator.promptId(promptId)})")
                            callback(true, promptId, null, ConnectionFailure.NONE)
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "Failed to parse submit response: ${e.message}")
                            callback(false, null, "Failed to parse response: ${e.message}", ConnectionFailure.NETWORK)
                        }
                    } else {
                        // Handle authentication errors with clean messages (avoid raw HTML)
                        if (response.code == 401 || response.code == 403) {
                            DebugLogger.e(TAG, "Authentication error ${response.code}")
                            callback(false, null, context?.getString(R.string.auth_error_unauthorized)
                                ?: "Authentication failed", ConnectionFailure.AUTHENTICATION)
                        } else {
                            // Try to extract error details from response body
                            val errorDetail = try {
                                val errorJson = JSONObject(responseBody)
                                errorJson.optString("error", "") +
                                    (errorJson.optJSONObject("node_errors")?.toString() ?: "")
                            } catch (e: Exception) {
                                responseBody.take(500) // Truncate long responses
                            }
                            DebugLogger.e(TAG, "Server error ${response.code}")
                            callback(false, null, "Server error ${response.code}: $errorDetail", ConnectionFailure.NETWORK)
                        }
                    }
                }
            }
        })
    }

    /**
     * Fetch all execution history
     * Used to get all generation history for the gallery
     *
     * @param callback Called with the result:
     *                 - historyJson: The full history JSON object with all prompts, or null on error
     */
    fun fetchAllHistory(callback: (historyJson: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/history"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val body = response.body?.string() ?: "{}"
                            val historyJson = JSONObject(body)
                            callback(historyJson)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch execution history for a prompt ID
     * Used to get information about generated images
     *
     * @param promptId The prompt ID to fetch history for
     * @param callback Called with the result:
     *                 - historyJson: The history JSON object, or null on error
     */
    fun fetchHistory(
        promptId: String,
        callback: (historyJson: JSONObject?) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/history/$promptId"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val historyJson = JSONObject(response.body?.string() ?: "{}")
                            callback(historyJson)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch the current queue status from the ComfyUI server
     * Returns both pending and running queue entries
     *
     * @param callback Called with the queue JSON containing "queue_running" and "queue_pending" arrays,
     *                 or null on error
     */
    fun fetchQueue(callback: (queueJson: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/queue"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val queueJson = JSONObject(response.body?.string() ?: "{}")
                            callback(queueJson)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch a generated image from the ComfyUI server
     * Downloads and decodes the image into a Bitmap
     *
     * @param filename The filename of the generated image
     * @param subfolder The subfolder where the image is stored (usually empty or "")
     * @param type The image type (usually "output" or "temp")
     * @param callback Called with the result:
     *                 - bitmap: The decoded image, or null on error
     */
    fun fetchImage(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        callback: (bitmap: Bitmap?) -> Unit
    ) {
        DebugLogger.d(TAG, "Fetching image: ${Obfuscator.filename(filename)}")
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/view?filename=$filename&subfolder=$subfolder&type=$type"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                callback(bitmap)
                            } else {
                                callback(null)
                            }
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch raw bytes from the ComfyUI server without decoding.
     * Used for metadata extraction where we need the original file bytes.
     *
     * @param filename The filename of the file
     * @param subfolder The subfolder where the file is stored
     * @param type The file type (usually "output" or "temp")
     * @param callback Called with the result:
     *                 - bytes: The raw file bytes, or null on error
     */
    fun fetchRawBytes(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        callback: (bytes: ByteArray?) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/view?filename=$filename&subfolder=$subfolder&type=$type"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val bytes = response.body?.bytes()
                            callback(bytes)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Fetch a generated video from the ComfyUI server
     * Downloads the video as raw bytes
     *
     * @param filename The filename of the generated video
     * @param subfolder The subfolder where the video is stored (usually empty or "video")
     * @param type The video type (usually "output" or "temp")
     * @param callback Called with the result:
     *                 - bytes: The raw video bytes, or null on error
     */
    fun fetchVideo(
        filename: String,
        subfolder: String = "",
        type: String = "output",
        callback: (bytes: ByteArray?) -> Unit
    ) {
        DebugLogger.d(TAG, "Fetching video: ${Obfuscator.filename(filename)}")
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/view?filename=$filename&subfolder=$subfolder&type=$type"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val bytes = response.body?.bytes()
                            callback(bytes)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Interrupt/cancel the currently running execution
     * Posts to the /interrupt endpoint to stop the current generation
     *
     * @param callback Called with the result: success true/false
     */
    fun interruptExecution(callback: (success: Boolean) -> Unit) {
        DebugLogger.i(TAG, "Interrupting execution")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Cannot interrupt: no connection")
            callback(false)
            return
        }

        val url = "$baseUrl/interrupt"

        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.e(TAG, "Interrupt failed: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        DebugLogger.i(TAG, "Interrupt successful")
                    } else {
                        DebugLogger.w(TAG, "Interrupt failed: ${response.code}")
                    }
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Get system stats from the ComfyUI server
     * Returns server information like device name, OS, etc.
     *
     * @param callback Called with the result:
     *                 - statsJson: The system stats JSON object, or null on error
     */
    fun getSystemStats(callback: (statsJson: JSONObject?) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(null)
            return
        }

        val url = "$baseUrl/system_stats"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val statsJson = JSONObject(response.body?.string() ?: "{}")
                            callback(statsJson)
                        } catch (e: Exception) {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }

    /**
     * Clear all tasks from the queue
     * Removes all pending tasks from the execution queue
     *
     * @param callback Called with the result: success true/false
     */
    fun clearQueue(callback: (success: Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false)
            return
        }

        val url = "$baseUrl/queue"

        val requestBody = JSONObject().apply {
            put("clear", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Clear the history of generated images
     * Removes all history entries
     *
     * @param callback Called with the result: success true/false
     */
    fun clearHistory(callback: (success: Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false)
            return
        }

        val url = "$baseUrl/history"

        val requestBody = JSONObject().apply {
            put("clear", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Delete a single history item by prompt ID
     * Removes the specified entry from execution history
     *
     * @param promptId The prompt ID of the history item to delete
     * @param callback Called with the result: success true/false
     */
    fun deleteHistoryItem(promptId: String, callback: (success: Boolean) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false)
            return
        }

        val url = "$baseUrl/history"

        val requestBody = JSONObject().apply {
            put("delete", org.json.JSONArray().put(promptId))
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(response.isSuccessful)
                }
            }
        })
    }

    /**
     * Upload an image to the ComfyUI server input folder
     * Used for Image-to-image to upload the source image with mask
     *
     * @param imageData The PNG image data as byte array
     * @param filename The desired filename for the uploaded image
     * @param subfolder The subfolder to upload to (default empty = root input folder)
     * @param overwrite Whether to overwrite existing file (default true)
     * @param callback Called with the result:
     *                 - success: true if uploaded successfully
     *                 - filename: The actual filename saved on the server
     *                 - errorMessage: error message if failed
     *                 - failureType: type of failure (NONE for success, AUTHENTICATION for 401/403, NETWORK for other errors)
     */
    fun uploadImage(
        imageData: ByteArray,
        filename: String,
        subfolder: String = "",
        overwrite: Boolean = true,
        callback: (success: Boolean, filename: String?, errorMessage: String?, failureType: ConnectionFailure) -> Unit
    ) {
        DebugLogger.i(TAG, "Uploading image: ${Obfuscator.filename(filename)} (${imageData.size} bytes)")
        val baseUrl = getBaseUrl() ?: run {
            DebugLogger.w(TAG, "Upload failed: no connection")
            callback(false, null, context?.getString(R.string.error_no_connection) ?: "No connection to server", ConnectionFailure.NETWORK)
            return
        }

        val url = "$baseUrl/upload/image"

        // Build multipart form data request
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                filename,
                imageData.toRequestBody("image/png".toMediaType())
            )
            .addFormDataPart("subfolder", subfolder)
            .addFormDataPart("overwrite", overwrite.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                DebugLogger.e(TAG, "Upload failed: ${e.message}")
                callback(false, null, "Upload failed: ${e.message}", ConnectionFailure.NETWORK)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val savedFilename = json.optString("name", filename)
                            DebugLogger.i(TAG, "Upload successful")
                            callback(true, savedFilename, null, ConnectionFailure.NONE)
                        } catch (e: Exception) {
                            DebugLogger.e(TAG, "Failed to parse upload response: ${e.message}")
                            callback(false, null, "Failed to parse response: ${e.message}", ConnectionFailure.NETWORK)
                        }
                    } else {
                        // Handle authentication errors with clean messages
                        if (response.code == 401 || response.code == 403) {
                            DebugLogger.e(TAG, "Upload auth error: ${response.code}")
                            callback(false, null, context?.getString(R.string.auth_error_unauthorized)
                                ?: "Authentication failed", ConnectionFailure.AUTHENTICATION)
                        } else {
                            DebugLogger.e(TAG, "Upload error: ${response.code}")
                            callback(false, null, "Server error: ${response.code}", ConnectionFailure.NETWORK)
                        }
                    }
                }
            }
        })
    }

    /**
     * Clean up resources
     * Should be called when done with the client
     */
    fun shutdown() {
        closeWebSocket()
        // Note: We don't shutdown the httpClient here as it might be reused
        // In a real app, you might want to manage the client lifecycle differently
    }
}

