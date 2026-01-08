package sh.hnet.comfychair.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Utility functions for UUID generation.
 */
object UuidUtils {

    /**
     * Generate a deterministic UUID from a seed string.
     * Same seed always produces the same UUID.
     * Used for built-in workflows to ensure stable IDs across app versions.
     */
    fun generateDeterministicId(seed: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(StandardCharsets.UTF_8))
        // Use first 16 bytes to construct UUID
        val msb = bytes.slice(0..7).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val lsb = bytes.slice(8..15).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return UUID(msb, lsb).toString()
    }

    /**
     * Generate a random UUID.
     * Used for user-created workflows and servers.
     */
    fun generateRandomId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Generate a unique filename for image uploads to prevent race conditions
     * when multiple jobs are queued rapidly. Uses timestamp for uniqueness.
     *
     * @param prefix The base name for the file (e.g., "editing_source", "itv_source")
     * @param extension The file extension without dot (default: "png")
     * @return A unique filename like "editing_source_1704729600000.png"
     */
    fun generateUniqueUploadFilename(prefix: String, extension: String = "png"): String {
        return "${prefix}_${System.currentTimeMillis()}.${extension}"
    }
}
