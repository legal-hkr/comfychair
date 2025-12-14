package sh.hnet.comfychair.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * Extracts metadata from PNG files.
 * ComfyUI stores workflow data in PNG tEXt or zTXt chunks with keyword "prompt".
 */
object PngMetadataExtractor {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Extract the "prompt" metadata from a PNG file.
     * Parses PNG chunks to find tEXt or zTXt chunk with keyword "prompt".
     *
     * @param bytes The raw PNG file bytes
     * @return The prompt JSON string, or null if not found
     */
    fun extractPromptMetadata(bytes: ByteArray): String? {
        if (bytes.size < 8) return null

        // Verify PNG signature
        for (i in 0 until 8) {
            if (bytes[i] != PNG_SIGNATURE[i]) return null
        }

        var offset = 8 // Skip PNG signature

        while (offset + 12 <= bytes.size) {
            // Read chunk length (4 bytes, big-endian)
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            if (length < 0) break

            // Read chunk type (4 bytes)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)

            // Chunk data starts at offset + 8
            val dataStart = offset + 8
            val dataEnd = dataStart + length

            if (dataEnd > bytes.size) break

            when (type) {
                "tEXt" -> {
                    // tEXt chunk: keyword + null byte + text
                    val result = parseTextChunk(bytes, dataStart, length)
                    if (result?.first == "prompt") {
                        return result.second
                    }
                }
                "zTXt" -> {
                    // zTXt chunk: keyword + null byte + compression method + compressed text
                    val result = parseCompressedTextChunk(bytes, dataStart, length)
                    if (result?.first == "prompt") {
                        return result.second
                    }
                }
                "iTXt" -> {
                    // iTXt chunk: keyword + nulls + text (international text)
                    val result = parseInternationalTextChunk(bytes, dataStart, length)
                    if (result?.first == "prompt") {
                        return result.second
                    }
                }
                "IEND" -> {
                    // End of PNG
                    break
                }
            }

            // Move to next chunk (length + type + data + CRC)
            offset = dataEnd + 4
        }

        return null
    }

    /**
     * Parse a tEXt chunk.
     * Format: keyword (1-79 bytes) + null byte + text
     */
    private fun parseTextChunk(bytes: ByteArray, start: Int, length: Int): Pair<String, String>? {
        if (length < 2) return null

        // Find null separator
        var nullPos = -1
        for (i in start until start + length) {
            if (bytes[i] == 0.toByte()) {
                nullPos = i
                break
            }
        }

        if (nullPos == -1 || nullPos == start) return null

        val keyword = String(bytes, start, nullPos - start, Charsets.ISO_8859_1)
        val textStart = nullPos + 1
        val textLength = length - (textStart - start)

        if (textLength <= 0) return keyword to ""

        val text = String(bytes, textStart, textLength, Charsets.ISO_8859_1)
        return keyword to text
    }

    /**
     * Parse a zTXt chunk.
     * Format: keyword + null byte + compression method (1 byte) + compressed text
     */
    private fun parseCompressedTextChunk(bytes: ByteArray, start: Int, length: Int): Pair<String, String>? {
        if (length < 3) return null

        // Find null separator
        var nullPos = -1
        for (i in start until start + length) {
            if (bytes[i] == 0.toByte()) {
                nullPos = i
                break
            }
        }

        if (nullPos == -1 || nullPos == start) return null

        val keyword = String(bytes, start, nullPos - start, Charsets.ISO_8859_1)

        // Compression method should be 0 (zlib deflate)
        val compressionMethod = bytes[nullPos + 1].toInt() and 0xFF
        if (compressionMethod != 0) return null

        val compressedStart = nullPos + 2
        val compressedLength = length - (compressedStart - start)

        if (compressedLength <= 0) return keyword to ""

        // Decompress using zlib
        val decompressed = decompress(bytes, compressedStart, compressedLength) ?: return null
        val text = String(decompressed, Charsets.ISO_8859_1)

        return keyword to text
    }

    /**
     * Parse an iTXt chunk.
     * Format: keyword + null + compression flag + compression method + language tag + null + translated keyword + null + text
     */
    private fun parseInternationalTextChunk(bytes: ByteArray, start: Int, length: Int): Pair<String, String>? {
        if (length < 5) return null

        // Find keyword null separator
        var pos = start
        while (pos < start + length && bytes[pos] != 0.toByte()) pos++
        if (pos >= start + length) return null

        val keyword = String(bytes, start, pos - start, Charsets.UTF_8)
        pos++ // Skip null

        if (pos + 2 > start + length) return null

        val compressionFlag = bytes[pos].toInt() and 0xFF
        val compressionMethod = bytes[pos + 1].toInt() and 0xFF
        pos += 2

        // Skip language tag
        while (pos < start + length && bytes[pos] != 0.toByte()) pos++
        if (pos >= start + length) return null
        pos++ // Skip null

        // Skip translated keyword
        while (pos < start + length && bytes[pos] != 0.toByte()) pos++
        if (pos >= start + length) return null
        pos++ // Skip null

        val textLength = (start + length) - pos
        if (textLength <= 0) return keyword to ""

        val text = if (compressionFlag == 1 && compressionMethod == 0) {
            // Compressed
            val decompressed = decompress(bytes, pos, textLength) ?: return null
            String(decompressed, Charsets.UTF_8)
        } else {
            // Not compressed
            String(bytes, pos, textLength, Charsets.UTF_8)
        }

        return keyword to text
    }

    /**
     * Decompress zlib deflate data.
     */
    private fun decompress(bytes: ByteArray, start: Int, length: Int): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(bytes, start, length)

            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)

            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                outputStream.write(buffer, 0, count)
            }

            inflater.end()
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
