package sh.hnet.comfychair.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowType
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
 * - Screen preferences (selected workflows, prompts, modes)
 * - Per-workflow saved values
 * - User-uploaded workflows (metadata + file contents)
 */
class BackupManager(private val context: Context) {

    companion object {
        const val BACKUP_VERSION = 1
        private const val USER_WORKFLOWS_DIR = "user_workflows"

        // SharedPreferences names
        private const val PREFS_CONNECTION = "ComfyChairPrefs"
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
        return try {
            val backup = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("exportedAt", getIso8601Timestamp())
                put("appVersion", getAppVersionName())

                put("connection", readConnectionPrefs())
                put("screenPreferences", readScreenPreferences())
                put("workflowValues", readWorkflowValues())
                put("userWorkflows", readUserWorkflows())
            }

            Result.success(backup.toString(2))
        } catch (e: Exception) {
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
        val json = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return RestoreResult.Failure(R.string.backup_error_invalid_json)
        }

        // Validate basic structure
        if (!validator.validateStructure(json)) {
            return RestoreResult.Failure(R.string.backup_error_invalid_json)
        }

        // Check version
        val version = json.optInt("version", -1)
        if (version > BACKUP_VERSION) {
            return RestoreResult.Failure(R.string.backup_error_unsupported_version)
        }

        // Restore connection (must succeed)
        val connection = json.optJSONObject("connection")
        if (connection == null) {
            return RestoreResult.Failure(R.string.backup_error_invalid_connection)
        }

        val connectionChanged = restoreConnectionPrefs(connection)
            ?: return RestoreResult.Failure(R.string.backup_error_invalid_connection)

        // Clear cached media files before restoring new configuration
        clearCacheFiles()

        // Restore screen preferences (lenient - skip invalid)
        json.optJSONObject("screenPreferences")?.let { prefs ->
            restoreScreenPreferences(prefs)
        }

        // Restore workflow values (lenient - skip invalid)
        json.optJSONObject("workflowValues")?.let { values ->
            restoreWorkflowValues(values)
        }

        // Restore user workflows (lenient - count skipped)
        var skippedWorkflows = 0
        json.optJSONArray("userWorkflows")?.let { workflows ->
            skippedWorkflows = restoreUserWorkflows(workflows)
        }

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
            if ((file.name.startsWith("last_generated_video") && file.name.endsWith(".mp4")) ||
                (file.name.startsWith("image_to_video_last_generated") && file.name.endsWith(".mp4"))) {
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
            put("isCheckpointMode", prefs.getBoolean("isCheckpointMode", true))
            put("positivePrompt", prefs.getString("positive_prompt", "") ?: "")
            put("checkpointWorkflow", prefs.getString("checkpointWorkflow", "") ?: "")
            put("unetWorkflow", prefs.getString("unetWorkflow", "") ?: "")
        }
    }

    private fun readImageToImagePrefs(): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_IMAGE, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("configMode", prefs.getString("config_mode", "CHECKPOINT") ?: "CHECKPOINT")
            put("checkpointWorkflow", prefs.getString("checkpoint_workflow", "") ?: "")
            put("unetWorkflow", prefs.getString("unet_workflow", "") ?: "")
            put("positivePrompt", prefs.getString("positive_prompt", "") ?: "")
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

    private fun restoreScreenPreferences(json: JSONObject) {
        json.optJSONObject("textToImage")?.let { restoreTextToImagePrefs(it) }
        json.optJSONObject("imageToImage")?.let { restoreImageToImagePrefs(it) }
        json.optJSONObject("textToVideo")?.let { restoreTextToVideoPrefs(it) }
        json.optJSONObject("imageToVideo")?.let { restoreImageToVideoPrefs(it) }
    }

    private fun restoreTextToImagePrefs(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_TEXT_TO_IMAGE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (json.has("isCheckpointMode")) {
                putBoolean("isCheckpointMode", json.optBoolean("isCheckpointMode", true))
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("positive_prompt", it)
                }
            }
            json.optString("checkpointWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("checkpointWorkflow", validator.sanitizeString(it, 100))
            }
            json.optString("unetWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("unetWorkflow", validator.sanitizeString(it, 100))
            }
            apply()
        }
    }

    private fun restoreImageToImagePrefs(json: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_IMAGE_TO_IMAGE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("configMode").takeIf { it.isNotEmpty() }?.let { mode ->
                if (validator.validateConfigMode(mode)) {
                    putString("config_mode", mode.uppercase())
                }
            }
            json.optString("checkpointWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("checkpoint_workflow", validator.sanitizeString(it, 100))
            }
            json.optString("unetWorkflow").takeIf { it.isNotEmpty() }?.let {
                putString("unet_workflow", validator.sanitizeString(it, 100))
            }
            json.optString("positivePrompt").takeIf { it.isNotEmpty() }?.let { prompt ->
                validator.validateAndSanitizePrompt(prompt)?.let {
                    putString("positive_prompt", it)
                }
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
                    "checkpointModel", "unetModel", "vaeModel", "clipModel",
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
