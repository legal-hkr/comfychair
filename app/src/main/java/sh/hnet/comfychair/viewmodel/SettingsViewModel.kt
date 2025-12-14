package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager

/**
 * UI state for server settings screen
 */
data class ServerSettingsUiState(
    val hostname: String = "",
    val port: Int = 8188,
    val systemStats: SystemStats? = null,
    val isLoadingStats: Boolean = false,
    val isClearingQueue: Boolean = false,
    val isClearingHistory: Boolean = false
)

/**
 * Parsed system stats from ComfyUI server
 */
data class SystemStats(
    val os: String,
    val comfyuiVersion: String,
    val pythonVersion: String,
    val pytorchVersion: String,
    val ramTotalGB: Double,
    val ramFreeGB: Double,
    val gpus: List<GpuInfo>
)

data class GpuInfo(
    val name: String,
    val vramTotalGB: Double,
    val vramFreeGB: Double
)

/**
 * Events emitted by settings operations
 */
sealed class SettingsEvent {
    data class ShowToast(val messageResId: Int) : SettingsEvent()
    object RefreshNeeded : SettingsEvent()
}

/**
 * ViewModel for Settings screens
 */
class SettingsViewModel : ViewModel() {

    private var comfyUIClient: ComfyUIClient? = null

    private val _serverSettingsState = MutableStateFlow(ServerSettingsUiState())
    val serverSettingsState: StateFlow<ServerSettingsUiState> = _serverSettingsState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    fun initialize(context: Context, hostname: String, port: Int) {
        val client = ComfyUIClient(context.applicationContext, hostname, port)
        comfyUIClient = client
        _serverSettingsState.value = _serverSettingsState.value.copy(
            hostname = hostname,
            port = port
        )
        // Test connection to determine working protocol (http or https)
        client.testConnection { success, _, _ ->
            // Connection test complete, protocol is now determined
        }
    }

    fun loadSystemStats() {
        val client = comfyUIClient ?: return

        _serverSettingsState.value = _serverSettingsState.value.copy(isLoadingStats = true)

        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.getSystemStats { statsJson ->
                        continuation.resumeWith(Result.success(statsJson))
                    }
                }
            }

            if (stats != null) {
                val parsedStats = parseSystemStats(stats)
                _serverSettingsState.value = _serverSettingsState.value.copy(
                    systemStats = parsedStats,
                    isLoadingStats = false
                )
            } else {
                _serverSettingsState.value = _serverSettingsState.value.copy(
                    isLoadingStats = false
                )
            }
        }
    }

    private fun parseSystemStats(statsJson: JSONObject): SystemStats {
        val system = statsJson.optJSONObject("system")
        val devices = statsJson.optJSONArray("devices")

        val os = system?.optString("os", "Unknown") ?: "Unknown"
        val comfyuiVersion = system?.optString("comfyui_version", "Unknown") ?: "Unknown"
        val pythonVersion = system?.optString("python_version", "Unknown") ?: "Unknown"
        val pytorchVersion = system?.optString("pytorch_version", "Unknown") ?: "Unknown"

        val ramTotal = system?.optLong("ram_total", 0) ?: 0
        val ramFree = system?.optLong("ram_free", 0) ?: 0
        val ramTotalGB = ramTotal / (1024.0 * 1024.0 * 1024.0)
        val ramFreeGB = ramFree / (1024.0 * 1024.0 * 1024.0)

        val gpus = mutableListOf<GpuInfo>()
        if (devices != null) {
            for (i in 0 until devices.length()) {
                val device = devices.optJSONObject(i)
                device?.let { dev ->
                    val name = dev.optString("name", "Unknown")
                    val vramTotal = dev.optLong("vram_total", 0)
                    val vramFree = dev.optLong("vram_free", 0)

                    // Extract GPU name from full name string
                    // Format: "cuda:0 NVIDIA GeForce RTX 4080 SUPER : cudaMallocAsync"
                    val gpuName = name.split(":").getOrNull(1)?.trim() ?: name

                    gpus.add(GpuInfo(
                        name = gpuName,
                        vramTotalGB = vramTotal / (1024.0 * 1024.0 * 1024.0),
                        vramFreeGB = vramFree / (1024.0 * 1024.0 * 1024.0)
                    ))
                }
            }
        }

        return SystemStats(
            os = os,
            comfyuiVersion = comfyuiVersion,
            pythonVersion = pythonVersion,
            pytorchVersion = pytorchVersion,
            ramTotalGB = ramTotalGB,
            ramFreeGB = ramFreeGB,
            gpus = gpus
        )
    }

    fun clearQueue() {
        val client = comfyUIClient ?: return

        _serverSettingsState.value = _serverSettingsState.value.copy(isClearingQueue = true)

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.clearQueue { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }

            _serverSettingsState.value = _serverSettingsState.value.copy(isClearingQueue = false)

            val messageResId = if (success) {
                sh.hnet.comfychair.R.string.queue_cleared_success
            } else {
                sh.hnet.comfychair.R.string.queue_cleared_failed
            }
            _events.emit(SettingsEvent.ShowToast(messageResId))
        }
    }

    fun clearHistory() {
        val client = comfyUIClient ?: return

        _serverSettingsState.value = _serverSettingsState.value.copy(isClearingHistory = true)

        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                kotlin.coroutines.suspendCoroutine { continuation ->
                    client.clearHistory { success ->
                        continuation.resumeWith(Result.success(success))
                    }
                }
            }

            _serverSettingsState.value = _serverSettingsState.value.copy(isClearingHistory = false)

            val messageResId = if (success) {
                sh.hnet.comfychair.R.string.history_cleared_success
            } else {
                sh.hnet.comfychair.R.string.history_cleared_failed
            }
            _events.emit(SettingsEvent.ShowToast(messageResId))
        }
    }

    fun clearCache(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cachedFiles = listOf(
                    "last_generated_image.png",
                    "last_generated_video.mp4",
                    "inpainting_last_preview.png",
                    "inpainting_last_source.png"
                )

                cachedFiles.forEach { filename ->
                    try {
                        context.deleteFile(filename)
                    } catch (e: Exception) {
                        // Failed to delete file
                    }
                }

                // Also clear any temp files in cache directory
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("gallery_video_") || file.name.endsWith(".png") || file.name.endsWith(".mp4")) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            // Failed to delete cache file
                        }
                    }
                }

                // Clear user-uploaded workflows
                val workflowManager = WorkflowManager(context)
                workflowManager.clearAllUserWorkflows()
            }

            _events.emit(SettingsEvent.ShowToast(sh.hnet.comfychair.R.string.cache_cleared_success))
            _events.emit(SettingsEvent.RefreshNeeded)
        }
    }

    fun restoreDefaults(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val prefsToDelete = listOf(
                    "TextToImageFragmentPrefs",
                    "TextToVideoFragmentPrefs",
                    "InpaintingFragmentPrefs"
                )

                prefsToDelete.forEach { prefsName ->
                    try {
                        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                        prefs.edit().clear().commit()
                    } catch (e: Exception) {
                        // Failed to clear preferences
                    }
                }
            }

            _events.emit(SettingsEvent.ShowToast(sh.hnet.comfychair.R.string.defaults_restored_success))
            _events.emit(SettingsEvent.RefreshNeeded)
        }
    }

    override fun onCleared() {
        super.onCleared()
        comfyUIClient?.shutdown()
    }
}
