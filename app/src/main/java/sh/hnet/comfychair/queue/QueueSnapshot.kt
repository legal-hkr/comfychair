package sh.hnet.comfychair.queue

import org.json.JSONArray
import org.json.JSONObject

/**
 * The server queue as /queue returns it, reduced to job ids in order. The response also carries every job's full
 * workflow, other clients' prompts included; nothing of it is kept here, so it can't end up on screen or in a log.
 *
 * @param running Jobs running now (normally one)
 * @param pending Waiting jobs in the order they will run
 */
data class QueueSnapshot(val running: List<String>, val pending: List<String>) {

    /** Whether a job is still on the server: running or waiting. */
    operator fun contains(promptId: String): Boolean = promptId in running || promptId in pending

    /** Position of a waiting job, 1 = next to run; null if it doesn't wait. */
    fun positionOf(promptId: String): Int? = pending.indexOf(promptId).takeIf { it >= 0 }?.plus(1)

    companion object {
        /** Entries are `[number, prompt_id, prompt, extra_data, outputs…]`; the number orders the queue. */
        fun parse(queueJson: JSONObject): QueueSnapshot =
            QueueSnapshot(ids(queueJson.optJSONArray("queue_running")), ids(queueJson.optJSONArray("queue_pending")))

        private fun ids(entries: JSONArray?): List<String> {
            if (entries == null) return emptyList()
            return (0 until entries.length())
                .mapNotNull { entries.optJSONArray(it) }
                .mapNotNull { entry ->
                    val id = entry.optString(1).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    entry.optDouble(0, Double.MAX_VALUE) to id
                }
                .sortedBy { it.first }
                .map { it.second }
        }

        /** The workflow of a waiting job, to submit it again (moving it to the front). */
        fun pendingPrompt(queueJson: JSONObject, promptId: String): JSONObject? {
            val pending = queueJson.optJSONArray("queue_pending") ?: return null
            for (i in 0 until pending.length()) {
                val entry = pending.optJSONArray(i) ?: continue
                if (entry.optString(1) == promptId) return entry.optJSONObject(2)
            }
            return null
        }
    }
}
