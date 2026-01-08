package sh.hnet.comfychair.model

import androidx.compose.runtime.Immutable

/**
 * Single source of truth for ALL generation parameters.
 * Used by all generation modes (TTI, ITI, TTV, ITV).
 *
 * Field visibility in the UI is controlled by WorkflowCapabilities,
 * which is derived from workflow placeholders.
 *
 * All fields are stored as Strings to match UI input fields.
 */
@Immutable
data class GenerationParameters(
    // Image dimensions
    val width: String = "1024",
    val height: String = "1024",
    val megapixels: String = "1.0",

    // Video parameters
    val length: String = "49",
    val fps: String = "24",

    // Sampling parameters
    val steps: String = "20",
    val cfg: String = "7.0",
    val sampler: String = "euler",
    val scheduler: String = "normal",
    val denoise: String = "1.0",

    // Seed
    val seed: String = "0",
    val randomSeed: Boolean = true,

    // Batch & scaling
    val batchSize: String = "1",
    val upscaleMethod: String = "nearest-exact",
    val scaleBy: String = "1.5",

    // CLIP
    val stopAtClipLayer: String = "-1",

    // Mandatory LoRA (single selection dropdown, not injection chain)
    val selectedLoraName: String = ""
)
