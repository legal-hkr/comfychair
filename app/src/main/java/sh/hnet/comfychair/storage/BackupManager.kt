package sh.hnet.comfychair.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.util.DebugLogger
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
        val connectionChanged: Boolean,
        val skippedWorkflows: Int = 0
    ) : RestoreResult()

    data class Failure(val errorMessageResId: Int) : RestoreResult()
}

/**
 * Manages backup and restore operations for app configuration.
 *
 * Backup includes:
 * - Connection settings (hostname, port)
 * - App settings (live preview, memory cache, debug logging)
 * - Screen preferences (selected workflows, prompts, modes)
 * - Per-workflow saved values
 * - User-uploaded workflows (metadata + file contents)
 */
class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"
        const val BACKUP_VERSION = 1
        private const val USER_WORKFLOWS_DIR = "user_workflows"

        // SharedPreferences names
        private const val PREFS_CONNECTION = "ComfyChairPrefs"
        private const val PREFS_APP_SETTINGS = "AppSettings"
        private const val PREFS_TEXT_TO_IMAGE = "TextToImageFragmentPrefs"
        private const val PREFS_IMAGE_TO_IMAGE = "ImageToImageFragmentPrefs"
        private const val PREFS_TEXT_TO_VIDEO = "TextToVideoFragmentPrefs"
        private const val PREFS_IMAGE_TO_VIDEO = "ImageToVideoFragmentPrefs"
        private const val PREFS_WORKFLOW_VALUES = "WorkflowValuesPrefs"
        private const val PREFS_USER_WORKFLOWS = "UserWorkflowsPrefs"
    }

    private val validator = BackupValidator()

    /**
     * Create a backup of all app configuration.
     * Returns JSON string on success, or failure with error.
     */
    fun createBackup(): Result<String> {
        DebugLogger.i(TAG, "Creating backup...")
        return try {
            val appSettings = readAppSettings()
            DebugLogger.d(TAG, "Backup appSettings: $appSettings")

            val backup = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("exportedAt", getIso8601Timestamp())
                put("appVersion", getAppVersionName())

                put("connection", readConnectionPrefs())
                put("appSettings", appSettings)
                put("screenPreferences", readScreenPreferences())
                put("workflowValues", readWorkflowValues())
                put("userWorkflows", readUserWorkflows())
            }

            DebugLogger.i(TAG, "Backup created successfully")
            Result.success(backup.toString(2))
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Backup creation failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Restore configuration from a backup JSON string.
     * Uses lenient validation: skips invalid entries but continues with valid data.
     * Connection settings must be valid or the entire restore fails.
     * Clears cached media files before restoring.
     */
    fun restoreBackup(jsonString: String): RestoreResult {
        DebugLogger.i(TAG, "Starting backup restore...")
        DebugLogger.d(TAG, "Backup JSON length: ${jsonString.length} chars")

        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to parse backup JSON: ${e.message}")
            return RestoreResult.Failure(R.string.backup_error_invalid_json)
        }

        // Validate basic structure
        if (!validator.validateStructure(json)) {
            DebugLogger.e(TAG, "Backup structure validation failed")
            return RestoreResult.Failure(R.string.backup_error_invalid_json)
        }

        // Check version
        val version = json.optInt("version", -1)
        DebugLogger.d(TAG, "Backup version: $version (current: $BACKUP_VERSION)")
        if (version > BACKUP_VERSION) {
            DebugLogger.e(TAG, "Unsupported backup version: $version")
            return RestoreResult.Failure(R.string.backup_error_unsupported_version)
        }

        // Restore connection (must succeed)
        val connection = json.optJSONObject("connection")
        if (connection == null) {
            DebugLogger.e(TAG, "No connection object in backup")
            return RestoreResult.Failure(R.string.backup_error_invalid_connection)
        }

        DebugLogger.d(TAG, "Restoring connection settings...")
        val connectionChanged = restoreConnectionPrefs(connection)
            ?: return RestoreResult.Failure(R.string.backup_error_invalid_connection)
        DebugLogger.d(TAG, "Connection restored, changed: $connectionChanged")

        // Clear cached media files before restoring new configuration
        DebugLogger.d(TAG, "Clearing cache files...")
        clearCacheFiles()

        // Restore app settings (lenient - skip invalid)
        val appSettingsJson = json.optJSONObject("appSettings")
        DebugLogger.d(TAG, "appSettings in backup: ${appSettingsJson != null}")
        if (appSettingsJson != null) {
            DebugLogger.d(TAG, "appSettings content: $appSettingsJson")
            restoreAppSettings(appSettingsJson)
        } else {
            DebugLogger.w(TAG, "No appSettings found in backup")
        }

        // Restore screen preferences (lenient - skip invalid)
        json.optJSONObject("screenPreferences")?.let { prefs ->
            DebugLogger.d(TAG, "Restoring screen preferences...")
            restoreScreenPreferences(prefs)
        }

        // Restore workflow values (lenient - skip invalid)
        json.optJSONObject("workflowValues")?.let { values ->
            DebugLogger.d(TAG, "Restoring workflow values...")
            restoreWorkflowValues(values)
        }

        // Restore user workflows (lenient - count skipped)
        var skippedWorkflows = 0
        json.optJSONArray("userWorkflows")?.let { workflows ->
            DebugLogger.d(TAG, "Restoring ${workflows.length()} user workflows...")
            skippedWorkflows = restoreUserWorkflows(workflows)
        }

        DebugLogger.i(TAG, "Backup restore completed. Connection changed: $connectionChanged, skipped workflows: $skippedWorkflows")
        return RestoreResult.Success(
            connectionChanged = connectionChanged,
            skippedWorkflows = skippedWorkflows
        )
    }

    /**
     * Clear cached media files (previews, sources, videos).
     * Does not clear user workflows (they are part of backup/restore).
     */
    private fun clearCacheFiles() {
        // Clear preview and source image files
        val cachedFiles = listOf(
            "tti_last_preview.png",
            "iti_last_preview.png",
            "iti_last_source.png",
            "ite_reference_1.png",
            "ite_reference_2.png",
            "ttv_last_preview.png",
            "itv_last_preview.png",
            "itv_last_source.png"
        )

        cachedFiles.forEach { filename ->
            try {
                context.deleteFile(filename)
            } catch (e: Exception) {
                // Ignore deletion errors
            }
        }

        // Clear video files with prompt ID suffixes in filesDir
        context.filesDir.listFiles()?.forEach { file ->
            if ((file.name.startsWith("ttv_") && file.name.endsWith(".mp4")) ||
                (file.name.startsWith("itv_") && file.name.endsWith(".mp4"))) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    // Ignore deletion errors
                }
            }
        }

        // Clear temp files in cache directory (gallery thumbnails, playback files)
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("gallery_video_") ||
                file.name.startsWith("playback_") ||
                file.name.endsWith(".png") ||
                file.name.endsWith(".mp4")) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    // Ignore deletion errors
                }
            }
        }
    }

    // ==================== READ METHODS ====================

    private fun readConnectionPrefs(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_CONNECTION, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("hostname", prefs.getString("hostname", "") ?: "")
            put("port", prefs.getInt("port", 8188))
        }
    }

    private fun readAppSettings(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("livePreviewEnabled", prefs.getBoolean("live_preview_enabled", true))
            put("memoryFirstCache", prefs.getBoolean("memory_first_cache", true))
            put("mediaCacheDisabled", prefs.getBoolean("media_cache_disabled", false))
            put("debugLoggingEnabled", prefs.getBoolean("debug_logging_enabled", false))
        }
    }

    private fun readScreenPreferences(): JSONObject {
        return JSONObject().apply {
            put("textToImage", readTextToImagePrefs())
            put("imageToImage", readImageToImagePrefs())
            put("textToVideo", readTextToVideoPrefs())
            put("imageToVideo", readImageToVideoPrefs())
        }
    }

    private fun readTextToImagePrefs(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_IMAGE, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("selectedWorkflow", prefs.getString("selectedWorkflow", "") ?: "")
            put("positivePrompt", prefs.getString("positive_prompt", "") ?: "")
        }
    }

    private fun readImageToImagePrefs(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_IMAGE, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("selectedWorkflow", prefs.getString("selectedWorkflow", "") ?: "")
            put("positivePrompt", prefs.getString("positive_prompt", "") ?: "")
            // Editing mode preferences
            put("mode", prefs.getString("mode", "INPAINTING") ?: "INPAINTING")
            put("selectedEditingWorkflow", prefs.getString("selectedEditingWorkflow", "") ?: "")
        }
    }

    private fun readTextToVideoPrefs(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_VIDEO, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("workflow", prefs.getString("workflow", "") ?: "")
            put("positivePrompt", prefs.getString("positive_prompt", "") ?: "")
        }
    }

    private fun readImageToVideoPrefs(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_VIDEO, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("workflow", prefs.getString("workflow", "") ?: "")
            put("positivePrompt", prefs.getString("positive_prompt", "") ?: "")
        }
    }

    private fun readWorkflowValues(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_WORKFLOW_VALUES, Context.MODE_PRIVATE)
        val result = JSONObject()

        // Iterate through all keys that start with "workflow_"
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("workflow_") && value is String) {
                // Store the raw JSON string value
                try {
                    result.put(key, JSONObject(value))
                } catch (e: Exception) {
                    // Skip invalid JSON values
                }
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

    // ==================== RESTORE METHODS ====================

    /**
     * Restore connection preferences.
     * Returns null if validation fails, otherwise returns whether connection changed.
     */
    private fun restoreConnectionPrefs(json: JSONObject): Boolean? {
        val hostname = json.optString("hostname", "")
        val port = json.optInt("port", -1)

        // Validate
        if (!validator.validateHostname(hostname)) return null
        if (!validator.validatePort(port)) return null

        // Check if connection changed
        val prefs = context.getSharedPreferences(PREFS_CONNECTION, Context.MODE_PRIVATE)
        val oldHostname = prefs.getString("hostname", "") ?: ""
        val oldPort = prefs.getInt("port", 8188)
        val changed = (hostname != oldHostname || port != oldPort)

        // Save
        prefs.edit().apply {
            putString("hostname", hostname)
            putInt("port", port)
            apply()
        }

        return changed
    }

    private fun restoreAppSettings(json: JSONObject) {
        DebugLogger.d(TAG, "restoreAppSettings called with: $json")

        val prefs = context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)

        // Read current values before restore
        val currentLivePreview = prefs.getBoolean("live_preview_enabled", true)
        val currentMemoryFirst = prefs.getBoolean("memory_first_cache", true)
        val currentMediaCacheDisabled = prefs.getBoolean("media_cache_disabled", false)
        val currentDebugLogging = prefs.getBoolean("debug_logging_enabled", false)
        DebugLogger.d(TAG, "Current appSettings - livePreview: $currentLivePreview, memoryFirst: $currentMemoryFirst, mediaCacheDisabled: $currentMediaCacheDisabled, debugLogging: $currentDebugLogging")

        val editor = prefs.edit()

        // Only restore if the key exists in the backup
        if (json.has("livePreviewEnabled")) {
            val value = json.optBoolean("livePreviewEnabled", true)
            DebugLogger.d(TAG, "Restoring livePreviewEnabled: $value")
            editor.putBoolean("live_preview_enabled", value)
        } else {
            DebugLogger.d(TAG, "livePreviewEnabled not in backup, skipping")
        }

        if (json.has("memoryFirstCache")) {
            val value = json.optBoolean("memoryFirstCache", true)
            DebugLogger.d(TAG, "Restoring memoryFirstCache: $value")
            editor.putBoolean("memory_first_cache", value)
        } else {
            DebugLogger.d(TAG, "memoryFirstCache not in backup, skipping")
        }

        if (json.has("mediaCacheDisabled")) {
            val value = json.optBoolean("mediaCacheDisabled", false)
            DebugLogger.d(TAG, "Restoring mediaCacheDisabled: $value")
            editor.putBoolean("media_cache_disabled", value)
        } else {
            DebugLogger.d(TAG, "mediaCacheDisabled not in backup, skipping")
        }

        if (json.has("debugLoggingEnabled")) {
            val value = json.optBoolean("debugLoggingEnabled", false)
            DebugLogger.d(TAG, "Restoring debugLoggingEnabled: $value")
            editor.putBoolean("debug_logging_enabled", value)
        } else {
            DebugLogger.d(TAG, "debugLoggingEnabled not in backup, skipping")
        }

        val commitResult = editor.commit()
        DebugLogger.d(TAG, "AppSettings commit result: $commitResult")

        // Verify values after restore
        val newLivePreview = prefs.getBoolean("live_preview_enabled", true)
        val newMemoryFirst = prefs.getBoolean("memory_first_cache", true)
        val newMediaCacheDisabled = prefs.getBoolean("media_cache_disabled", false)
        val newDebugLogging = prefs.getBoolean("debug_logging_enabled", false)
        DebugLogger.d(TAG, "After restore appSettings - livePreview: $newLivePreview, memoryFirst: $newMemoryFirst, mediaCacheDisabled: $newMediaCacheDisabled, debugLogging: $newDebugLogging")
    }

    private fun restoreScreenPreferences(json: JSONObject) {
        json.optJSONObject("textToImage")?.let { restoreTextToImagePrefs(it) }
        json.optJSONObject("imageToImage")?.let { restoreImageToImagePrefs(it) }
        json.optJSONObject("textToVideo")?.let { restoreTextToVideoPrefs(it) }
        json.optJSONObject("imageToVideo")?.let { restoreImageToVideoPrefs(it) }
    }

    private fun restoreTextToImagePrefs(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_IMAGE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            // Handle new format (selectedWorkflow)
            json.optString("selectedWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("selectedWorkflow", validator.sanitizeString(it, 100))
            }

            // Legacy support: if old format detected, derive selectedWorkflow
            if (!json.has("selectedWorkflow") && json.has("isCheckpointMode")) {
                val isCheckpoint = json.optBoolean("isCheckpointMode", true)
                val workflow = if (isCheckpoint) {
                    json.optString("checkpointWorkflow", "")
                } else {
                    json.optString("unetWorkflow", "")
                }
                if (workflow.isNotEmpty()) {
                    putString("selectedWorkflow", validator.sanitizeString(workflow, 100))
                }
            }

            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("positive_prompt", it)
                }
            }
            apply()
        }
    }

    private fun restoreImageToImagePrefs(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_IMAGE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            // Handle new format (selectedWorkflow)
            json.optString("selectedWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("selectedWorkflow", validator.sanitizeString(it, 100))
            }

            // Legacy support: if old format detected, derive selectedWorkflow
            if (!json.has("selectedWorkflow") && json.has("configMode")) {
                val isCheckpoint = json.optString("configMode", "CHECKPOINT").uppercase() == "CHECKPOINT"
                val workflow = if (isCheckpoint) {
                    json.optString("checkpointWorkflow", "")
                } else {
                    json.optString("unetWorkflow", "")
                }
                if (workflow.isNotEmpty()) {
                    putString("selectedWorkflow", validator.sanitizeString(workflow, 100))
                }
            }

            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("positive_prompt", it)
                }
            }

            // Editing mode preferences
            json.optString("mode").takeIf { it == "INPAINTING" || it == "EDITING" }?.let {
                putString("mode", it)
            }
            json.optString("selectedEditingWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("selectedEditingWorkflow", validator.sanitizeString(it, 100))
            }

            apply()
        }
    }

    private fun restoreTextToVideoPrefs(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_VIDEO, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("workflow").takeIf { it.isNotEmpty() }?.let {
                putString("workflow", validator.sanitizeString(it, 100))
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("positive_prompt", it)
                }
            }
            apply()
        }
    }

    private fun restoreImageToVideoPrefs(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_VIDEO, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("workflow").takeIf { it.isNotEmpty() }?.let {
                putString("workflow", validator.sanitizeString(it, 100))
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("positive_prompt", it)
                }
            }
            apply()
        }
    }

    private fun restoreWorkflowValues(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_WORKFLOW_VALUES, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.startsWith("workflow_")) continue

            try {
                val valueObj = json.optJSONObject(key) ?: continue

                // Validate and sanitize individual fields
                val sanitizedValue = sanitizeWorkflowValue(valueObj)
                if (sanitizedValue != null) {
                    editor.putString(key, sanitizedValue.toString())
                }
            } catch (e: Exception) {
                // Skip invalid entries
            }
        }

        editor.apply()
    }

    private fun sanitizeWorkflowValue(json: JSONObject): JSONObject? {
        return try {
            JSONObject().apply {
                // Copy and validate each field
                json.optString("workflowId").takeIf { it.isNotEmpty() }?.let {
                    put("workflowId", it)
                }

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

                // Prompts and model names - just sanitize strings
                if (json.has("negativePrompt")) {
                    val negPrompt = json.optString("negativePrompt")
                    put("negativePrompt", validator.sanitizeString(negPrompt))
                }

                json.optDouble("megapixels").takeIf { !it.isNaN() && validator.validateMegapixels(it.toFloat()) }?.let {
                    put("megapixels", it)
                }
                json.optInt("length").takeIf { it > 0 && validator.validateLength(it) }?.let {
                    put("length", it)
                }
                json.optInt("frameRate").takeIf { it > 0 && validator.validateFrameRate(it) }?.let {
                    put("frameRate", it)
                }

                // Model names - just sanitize (don't validate against server models)
                listOf(
                    "checkpointModel", "unetModel", "loraModel", "vaeModel", "clipModel",
                    "clip1Model", "clip2Model",  // Dual CLIP for Flux
                    "highnoiseUnetModel", "lownoiseUnetModel",
                    "highnoiseLoraModel", "lownoiseLoraModel"
                ).forEach { key ->
                    json.optString(key).takeIf { it.isNotEmpty() }?.let {
                        put(key, validator.sanitizeString(it, 500))
                    }
                }

                // LoRA chains - copy as-is (they're JSON strings)
                listOf("loraChain", "highnoiseLoraChain", "lownoiseLoraChain").forEach { key ->
                    json.optString(key).takeIf { it.isNotEmpty() }?.let {
                        put(key, it)
                    }
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
                if (!validator.validateWorkflowName(name)) {
                    skipped++
                    continue
                }
                if (!validator.validateWorkflowDescription(description)) {
                    skipped++
                    continue
                }
                if (!validator.validateWorkflowType(typeStr)) {
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
            } catch (e: Exception) {
                skipped++
            }
        }

        // Save updated metadata
        prefs.edit().putString("user_workflows_json", existingArray.toString()).apply()

        return skipped
    }

    // ==================== UTILITIES ====================

    private fun getIso8601Timestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        return dateFormat.format(Date())
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}
