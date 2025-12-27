package sh.hnet.comfychair.util

import android.content.Context
import sh.hnet.comfychair.R

/**
 * Centralized validation utilities for generation parameters.
 * Reduces duplicate validation logic across ViewModels.
 */
object ValidationUtils {

    /**
     * Validate dimension value (width/height) for image generation.
     * @param value The dimension value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 4096)
     * @return Error message if invalid, null if valid
     */
    fun validateDimension(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 4096
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_dimension_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate steps value for generation.
     * @param value The steps value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 255)
     * @return Error message if invalid, null if valid
     */
    fun validateSteps(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 255
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_steps_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate CFG (Classifier-Free Guidance) value.
     * @param value The CFG value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0.0)
     * @param max Maximum allowed value (default 100.0)
     * @return Error message if invalid, null if valid
     */
    fun validateCfg(
        value: String,
        context: Context? = null,
        min: Float = 0.0f,
        max: Float = 100.0f
    ): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
        return if (floatValue == null || floatValue !in min..max) {
            context?.getString(R.string.error_cfg_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate megapixels value for image-to-image generation.
     * @param value The megapixels value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 0.1)
     * @param max Maximum allowed value (default 8.3)
     * @return Error message if invalid, null if valid
     */
    fun validateMegapixels(
        value: String,
        context: Context? = null,
        min: Float = 0.1f,
        max: Float = 8.3f
    ): String? {
        val floatValue = value.toFloatOrNull()
            ?: return context?.getString(R.string.error_invalid_number) ?: "Invalid number"
        return if (floatValue !in min..max) {
            context?.getString(R.string.error_megapixels_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate video length in frames.
     * @param value The length value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 500)
     * @return Error message if invalid, null if valid
     */
    fun validateLength(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 500
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_length_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate frame rate value.
     * @param value The frame rate value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (default 1)
     * @param max Maximum allowed value (default 120)
     * @return Error message if invalid, null if valid
     */
    fun validateFrameRate(
        value: String,
        context: Context? = null,
        min: Int = 1,
        max: Int = 120
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
        return if (intValue == null || intValue !in min..max) {
            context?.getString(R.string.error_fps_range) ?: "Must be $min-$max"
        } else null
    }

    /**
     * Validate model selection against available models.
     * Returns current if valid, otherwise defaults to first available or empty.
     * @param current The currently selected model
     * @param available List of available models
     * @return Valid model selection
     */
    fun validateModelSelection(current: String, available: List<String>): String {
        return when {
            current.isNotEmpty() && available.contains(current) -> current
            available.isNotEmpty() -> available[0]
            else -> ""
        }
    }

    // ===========================================
    // Node Attribute Editor Validation Functions
    // ===========================================

    /**
     * Validate an integer value with min/max constraints.
     * @param value The value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (nullable = no limit)
     * @param max Maximum allowed value (nullable = no limit)
     * @return Error message if invalid, null if valid
     */
    fun validateInt(
        value: String,
        context: Context? = null,
        min: Int? = null,
        max: Int? = null
    ): String? {
        if (value.isEmpty()) return null
        val intValue = value.toIntOrNull()
            ?: return context?.getString(R.string.error_invalid_integer) ?: "Must be a whole number"

        if (min != null && intValue < min) {
            return context?.getString(R.string.error_value_min, min) ?: "Minimum: $min"
        }
        if (max != null && intValue > max) {
            return context?.getString(R.string.error_value_max, max) ?: "Maximum: $max"
        }
        return null
    }

    /**
     * Validate a float/double value with min/max constraints.
     * @param value The value as string
     * @param context Optional context for localized error message
     * @param min Minimum allowed value (nullable = no limit)
     * @param max Maximum allowed value (nullable = no limit)
     * @return Error message if invalid, null if valid
     */
    fun validateFloat(
        value: String,
        context: Context? = null,
        min: Float? = null,
        max: Float? = null
    ): String? {
        if (value.isEmpty()) return null
        val floatValue = value.toFloatOrNull()
            ?: return context?.getString(R.string.error_invalid_number) ?: "Must be a number"

        if (min != null && floatValue < min) {
            return context?.getString(R.string.error_value_min_float, min) ?: "Minimum: $min"
        }
        if (max != null && floatValue > max) {
            return context?.getString(R.string.error_value_max_float, max) ?: "Maximum: $max"
        }
        return null
    }

    /**
     * Validate a value against a list of allowed options.
     * @param value The value to validate
     * @param options List of valid options
     * @param context Optional context for localized error message
     * @return Error message if invalid, null if valid
     */
    fun validateEnum(
        value: String,
        options: List<String>,
        context: Context? = null
    ): String? {
        if (value.isEmpty()) return null
        return if (value !in options) {
            context?.getString(R.string.error_invalid_option) ?: "Invalid option"
        } else null
    }

    /**
     * Validate a string value with max length constraint.
     * @param value The value to validate
     * @param maxLength Maximum allowed length (default 10000)
     * @param context Optional context for localized error message
     * @return Error message if invalid, null if valid
     */
    fun validateString(
        value: String,
        maxLength: Int = 10000,
        context: Context? = null
    ): String? {
        return if (value.length > maxLength) {
            context?.getString(R.string.error_string_too_long, maxLength)
                ?: "Maximum length: $maxLength"
        } else null
    }

    // ===========================================
    // Workflow Name/Description Validation
    // ===========================================

    /** Maximum length for workflow names */
    const val MAX_WORKFLOW_NAME_LENGTH = 40

    /** Maximum length for workflow descriptions */
    const val MAX_WORKFLOW_DESCRIPTION_LENGTH = 80

    /** Valid characters pattern for workflow names (alphanumeric, space, and common punctuation) */
    private val WORKFLOW_NAME_PATTERN = Regex("^[a-zA-Z0-9 _\\-\\[\\]()]+$")

    /**
     * Validate workflow name format.
     * @param name The workflow name to validate
     * @param context Optional context for localized error messages
     * @return Error message if invalid, null if valid
     */
    fun validateWorkflowName(name: String, context: Context? = null): String? {
        if (name.isBlank()) {
            return context?.getString(R.string.error_required) ?: "Required"
        }
        if (name.length > MAX_WORKFLOW_NAME_LENGTH) {
            return context?.getString(R.string.workflow_name_error_too_long)
                ?: "Name too long (max $MAX_WORKFLOW_NAME_LENGTH characters)"
        }
        if (!WORKFLOW_NAME_PATTERN.matches(name)) {
            return context?.getString(R.string.workflow_name_error_invalid_chars)
                ?: "Invalid characters in name"
        }
        return null
    }

    /**
     * Validate workflow description format.
     * @param description The workflow description to validate
     * @param context Optional context for localized error messages
     * @return Error message if invalid, null if valid
     */
    fun validateWorkflowDescription(description: String, context: Context? = null): String? {
        if (description.length > MAX_WORKFLOW_DESCRIPTION_LENGTH) {
            return context?.getString(R.string.workflow_description_error_too_long)
                ?: "Description too long (max $MAX_WORKFLOW_DESCRIPTION_LENGTH characters)"
        }
        return null
    }

    /**
     * Truncate workflow name to maximum allowed length.
     */
    fun truncateWorkflowName(name: String): String = name.take(MAX_WORKFLOW_NAME_LENGTH)

    /**
     * Truncate workflow description to maximum allowed length.
     */
    fun truncateWorkflowDescription(description: String): String = description.take(MAX_WORKFLOW_DESCRIPTION_LENGTH)
}
