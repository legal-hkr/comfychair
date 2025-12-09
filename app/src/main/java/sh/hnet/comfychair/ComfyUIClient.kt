package sh.hnet.comfychair

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

    // Client ID for tracking WebSocket messages
    // This must match the client_id used when submitting prompts
    // Generate a unique ID for each client instance to support multiple concurrent users
    private val clientId = "comfychair_android_${UUID.randomUUID()}".also {
        println("ComfyUIClient: Generated unique client ID: $it")
    }

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
     * IMPORTANT: The client_id query parameter is required to receive
     * progress and preview events for this client's jobs
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

        // Build WebSocket URL with client_id parameter
        // This is crucial - ComfyUI only sends progress/preview events to the matching client_id
        // ws:// for http, wss:// for https
        val wsProtocol = if (protocol == "https") "wss" else "ws"
        val wsUrl = "$wsProtocol://$hostname:$port/ws?clientId=$clientId"

        println("Opening WebSocket with URL: $wsUrl")

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
     *                 - unets: List of UNET model filenames, or empty list on error
     */
    fun fetchUNETs(callback: (unets: List<String>) -> Unit) {
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
                println("Failed to fetch UNETs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            println("UNET response: $responseBody")

                            val json = JSONObject(responseBody)
                            // Get the UNETLoader object
                            val unetLoaderJson = json.optJSONObject("UNETLoader")
                            val inputJson = unetLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val unetNameArray = requiredJson?.optJSONArray("unet_name")

                            println("UNET parsing: UNETLoader=${unetLoaderJson != null}, input=${inputJson != null}, required=${requiredJson != null}, unet_name=${unetNameArray != null}")

                            // The first element is the array of UNET model names
                            val optionsArray = unetNameArray?.optJSONArray(0)

                            println("Options array: ${optionsArray != null}, length=${optionsArray?.length()}")

                            val unets = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val unet = optionsArray.getString(i)
                                    println("Found UNET: $unet")
                                    unets.add(unet)
                                }
                            }
                            println("Total UNETs found: ${unets.size}")
                            callback(unets)
                        } catch (e: Exception) {
                            println("Failed to parse UNETs: ${e.message}")
                            e.printStackTrace()
                            callback(emptyList())
                        }
                    } else {
                        println("Server returned error when fetching UNETs: ${response.code}")
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
                println("Failed to fetch VAEs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            println("VAE response: $responseBody")

                            val json = JSONObject(responseBody)
                            // Get the VAELoader object
                            val vaeLoaderJson = json.optJSONObject("VAELoader")
                            val inputJson = vaeLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val vaeNameArray = requiredJson?.optJSONArray("vae_name")

                            println("VAE parsing: VAELoader=${vaeLoaderJson != null}, input=${inputJson != null}, required=${requiredJson != null}, vae_name=${vaeNameArray != null}")

                            // The first element is the array of VAE model names
                            val optionsArray = vaeNameArray?.optJSONArray(0)

                            println("Options array: ${optionsArray != null}, length=${optionsArray?.length()}")

                            val vaes = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val vae = optionsArray.getString(i)
                                    println("Found VAE: $vae")
                                    vaes.add(vae)
                                }
                            }
                            println("Total VAEs found: ${vaes.size}")
                            callback(vaes)
                        } catch (e: Exception) {
                            println("Failed to parse VAEs: ${e.message}")
                            e.printStackTrace()
                            callback(emptyList())
                        }
                    } else {
                        println("Server returned error when fetching VAEs: ${response.code}")
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
                println("Failed to fetch CLIPs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            println("CLIP response: $responseBody")

                            val json = JSONObject(responseBody)
                            // Get the CLIPLoader object
                            val clipLoaderJson = json.optJSONObject("CLIPLoader")
                            val inputJson = clipLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val clipNameArray = requiredJson?.optJSONArray("clip_name")

                            println("CLIP parsing: CLIPLoader=${clipLoaderJson != null}, input=${inputJson != null}, required=${requiredJson != null}, clip_name=${clipNameArray != null}")

                            // The first element is the array of CLIP model names
                            val optionsArray = clipNameArray?.optJSONArray(0)

                            println("Options array: ${optionsArray != null}, length=${optionsArray?.length()}")

                            val clips = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val clip = optionsArray.getString(i)
                                    println("Found CLIP: $clip")
                                    clips.add(clip)
                                }
                            }
                            println("Total CLIPs found: ${clips.size}")
                            callback(clips)
                        } catch (e: Exception) {
                            println("Failed to parse CLIPs: ${e.message}")
                            e.printStackTrace()
                            callback(emptyList())
                        }
                    } else {
                        println("Server returned error when fetching CLIPs: ${response.code}")
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
                println("Failed to fetch LoRAs: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val responseBody = response.body?.string() ?: "{}"
                            println("LoRA response: $responseBody")

                            val json = JSONObject(responseBody)
                            // Get the LoraLoaderModelOnly object
                            val loraLoaderJson = json.optJSONObject("LoraLoaderModelOnly")
                            val inputJson = loraLoaderJson?.optJSONObject("input")
                            val requiredJson = inputJson?.optJSONObject("required")
                            val loraNameArray = requiredJson?.optJSONArray("lora_name")

                            println("LoRA parsing: LoraLoaderModelOnly=${loraLoaderJson != null}, input=${inputJson != null}, required=${requiredJson != null}, lora_name=${loraNameArray != null}")

                            // The first element is the array of LoRA model names
                            val optionsArray = loraNameArray?.optJSONArray(0)

                            println("Options array: ${optionsArray != null}, length=${optionsArray?.length()}")

                            val loras = mutableListOf<String>()
                            if (optionsArray != null) {
                                for (i in 0 until optionsArray.length()) {
                                    val lora = optionsArray.getString(i)
                                    println("Found LoRA: $lora")
                                    loras.add(lora)
                                }
                            }
                            println("Total LoRAs found: ${loras.size}")
                            callback(loras)
                        } catch (e: Exception) {
                            println("Failed to parse LoRAs: ${e.message}")
                            e.printStackTrace()
                            callback(emptyList())
                        }
                    } else {
                        println("Server returned error when fetching LoRAs: ${response.code}")
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
        // The client_id here must match the clientId used in the WebSocket connection
        val promptRequest = JSONObject().apply {
            put("prompt", nodesObject)
            put("client_id", clientId)
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
                println("Failed to fetch video: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val bytes = response.body?.bytes()
                            callback(bytes)
                        } catch (e: Exception) {
                            println("Failed to read video bytes: ${e.message}")
                            callback(null)
                        }
                    } else {
                        println("Server returned error when fetching video: ${response.code}")
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
        val baseUrl = getBaseUrl() ?: run {
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
                println("Failed to interrupt execution: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        println("Execution interrupted successfully")
                        callback(true)
                    } else {
                        println("Server returned error when interrupting: ${response.code}")
                        callback(false)
                    }
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
                println("Failed to fetch system stats: ${e.message}")
                callback(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val statsJson = JSONObject(response.body?.string() ?: "{}")
                            callback(statsJson)
                        } catch (e: Exception) {
                            println("Failed to parse system stats: ${e.message}")
                            callback(null)
                        }
                    } else {
                        println("Server returned error when fetching system stats: ${response.code}")
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
                println("Failed to clear queue: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        println("Queue cleared successfully")
                        callback(true)
                    } else {
                        println("Server returned error when clearing queue: ${response.code}")
                        callback(false)
                    }
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
                println("Failed to clear history: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        println("History cleared successfully")
                        callback(true)
                    } else {
                        println("Server returned error when clearing history: ${response.code}")
                        callback(false)
                    }
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
                println("Failed to delete history item: ${e.message}")
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        println("History item deleted successfully: $promptId")
                        callback(true)
                    } else {
                        println("Server returned error when deleting history item: ${response.code}")
                        callback(false)
                    }
                }
            }
        })
    }

    /**
     * Upload an image to the ComfyUI server input folder
     * Used for inpainting to upload the source image with mask
     *
     * @param imageData The PNG image data as byte array
     * @param filename The desired filename for the uploaded image
     * @param subfolder The subfolder to upload to (default empty = root input folder)
     * @param overwrite Whether to overwrite existing file (default true)
     * @param callback Called with the result:
     *                 - success: true if uploaded successfully
     *                 - filename: The actual filename saved on the server
     *                 - errorMessage: error message if failed
     */
    fun uploadImage(
        imageData: ByteArray,
        filename: String,
        subfolder: String = "",
        overwrite: Boolean = true,
        callback: (success: Boolean, filename: String?, errorMessage: String?) -> Unit
    ) {
        val baseUrl = getBaseUrl() ?: run {
            callback(false, null, "No connection to server")
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
                println("Failed to upload image: ${e.message}")
                callback(false, null, "Upload failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val savedFilename = json.optString("name", filename)
                            println("Image uploaded successfully: $savedFilename")
                            callback(true, savedFilename, null)
                        } catch (e: Exception) {
                            println("Failed to parse upload response: ${e.message}")
                            callback(false, null, "Failed to parse response: ${e.message}")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        println("Server returned error when uploading: ${response.code} - $errorBody")
                        callback(false, null, "Server error: ${response.code}")
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

