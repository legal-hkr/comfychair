package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowCapabilities

/**
 * Interface for common generation state properties shared across all UiState types.
 *
 * This enables shared helper functions for BottomSheetConfig construction.
 * Each screen's UiState data class implements this interface.
 *
 * Note: Kotlin data classes automatically satisfy interface properties
 * if they have matching property names, so implementing this interface
 * requires no additional code in the UiState classes.
 */
interface CommonGenerationState {
    // Workflow capabilities (controls field visibility)
    val capabilities: WorkflowCapabilities

    // Model selections
    val selectedCheckpoint: String
    val selectedUnet: String
    val selectedVae: String
    val selectedClip: String
    val selectedClip1: String
    val selectedClip2: String
    val selectedClip3: String
    val selectedClip4: String
    val selectedTextEncoder: String
    val selectedLatentUpscaleModel: String
    val selectedLoraName: String  // Mandatory LoRA (single selection)

    // Available model lists (from server)
    val availableCheckpoints: List<String>
    val availableUnets: List<String>
    val availableVaes: List<String>
    val availableClips: List<String>
    val availableLoras: List<String>
    val availableTextEncoders: List<String>
    val availableLatentUpscaleModels: List<String>
    val availableUpscaleMethods: List<String>

    // Workflow-filtered model options
    val filteredCheckpoints: List<String>?
    val filteredUnets: List<String>?
    val filteredVaes: List<String>?
    val filteredClips: List<String>?
    val filteredClips1: List<String>?
    val filteredClips2: List<String>?
    val filteredClips3: List<String>?
    val filteredClips4: List<String>?
    val filteredTextEncoders: List<String>?
    val filteredLatentUpscaleModels: List<String>?
    val filteredLoras: List<String>?

    // Generation parameters
    val steps: String
    val cfg: String
    val sampler: String
    val scheduler: String
    val randomSeed: Boolean
    val seed: String
    val denoise: String
    val batchSize: String
    val upscaleMethod: String
    val scaleBy: String
    val stopAtClipLayer: String

    // Validation errors
    val stepsError: String?
    val cfgError: String?
    val seedError: String?
    val denoiseError: String?
    val batchSizeError: String?
    val scaleByError: String?
    val stopAtClipLayerError: String?

    // LoRA chain (for screens that support it)
    val loraChain: List<LoraSelection>
}
