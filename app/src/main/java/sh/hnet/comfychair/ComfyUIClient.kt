package sh.hnet.comfychair

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ComfyUIClient - Handles communication with ComfyUI server
 *
 * This class is responsible for:
 * 1. Testing HTTP/HTTPS connection to the server
 * 2. Auto-detecting whether to use HTTP or HTTPS
 * 3. Opening WebSocket connection for real-time updates
 *
 * @param hostname The server hostname or IP address (e.g., "192.168.1.100")
 * @param port The server port number (default: 8188)
 */
class ComfyUIClient(
    private val hostname: String,
    private val port: Int
) {
    // OkHttpClient - handles all HTTP/HTTPS and WebSocket connections
    // We configure it with reasonable timeouts and self-signed certificate support
    private val httpClient = SelfSignedCertHelper.configureToAcceptSelfSigned(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)  // Max time to establish connection
            .readTimeout(10, TimeUnit.SECONDS)     // Max time to read response
            .writeTimeout(10, TimeUnit.SECONDS)    // Max time to write request
    ).build()

    // Store which protocol worked (http or https)
    private var workingProtocol: String? = null

    // Store the active WebSocket connection
    private var webSocket: WebSocket? = null

    /**
     * Test connection to the ComfyUI server
     * Tries HTTPS first, then falls back to HTTP if HTTPS fails
     *
     * @param callback Called with the result:
     *                 - success: true if connected, false if failed
     *                 - errorMessage: error message if failed, null if success
     *                 - certIssue: the type of certificate issue detected (NONE, SELF_SIGNED, UNKNOWN_CA)
     */
    fun testConnection(callback: (success: Boolean, errorMessage: String?, certIssue: CertificateIssue) -> Unit) {
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
        callback: (success: Boolean, errorMessage: String?, certIssue: CertificateIssue) -> Unit
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
                    callback(false, "Connection failed: ${e.message}", CertificateIssue.NONE)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        // Connection successful!
                        // Save which protocol worked for future use
                        workingProtocol = protocol
                        // Check what type of certificate issue was detected
                        val certIssue = SelfSignedCertHelper.certificateIssue
                        callback(true, null, certIssue)
                    } else {
                        // Got response but status code indicates error (e.g., 404, 500)
                        if (protocol == "https") {
                            // Try HTTP as fallback
                            tryConnection("http", callback)
                        } else {
                            // Both protocols failed
                            callback(false, "Server returned error: ${response.code}", CertificateIssue.NONE)
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
     * @param listener WebSocket event listener
     * @return true if connection attempt started, false if no working protocol found
     */
    fun openWebSocket(listener: WebSocketListener): Boolean {
        // Make sure we have a working protocol from testConnection
        val protocol = workingProtocol ?: run {
            // No protocol set - need to test connection first
            return false
        }

        // Build WebSocket URL
        // ws:// for http, wss:// for https
        val wsProtocol = if (protocol == "https") "wss" else "ws"
        val wsUrl = "$wsProtocol://$hostname:$port/ws"

        // Create WebSocket request
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        // Open WebSocket connection
        webSocket = httpClient.newWebSocket(request, listener)

        return true
    }

    /**
     * Close the WebSocket connection
     * Should be called when done with the connection to free resources
     *
     * @param code Close code (1000 = normal closure)
     * @param reason Optional reason for closing
     */
    fun closeWebSocket(code: Int = 1000, reason: String? = null) {
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
     * Fetch available checkpoints from the ComfyUI server
     * Retrieves the list of checkpoint models that can be used for generation
     *
     * @param callback Called with the result:
     *                 - checkpoints: List of checkpoint filenames, or empty list on error
     */
    fun fetchCheckpoints(callback: (checkpoints: List<String>) -> Unit) {
        val baseUrl = getBaseUrl() ?: run {
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
                println("Failed to fetch checkpoints: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            println("Checkpoint response: $responseBody")

                            val json = JSONObject(responseBody)
                            // First get the CheckpointLoaderSimple object
                            val checkpointLoaderJson = json.optJSONObject("CheckpointLoaderSimple")
                            val inputJson = checkpointLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val ckptNameArray = requiredJson?.optJSONArray("ckpt_name")

                            println("Checkpoint parsing: CheckpointLoaderSimple=${checkpointLoaderJson != null}, input=${inputJson != null}, required=${requiredJson != null}, ckpt_name=${ckptNameArray != null}")

                            // The first element is the array of checkpoint names
                            val optionsArray = ckptNameArray?.optJSONArray(0)

                            println("Options array: ${optionsArray != null}, length=${optionsArray?.length()}")

                            val checkpoints = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val checkpoint = optionsArray.getString(i)
                                    println("Found checkpoint: $checkpoint")
                                    checkpoints.add(checkpoint)
                                }
                            }
                            println("Total checkpoints found: ${checkpoints.size}")
                            callback(checkpoints)
                        } catch (e: Exception) {
                            println("Failed to parse checkpoints: ${e.message}")
                            e.printStackTrace()
                            callback(emptyList())
                        }
                    } else {
                        println("Server returned error when fetching checkpoints: ${response.code}")
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
     *                 - diffusers: List of diffuser model filenames, or empty list on error
     */
    fun fetchDiffusers(callback: (diffusers: List<String>) -> Unit) {
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
                println("Failed to fetch diffusers: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            println("Diffusers response: $responseBody")

                            val json = JSONObject(responseBody)
                            // Get the UNETLoader object
                            val unetLoaderJson = json.optJSONObject("UNETLoader")
                            val inputJson = unetLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val unetNameArray = requiredJson?.optJSONArray("unet_name")

                            println("Diffusers parsing: UNETLoader=${unetLoaderJson != null}, input=${inputJson != null}, required=${requiredJson != null}, unet_name=${unetNameArray != null}")

                            // The first element is the array of diffuser model names
                            val optionsArray = unetNameArray?.optJSONArray(0)

                            println("Options array: ${optionsArray != null}, length=${optionsArray?.length()}")

                            val diffusers = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val diffuser = optionsArray.getString(i)
                                    println("Found diffuser: $diffuser")
                                    diffusers.add(diffuser)
                                }
                            }
                            println("Total diffusers found: ${diffusers.size}")
                            callback(diffusers)
                        } catch (e: Exception) {
                            println("Failed to parse diffusers: ${e.message}")
                            e.printStackTrace()
                            callback(emptyList())
                        }
                    } else {
                        println("Server returned error when fetching diffusers: ${response.code}")
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
     */
    fun submitPrompt(
        workflowJson: String,
        callback: (success: Boolean, promptId: String?, errorMessage: String?) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false, null, "No connection to server")
            return
        }

        val url = "$baseUrl/prompt"

        // Parse the workflow JSON to extract just the nodes section
        val workflowObject = JSONObject(workflowJson)
        val nodesObject = workflowObject.optJSONObject("nodes")

        // Create the prompt request
        val promptRequest = JSONObject().apply {
            put("prompt", nodesObject)
            put("client_id", "comfychair_android")
        }

        val requestBody = promptRequest.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                callback(false, null, "Failed to submit prompt: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val promptId = json.optString("prompt_id")
                            callback(true, promptId, null)
                        } catch (e: Exception) {
                            callback(false, null, "Failed to parse response: ${e.message}")
                        }
                    } else {
                        callback(false, null, "Server returned error: ${response.code}")
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
                println("Failed to fetch all history: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val historyJson = JSONObject(response.body?.string() ?: "{}")
                            callback(historyJson)
                        } catch (e: Exception) {
                            println("Failed to parse all history: ${e.message}")
                            callback(null)
                        }
                    } else {
                        println("Server returned error when fetching all history: ${response.code}")
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
                println("Failed to fetch history: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val historyJson = JSONObject(response.body?.string() ?: "{}")
                            callback(historyJson)
                        } catch (e: Exception) {
                            println("Failed to parse history: ${e.message}")
                            callback(null)
                        }
                    } else {
                        println("Server returned error when fetching history: ${response.code}")
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
                println("Failed to fetch image: ${e.message}")
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
                            println("Failed to decode image: ${e.message}")
                            callback(null)
                        }
                    } else {
                        println("Server returned error when fetching image: ${response.code}")
                        callback(null)
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

