package sh.hnet.comfychair.util

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/**
 * A RequestBody wrapper that tracks upload progress by monitoring bytes written.
 * Used for stall detection - if no bytes are written for an extended period,
 * the transfer is considered stalled.
 *
 * @param delegate The original RequestBody to wrap
 * @param onProgress Optional callback invoked after each write with (bytesWritten, totalBytes)
 */
class ProgressTrackingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
) : RequestBody() {

    /**
     * Timestamp of the last progress update (bytes written).
     * Used by watchdog coroutine to detect stalls.
     */
    @Volatile
    var lastProgressTime: Long = System.currentTimeMillis()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val countingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                lastProgressTime = System.currentTimeMillis()
                onProgress?.invoke(bytesWritten, contentLength())
            }
        }
        val bufferedCountingSink = countingSink.buffer()
        delegate.writeTo(bufferedCountingSink)
        bufferedCountingSink.flush()
    }
}
