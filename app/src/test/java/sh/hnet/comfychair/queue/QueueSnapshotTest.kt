package sh.hnet.comfychair.queue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSnapshotTest {

    // Shaped like ComfyUI's /queue: [number, prompt_id, prompt, extra_data, outputs]; front-of-queue jobs get
    // negative numbers
    private val json = JSONObject(
        """
        {
          "queue_running": [[5, "run", {"1": {"class_type": "KSampler"}}, {}, []]],
          "queue_pending": [
            [7, "later", {"1": {}}, {}, []],
            [-1, "front", {"2": {"class_type": "SaveImage"}}, {}, []],
            [6, "next", {}, {}, []]
          ]
        }
        """
    )

    @Test
    fun waitingJobsAreOrderedByTheirNumber() {
        val snapshot = QueueSnapshot.parse(json)
        assertEquals(listOf("run"), snapshot.running)
        assertEquals(listOf("front", "next", "later"), snapshot.pending)
    }

    @Test
    fun positionsCountFromOneForTheNextJob() {
        val snapshot = QueueSnapshot.parse(json)
        assertEquals(1, snapshot.positionOf("front"))
        assertEquals(3, snapshot.positionOf("later"))
        assertNull(snapshot.positionOf("run"))
    }

    @Test
    fun pendingPromptIsFoundOnlyForWaitingJobs() {
        assertEquals("SaveImage", QueueSnapshot.pendingPrompt(json, "front")!!.getJSONObject("2").getString("class_type"))
        assertNull(QueueSnapshot.pendingPrompt(json, "run"))
    }

    @Test
    fun emptyOrMissingArraysGiveAnEmptyQueue() {
        val snapshot = QueueSnapshot.parse(JSONObject("{}"))
        assertEquals(emptyList<String>(), snapshot.running)
        assertEquals(emptyList<String>(), snapshot.pending)
    }
}
