package sh.hnet.comfychair.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * WorkManager periodic worker that checks for completed generations
 * that weren't notified (e.g., because the app was killed during generation).
 *
 * This acts as a fallback mechanism to ensure notifications are eventually delivered.
 *
 * Runs every 15 minutes (minimum WorkManager interval).
 */
class GenerationCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GenCheckWorker"

        /**
         * Schedule the periodic generation check worker.
         * Safe to call multiple times - WorkManager deduplicates.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<GenerationCheckWorker>(
                repeatInterval = 5,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(NotificationConstants.WORK_TAG_GENERATION_CHECK)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NotificationConstants.WORK_NAME_GENERATION_CHECK,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

            DebugLogger.d(TAG, "Scheduled periodic generation check")
        }

        /**
         * Cancel the periodic generation check worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NotificationConstants.WORK_NAME_GENERATION_CHECK)
            DebugLogger.d(TAG, "Cancelled periodic generation check")
        }
    }

    override suspend fun doWork(): Result {
        DebugLogger.d(TAG, "Running generation check")

        // Check if we have pending generation info
        if (!NotificationHelper.hasPendingGeneration(applicationContext)) {
            DebugLogger.d(TAG, "No pending generation to check")
            return Result.success()
        }

        // Check if connected to a server
        if (!ConnectionManager.isConnected) {
            DebugLogger.d(TAG, "Not connected, skipping check")
            return Result.retry()
        }

        val pendingGeneration = NotificationHelper.getPendingGeneration(applicationContext)
        if (pendingGeneration == null) {
            DebugLogger.d(TAG, "No pending generation info")
            return Result.success()
        }

        DebugLogger.i(TAG, "Checking pending generation: promptId=${Obfuscator.promptId(pendingGeneration.promptId)}, ownerId=${pendingGeneration.ownerId}")

        val client = ConnectionManager.clientOrNull
        if (client == null) {
            DebugLogger.w(TAG, "ComfyUIClient not available")
            return Result.retry()
        }

        // Check if this prompt has already been notified
        if (NotificationHelper.hasNotified(applicationContext, pendingGeneration.promptId)) {
            DebugLogger.d(TAG, "Already notified for this promptId, clearing pending")
            NotificationHelper.clearPendingGeneration(applicationContext)
            return Result.success()
        }

        // Poll the server to check if the generation is complete
        return try {
            val isComplete = checkGenerationComplete(client, pendingGeneration.promptId)
            if (isComplete) {
                DebugLogger.i(TAG, "Pending generation is complete, showing notification")
                val isVideo = pendingGeneration.contentType == "VIDEO"
                NotificationHelper.showCompletionNotification(
                    context = applicationContext,
                    promptId = pendingGeneration.promptId,
                    ownerId = pendingGeneration.ownerId,
                    contentType = pendingGeneration.contentType,
                    isVideo = isVideo
                )
                NotificationHelper.markNotified(applicationContext, pendingGeneration.promptId)
                NotificationHelper.clearPendingGeneration(applicationContext)
                Result.success()
            } else {
                DebugLogger.d(TAG, "Generation not yet complete")
                Result.success()
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Error checking generation: ${e.message}")
            Result.retry()
        }
    }

    /**
     * Check if a generation is complete by querying the server history.
     */
    private suspend fun checkGenerationComplete(client: ComfyUIClient, promptId: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            client.fetchHistory(promptId) { historyJson ->
                if (continuation.isActive) {
                    // History exists = generation complete
                    continuation.resume(historyJson != null)
                }
            }
        }
    }

    private object Obfuscator {
        fun promptId(id: String): String = if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else id
    }
}
