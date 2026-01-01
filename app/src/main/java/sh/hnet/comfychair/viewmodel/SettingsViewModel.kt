package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.BackupManager
import sh.hnet.comfychair.storage.RestoreResult
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.DebugLogger

/**
 * UI state for server settings screen
 */
data class ServerSettingsUiState(
    val hostname: String = "",
    val port: Int = 8188,
    val systemStats: SystemStats? = null,
    val isLoadingStats: Boolean = false,
    val isClearingHistory: Boolean = false,
    val isRefreshingModels: Boolean = false
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
    data object RefreshNeeded : SettingsEvent()
    data class ShowRestoreDialog(val uri: Uri) : SettingsEvent()
    data object NavigateToLogin : SettingsEvent()
}

/**
 * ViewModel for Settings screens
 */
class SettingsViewModel : ViewModel() {

    // Constants
    companion object {
        private const val TAG = "SettingsViewModel"
    }

    // State
    // Accessor for shared client from ConnectionManager
    private val comfyUIClient: ComfyUIClient?
        get() = ConnectionManager.clientOrNull

    private var resourceRefreshJob: Job? = null

    private val _serverSettingsState = MutableStateFlow(ServerSettingsUiState())
    val serverSettingsState: StateFlow<ServerSettingsUiState> = _serverSettingsState.asStateFlow()

    private val _isLivePreviewEnabled = MutableStateFlow(true)
    val isLivePreviewEnabled: StateFlow<Boolean> = _isLivePreviewEnabled.asStateFlow()

    private val _isMemoryFirstCache = MutableStateFlow(true)
    val isMemoryFirstCache: StateFlow<Boolean> = _isMemoryFirstCache.asStateFlow()

    private val _isMediaCacheDisabled = MutableStateFlow(false)
    val isMediaCacheDisabled: StateFlow<Boolean> = _isMediaCacheDisabled.asStateFlow()

    private val _isDebugLoggingEnabled = MutableStateFlow(false)
    val isDebugLoggingEnabled: StateFlow<Boolean> = _isDebugLoggingEnabled.asStateFlow()

    private val _isAutoConnectEnabled = MutableStateFlow(true)
    val isAutoConnectEnabled: StateFlow<Boolean> = _isAutoConnectEnabled.asStateFlow()

    private val _isShowBuiltInWorkflows = MutableStateFlow(true)
    val isShowBuiltInWorkflows: StateFlow<Boolean> = _isShowBuiltInWorkflows.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    fun initialize(context: Context) {
        _serverSettingsState.value = _serverSettingsState.value.copy(
            hostname = ConnectionManager.hostname,
            port = ConnectionManager.port
        )
        // Load settings
        _isLivePreviewEnabled.value = AppSettings.isLivePreviewEnabled(context)
        _isMemoryFirstCache.value = AppSettings.isMemoryFirstCache(context)
        _isMediaCacheDisabled.value = AppSettings.isMediaCacheDisabled(context)
        _isDebugLoggingEnabled.value = AppSettings.isDebugLoggingEnabled(context)
        _isAutoConnectEnabled.value = AppSettings.isAutoConnectEnabled(context)
        _isShowBuiltInWorkflows.value = AppSettings.isShowBuiltInWorkflows(context)

        // Initialize debug logger with saved state
        DebugLogger.setEnabled(_isDebugLoggingEnabled.value)
    }

    fun loadSystemStats() {
        loadSystemStats(showLoading = true)
    }

    private fun loadSystemStats(showLoading: Boolean) {
        val client = comfyUIClient ?: return

        if (showLoading) {
            _serverSettingsState.value = _serverSettingsState.value.copy(isLoadingStats = true)
        }

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

    /**
     * Start auto-refreshing resource stats every 2 seconds
     */
    fun startResourceAutoRefresh() {
        stopResourceAutoRefresh()
        resourceRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                loadSystemStats(showLoading = false)
            }
        }
    }

    /**
     * Stop auto-refreshing resource stats
     */
    fun stopResourceAutoRefresh() {
        resourceRefreshJob?.cancel()
        resourceRefreshJob = null
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
                    val gpuNameRaw = name.split(":").getOrNull(1)?.trim() ?: name
                    // Remove leading device index (e.g., "0 " or "1 ")
                    val gpuName = gpuNameRaw.replaceFirst(Regex("^\\d+\\s+"), "")

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

    /**
     * Refresh server data (node types and model lists) from the ComfyUI server.
     * This fetches the latest /object_info and updates the centralized cache.
     */
    fun refreshServerData() {
        _serverSettingsState.value = _serverSettingsState.value.copy(isRefreshingModels = true)

        viewModelScope.launch {
            // Trigger refresh in ConnectionManager
            ConnectionManager.refreshServerData()

            // Wait for cache to finish loading
            ConnectionManager.modelCache
                .filter { !it.isLoading }
                .first()

            val cache = ConnectionManager.modelCache.value
            val success = cache.lastError == null && cache.isLoaded

            _serverSettingsState.value = _serverSettingsState.value.copy(isRefreshingModels = false)

            val messageResId = if (success) {
                R.string.models_refreshed_success
            } else {
                R.string.models_refreshed_failed
            }
            _events.emit(SettingsEvent.ShowToast(messageResId))
        }
    }

    fun clearCache(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Clear preview files (handles serverId-prefixed filenames)
                MediaStateHolder.clearDiskCache(context)

                // Clear any temp files in cache directory
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("gallery_video_") ||
                        file.name.startsWith("playback_") ||
                        file.name.endsWith(".png") ||
                        file.name.endsWith(".mp4")) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            // Failed to delete cache file
                        }
                    }
                }

                // Clear user-uploaded workflows
                WorkflowManager.ensureInitialized(context)
                WorkflowManager.clearAllUserWorkflows()
            }

            // Clear in-memory media caches
            MediaCache.clearAll()
            MediaStateHolder.clearAll()

            _events.emit(SettingsEvent.ShowToast(sh.hnet.comfychair.R.string.cache_cleared_success))
            _events.emit(SettingsEvent.RefreshNeeded)
        }
    }

    fun restoreDefaults(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Clear per-workflow saved values
                val workflowValuesStorage = WorkflowValuesStorage(context)
                workflowValuesStorage.clearAll()

                // Clear global preferences (mode, workflow selections, prompts)
                val prefsToDelete = listOf(
                    "TextToImageFragmentPrefs",
                    "ImageToImageFragmentPrefs",
                    "TextToVideoFragmentPrefs",
                    "ImageToVideoFragmentPrefs"
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

    /**
     * Reset prompts to seasonal defaults.
     * Clears positive prompts from SharedPreferences (so ViewModels will load seasonal defaults)
     * and clears negative prompts from per-workflow saved values.
     */
    fun resetPrompts(context: Context) {
        val serverId = ConnectionManager.currentServerId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Clear positive prompts from per-server preferences
                val serverPrefix = "${serverId}_"
                val prefNames = listOf(
                    "TextToImageFragmentPrefs",
                    "ImageToImageFragmentPrefs",
                    "TextToVideoFragmentPrefs",
                    "ImageToVideoFragmentPrefs"
                )

                for (prefName in prefNames) {
                    val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    // Remove keys that start with the server prefix and end with positivePrompt
                    prefs.all.keys
                        .filter { it.startsWith(serverPrefix) && it.endsWith("positivePrompt") }
                        .forEach { key ->
                            prefs.edit().remove(key).apply()
                        }
                }

                // Clear negative prompts from per-workflow saved values
                val workflowValuesStorage = WorkflowValuesStorage(context)
                workflowValuesStorage.clearNegativePromptsForServer(serverId)
            }

            _events.emit(SettingsEvent.ShowToast(R.string.reset_prompts_success))
            _events.emit(SettingsEvent.RefreshNeeded)
        }
    }

    /**
     * Set whether live preview should be enabled.
     */
    fun setLivePreviewEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            AppSettings.setLivePreviewEnabled(context, enabled)
            _isLivePreviewEnabled.value = enabled
        }
    }

    /**
     * Set whether memory-first caching should be enabled.
     * When disabled, switches to disk-first mode and forces media cache to be enabled.
     */
    fun setMemoryFirstCache(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            // Persist current state before changing mode
            // This is critical because activity restart may skip onStop()
            if (MediaStateHolder.isMemoryFirstMode() && !enabled) {
                // Switching FROM memory-first: persist before clearing
                MediaStateHolder.persistToDisk(context)
            }

            AppSettings.setMemoryFirstCache(context, enabled)
            _isMemoryFirstCache.value = enabled

            if (!enabled) {
                // When switching to disk-first, disable media cache must be OFF
                AppSettings.setMediaCacheDisabled(context, false)
                _isMediaCacheDisabled.value = false
            }

            _events.emit(SettingsEvent.RefreshNeeded)
        }
    }

    /**
     * Set whether media cache should be disabled.
     * When enabled, clears the cache first and prevents future disk persistence.
     * Only applicable in memory-first mode.
     */
    fun setMediaCacheDisabled(context: Context, disabled: Boolean) {
        viewModelScope.launch {
            if (disabled) {
                // Clear cache first when disabling media cache
                clearCache(context)
            }
            AppSettings.setMediaCacheDisabled(context, disabled)
            _isMediaCacheDisabled.value = disabled
        }
    }

    /**
     * Set whether debug logging should be enabled.
     * When enabled, logs are captured in memory for troubleshooting.
     */
    fun setDebugLoggingEnabled(context: Context, enabled: Boolean) {
        AppSettings.setDebugLoggingEnabled(context, enabled)
        _isDebugLoggingEnabled.value = enabled
        DebugLogger.setEnabled(enabled)
    }

    /**
     * Set whether auto-connect should be enabled.
     * When enabled, the app will automatically connect to the last server on launch.
     */
    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        AppSettings.setAutoConnectEnabled(context, enabled)
        _isAutoConnectEnabled.value = enabled
    }

    fun setShowBuiltInWorkflows(context: Context, show: Boolean) {
        AppSettings.setShowBuiltInWorkflows(context, show)
        _isShowBuiltInWorkflows.value = show
        // Notify that workflows need to be reloaded
        viewModelScope.launch {
            _events.emit(SettingsEvent.RefreshNeeded)
        }
    }

    /**
     * Create a backup and write it to the given URI.
     */
    fun createBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val backupManager = BackupManager(context)
                backupManager.createBackup()
            }

            result.onSuccess { json ->
                val writeSuccess = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(json.toByteArray(Charsets.UTF_8))
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }

                if (writeSuccess) {
                    _events.emit(SettingsEvent.ShowToast(R.string.backup_created_success))
                } else {
                    _events.emit(SettingsEvent.ShowToast(R.string.backup_created_failed))
                }
            }.onFailure {
                _events.emit(SettingsEvent.ShowToast(R.string.backup_created_failed))
            }
        }
    }

    /**
     * Show restore confirmation dialog before restoring.
     */
    fun startRestore(uri: Uri) {
        viewModelScope.launch {
            _events.emit(SettingsEvent.ShowRestoreDialog(uri))
        }
    }

    /**
     * Restore configuration from a backup file at the given URI.
     */
    fun restoreBackup(context: Context, uri: Uri) {
        // Capture debug logging state BEFORE restore - if it was enabled, keep it enabled
        // so we can debug restore issues even if the backup has logging disabled
        val wasDebugLoggingEnabled = _isDebugLoggingEnabled.value

        DebugLogger.i(TAG, "restoreBackup called (debug logging was: $wasDebugLoggingEnabled)")
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    }
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to read backup file: ${e.message}")
                    null
                }
            }

            if (json == null) {
                DebugLogger.e(TAG, "Backup file read returned null")
                _events.emit(SettingsEvent.ShowToast(R.string.backup_restore_failed))
                return@launch
            }

            DebugLogger.d(TAG, "Backup file read, length: ${json.length}")

            val result = withContext(Dispatchers.IO) {
                BackupManager(context).restoreBackup(json)
            }

            DebugLogger.d(TAG, "Restore result: $result")

            when (result) {
                is RestoreResult.Success -> {
                    if (result.serversChanged) {
                        // Disconnect and clear all caches via ConnectionManager
                        // (also reloads workflows)
                        ConnectionManager.invalidateForRestore()
                    } else {
                        // Just clear in-memory caches (disk files already cleared by BackupManager)
                        MediaCache.clearAll()
                        MediaStateHolder.clearAll()
                        // Reload workflows to pick up restored user workflows
                        WorkflowManager.reloadWorkflows()
                    }

                    // Reload AppSettings into ViewModel state, preserving debug logging if it was enabled
                    DebugLogger.d(TAG, "Reloading AppSettings into ViewModel...")
                    reloadAppSettings(context, preserveDebugLogging = wasDebugLoggingEnabled)

                    if (result.skippedWorkflows > 0) {
                        _events.emit(SettingsEvent.ShowToast(R.string.backup_restore_partial))
                    } else {
                        _events.emit(SettingsEvent.ShowToast(R.string.backup_restore_success))
                    }
                    _events.emit(SettingsEvent.RefreshNeeded)
                    if (result.serversChanged) {
                        _events.emit(SettingsEvent.NavigateToLogin)
                    }
                }
                is RestoreResult.Failure -> {
                    DebugLogger.e(TAG, "Restore failed with error: ${result.errorMessageResId}")
                    _events.emit(SettingsEvent.ShowToast(result.errorMessageResId))
                }
            }
        }
    }

    /**
     * Reload AppSettings from SharedPreferences into ViewModel StateFlows.
     * Called after restore to update the UI with restored values.
     *
     * @param preserveDebugLogging If true and debug logging was enabled before restore,
     *        keep it enabled regardless of the backup's value. This ensures we can debug
     *        restore issues even if the backup has logging disabled.
     */
    private fun reloadAppSettings(context: Context, preserveDebugLogging: Boolean = false) {
        val newLivePreview = AppSettings.isLivePreviewEnabled(context)
        val newMemoryFirst = AppSettings.isMemoryFirstCache(context)
        val newMediaCacheDisabled = AppSettings.isMediaCacheDisabled(context)
        val restoredDebugLogging = AppSettings.isDebugLoggingEnabled(context)
        val newAutoConnect = AppSettings.isAutoConnectEnabled(context)
        val newShowBuiltInWorkflows = AppSettings.isShowBuiltInWorkflows(context)

        // If debug logging was enabled before restore, keep it enabled
        val finalDebugLogging = if (preserveDebugLogging && !restoredDebugLogging) {
            DebugLogger.i(TAG, "Preserving debug logging (was enabled before restore, backup had it disabled)")
            // Also save back to SharedPreferences so it persists
            AppSettings.setDebugLoggingEnabled(context, true)
            true
        } else {
            restoredDebugLogging
        }

        DebugLogger.d(TAG, "Reloaded AppSettings - livePreview: $newLivePreview, memoryFirst: $newMemoryFirst, mediaCacheDisabled: $newMediaCacheDisabled, debugLogging: $finalDebugLogging, autoConnect: $newAutoConnect, showBuiltInWorkflows: $newShowBuiltInWorkflows")

        _isLivePreviewEnabled.value = newLivePreview
        _isMemoryFirstCache.value = newMemoryFirst
        _isMediaCacheDisabled.value = newMediaCacheDisabled
        _isDebugLoggingEnabled.value = finalDebugLogging
        _isAutoConnectEnabled.value = newAutoConnect
        _isShowBuiltInWorkflows.value = newShowBuiltInWorkflows

        // Update DebugLogger state to match
        DebugLogger.setEnabled(finalDebugLogging)
    }

    override fun onCleared() {
        super.onCleared()
        stopResourceAutoRefresh()
        // Client is managed by ConnectionManager, don't shutdown here
    }
}
