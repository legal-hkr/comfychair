package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable

/**
 * Validation errors for GenerationParameters fields.
 * Each field corresponds to a parameter in GenerationParameters.
 */
@Immutable
data class GenerationParameterErrors(
    // Image dimensions
    val widthError: String? = null,
    val heightError: String? = null,
    val megapixelsError: String? = null,

    // Video parameters
    val lengthError: String? = null,
    val fpsError: String? = null,

    // Sampling parameters
    val stepsError: String? = null,
    val cfgError: String? = null,
    val denoiseError: String? = null,

    // Seed
    val seedError: String? = null,

    // Batch & scaling
    val batchSizeError: String? = null,
    val scaleByError: String? = null,

    // CLIP
    val stopAtClipLayerError: String? = null
)
