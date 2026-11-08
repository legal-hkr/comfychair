package sh.hnet.comfychair.queue

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.workflow.NodeTypeRegistry

class QueueSnapshotPromptsTest {

    private fun job(number: Int, id: String, positive: String, negative: String) = """
        [$number, "$id", {
            "1": {"class_type": "CLIPTextEncode", "inputs": {"text": "$positive", "clip": ["4", 1]}},
            "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "$negative", "clip": ["4", 1]}},
            "3": {"class_type": "KSampler", "inputs": {"positive": ["1", 0], "negative": ["2", 0], "seed": 1}}
        }, {"client_id": "other"}, ["9"]]
    """

    @Test
    fun otherClientsJobsKeepTheirPositivePrompts() {
        val queue = JSONObject(
            """{"queue_running": [${job(5, "run-a", "a lighthouse at dusk", "blurry")}],
                "queue_pending": [${job(7, "wait-b", "a red cube", "low quality")}]}"""
        )
        val snapshot = QueueSnapshot.parseWithPrompts(queue, NodeTypeRegistry())
        assertEquals(listOf("run-a"), snapshot.running)
        assertEquals(listOf("wait-b"), snapshot.pending)
        assertEquals(mapOf("run-a" to "a lighthouse at dusk", "wait-b" to "a red cube"), snapshot.prompts)
        // The plain parse keeps none
        assertEquals(emptyMap<String, String>(), QueueSnapshot.parse(queue).prompts)
    }
}
