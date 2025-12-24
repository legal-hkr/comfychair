package sh.hnet.comfychair.queue

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.viewmodel.ContentType

/**
 * Status of a tracked job in the queue system.
 */
enum class JobStatus {
    PENDING,    // Job submitted, waiting in queue
    EXECUTING,  // Job currently being processed
    COMPLETED,  // Job finished successfully
    FAILED      // Job failed
}

/**
 * Represents a job tracked by the JobRegistry.
 */
data class TrackedJob(
    val promptId: String,
    val ownerId: String,
    val contentType: ContentType,
    val status: JobStatus
)

/**
 * Represents the current state of the queue system.
 * This state is observable and updates in real-time based on WebSocket events.
 */
data class QueueState(
    /** The prompt ID of the currently executing job (if any) */
    val executingPromptId: String? = null,
    /** The owner ID of the currently executing job (for preview routing) */
    val executingOwnerId: String? = null,
    /** The content type of the currently executing job */
    val executingContentType: ContentType? = null,
    /** Total number of jobs in the server queue (all clients) */
    val totalQueueSize: Int = 0,
    /** Our tracked jobs by promptId */
    val ownJobs: Map<String, TrackedJob> = emptyMap()
) {
    /** Whether any job is currently executing */
    val isExecuting: Boolean get() = executingPromptId != null

    /** Number of our own jobs that are still pending or executing */
    val ownActiveJobCount: Int get() = ownJobs.values.count {
        it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING
    }
}

/**
 * Central singleton for tracking jobs and server queue state.
 *
 * JobRegistry provides:
 * - Unified job tracking across all generation screens
 * - Real-time server queue size updates
 * - Preview routing based on which screen submitted the executing job
 * - Persistence across app restart with server validation
 *
 * Architecture:
 * - ConnectionManager notifies JobRegistry of WebSocket events
 * - GenerationViewModel registers jobs when submitting workflows
 * - Generation screens observe queueState for UI updates
 */
object JobRegistry {
    private const val TAG = "JobRegistry"
    private const val PREFS_NAME = "JobRegistryPrefs"
    private const val PREF_JOBS_JSON = "jobs"

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    /**
     * Register a new job when a workflow is submitted.
     * Call this immediately after receiving a successful prompt submission response.
     *
     * @param promptId The prompt ID returned by the server
     * @param ownerId The owner ID (e.g., "TEXT_TO_IMAGE", "IMAGE_TO_VIDEO")
     * @param contentType The type of content being generated
     */
    @Synchronized
    fun registerJob(promptId: String, ownerId: String, contentType: ContentType) {
        DebugLogger.i(TAG, "Registering job: ${Obfuscator.promptId(promptId)} (owner: $ownerId, type: $contentType)")

        val job = TrackedJob(
            promptId = promptId,
            ownerId = ownerId,
            contentType = contentType,
            status = JobStatus.PENDING
        )

        val currentState = _queueState.value
        val newJobs = currentState.ownJobs + (promptId to job)

        _queueState.value = currentState.copy(ownJobs = newJobs)
    }

    /**
     * Find the owner of a job by its prompt ID.
     * Used for routing previews and completion events to the correct screen.
     *
     * @return The owner ID, or null if not found
     */
    fun findOwner(promptId: String): String? {
        return _queueState.value.ownJobs[promptId]?.ownerId
    }

    /**
     * Find the content type of a job by its prompt ID.
     *
     * @return The content type, or null if not found
     */
    fun findContentType(promptId: String): ContentType? {
        return _queueState.value.ownJobs[promptId]?.contentType
    }

    /**
     * Mark a job as executing when execution_start is received.
     * This updates the executingPromptId/ownerId for preview routing.
     *
     * @param promptId The prompt ID from the execution_start event
     */
    @Synchronized
    fun markExecuting(promptId: String) {
        val currentState = _queueState.value
        val job = currentState.ownJobs[promptId]

        if (job != null) {
            DebugLogger.i(TAG, "Job executing: ${Obfuscator.promptId(promptId)} (owner: ${job.ownerId})")

            val updatedJob = job.copy(status = JobStatus.EXECUTING)
            val newJobs = currentState.ownJobs + (promptId to updatedJob)

            _queueState.value = currentState.copy(
                executingPromptId = promptId,
                executingOwnerId = job.ownerId,
                executingContentType = job.contentType,
                ownJobs = newJobs
            )
        } else {
            // Job from another client - just track execution state
            DebugLogger.d(TAG, "External job executing: ${Obfuscator.promptId(promptId)}")
            _queueState.value = currentState.copy(
                executingPromptId = promptId,
                executingOwnerId = null,
                executingContentType = null
            )
        }
    }

    /**
     * Mark a job as completed when execution finishes (executing with node=null).
     * Clears the executing state and removes the job from tracking.
     *
     * @param promptId The prompt ID from the executing event
     */
    @Synchronized
    fun markCompleted(promptId: String) {
        val currentState = _queueState.value
        val job = currentState.ownJobs[promptId]

        if (job != null) {
            DebugLogger.i(TAG, "Job completed: ${Obfuscator.promptId(promptId)} (owner: ${job.ownerId})")
        } else {
            DebugLogger.d(TAG, "External job completed: ${Obfuscator.promptId(promptId)}")
        }

        // Clear executing state if this was the executing job
        val isCurrentlyExecuting = currentState.executingPromptId == promptId

        // Remove the job from tracking
        val newJobs = currentState.ownJobs - promptId

        _queueState.value = currentState.copy(
            executingPromptId = if (isCurrentlyExecuting) null else currentState.executingPromptId,
            executingOwnerId = if (isCurrentlyExecuting) null else currentState.executingOwnerId,
            executingContentType = if (isCurrentlyExecuting) null else currentState.executingContentType,
            ownJobs = newJobs
        )
    }

    /**
     * Mark a job as failed.
     *
     * @param promptId The prompt ID of the failed job
     */
    @Synchronized
    fun markFailed(promptId: String) {
        val currentState = _queueState.value
        val job = currentState.ownJobs[promptId]

        if (job != null) {
            DebugLogger.w(TAG, "Job failed: ${Obfuscator.promptId(promptId)} (owner: ${job.ownerId})")

            // Remove the failed job
            val newJobs = currentState.ownJobs - promptId

            // Clear executing state if this was the executing job
            val isCurrentlyExecuting = currentState.executingPromptId == promptId

            _queueState.value = currentState.copy(
                executingPromptId = if (isCurrentlyExecuting) null else currentState.executingPromptId,
                executingOwnerId = if (isCurrentlyExecuting) null else currentState.executingOwnerId,
                executingContentType = if (isCurrentlyExecuting) null else currentState.executingContentType,
                ownJobs = newJobs
            )
        }
    }

    /**
     * Remove a job from tracking.
     *
     * @param promptId The prompt ID to remove
     */
    @Synchronized
    fun removeJob(promptId: String) {
        val currentState = _queueState.value
        if (currentState.ownJobs.containsKey(promptId)) {
            DebugLogger.d(TAG, "Removing job: ${Obfuscator.promptId(promptId)}")
            val newJobs = currentState.ownJobs - promptId
            _queueState.value = currentState.copy(ownJobs = newJobs)
        }
    }

    /**
     * Update queue size from WebSocket status message.
     * The status message contains exec_info with the queue remaining count.
     *
     * @param queueRemaining Number of items in the server queue
     */
    @Synchronized
    fun updateFromStatus(queueRemaining: Int) {
        val currentState = _queueState.value
        if (currentState.totalQueueSize != queueRemaining) {
            DebugLogger.d(TAG, "Queue size updated: $queueRemaining")
            _queueState.value = currentState.copy(totalQueueSize = queueRemaining)
        }
    }

    /**
     * Update state from server queue response.
     * This provides detailed information about running and pending jobs.
     *
     * @param running The queue_running array from /queue endpoint
     * @param pending The queue_pending array from /queue endpoint
     */
    @Synchronized
    fun updateFromServerQueue(running: JSONArray?, pending: JSONArray?) {
        val runningCount = running?.length() ?: 0
        val pendingCount = pending?.length() ?: 0
        val totalCount = runningCount + pendingCount

        val currentState = _queueState.value
        var newState = currentState

        // Update total queue size
        if (newState.totalQueueSize != totalCount) {
            newState = newState.copy(totalQueueSize = totalCount)
        }

        // Find currently executing job from server queue
        if (running != null && running.length() > 0) {
            val firstRunning = running.optJSONArray(0)
            if (firstRunning != null && firstRunning.length() > 1) {
                val runningPromptId = firstRunning.optString(1)
                if (runningPromptId.isNotEmpty() && newState.executingPromptId != runningPromptId) {
                    // Update executing job based on server state
                    val job = newState.ownJobs[runningPromptId]
                    newState = newState.copy(
                        executingPromptId = runningPromptId,
                        executingOwnerId = job?.ownerId,
                        executingContentType = job?.contentType
                    )
                }
            }
        } else if (newState.executingPromptId != null && runningCount == 0) {
            // No running jobs on server but we thought something was executing
            newState = newState.copy(
                executingPromptId = null,
                executingOwnerId = null,
                executingContentType = null
            )
        }

        // Validate our tracked jobs against server queue
        val serverPromptIds = mutableSetOf<String>()
        running?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONArray(i)?.optString(1)?.takeIf { it.isNotEmpty() }?.let {
                    serverPromptIds.add(it)
                }
            }
        }
        pending?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONArray(i)?.optString(1)?.takeIf { it.isNotEmpty() }?.let {
                    serverPromptIds.add(it)
                }
            }
        }

        // Remove jobs that are no longer in the server queue (completed or removed)
        val validJobs = newState.ownJobs.filter { (promptId, job) ->
            val isInQueue = serverPromptIds.contains(promptId)
            val isPendingOrExecuting = job.status == JobStatus.PENDING || job.status == JobStatus.EXECUTING
            // Keep jobs that are in the queue OR are not pending/executing (completed/failed awaiting cleanup)
            isInQueue || !isPendingOrExecuting
        }

        if (validJobs.size != newState.ownJobs.size) {
            val removedCount = newState.ownJobs.size - validJobs.size
            DebugLogger.d(TAG, "Removed $removedCount stale jobs not found in server queue")
            newState = newState.copy(ownJobs = validJobs)
        }

        if (newState != currentState) {
            _queueState.value = newState
        }
    }

    /**
     * Save job state to SharedPreferences for persistence across app restart.
     *
     * @param context Application context
     */
    fun saveState(context: Context) {
        val currentState = _queueState.value
        val jobsArray = JSONArray()

        currentState.ownJobs.values.forEach { job ->
            val jobJson = JSONObject().apply {
                put("promptId", job.promptId)
                put("ownerId", job.ownerId)
                put("contentType", job.contentType.name)
                put("status", job.status.name)
            }
            jobsArray.put(jobJson)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(PREF_JOBS_JSON, jobsArray.toString())
            apply()
        }

        DebugLogger.d(TAG, "Saved ${currentState.ownJobs.size} jobs to preferences")
    }

    /**
     * Restore job state from SharedPreferences.
     * Should be called early in app startup.
     *
     * @param context Application context
     */
    fun restoreState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jobsJson = prefs.getString(PREF_JOBS_JSON, null) ?: return

        try {
            val jobsArray = JSONArray(jobsJson)
            val jobs = mutableMapOf<String, TrackedJob>()

            for (i in 0 until jobsArray.length()) {
                val jobObj = jobsArray.getJSONObject(i)
                val promptId = jobObj.getString("promptId")
                val ownerId = jobObj.getString("ownerId")
                val contentType = try {
                    ContentType.valueOf(jobObj.getString("contentType"))
                } catch (e: IllegalArgumentException) {
                    ContentType.IMAGE
                }
                val status = try {
                    JobStatus.valueOf(jobObj.getString("status"))
                } catch (e: IllegalArgumentException) {
                    JobStatus.PENDING
                }

                // Only restore pending/executing jobs (completed/failed don't need restoration)
                if (status == JobStatus.PENDING || status == JobStatus.EXECUTING) {
                    jobs[promptId] = TrackedJob(
                        promptId = promptId,
                        ownerId = ownerId,
                        contentType = contentType,
                        status = JobStatus.PENDING  // Reset to pending, will validate against server
                    )
                }
            }

            if (jobs.isNotEmpty()) {
                _queueState.value = _queueState.value.copy(ownJobs = jobs)
                DebugLogger.i(TAG, "Restored ${jobs.size} jobs from preferences")
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to restore jobs: ${e.message}")
        }
    }

    /**
     * Clear all state. Called on logout.
     */
    @Synchronized
    fun clear() {
        DebugLogger.i(TAG, "Clearing all job state")
        _queueState.value = QueueState()
    }

    /**
     * Clear persisted state. Called on logout.
     *
     * @param context Application context
     */
    fun clearPersistedState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        DebugLogger.d(TAG, "Cleared persisted job state")
    }
}
