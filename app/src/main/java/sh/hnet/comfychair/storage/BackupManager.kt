package sh.hnet.comfychair.storage

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.UuidUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Result of a restore operation.
 */
sealed class RestoreResult {
    data class Success(
        val serversChanged: Boolean,
        val skippedWorkflows: Int = 0
    ) : RestoreResult()

    data class Failure(val errorMessageResId: Int) : RestoreResult()
}

/**
 * Manages backup and restore operations for app configuration.
 *
 * Backup v4 format (hierarchical, server-first):
 * - servers: Array of server objects with id, name, hostname, port
 * - selectedServerId: Currently selected server ID
 * - appSettings: App-wide settings
 * - screenPreferences: { serverId: { textToImage: {...}, imageToImage: {...}, ... } }
 * - workflowValues: { serverId: { workflowId: {...}, ... } }
 * - userWorkflows: Array of user-uploaded workflows
 */
class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"
        const val BACKUP_VERSION = 5
        private const val USER_WORKFLOWS_DIR = "user_workflows"

        // SharedPreferences names
        private const val PREFS_APP_SETTINGS = "AppSettings"
        private const val PREFS_TEXT_TO_IMAGE = "TextToImageFragmentPrefs"
        private const val PREFS_IMAGE_TO_IMAGE = "ImageToImageFragmentPrefs"
        private const val PREFS_TEXT_TO_VIDEO = "TextToVideoFragmentPrefs"
        private const val PREFS_IMAGE_TO_VIDEO = "ImageToVideoFragmentPrefs"
        private const val PREFS_WORKFLOW_VALUES = "WorkflowValuesPrefs"
        private const val PREFS_USER_WORKFLOWS = "UserWorkflowsPrefs"
        private const val PREFS_PROMPT_PRESETS = "PromptPresetPrefs"
    }

    private val validator = BackupValidator()
    private val serverStorage = ServerStorage(context)

    /**
     * Create a backup of all app configuration.
     * Returns JSON string on success, or failure with error.
     */
    fun createBackup(): Result<String> {
        DebugLogger.i(TAG, "Creating backup (v$BACKUP_VERSION)...")
        return try {
            val servers = serverStorage.getServers()
            val selectedServerId = serverStorage.getSelectedServerId()

            val backup = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("exportedAt", getIso8601Timestamp())
                put("appVersion", getAppVersionName())

                // Servers
                put("servers", JSONArray().apply {
                    servers.forEach { server ->
                        put(server.toJson())
                    }
                })
                put("selectedServerId", selectedServerId ?: "")

                put("appSettings", readAppSettings())
                put("screenPreferences", readScreenPreferences(servers))
                put("workflowValues", readWorkflowValues(servers))
                put("userWorkflows", readUserWorkflows())
                put("promptPresets", readPromptPresets())
            }

            DebugLogger.i(TAG, "Backup created successfully with ${servers.size} servers")
            Result.success(backup.toString(2))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Backup creation failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Restore configuration from a backup JSON string.
     * Uses lenient validation: skips invalid entries but continues with valid data.
     * Server ID mapping: if a server name exists, map backup server ID to existing server ID.
     */
    fun restoreBackup(jsonString: String): RestoreResult {
        DebugLogger.i(TAG, "Starting backup restore...")

        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to parse backup JSON: ${e.message}")
            return RestoreResult.Failure(R.string.error_backup_invalid_json)
        }

        // Validate basic structure
        if (!validator.validateStructure(json)) {
            DebugLogger.e(TAG, "Backup structure validation failed")
            return RestoreResult.Failure(R.string.error_backup_invalid_json)
        }

        // Check version - v4 and v5 are supported
        val version = json.optInt("version", -1)
        if (version !in 4..BACKUP_VERSION) {
            DebugLogger.e(TAG, "Unsupported backup version: $version (required: 4-$BACKUP_VERSION)")
            return RestoreResult.Failure(R.string.error_backup_unsupported_version)
        }

        // Clear cached media files before restoring
        clearCacheFiles()

        return restoreBackupV4(json)
    }

    /**
     * Restore backup (v4 format - hierarchical, server-first).
     */
    private fun restoreBackupV4(json: JSONObject): RestoreResult {
        // Restore servers
        val serversArray = json.optJSONArray("servers")
        if (serversArray == null || serversArray.length() == 0) {
            DebugLogger.e(TAG, "No servers array in backup")
            return RestoreResult.Failure(R.string.error_backup_no_servers)
        }

        val existingServers = serverStorage.getServers()
        val serverIdMapping = mutableMapOf<String, String>() // backupId -> actualId
        var serversChanged = false

        for (i in 0 until serversArray.length()) {
            val serverJson = serversArray.optJSONObject(i) ?: continue
            val backupServer = Server.fromJson(serverJson) ?: continue

            // Check if server with same name exists
            val existingServer = existingServers.find { it.name == backupServer.name }
            if (existingServer != null) {
                // Map backup server ID to existing server ID
                serverIdMapping[backupServer.id] = existingServer.id
            } else {
                // Add new server with its original ID
                serverStorage.addServer(backupServer)
                serverIdMapping[backupServer.id] = backupServer.id
                serversChanged = true
            }
        }

        // Set selected server
        val selectedServerId = json.optString("selectedServerId", "")
        if (selectedServerId.isNotEmpty()) {
            val mappedId = serverIdMapping[selectedServerId] ?: selectedServerId
            serverStorage.setSelectedServerId(mappedId)
        }

        // Restore app settings
        json.optJSONObject("appSettings")?.let { restoreAppSettings(it) }

        // Restore screen preferences with server ID mapping
        json.optJSONObject("screenPreferences")?.let { prefs ->
            restoreScreenPreferencesV4(prefs, serverIdMapping)
        }

        // Restore workflow values with server ID mapping
        json.optJSONObject("workflowValues")?.let { values ->
            restoreWorkflowValues(values, serverIdMapping)
        }

        // Restore user workflows
        var skippedWorkflows = 0
        json.optJSONArray("userWorkflows")?.let { workflows ->
            skippedWorkflows = restoreUserWorkflows(workflows)
        }

        // Restore prompt presets (v5+)
        json.optJSONArray("promptPresets")?.let { presets ->
            restorePromptPresets(presets)
        }

        DebugLogger.i(TAG, "Backup restore completed. Servers changed: $serversChanged, skipped workflows: $skippedWorkflows")
        return RestoreResult.Success(
            serversChanged = serversChanged,
            skippedWorkflows = skippedWorkflows
        )
    }

    // Read methods

    private fun readAppSettings(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("autoConnectEnabled", prefs.getBoolean("autoConnect", true))
            put("livePreviewEnabled", prefs.getBoolean("live_preview_enabled", true))
            val storageMode = AppSettings.getMediaStorageMode(context)
            put("mediaStorageMode", storageMode.key)
            // Equivalent former switches, so older app versions restore the same behaviour
            put("memoryFirstCache", storageMode != MediaStorageMode.DEVICE)
            put("mediaCacheDisabled", storageMode == MediaStorageMode.MEMORY_ONLY)
            put("debugLoggingEnabled", prefs.getBoolean("debug_logging_enabled", false))
            put("disabledWorkflows", JSONArray(prefs.getStringSet("disabled_workflows", null).orEmpty().sorted()))
            put("promptSpellCheckEnabled", prefs.getBoolean("prompt_spell_check", false))
        }
    }

    private fun readScreenPreferences(servers: List<Server>): JSONObject {
        val result = JSONObject()
        servers.forEach { server ->
            result.put(server.id, JSONObject().apply {
                put("textToImage", readTextToImagePrefs(server.id))
                put("imageToImage", readImageToImagePrefs(server.id))
                put("textToVideo", readTextToVideoPrefs(server.id))
                put("imageToVideo", readImageToVideoPrefs(server.id))
            })
        }
        return result
    }

    private fun readTextToImagePrefs(serverId: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_IMAGE, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("selectedWorkflowId", prefs.getString("${serverId}_selectedWorkflowId", "") ?: "")
            put("positivePrompt", prefs.getString("${serverId}_positivePrompt", "") ?: "")
        }
    }

    private fun readImageToImagePrefs(serverId: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_IMAGE, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("selectedWorkflowId", prefs.getString("${serverId}_selectedWorkflowId", "") ?: "")
            put("positivePrompt", prefs.getString("${serverId}_positivePrompt", "") ?: "")
            put("mode", prefs.getString("${serverId}_mode", "INPAINTING") ?: "INPAINTING")
            put("selectedEditingWorkflowId", prefs.getString("${serverId}_selectedEditingWorkflowId", "") ?: "")
        }
    }

    private fun readTextToVideoPrefs(serverId: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_VIDEO, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("selectedWorkflowId", prefs.getString("${serverId}_selectedWorkflowId", "") ?: "")
            put("positivePrompt", prefs.getString("${serverId}_positivePrompt", "") ?: "")
        }
    }

    private fun readImageToVideoPrefs(serverId: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_VIDEO, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("selectedWorkflowId", prefs.getString("${serverId}_selectedWorkflowId", "") ?: "")
            put("positivePrompt", prefs.getString("${serverId}_positivePrompt", "") ?: "")
        }
    }

    private fun readWorkflowValues(servers: List<Server>): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_WORKFLOW_VALUES, Context.MODE_PRIVATE)
        val result = JSONObject()

        servers.forEach { server ->
            val serverValues = JSONObject()

            // Find all keys that start with this server's ID
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("${server.id}_") && value is String) {
                    val workflowId = key.removePrefix("${server.id}_")
                    try {
                        serverValues.put(workflowId, JSONObject(value))
                    } catch (e: Exception) {
                        // Skip invalid JSON
                    }
                }
            }

            if (serverValues.length() > 0) {
                result.put(server.id, serverValues)
            }
        }

        return result
    }

    private fun readUserWorkflows(): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_USER_WORKFLOWS, Context.MODE_PRIVATE)
        val metadataJson = prefs.getString("user_workflows_json", null) ?: return JSONArray()

        val result = JSONArray()
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)

        try {
            val metadataArray = JSONArray(metadataJson)

            for (i in 0 until metadataArray.length()) {
                val metadata = metadataArray.getJSONObject(i)
                val filename = metadata.getString("filename")
                val file = File(dir, filename)

                if (file.exists()) {
                    val workflowEntry = JSONObject().apply {
                        put("id", metadata.getString("id"))
                        put("name", metadata.getString("name"))
                        put("description", metadata.optString("description", ""))
                        put("type", metadata.getString("type"))
                        put("filename", filename)
                        put("fileContent", file.readText())
                    }
                    result.put(workflowEntry)
                }
            }
        } catch (e: Exception) {
            // Return partial result on error
        }

        return result
    }

    private fun readPromptPresets(): JSONArray {
        val prefs = context.getSharedPreferences(PREFS_PROMPT_PRESETS, Context.MODE_PRIVATE)
        val presetsJson = prefs.getString("presets_json", null) ?: return JSONArray()

        return try {
            JSONArray(presetsJson)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    // Restore methods

    private fun restoreAppSettings(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (json.has("autoConnectEnabled")) {
            editor.putBoolean("autoConnect", json.optBoolean("autoConnectEnabled", true))
        }
        if (json.has("livePreviewEnabled")) {
            editor.putBoolean("live_preview_enabled", json.optBoolean("livePreviewEnabled", true))
        }
        // Media storage: the current setting, or migrated from the former switches of older backups
        val storageMode = MediaStorageMode.fromKey(json.optString("mediaStorageMode").ifEmpty { null })
            ?: if (json.has("memoryFirstCache")) MediaStorageMode.migrate(json.optBoolean("memoryFirstCache", true)) else null
        if (storageMode != null) {
            editor.putString("media_storage_mode", storageMode.key)
            editor.remove("memory_first_cache")
            editor.remove("media_cache_disabled")
        }
        if (json.has("debugLoggingEnabled")) {
            editor.putBoolean("debug_logging_enabled", json.optBoolean("debugLoggingEnabled", false))
        }
        // Workflows switched off one by one, or the former switch for all built-in workflows of
        // older backups, which AppSettings migrates when the workflows are reloaded
        val disabledWorkflows = json.optJSONArray("disabledWorkflows")
        if (disabledWorkflows != null) {
            editor.putStringSet("disabled_workflows", (0 until disabledWorkflows.length()).map { disabledWorkflows.optString(it) }.filter { it.isNotEmpty() }.toSet())
            editor.remove("show_built_in_workflows")
        } else if (json.has("showBuiltInWorkflows")) {
            editor.remove("disabled_workflows")
            editor.putBoolean("show_built_in_workflows", json.optBoolean("showBuiltInWorkflows", true))
        }
        if (json.has("promptSpellCheckEnabled")) {
            editor.putBoolean("prompt_spell_check", json.optBoolean("promptSpellCheckEnabled", false))
        }

        editor.apply()
    }

    private fun restoreScreenPreferencesV4(json: JSONObject, serverIdMapping: Map<String, String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val backupServerId = keys.next()
            val actualServerId = serverIdMapping[backupServerId] ?: backupServerId
            val serverPrefs = json.optJSONObject(backupServerId) ?: continue

            serverPrefs.optJSONObject("textToImage")?.let {
                restoreTextToImagePrefs(it, actualServerId)
            }
            serverPrefs.optJSONObject("imageToImage")?.let {
                restoreImageToImagePrefs(it, actualServerId)
            }
            serverPrefs.optJSONObject("textToVideo")?.let {
                restoreTextToVideoPrefs(it, actualServerId)
            }
            serverPrefs.optJSONObject("imageToVideo")?.let {
                restoreImageToVideoPrefs(it, actualServerId)
            }
        }
    }

    private fun restoreTextToImagePrefs(json: JSONObject, serverId: String) {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_IMAGE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("selectedWorkflowId").takeIf { it.isNotEmpty() }?.let {
                putString("${serverId}_selectedWorkflowId", it)
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("${serverId}_positivePrompt", it)
                }
            }
            apply()
        }
    }

    private fun restoreImageToImagePrefs(json: JSONObject, serverId: String) {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_IMAGE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("selectedWorkflowId").takeIf { it.isNotEmpty() }?.let {
                putString("${serverId}_selectedWorkflowId", it)
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("${serverId}_positivePrompt", it)
                }
            }
            json.optString("mode").takeIf { it == "INPAINTING" || it == "EDITING" }?.let {
                putString("${serverId}_mode", it)
            }
            json.optString("selectedEditingWorkflowId").takeIf { it.isNotEmpty() }?.let {
                putString("${serverId}_selectedEditingWorkflowId", it)
            }
            apply()
        }
    }

    private fun restoreTextToVideoPrefs(json: JSONObject, serverId: String) {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_VIDEO, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("selectedWorkflowId").takeIf { it.isNotEmpty() }?.let {
                putString("${serverId}_selectedWorkflowId", it)
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("${serverId}_positivePrompt", it)
                }
            }
            apply()
        }
    }

    private fun restoreImageToVideoPrefs(json: JSONObject, serverId: String) {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_VIDEO, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("selectedWorkflowId").takeIf { it.isNotEmpty() }?.let {
                putString("${serverId}_selectedWorkflowId", it)
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("${serverId}_positivePrompt", it)
                }
            }
            apply()
        }
    }

    private fun restoreWorkflowValues(json: JSONObject, serverIdMapping: Map<String, String>) {
        val prefs = context.getSharedPreferences(PREFS_WORKFLOW_VALUES, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val serverKeys = json.keys()
        while (serverKeys.hasNext()) {
            val backupServerId = serverKeys.next()
            val actualServerId = serverIdMapping[backupServerId] ?: backupServerId
            val serverValues = json.optJSONObject(backupServerId) ?: continue

            val workflowKeys = serverValues.keys()
            while (workflowKeys.hasNext()) {
                val workflowId = workflowKeys.next()
                val valueObj = serverValues.optJSONObject(workflowId) ?: continue

                val sanitizedValue = sanitizeWorkflowValue(valueObj)
                if (sanitizedValue != null) {
                    editor.putString("${actualServerId}_$workflowId", sanitizedValue.toString())
                }
            }
        }

        editor.apply()
    }

    private fun sanitizeWorkflowValue(json: JSONObject): JSONObject? {
        return try {
            JSONObject().apply {
                // Dimensions and parameters
                json.optInt("width").takeIf { it > 0 && validator.validateDimension(it) }?.let {
                    put("width", it)
                }
                json.optInt("height").takeIf { it > 0 && validator.validateDimension(it) }?.let {
                    put("height", it)
                }
                json.optInt("steps").takeIf { it > 0 && validator.validateSteps(it) }?.let {
                    put("steps", it)
                }
                json.optDouble("cfg").takeIf { !it.isNaN() && validator.validateCfg(it.toFloat()) }?.let {
                    put("cfg", it)
                }
                json.optString("samplerName").takeIf { it.isNotEmpty() && validator.validateSampler(it) }?.let {
                    put("samplerName", it)
                }
                json.optString("scheduler").takeIf { it.isNotEmpty() && validator.validateScheduler(it) }?.let {
                    put("scheduler", it)
                }

                // Prompts
                if (json.has("negativePrompt")) {
                    put("negativePrompt", validator.sanitizeString(json.optString("negativePrompt")))
                }

                // Video parameters
                json.optDouble("megapixels").takeIf { !it.isNaN() && validator.validateMegapixels(it.toFloat()) }?.let {
                    put("megapixels", it)
                }
                json.optInt("length").takeIf { it > 0 && validator.validateLength(it) }?.let {
                    put("length", it)
                }
                json.optInt("frameRate").takeIf { it > 0 && validator.validateFrameRate(it) }?.let {
                    put("frameRate", it)
                }

                // Model names - must match WorkflowValues field names exactly
                listOf(
                    "model",  // Unified model (checkpoint or UNET)
                    "loraModel", "vaeModel", "clipModel",
                    "clip1Model", "clip2Model", "clip3Model", "clip4Model",  // All 4 CLIP slots
                    "highnoiseUnetModel", "lownoiseUnetModel",
                    "highnoiseLoraModel", "lownoiseLoraModel"
                ).forEach { key ->
                    json.optString(key).takeIf { it.isNotEmpty() }?.let {
                        put(key, validator.sanitizeString(it, 500))
                    }
                }

                // LoRA chains
                listOf("loraChain", "highnoiseLoraChain", "lownoiseLoraChain").forEach { key ->
                    json.optString(key).takeIf { it.isNotEmpty() }?.let {
                        put(key, it)
                    }
                }

                // Advanced generation parameters
                json.optLong("seed", -1).takeIf { it >= 0 && validator.validateSeed(it) }?.let {
                    put("seed", it)
                }
                if (json.has("randomSeed")) {
                    put("randomSeed", json.optBoolean("randomSeed"))
                }
                json.optDouble("denoise").takeIf { !it.isNaN() && validator.validateDenoise(it.toFloat()) }?.let {
                    put("denoise", it)
                }
                json.optInt("batchSize").takeIf { it > 0 && validator.validateBatchSize(it) }?.let {
                    put("batchSize", it)
                }
                json.optString("upscaleMethod").takeIf { it.isNotEmpty() }?.let {
                    put("upscaleMethod", validator.sanitizeString(it, 100))
                }
                json.optDouble("scaleBy").takeIf { !it.isNaN() && validator.validateScaleBy(it.toFloat()) }?.let {
                    put("scaleBy", it)
                }
                json.optInt("stopAtClipLayer", 1).takeIf { it != 1 && validator.validateStopAtClipLayer(it) }?.let {
                    put("stopAtClipLayer", it)
                }

                // Node attribute edits
                if (json.has("nodeAttributeEdits")) {
                    put("nodeAttributeEdits", json.optString("nodeAttributeEdits"))
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Restore user workflows.
     * Returns the count of skipped workflows.
     */
    private fun restoreUserWorkflows(workflows: JSONArray): Int {
        var skipped = 0
        var restored = 0
        val dir = File(context.filesDir, USER_WORKFLOWS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val prefs = context.getSharedPreferences(PREFS_USER_WORKFLOWS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString("user_workflows_json", null)
        val existingArray = if (existingJson != null) {
            try { JSONArray(existingJson) } catch (e: Exception) { JSONArray() }
        } else {
            JSONArray()
        }

        // Collect existing workflow IDs to avoid duplicates
        val existingIds = mutableSetOf<String>()
        for (i in 0 until existingArray.length()) {
            existingArray.optJSONObject(i)?.optString("id")?.let { existingIds.add(it) }
        }

        for (i in 0 until workflows.length()) {
            try {
                val workflow = workflows.getJSONObject(i)
                val id = workflow.getString("id")
                val name = workflow.getString("name")
                val description = workflow.optString("description", "")
                val typeStr = workflow.getString("type")
                val filename = workflow.getString("filename")
                val fileContent = workflow.getString("fileContent")

                // Validate
                if (!validator.validateWorkflowName(name) ||
                    !validator.validateWorkflowDescription(description) ||
                    !validator.validateWorkflowType(typeStr)) {
                    skipped++
                    continue
                }

                // Validate file content is valid JSON
                try {
                    JSONObject(fileContent)
                } catch (e: Exception) {
                    skipped++
                    continue
                }

                // Skip if workflow with same ID already exists
                if (id in existingIds) {
                    skipped++
                    continue
                }

                // Save workflow file
                val file = File(dir, filename)
                file.writeText(fileContent)

                // Add to metadata
                val metadata = JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("description", description)
                    put("type", typeStr)
                    put("filename", filename)
                }
                existingArray.put(metadata)
                existingIds.add(id)
                restored++
            } catch (e: Exception) {
                skipped++
            }
        }

        // Save updated metadata
        prefs.edit().putString("user_workflows_json", existingArray.toString()).apply()

        DebugLogger.i(TAG, "User workflows restored: $restored, skipped: $skipped")
        return skipped
    }

    /**
     * Restore prompt presets.
     * Merges with existing presets, skipping duplicates by ID.
     */
    private fun restorePromptPresets(presets: JSONArray) {
        val prefs = context.getSharedPreferences(PREFS_PROMPT_PRESETS, Context.MODE_PRIVATE)
        val existingJson = prefs.getString("presets_json", null)
        val existingArray = if (existingJson != null) {
            try { JSONArray(existingJson) } catch (e: Exception) { JSONArray() }
        } else {
            JSONArray()
        }

        // Collect existing preset IDs to avoid duplicates
        val existingIds = mutableSetOf<String>()
        for (i in 0 until existingArray.length()) {
            existingArray.optJSONObject(i)?.optString("id")?.let { existingIds.add(it) }
        }

        var restored = 0
        var skipped = 0

        for (i in 0 until presets.length()) {
            try {
                val preset = presets.getJSONObject(i)
                val id = preset.optString("id")
                val screenType = preset.optString("screenType")
                val name = preset.optString("name")
                val prompt = preset.optString("prompt")
                val tagsArray = preset.optJSONArray("tags")
                val isFavorite = preset.optBoolean("isFavorite", false)
                val createdAt = preset.optLong("createdAt", System.currentTimeMillis())

                // Validate required fields
                if (id.isBlank() || !validator.validateScreenType(screenType) ||
                    !validator.validatePresetName(name)) {
                    skipped++
                    continue
                }

                // Skip if preset with same ID already exists
                if (id in existingIds) {
                    skipped++
                    continue
                }

                // Validate and sanitize tags
                val validTags = JSONArray()
                if (tagsArray != null) {
                    val tagCount = minOf(tagsArray.length(), BackupValidator.MAX_TAGS_PER_PRESET)
                    for (j in 0 until tagCount) {
                        val tag = tagsArray.optString(j)
                        if (validator.validateTag(tag)) {
                            validTags.put(validator.sanitizeString(tag, BackupValidator.MAX_TAG_LENGTH))
                        }
                    }
                }

                // Sanitize prompt
                val sanitizedPrompt = validator.validateAndSanitizePrompt(prompt) ?: ""

                // Create validated preset JSON
                val validatedPreset = JSONObject().apply {
                    put("id", id)
                    put("screenType", screenType)
                    put("name", validator.sanitizeString(name, BackupValidator.MAX_PRESET_NAME_LENGTH))
                    put("prompt", sanitizedPrompt)
                    put("tags", validTags)
                    put("isFavorite", isFavorite)
                    put("createdAt", createdAt)
                }

                existingArray.put(validatedPreset)
                existingIds.add(id)
                restored++
            } catch (e: Exception) {
                skipped++
            }
        }

        // Save updated presets
        prefs.edit().putString("presets_json", existingArray.toString()).apply()

        DebugLogger.i(TAG, "Prompt presets restored: $restored, skipped: $skipped")
    }

    /**
     * Clear cached media files.
     */
    private fun clearCacheFiles() {
        // Clear files with server ID prefix pattern
        context.filesDir.listFiles()?.forEach { file ->
            val name = file.name
            // Clear media files (have UUID prefix followed by underscore)
            if (name.matches(Regex("[a-f0-9-]{36}_.*\\.(png|mp4)"))) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        // Clear cache directory
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("gallery_video_") ||
                file.name.startsWith("playback_") ||
                file.name.endsWith(".png") ||
                file.name.endsWith(".mp4")) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    // Utilities

    private fun getIso8601Timestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}
