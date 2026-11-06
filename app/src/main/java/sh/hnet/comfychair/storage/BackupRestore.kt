package sh.hnet.comfychair.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator

/**
 * Restoring a backup file, the same from Settings and from the welcome wizard: reading the file, checking it, restoring
 * it and bringing the running app up to date (workflows, caches, prompt libraries, connection).
 */
object BackupRestore {
    private const val TAG = "BackupRestore"

    /** A backup file read from the device: its text and its name as the file picker shows it. */
    data class BackupFile(val json: String, val displayName: String?)

    /** Reads a picked file; null when it can't be opened. */
    suspend fun read(context: Context, uri: Uri): BackupFile? = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            json?.let { BackupFile(it, displayName(context, uri)) }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to read backup file: ${Obfuscator.error(e)}")
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** What the file is and what it would bring back, without writing anything. */
    suspend fun check(context: Context, json: String): BackupCheck =
        withContext(Dispatchers.Default) { BackupManager(context).check(json) }

    /**
     * Restores the backup and brings the running app up to date. A partial restore is reported as an error with the
     * skipped items behind Show; the caller reports success and failures in its own way.
     */
    suspend fun restore(context: Context, json: String): RestoreResult {
        val appContext = context.applicationContext
        val result = withContext(Dispatchers.IO) { BackupManager(appContext).restoreBackup(json) }
        if (result is RestoreResult.Success) {
            // The welcome wizard may restore before anything loaded the workflows
            WorkflowManager.initialize(appContext)
            if (result.serversChanged) {
                // Disconnects and clears all caches, and reloads the workflows
                ConnectionManager.invalidateForRestore()
            } else {
                // Just clear in-memory caches (disk files already cleared by BackupManager)
                MediaCache.clearAll()
                MediaStateHolder.clearAll()
                // Reload workflows to pick up restored user workflows
                WorkflowManager.reloadWorkflows()
            }
            // Written straight to the preferences: the prompt libraries on the screens refresh
            PromptPresetStorage.notifyChanged()
            reportSkipped(appContext, result)
        }
        return result
    }

    private fun reportSkipped(context: Context, result: RestoreResult.Success) {
        if (result.skippedWorkflows.isEmpty() && result.skippedPrompts.isEmpty()) return
        val details = buildList {
            if (result.skippedWorkflows.isNotEmpty()) {
                add(context.getString(R.string.label_backup_skipped_workflows, result.skippedWorkflows.joinToString(", ")))
            }
            if (result.skippedPrompts.isNotEmpty()) {
                add(context.getString(R.string.label_backup_skipped_prompts, result.skippedPrompts.joinToString(", ")))
            }
        }
        AppErrors.report(AppError(context.getString(R.string.error_backup_restore_partial), details = details))
    }
}
