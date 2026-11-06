package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowCapabilities

/**
 * Read-only view of the generation parameters every screen carries.
 *
 * Implemented by [sh.hnet.comfychair.viewmodel.base.GenerationParams]; each screen's UiState delegates to its
 * params (`: CommonGenerationState by params`), so screens and the options sheet builder read these fields directly.
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
    val selectedAudioVae: String
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
    val availableSamplers: List<String>
    val availableSchedulers: List<String>

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
    val filteredAudioVaes: List<String>?
    val filteredLoras: List<String>?
    /** Options of the node that holds {{upscale_method}}; the server-wide list mixes in custom nodes' values */
    val filteredUpscaleMethods: List<String>?

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

    // Workflow, prompts, dimensions, video length and dual-model selections
    val selectedWorkflow: String
    val selectedWorkflowId: String
    val workflowPlaceholders: Set<String>
    val positivePrompt: String
    val negativePrompt: String
    val selectedHighnoiseUnet: String
    val selectedLownoiseUnet: String
    val selectedHighnoiseLora: String
    val selectedLownoiseLora: String
    val isLoadingModels: Boolean
    val modelsLoaded: Boolean
    val width: String
    val height: String
    val megapixels: String
    val length: String
    val fps: String
    val widthError: String?
    val heightError: String?
    val megapixelsError: String?
    val lengthError: String?
    val fpsError: String?
    val highnoiseLoraChain: List<LoraSelection>
    val lownoiseLoraChain: List<LoraSelection>
}
