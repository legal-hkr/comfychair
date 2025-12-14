package sh.hnet.comfychair.util

/**
 * Extracts metadata from MP4 files.
 * ComfyUI stores workflow data in MP4 metadata fields.
 */
object Mp4MetadataExtractor {

    /**
     * Extract the "prompt" metadata from an MP4 file.
     * Searches for the prompt JSON in the MP4 metadata boxes.
     *
     * @param bytes The raw MP4 file bytes
     * @return The prompt JSON string, or null if not found
     */
    fun extractPromptMetadata(bytes: ByteArray): String? {
        // Try to find prompt in moov/udta box structure
        val fromBoxes = extractFromBoxStructure(bytes)
        if (fromBoxes != null) return fromBoxes

        // Fallback: search for prompt JSON pattern in file
        return searchForPromptJson(bytes)
    }

    /**
     * Extract metadata by parsing MP4 box structure.
     * Looks for moov -> udta -> meta -> ilst or similar structures.
     */
    private fun extractFromBoxStructure(bytes: ByteArray): String? {
        var offset = 0

        while (offset + 8 <= bytes.size) {
            val boxSize = readInt32BE(bytes, offset)
            val boxType = readBoxType(bytes, offset + 4)

            if (boxSize < 8) break

            when (boxType) {
                "moov" -> {
                    // Parse inside moov box for udta
                    val result = parseInsideBox(bytes, offset + 8, boxSize - 8)
                    if (result != null) return result
                }
                "udta" -> {
                    // User data box - might contain metadata directly
                    val result = parseUdtaBox(bytes, offset + 8, boxSize - 8)
                    if (result != null) return result
                }
            }

            offset += boxSize
        }

        return null
    }

    /**
     * Parse inside a container box looking for metadata.
     */
    private fun parseInsideBox(bytes: ByteArray, start: Int, length: Int): String? {
        var offset = start
        val end = start + length

        while (offset + 8 <= end) {
            val boxSize = readInt32BE(bytes, offset)
            val boxType = readBoxType(bytes, offset + 4)

            if (boxSize < 8 || offset + boxSize > end) break

            when (boxType) {
                "udta" -> {
                    val result = parseUdtaBox(bytes, offset + 8, boxSize - 8)
                    if (result != null) return result
                }
                "meta" -> {
                    // meta box has 4 extra bytes (version/flags) after the header
                    val result = parseMetaBox(bytes, offset + 12, boxSize - 12)
                    if (result != null) return result
                }
            }

            offset += boxSize
        }

        return null
    }

    /**
     * Parse udta (user data) box for prompt metadata.
     */
    private fun parseUdtaBox(bytes: ByteArray, start: Int, length: Int): String? {
        var offset = start
        val end = start + length

        while (offset + 8 <= end) {
            val boxSize = readInt32BE(bytes, offset)
            val boxType = readBoxType(bytes, offset + 4)

            if (boxSize < 8 || offset + boxSize > end) break

            when (boxType) {
                "meta" -> {
                    // meta box has version/flags
                    val result = parseMetaBox(bytes, offset + 12, boxSize - 12)
                    if (result != null) return result
                }
                // Custom metadata might be stored directly in udta
                else -> {
                    // Check if this box contains prompt data
                    val dataStart = offset + 8
                    val dataLength = boxSize - 8
                    if (dataLength > 0) {
                        val content = tryExtractPromptFromData(bytes, dataStart, dataLength)
                        if (content != null) return content
                    }
                }
            }

            offset += boxSize
        }

        return null
    }

    /**
     * Parse meta box for ilst or other metadata containers.
     */
    private fun parseMetaBox(bytes: ByteArray, start: Int, length: Int): String? {
        var offset = start
        val end = start + length

        while (offset + 8 <= end) {
            val boxSize = readInt32BE(bytes, offset)
            val boxType = readBoxType(bytes, offset + 4)

            if (boxSize < 8 || offset + boxSize > end) break

            when (boxType) {
                "ilst" -> {
                    val result = parseIlstBox(bytes, offset + 8, boxSize - 8)
                    if (result != null) return result
                }
            }

            offset += boxSize
        }

        return null
    }

    /**
     * Parse ilst (item list) box for metadata items.
     */
    private fun parseIlstBox(bytes: ByteArray, start: Int, length: Int): String? {
        var offset = start
        val end = start + length

        while (offset + 8 <= end) {
            val boxSize = readInt32BE(bytes, offset)
            val boxType = readBoxType(bytes, offset + 4)

            if (boxSize < 8 || offset + boxSize > end) break

            // Look for "data" sub-box
            val dataStart = offset + 8
            val dataEnd = offset + boxSize

            var dataOffset = dataStart
            while (dataOffset + 16 <= dataEnd) {
                val dataBoxSize = readInt32BE(bytes, dataOffset)
                val dataBoxType = readBoxType(bytes, dataOffset + 4)

                if (dataBoxType == "data" && dataBoxSize > 16) {
                    // data box: size(4) + type(4) + type indicator(4) + locale(4) + value
                    val valueStart = dataOffset + 16
                    val valueLength = dataBoxSize - 16

                    if (valueLength > 0 && valueStart + valueLength <= bytes.size) {
                        val value = String(bytes, valueStart, valueLength, Charsets.UTF_8)
                        if (value.trimStart().startsWith("{") && value.contains("class_type")) {
                            return value.trim()
                        }
                    }
                }

                if (dataBoxSize < 8) break
                dataOffset += dataBoxSize
            }

            offset += boxSize
        }

        return null
    }

    /**
     * Try to extract prompt JSON from raw data.
     */
    private fun tryExtractPromptFromData(bytes: ByteArray, start: Int, length: Int): String? {
        if (length < 10) return null

        val content = String(bytes, start, length, Charsets.UTF_8)
        if (content.trimStart().startsWith("{") && content.contains("class_type")) {
            return content.trim()
        }

        return null
    }

    /**
     * Fallback: Search for prompt JSON pattern directly in the file.
     * This handles cases where metadata might be stored in non-standard locations.
     */
    private fun searchForPromptJson(bytes: ByteArray): String? {
        // Search for the start of a JSON object that looks like ComfyUI workflow
        val searchPattern = "\"class_type\""
        val patternBytes = searchPattern.toByteArray(Charsets.UTF_8)

        for (i in 0 until bytes.size - patternBytes.size) {
            var found = true
            for (j in patternBytes.indices) {
                if (bytes[i + j] != patternBytes[j]) {
                    found = false
                    break
                }
            }

            if (found) {
                // Found class_type, search backwards for opening brace
                var jsonStart = i - 1
                var braceCount = 0
                while (jsonStart >= 0) {
                    when (bytes[jsonStart].toInt().toChar()) {
                        '{' -> {
                            braceCount++
                            // Check if this might be the root object
                            if (braceCount == 1) {
                                // Try to parse from here
                                val jsonContent = extractJsonFromPosition(bytes, jsonStart)
                                if (jsonContent != null && jsonContent.contains("class_type")) {
                                    return jsonContent
                                }
                            }
                        }
                    }
                    jsonStart--
                }
            }
        }

        return null
    }

    /**
     * Extract a complete JSON object starting from a given position.
     */
    private fun extractJsonFromPosition(bytes: ByteArray, start: Int): String? {
        if (start >= bytes.size || bytes[start].toInt().toChar() != '{') return null

        var braceCount = 0
        var inString = false
        var escape = false
        var end = start

        while (end < bytes.size) {
            val c = bytes[end].toInt().toChar()

            if (escape) {
                escape = false
            } else if (c == '\\' && inString) {
                escape = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                when (c) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            return String(bytes, start, end - start + 1, Charsets.UTF_8)
                        }
                    }
                }
            }

            end++
        }

        return null
    }

    /**
     * Read a 32-bit big-endian integer from byte array.
     */
    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    /**
     * Read a 4-character box type from byte array.
     */
    private fun readBoxType(bytes: ByteArray, offset: Int): String {
        if (offset + 4 > bytes.size) return ""
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }
}
