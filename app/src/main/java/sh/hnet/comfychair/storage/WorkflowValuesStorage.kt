package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.model.WorkflowValues

/**
 * Storage for per-workflow user values.
 * Each workflow's values are stored as a JSON string keyed by server ID and workflow ID.
 * Key format: {serverId}_{workflowId}
 */
class WorkflowValuesStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save values for a specific workflow on a specific server.
     */
    fun saveValues(serverId: String, workflowId: String, values: WorkflowValues) {
        val json = WorkflowValues.toJson(values)
        prefs.edit().putString(keyFor(serverId, workflowId), json).apply()
    }

    /**
     * Load saved values for a specific workflow on a specific server.
     * Returns null if no values have been saved for this workflow.
     */
    fun loadValues(serverId: String, workflowId: String): WorkflowValues? {
        val json = prefs.getString(keyFor(serverId, workflowId), null) ?: return null
        return try {
            WorkflowValues.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete saved values for a specific workflow on a specific server.
     */
    fun deleteValues(serverId: String, workflowId: String) {
        prefs.edit().remove(keyFor(serverId, workflowId)).apply()
    }

    /**
     * Delete all saved values for a specific server.
     */
    fun deleteValuesForServer(serverId: String) {
        val prefix = "${serverId}_"
        val keysToRemove = prefs.all.keys.filter { it.startsWith(prefix) }
        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Clear all saved workflow values.
     * Called when user restores defaults.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Clear negative prompts from all saved workflow values for a specific server.
     * Called when user resets prompts to defaults.
     */
    fun clearNegativePromptsForServer(serverId: String) {
        val prefix = "${serverId}_"
        val allKeys = prefs.all.keys.filter { it.startsWith(prefix) }
        for (key in allKeys) {
            val json = prefs.getString(key, null) ?: continue
            val values = try {
                WorkflowValues.fromJson(json)
            } catch (e: Exception) {
                continue
            }
            val updated = values.copy(negativePrompt = "")
            prefs.edit().putString(key, WorkflowValues.toJson(updated)).apply()
        }
    }

    /**
     * Get all workflow values for a specific server.
     * Returns a map of workflowId to WorkflowValues.
     */
    fun getAllValuesForServer(serverId: String): Map<String, WorkflowValues> {
        val prefix = "${serverId}_"
        val result = mutableMapOf<String, WorkflowValues>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String) {
                val workflowId = key.removePrefix(prefix)
                try {
                    WorkflowValues.fromJson(value)?.let {
                        result[workflowId] = it
                    }
                } catch (_: Exception) {
                    // Skip invalid entries
                }
            }
        }
        return result
    }

    private fun keyFor(serverId: String, workflowId: String): String = "${serverId}_$workflowId"

    companion object {
        private const val PREFS_NAME = "WorkflowValuesPrefs"
    }
}
