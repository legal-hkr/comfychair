package sh.hnet.comfychair.queue

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.repository.GalleryRepository
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
 * What the queue sheet shows about one of our jobs. Kept in memory only, never saved with the tracked jobs: the
 * prompt is private, and after a restart the sheet simply shows less.
 */
data class JobDescription(val prompt: String, val workflow: String)

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

    /**
     * Jobs of other clients in the server queue. The total and our own jobs arrive by different paths and may briefly
     * disagree, so this never goes below zero.
     */
    val otherJobCount: Int get() = (totalQueueSize - ownActiveJobCount).coerceAtLeast(0)

    /** Our own jobs waiting in the queue (not running yet). */
    val ownWaitingJobCount: Int get() = ownJobs.values.count { it.status == JobStatus.PENDING }

    /**
     * Whether a job of this screen waits in the queue. Only waiting jobs count: finished ones stay tracked until they
     * are cleaned up (also across an app restart) and must not make the screen look busy.
     */
    fun hasWaitingJob(ownerId: String): Boolean =
        ownJobs.values.any { it.ownerId == ownerId && it.status == JobStatus.PENDING }
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

    // Owners (screens) of our jobs that finished with results, for the "ready" notice and tab badges
    private val _readyOwners = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val readyOwners: SharedFlow<String> = _readyOwners.asSharedFlow()

    /**
     * One of our jobs finished successfully. Call before [markCompleted], which stops tracking it: completion alone
     * also follows errors and interruptions, so it can't tell a ready result.
     */
    fun notifyReady(promptId: String) {
        _queueState.value.ownJobs[promptId]?.let { _readyOwners.tryEmit(it.ownerId) }
    }

    // When each running job started (elapsed realtime), so the status card's timer survives leaving the screen
    private val startTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** When a running job started; first asked counts as the start if the server's start wasn't seen. */
    fun executionStartedAt(promptId: String): Long =
        startTimes.getOrPut(promptId) { android.os.SystemClock.elapsedRealtime() }

    // Descriptions of our jobs for the queue sheet, in memory only (see JobDescription)
    private val descriptions = java.util.concurrent.ConcurrentHashMap<String, JobDescription>()

    fun describeJob(promptId: String, description: JobDescription) {
        descriptions[promptId] = description
    }

    fun description(promptId: String): JobDescription? = descriptions[promptId]

    /**
     * Track a job under a new id, e.g. after it was submitted again and the server assigned one. Keeps its owner,
     * status and description.
     */
    @Synchronized
    fun rekeyJob(oldPromptId: String, newPromptId: String) {
        if (oldPromptId == newPromptId) return
        val currentState = _queueState.value
        val job = currentState.ownJobs[oldPromptId] ?: return
        DebugLogger.d(TAG, "Job ${Obfuscator.promptId(oldPromptId)} now tracked as ${Obfuscator.promptId(newPromptId)}")
        _queueState.value = currentState.copy(
            ownJobs = currentState.ownJobs - oldPromptId + (newPromptId to job.copy(promptId = newPromptId))
        )
        descriptions.remove(oldPromptId)?.let { descriptions[newPromptId] = it }
        startTimes.remove(oldPromptId)?.let { startTimes[newPromptId] = it }
    }

    /**
     * Follow a job moved to the front (removed and submitted again) under its id after the move. If it was dropped in
     * between (a queue poll didn't find it while it was off the server), it's tracked again as [job] was, with its
     * [description].
     */
    @Synchronized
    fun followMovedJob(job: TrackedJob, newPromptId: String, description: JobDescription?) {
        if (_queueState.value.ownJobs.containsKey(job.promptId)) {
            rekeyJob(job.promptId, newPromptId)
            return
        }
        DebugLogger.d(TAG, "Moved job ${Obfuscator.promptId(job.promptId)} tracked again as ${Obfuscator.promptId(newPromptId)}")
        registerJob(newPromptId, job.ownerId, job.contentType)
        descriptions.remove(job.promptId)
        description?.let { descriptions[newPromptId] = it }
    }

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

        val currentState = _queueState.value

        // Determine initial status - if this job is already executing, mark it as such
        val isAlreadyExecuting = currentState.executingPromptId == promptId
        val initialStatus = if (isAlreadyExecuting) JobStatus.EXECUTING else JobStatus.PENDING

        val job = TrackedJob(
            promptId = promptId,
            ownerId = ownerId,
            contentType = contentType,
            status = initialStatus
        )

        val newJobs = currentState.ownJobs + (promptId to job)

        // If this job is already executing (execution_start came before registration),
        // update the executing owner info now that we know it
        if (isAlreadyExecuting) {
            DebugLogger.d(TAG, "Job was already executing, updating owner to: $ownerId")
            _queueState.value = currentState.copy(
                ownJobs = newJobs,
                executingOwnerId = ownerId,
                executingContentType = contentType
            )
        } else {
            _queueState.value = currentState.copy(ownJobs = newJobs)
        }
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
        startTimes.putIfAbsent(promptId, android.os.SystemClock.elapsedRealtime())
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
        descriptions.remove(promptId)
        startTimes.remove(promptId)

        _queueState.value = currentState.copy(
            executingPromptId = if (isCurrentlyExecuting) null else currentState.executingPromptId,
            executingOwnerId = if (isCurrentlyExecuting) null else currentState.executingOwnerId,
            executingContentType = if (isCurrentlyExecuting) null else currentState.executingContentType,
            ownJobs = newJobs
        )

        // Trigger gallery refresh - single source of truth for completion-triggered refreshes
        // Handles both in-app jobs and external jobs (from other clients)
        DebugLogger.d(TAG, "Triggering gallery refresh for job completion")
        GalleryRepository.getInstance().refresh()
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
        descriptions.remove(promptId)
        startTimes.remove(promptId)
    }

    /**
     * Update queue size from WebSocket status message.
     * The status message contains exec_info with the queue remaining count.
     *
     * When the queue size decreases, it indicates a job completed.
     * For external jobs (from other clients), we don't receive `executing` or
     * `execution_success` messages, so we detect completion via queue size decrease.
     *
     * @param queueRemaining Number of items in the server queue
     */
    @Synchronized
    fun updateFromStatus(queueRemaining: Int) {
        val currentState = _queueState.value
        val previousSize = currentState.totalQueueSize

        if (previousSize != queueRemaining) {
            DebugLogger.d(TAG, "Queue size updated: $queueRemaining")
            _queueState.value = currentState.copy(totalQueueSize = queueRemaining)

            // Detect job completion via queue size decrease
            // This handles external jobs that don't send execution messages to our client
            if (queueRemaining < previousSize) {
                val completedCount = previousSize - queueRemaining
                DebugLogger.d(TAG, "Detected $completedCount job(s) completed via queue decrease")
                // Trigger gallery refresh for external job completions
                GalleryRepository.getInstance().refresh()
            }
        }
    }

    /**
     * Bring the tracked jobs in line with the server queue: its size, the job running, and our jobs that left it
     * (completed, removed) dropped, unless they're done and awaiting cleanup.
     */
    fun updateFromServerQueue(snapshot: QueueSnapshot) {
        val currentState = _queueState.value
        var newState = currentState

        val totalCount = snapshot.running.size + snapshot.pending.size
        if (newState.totalQueueSize != totalCount) {
            newState = newState.copy(totalQueueSize = totalCount)
        }

        // The job running on the server
        val runningPromptId = snapshot.running.firstOrNull()
        if (runningPromptId != null) {
            if (newState.executingPromptId != runningPromptId) {
                val job = newState.ownJobs[runningPromptId]
                newState = newState.copy(
                    executingPromptId = runningPromptId,
                    executingOwnerId = job?.ownerId,
                    executingContentType = job?.contentType
                )
            }
        } else if (newState.executingPromptId != null) {
            // No running jobs on server but we thought something was executing
            newState = newState.copy(executingPromptId = null, executingOwnerId = null, executingContentType = null)
        }

        // Keep jobs that are in the queue OR are not pending/executing (completed/failed awaiting cleanup)
        val (validJobs, staleJobs) = newState.ownJobs.entries.partition { (promptId, job) ->
            promptId in snapshot || (job.status != JobStatus.PENDING && job.status != JobStatus.EXECUTING)
        }
        if (staleJobs.isNotEmpty()) {
            DebugLogger.d(TAG, "Removed ${staleJobs.size} stale jobs not found in server queue")
            staleJobs.forEach { startTimes.remove(it.key) }
            newState = newState.copy(ownJobs = validJobs.associate { it.key to it.value })
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
