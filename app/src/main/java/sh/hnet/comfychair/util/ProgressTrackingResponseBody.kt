package sh.hnet.comfychair.util

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * A ResponseBody wrapper that tracks download progress by monitoring bytes read.
 * Used for stall detection - if no bytes are read for an extended period,
 * the transfer is considered stalled.
 *
 * @param delegate The original ResponseBody to wrap
 * @param onProgress Optional callback invoked after each read with (bytesRead, totalBytes)
 */
class ProgressTrackingResponseBody(
    private val delegate: ResponseBody,
    private val onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
) : ResponseBody() {

    /**
     * Timestamp of the last progress update (bytes read).
     * Used by watchdog coroutine to detect stalls.
     */
    @Volatile
    var lastProgressTime: Long = System.currentTimeMillis()

    /** Cached buffered source to ensure consistent tracking across multiple source() calls */
    private val bufferedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            var bytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read != -1L) {
                    bytesRead += read
                    lastProgressTime = System.currentTimeMillis()
                    onProgress?.invoke(bytesRead, contentLength())
                }
                return read
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = bufferedSource
}
