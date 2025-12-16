package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.model.WorkflowValues

/**
 * Storage for per-workflow user values.
 * Each workflow's values are stored as a JSON string keyed by workflow ID.
 */
class WorkflowValuesStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save values for a specific workflow.
     */
    fun saveValues(workflowId: String, values: WorkflowValues) {
        val json = WorkflowValues.toJson(values)
        prefs.edit().putString(keyFor(workflowId), json).apply()
    }

    /**
     * Load saved values for a specific workflow.
     * Returns null if no values have been saved for this workflow.
     */
    fun loadValues(workflowId: String): WorkflowValues? {
        val json = prefs.getString(keyFor(workflowId), null) ?: return null
        return try {
            WorkflowValues.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete saved values for a specific workflow.
     */
    fun deleteValues(workflowId: String) {
        prefs.edit().remove(keyFor(workflowId)).apply()
    }

    /**
     * Clear all saved workflow values.
     * Called when user restores defaults.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(workflowId: String): String = "workflow_$workflowId"

    companion object {
        private const val PREFS_NAME = "WorkflowValuesPrefs"
    }
}
