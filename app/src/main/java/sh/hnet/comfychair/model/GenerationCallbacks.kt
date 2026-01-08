package sh.hnet.comfychair.model

import androidx.compose.runtime.Stable

/**
 * Callbacks for all GenerationParameters fields.
 * Each generation screen provides implementations for these callbacks.
 *
 * This enables a single shared function to map parameters to UI fields.
 */
@Stable
data class GenerationCallbacks(
    // Image dimensions
    val onWidthChange: (String) -> Unit,
    val onHeightChange: (String) -> Unit,
    val onMegapixelsChange: (String) -> Unit,

    // Video parameters
    val onLengthChange: (String) -> Unit,
    val onFpsChange: (String) -> Unit,

    // Sampling parameters
    val onStepsChange: (String) -> Unit,
    val onCfgChange: (String) -> Unit,
    val onSamplerChange: (String) -> Unit,
    val onSchedulerChange: (String) -> Unit,
    val onDenoiseChange: (String) -> Unit,

    // Seed
    val onSeedChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onRandomizeSeed: () -> Unit,

    // Batch & scaling
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,

    // CLIP
    val onStopAtClipLayerChange: (String) -> Unit,

    // Mandatory LoRA
    val onLoraNameChange: (String) -> Unit
) {
    companion object {
        /**
         * Creates a GenerationCallbacks with all no-op callbacks.
         * Useful as a fallback or for testing.
         */
        val EMPTY = GenerationCallbacks(
            onWidthChange = {},
            onHeightChange = {},
            onMegapixelsChange = {},
            onLengthChange = {},
            onFpsChange = {},
            onStepsChange = {},
            onCfgChange = {},
            onSamplerChange = {},
            onSchedulerChange = {},
            onDenoiseChange = {},
            onSeedChange = {},
            onRandomSeedToggle = {},
            onRandomizeSeed = {},
            onBatchSizeChange = {},
            onUpscaleMethodChange = {},
            onScaleByChange = {},
            onStopAtClipLayerChange = {},
            onLoraNameChange = {}
        )
    }
}
