package sh.hnet.comfychair.queue

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.viewmodel.ContentType

class QueueStateTest {

    private fun job(id: String, status: JobStatus) = id to TrackedJob(id, "TEXT_TO_IMAGE", ContentType.IMAGE, status)

    @Test
    fun othersAreTheRestOfTheServerQueue() {
        val state = QueueState(
            totalQueueSize = 5,
            ownJobs = mapOf(job("a", JobStatus.EXECUTING), job("b", JobStatus.PENDING))
        )
        assertEquals(2, state.ownActiveJobCount)
        assertEquals(3, state.otherJobCount)
    }

    @Test
    fun finishedOwnJobsDoNotCount() {
        val state = QueueState(
            totalQueueSize = 1,
            ownJobs = mapOf(job("a", JobStatus.COMPLETED), job("b", JobStatus.FAILED), job("c", JobStatus.PENDING))
        )
        assertEquals(1, state.ownActiveJobCount)
        assertEquals(0, state.otherJobCount)
    }

    @Test
    fun othersNeverGoNegativeWhileCountsDisagree() {
        // Our job is registered before the server's queue size reaches us
        val state = QueueState(totalQueueSize = 0, ownJobs = mapOf(job("a", JobStatus.PENDING)))
        assertEquals(0, state.otherJobCount)
    }

    @Test
    fun onlyWaitingJobsMakeAScreenBusy() {
        // After a restart, finished jobs stay tracked until they are cleaned up
        val finished = QueueState(ownJobs = mapOf(job("a", JobStatus.COMPLETED), job("b", JobStatus.FAILED)))
        assertEquals(false, finished.hasWaitingJob("TEXT_TO_IMAGE"))

        val waiting = QueueState(ownJobs = mapOf(job("a", JobStatus.COMPLETED), job("c", JobStatus.PENDING)))
        assertEquals(true, waiting.hasWaitingJob("TEXT_TO_IMAGE"))
        assertEquals(false, waiting.hasWaitingJob("IMAGE_TO_VIDEO"))
    }
}
