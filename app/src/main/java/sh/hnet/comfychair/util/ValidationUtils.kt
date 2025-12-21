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
}
