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
     * The name a source uploaded to the server's input folder gets: [prefix] and a hash of the exact [bytes], so the
     * same content is the same file (uploaded once, issue #62) and different content never shares a name, however
     * quickly jobs follow each other.
     *
     * @param extension The file extension without dot (default: "png")
     * @return A name like "editing_source_3f2a…c41d.png"
     */
    fun contentUploadFilename(prefix: String, bytes: ByteArray, extension: String = "png"): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return "${prefix}_$hex.$extension"
    }
}
