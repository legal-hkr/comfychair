package sh.hnet.comfychair.storage

import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.util.ValidationUtils

/**
 * Validation utilities for backup/restore operations.
 * Validates JSON structure, field values, and sanitizes strings.
 */
class BackupValidator {

    companion object {
        // Regex patterns from LoginScreen
        private val IP_ADDRESS_PATTERN = Regex(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
            "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
        )
        private val HOSTNAME_PATTERN = Regex(
            "^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*" +
            "([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$"
        )

        // Valid config modes for Image-to-Image
        private val VALID_CONFIG_MODES = setOf("CHECKPOINT", "UNET")

        // Max lengths for strings
        const val MAX_PROMPT_LENGTH = 10000
        val MAX_WORKFLOW_NAME_LENGTH = ValidationUtils.MAX_WORKFLOW_NAME_LENGTH
        val MAX_WORKFLOW_DESCRIPTION_LENGTH = ValidationUtils.MAX_WORKFLOW_DESCRIPTION_LENGTH

        // Numeric ranges
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
        const val MIN_DIMENSION = 1
        const val MAX_DIMENSION = 4096
        const val MIN_STEPS = 1
        const val MAX_STEPS = 255
        const val MIN_CFG = 0.0f
        const val MAX_CFG = 100.0f
        const val MIN_MEGAPIXELS = 0.1f
        const val MAX_MEGAPIXELS = 8.3f
        const val MIN_LENGTH = 1
        const val MAX_LENGTH = 129
        const val MIN_FRAME_RATE = 1
        const val MAX_FRAME_RATE = 120
    }

    /**
     * Validate overall backup JSON structure.
     * Returns true if the structure has all required top-level keys.
     * v4+ requires "servers" array, v1-v3 requires "connection" object.
     */
    fun validateStructure(json: JSONObject): Boolean {
        // Version is always required
        if (!json.has("version")) return false

        // Version must be a number
        val version = json.optInt("version", -1)
        if (version < 1) return false

        // v4+ requires "servers" array
        if (version >= 4) {
            val servers = json.optJSONArray("servers")
            if (servers == null || servers.length() == 0) return false
            return true
        }

        // v1-v3 requires "connection" object with hostname and port
        val connection = json.optJSONObject("connection") ?: return false
        if (!connection.has("hostname") || !connection.has("port")) return false

        return true
    }

    /**
     * Validate hostname format (IP address or hostname).
     */
    fun validateHostname(hostname: String): Boolean {
        if (hostname.isBlank()) return false
        return IP_ADDRESS_PATTERN.matches(hostname) || HOSTNAME_PATTERN.matches(hostname)
    }

    /**
     * Validate port number is in valid range.
     */
    fun validatePort(port: Int): Boolean {
        return port in MIN_PORT..MAX_PORT
    }

    /**
     * Validate workflow type against WorkflowType enum values.
     */
    fun validateWorkflowType(type: String): Boolean {
        return try {
            WorkflowType.valueOf(type)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Validate sampler against allowed list.
     */
    fun validateSampler(sampler: String): Boolean {
        return sampler in SamplerOptions.SAMPLERS
    }

    /**
     * Validate scheduler against allowed list.
     */
    fun validateScheduler(scheduler: String): Boolean {
        return scheduler in SamplerOptions.SCHEDULERS
    }

    /**
     * Validate config mode for Image-to-Image (CHECKPOINT or UNET).
     */
    fun validateConfigMode(mode: String): Boolean {
        return mode.uppercase() in VALID_CONFIG_MODES
    }

    /**
     * Validate dimension (width or height).
     */
    fun validateDimension(value: Int): Boolean {
        return value in MIN_DIMENSION..MAX_DIMENSION
    }

    /**
     * Validate steps value.
     */
    fun validateSteps(value: Int): Boolean {
        return value in MIN_STEPS..MAX_STEPS
    }

    /**
     * Validate CFG value.
     */
    fun validateCfg(value: Float): Boolean {
        return value in MIN_CFG..MAX_CFG
    }

    /**
     * Validate megapixels value.
     */
    fun validateMegapixels(value: Float): Boolean {
        return value in MIN_MEGAPIXELS..MAX_MEGAPIXELS
    }

    /**
     * Validate video length value.
     */
    fun validateLength(value: Int): Boolean {
        return value in MIN_LENGTH..MAX_LENGTH
    }

    /**
     * Validate frame rate value.
     */
    fun validateFrameRate(value: Int): Boolean {
        return value in MIN_FRAME_RATE..MAX_FRAME_RATE
    }

    /**
     * Validate workflow name format.
     * Matches ValidationUtils.validateWorkflowName - no character restrictions,
     * just checks blank and length.
     */
    fun validateWorkflowName(name: String): Boolean {
        return name.isNotBlank() && name.length <= MAX_WORKFLOW_NAME_LENGTH
    }

    /**
     * Validate workflow description.
     */
    fun validateWorkflowDescription(description: String): Boolean {
        return description.length <= MAX_WORKFLOW_DESCRIPTION_LENGTH
    }

    /**
     * Sanitize string input by removing control characters and limiting length.
     */
    fun sanitizeString(input: String, maxLength: Int = MAX_PROMPT_LENGTH): String {
        // Remove control characters except newlines and tabs
        val sanitized = input.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        // Limit length
        return if (sanitized.length > maxLength) {
            sanitized.substring(0, maxLength)
        } else {
            sanitized
        }
    }

    /**
     * Validate and sanitize a prompt string.
     */
    fun validateAndSanitizePrompt(prompt: String): String? {
        if (prompt.length > MAX_PROMPT_LENGTH * 2) {
            // Way too long, reject entirely
            return null
        }
        return sanitizeString(prompt, MAX_PROMPT_LENGTH)
    }

    /**
     * Validate and sanitize node attribute edits JSON.
     * Structure: {"nodeId": {"inputName": value, ...}, ...}
     * Returns sanitized JSON or null if invalid.
     */
    fun sanitizeNodeAttributeEdits(json: String?): String? {
        if (json.isNullOrBlank()) return null

        return try {
            val root = JSONObject(json)
            val sanitized = JSONObject()

            val nodeIds = root.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()

                // Validate nodeId format (non-empty, alphanumeric + underscore)
                if (nodeId.isBlank() || nodeId.length > 100) continue
                if (!nodeId.matches(Regex("^[a-zA-Z0-9_]+$"))) continue

                val nodeEdits = root.optJSONObject(nodeId) ?: continue
                val sanitizedEdits = JSONObject()

                val inputNames = nodeEdits.keys()
                while (inputNames.hasNext()) {
                    val inputName = inputNames.next()

                    // Validate inputName format (non-empty, alphanumeric + underscore)
                    if (inputName.isBlank() || inputName.length > 100) continue
                    if (!inputName.matches(Regex("^[a-zA-Z0-9_]+$"))) continue

                    val value = nodeEdits.opt(inputName)
                    if (value == null || value == JSONObject.NULL) continue

                    // Validate and sanitize value based on type
                    when (value) {
                        is String -> {
                            val sanitizedValue = sanitizeString(value, MAX_PROMPT_LENGTH)
                            if (sanitizedValue.isNotEmpty()) {
                                sanitizedEdits.put(inputName, sanitizedValue)
                            }
                        }
                        is Int -> {
                            // Allow any reasonable int range
                            if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                                sanitizedEdits.put(inputName, value)
                            }
                        }
                        is Long -> {
                            // Coerce to int if in range
                            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                                sanitizedEdits.put(inputName, value.toInt())
                            }
                        }
                        is Double -> {
                            // Allow reasonable float ranges, reject NaN/Infinity
                            if (!value.isNaN() && !value.isInfinite()) {
                                sanitizedEdits.put(inputName, value)
                            }
                        }
                        is Boolean -> {
                            sanitizedEdits.put(inputName, value)
                        }
                        // Skip other types
                    }
                }

                if (sanitizedEdits.length() > 0) {
                    sanitized.put(nodeId, sanitizedEdits)
                }
            }

            if (sanitized.length() > 0) sanitized.toString() else null
        } catch (e: Exception) {
            null
        }
    }
}
