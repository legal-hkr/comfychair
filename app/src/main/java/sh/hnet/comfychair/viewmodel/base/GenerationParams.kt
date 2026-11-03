package sh.hnet.comfychair.viewmodel.base

import androidx.compose.runtime.Immutable
import sh.hnet.comfychair.connection.ModelCache
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.model.WorkflowValues
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.ValidationUtils

/**
 * A model selection of a workflow: which server list it is chosen from, the field key used to ask
 * the workflow's own node for its options, and where it is kept in [WorkflowValues].
 */
enum class ModelSlot(
    val optionsFieldKey: String,
    val available: (ModelCache) -> List<String>,
    val saved: (WorkflowValues) -> String?
) {
    CHECKPOINT("ckpt_name", { it.checkpoints }, { it.model }),
    // Older Text/Image to Image versions kept the UNET in `model`; the name check keeps a checkpoint name from being taken
    UNET("unet_name", { it.unets }, { it.unetModel ?: it.model }),
    VAE("vae_name", { it.vaes }, { it.vaeModel }),
    CLIP("clip_name", { it.clips }, { it.clipModel }),
    CLIP1("clip_name1", { it.clips }, { it.clip1Model }),
    CLIP2("clip_name2", { it.clips }, { it.clip2Model }),
    CLIP3("clip_name3", { it.clips }, { it.clip3Model }),
    CLIP4("clip_name4", { it.clips }, { it.clip4Model }),
    TEXT_ENCODER("text_encoder_name", { it.textEncoders }, { it.textEncoderModel }),
    LATENT_UPSCALE_MODEL("latent_upscale_model", { it.latentUpscaleModels }, { it.latentUpscaleModel }),
    LORA("lora_name", { it.loras }, { it.loraModel }),
    HIGHNOISE_UNET("highnoise_unet_name", { it.unets }, { it.highnoiseUnetModel }),
    LOWNOISE_UNET("lownoise_unet_name", { it.unets }, { it.lownoiseUnetModel }),
    HIGHNOISE_LORA("highnoise_lora_name", { it.loras }, { it.highnoiseLoraModel }),
    LOWNOISE_LORA("lownoise_lora_name", { it.loras }, { it.lownoiseLoraModel })
}

/**
 * All generation parameters a screen reads. Extends [CommonGenerationState] (what the options sheet
 * builders need) with the fields that were so far declared by each screen's UiState on its own.
 * Screens move to it one at a time; once all have, the two interfaces merge.
 */
interface GenerationParamsState : CommonGenerationState {
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

/** The three LoRA chains a workflow can use. */
enum class LoraChainKind { MAIN, HIGHNOISE, LOWNOISE }

/**
 * Everything a generation screen sends to a workflow, in one place for all four screens (and for
 * both Image to Image modes). Any workflow can use any placeholder, so every screen carries every
 * field; [capabilities] decide which ones are shown.
 *
 * Values are kept as typed text, like the input fields, with the validation error next to each.
 */
@Immutable
data class GenerationParams(
    // Workflow
    override val selectedWorkflow: String = "",
    override val selectedWorkflowId: String = "",
    override val workflowPlaceholders: Set<String> = emptySet(),
    override val capabilities: WorkflowCapabilities = WorkflowCapabilities(),

    // Prompts
    override val positivePrompt: String = "",
    override val negativePrompt: String = "",

    // Models: current selection, selection waiting for the server's lists, and the workflow node's own options
    val selections: Map<ModelSlot, String> = emptyMap(),
    val deferredSelections: Map<ModelSlot, String> = emptyMap(),
    val filteredOptions: Map<ModelSlot, List<String>> = emptyMap(),
    override val filteredUpscaleMethods: List<String>? = null,
    val models: ModelCache = ModelCache(),

    // Parameters
    override val width: String = "1024",
    override val height: String = "1024",
    override val megapixels: String = "1.0",
    override val length: String = "33",
    override val fps: String = "16",
    override val steps: String = "20",
    override val cfg: String = "7.0",
    override val sampler: String = "euler",
    override val scheduler: String = "normal",
    override val randomSeed: Boolean = true,
    override val seed: String = "0",
    override val denoise: String = "1.0",
    override val batchSize: String = "1",
    override val upscaleMethod: String = "nearest-exact",
    override val scaleBy: String = "1.5",
    override val stopAtClipLayer: String = "-1",

    // LoRA chains
    override val loraChain: List<LoraSelection> = emptyList(),
    override val highnoiseLoraChain: List<LoraSelection> = emptyList(),
    override val lownoiseLoraChain: List<LoraSelection> = emptyList(),

    // Validation errors
    override val widthError: String? = null,
    override val heightError: String? = null,
    override val megapixelsError: String? = null,
    override val lengthError: String? = null,
    override val fpsError: String? = null,
    override val stepsError: String? = null,
    override val cfgError: String? = null,
    override val seedError: String? = null,
    override val denoiseError: String? = null,
    override val batchSizeError: String? = null,
    override val scaleByError: String? = null,
    override val stopAtClipLayerError: String? = null
) : GenerationParamsState {

    fun selection(slot: ModelSlot): String = selections[slot].orEmpty()

    override val selectedCheckpoint: String get() = selection(ModelSlot.CHECKPOINT)
    override val selectedUnet: String get() = selection(ModelSlot.UNET)
    override val selectedVae: String get() = selection(ModelSlot.VAE)
    override val selectedClip: String get() = selection(ModelSlot.CLIP)
    override val selectedClip1: String get() = selection(ModelSlot.CLIP1)
    override val selectedClip2: String get() = selection(ModelSlot.CLIP2)
    override val selectedClip3: String get() = selection(ModelSlot.CLIP3)
    override val selectedClip4: String get() = selection(ModelSlot.CLIP4)
    override val selectedTextEncoder: String get() = selection(ModelSlot.TEXT_ENCODER)
    override val selectedLatentUpscaleModel: String get() = selection(ModelSlot.LATENT_UPSCALE_MODEL)
    override val selectedLoraName: String get() = selection(ModelSlot.LORA)
    override val selectedHighnoiseUnet: String get() = selection(ModelSlot.HIGHNOISE_UNET)
    override val selectedLownoiseUnet: String get() = selection(ModelSlot.LOWNOISE_UNET)
    override val selectedHighnoiseLora: String get() = selection(ModelSlot.HIGHNOISE_LORA)
    override val selectedLownoiseLora: String get() = selection(ModelSlot.LOWNOISE_LORA)

    override val availableCheckpoints: List<String> get() = models.checkpoints
    override val availableUnets: List<String> get() = models.unets
    override val availableVaes: List<String> get() = models.vaes
    override val availableClips: List<String> get() = models.clips
    override val availableLoras: List<String> get() = models.loras
    override val availableTextEncoders: List<String> get() = models.textEncoders
    override val availableLatentUpscaleModels: List<String> get() = models.latentUpscaleModels
    override val availableUpscaleMethods: List<String> get() = models.upscaleMethods
    override val availableSamplers: List<String> get() = models.samplers
    override val availableSchedulers: List<String> get() = models.schedulers
    override val isLoadingModels: Boolean get() = models.isLoading
    override val modelsLoaded: Boolean get() = models.isLoaded

    override val filteredCheckpoints: List<String>? get() = filteredOptions[ModelSlot.CHECKPOINT]
    override val filteredUnets: List<String>? get() = filteredOptions[ModelSlot.UNET]
    override val filteredVaes: List<String>? get() = filteredOptions[ModelSlot.VAE]
    override val filteredClips: List<String>? get() = filteredOptions[ModelSlot.CLIP]
    override val filteredClips1: List<String>? get() = filteredOptions[ModelSlot.CLIP1]
    override val filteredClips2: List<String>? get() = filteredOptions[ModelSlot.CLIP2]
    override val filteredClips3: List<String>? get() = filteredOptions[ModelSlot.CLIP3]
    override val filteredClips4: List<String>? get() = filteredOptions[ModelSlot.CLIP4]
    override val filteredTextEncoders: List<String>? get() = filteredOptions[ModelSlot.TEXT_ENCODER]
    override val filteredLatentUpscaleModels: List<String>? get() = filteredOptions[ModelSlot.LATENT_UPSCALE_MODEL]
    override val filteredLoras: List<String>? get() = filteredOptions[ModelSlot.LORA]

    fun withSelection(slot: ModelSlot, value: String): GenerationParams =
        copy(selections = selections + (slot to value))

    fun loraChain(kind: LoraChainKind): List<LoraSelection> = when (kind) {
        LoraChainKind.MAIN -> loraChain
        LoraChainKind.HIGHNOISE -> highnoiseLoraChain
        LoraChainKind.LOWNOISE -> lownoiseLoraChain
    }

    fun withLoraChain(kind: LoraChainKind, chain: List<LoraSelection>): GenerationParams = when (kind) {
        LoraChainKind.MAIN -> copy(loraChain = chain)
        LoraChainKind.HIGHNOISE -> copy(highnoiseLoraChain = chain)
        LoraChainKind.LOWNOISE -> copy(lownoiseLoraChain = chain)
    }

    /** Whether any field shows a validation error. */
    val hasErrors: Boolean
        get() = listOf(
            widthError, heightError, megapixelsError, lengthError, fpsError, stepsError, cfgError,
            seedError, denoiseError, batchSizeError, scaleByError, stopAtClipLayerError
        ).any { it != null }

    /**
     * Apply the server's model lists: a selection restored before the lists arrived wins if the
     * server has it, otherwise the current selection is kept if valid, or the first model is taken.
     */
    fun withModelCache(cache: ModelCache): GenerationParams = if (!cache.isLoaded) {
        // Lists not there yet (or reloading): selections waiting for them must survive until they arrive
        copy(models = cache)
    } else copy(
        models = cache,
        selections = ModelSlot.entries.associateWith { slot ->
            deferredSelections[slot]?.takeIf { it in slot.available(cache) }
                ?: ValidationUtils.validateModelSelection(selection(slot), slot.available(cache))
        },
        deferredSelections = emptyMap(),
        loraChain = LoraChainManager.filterUnavailable(loraChain, cache.loras),
        highnoiseLoraChain = LoraChainManager.filterUnavailable(highnoiseLoraChain, cache.loras),
        lownoiseLoraChain = LoraChainManager.filterUnavailable(lownoiseLoraChain, cache.loras)
    )

    /**
     * Load a workflow: its saved values, else the template's defaults, else [fallback].
     * Model selections the server doesn't list (yet) are kept as deferred and applied by [withModelCache].
     */
    fun withWorkflow(
        workflowId: String,
        workflowName: String,
        placeholders: Set<String>,
        saved: WorkflowValues?,
        defaults: WorkflowDefaults?,
        options: (fieldKey: String) -> List<String>?,
        fallback: GenerationParams
    ): GenerationParams {
        val cache = models
        val savedSelections = saved?.let { values ->
            ModelSlot.entries.mapNotNull { slot -> slot.saved(values)?.let { slot to it } }.toMap()
        }.orEmpty()
        return copy(
            selectedWorkflow = workflowName,
            selectedWorkflowId = workflowId,
            workflowPlaceholders = placeholders,
            capabilities = WorkflowCapabilities.fromPlaceholders(placeholders),
            negativePrompt = saved?.negativePrompt ?: defaults?.negativePrompt ?: fallback.negativePrompt,
            selections = ModelSlot.entries.associateWith { slot ->
                savedSelections[slot]?.takeIf { it in slot.available(cache) }
                    ?: ValidationUtils.validateModelSelection(selection(slot), slot.available(cache))
            },
            deferredSelections = savedSelections,
            filteredOptions = ModelSlot.entries.mapNotNull { slot ->
                val own = options(slot.optionsFieldKey)
                    // A dual-model workflow has no plain unet_name; its UNET list comes from the high-noise node
                    ?: if (slot == ModelSlot.UNET) options(ModelSlot.HIGHNOISE_UNET.optionsFieldKey) else null
                own?.let { slot to it }
            }.toMap(),
            filteredUpscaleMethods = options("upscale_method"),
            width = saved?.width?.toString() ?: defaults?.width?.toString() ?: fallback.width,
            height = saved?.height?.toString() ?: defaults?.height?.toString() ?: fallback.height,
            megapixels = saved?.megapixels?.toString() ?: defaults?.megapixels?.toString() ?: fallback.megapixels,
            length = saved?.length?.toString() ?: defaults?.length?.toString() ?: fallback.length,
            fps = saved?.frameRate?.toString() ?: defaults?.frameRate?.toString() ?: fallback.fps,
            steps = saved?.steps?.toString() ?: defaults?.steps?.toString() ?: fallback.steps,
            cfg = saved?.cfg?.toString() ?: defaults?.cfg?.toString() ?: fallback.cfg,
            sampler = saved?.samplerName ?: defaults?.samplerName ?: fallback.sampler,
            scheduler = saved?.scheduler ?: defaults?.scheduler ?: fallback.scheduler,
            randomSeed = saved?.randomSeed ?: fallback.randomSeed,
            seed = saved?.seed?.toString() ?: defaults?.seed?.toString() ?: fallback.seed,
            denoise = saved?.denoise?.toString() ?: defaults?.denoise?.toString() ?: fallback.denoise,
            batchSize = saved?.batchSize?.toString() ?: defaults?.batchSize?.toString() ?: fallback.batchSize,
            upscaleMethod = saved?.upscaleMethod ?: defaults?.upscaleMethod ?: fallback.upscaleMethod,
            scaleBy = saved?.scaleBy?.toString() ?: defaults?.scaleBy?.toString() ?: fallback.scaleBy,
            stopAtClipLayer = saved?.stopAtClipLayer?.toString() ?: defaults?.stopAtClipLayer?.toString() ?: fallback.stopAtClipLayer,
            loraChain = saved?.loraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            highnoiseLoraChain = saved?.highnoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            lownoiseLoraChain = saved?.lownoiseLoraChain?.let { LoraSelection.fromJsonString(it) } ?: emptyList(),
            // Values come from storage or the template, so no field starts with an error
            widthError = null, heightError = null, megapixelsError = null, lengthError = null, fpsError = null,
            stepsError = null, cfgError = null, seedError = null, denoiseError = null, batchSizeError = null,
            scaleByError = null, stopAtClipLayerError = null
        )
    }

    /** Selection to store: a model still waiting for the server's lists counts as selected. */
    private fun stored(slot: ModelSlot): String? =
        selection(slot).ifEmpty { deferredSelections[slot].orEmpty() }.takeIf { it.isNotEmpty() }

    /**
     * The per-workflow values to store. Node edits made in the Workflow Editor are kept from [existing].
     */
    fun toWorkflowValues(existing: WorkflowValues?): WorkflowValues = WorkflowValues(
        width = width.toIntOrNull(),
        height = height.toIntOrNull(),
        megapixels = megapixels.toFloatOrNull(),
        length = length.toIntOrNull(),
        frameRate = fps.toIntOrNull(),
        steps = steps.toIntOrNull(),
        cfg = cfg.toFloatOrNull(),
        samplerName = sampler,
        scheduler = scheduler,
        negativePrompt = negativePrompt.takeIf { it.isNotEmpty() },
        model = stored(ModelSlot.CHECKPOINT),
        unetModel = stored(ModelSlot.UNET),
        loraModel = stored(ModelSlot.LORA),
        vaeModel = stored(ModelSlot.VAE),
        clipModel = stored(ModelSlot.CLIP),
        clip1Model = stored(ModelSlot.CLIP1),
        clip2Model = stored(ModelSlot.CLIP2),
        clip3Model = stored(ModelSlot.CLIP3),
        clip4Model = stored(ModelSlot.CLIP4),
        textEncoderModel = stored(ModelSlot.TEXT_ENCODER),
        latentUpscaleModel = stored(ModelSlot.LATENT_UPSCALE_MODEL),
        highnoiseUnetModel = stored(ModelSlot.HIGHNOISE_UNET),
        lownoiseUnetModel = stored(ModelSlot.LOWNOISE_UNET),
        highnoiseLoraModel = stored(ModelSlot.HIGHNOISE_LORA),
        lownoiseLoraModel = stored(ModelSlot.LOWNOISE_LORA),
        loraChain = loraChain.takeIf { it.isNotEmpty() }?.let { LoraSelection.toJsonString(it) },
        highnoiseLoraChain = highnoiseLoraChain.takeIf { it.isNotEmpty() }?.let { LoraSelection.toJsonString(it) },
        lownoiseLoraChain = lownoiseLoraChain.takeIf { it.isNotEmpty() }?.let { LoraSelection.toJsonString(it) },
        seed = seed.toLongOrNull(),
        randomSeed = randomSeed,
        denoise = denoise.toFloatOrNull(),
        batchSize = batchSize.toIntOrNull(),
        upscaleMethod = upscaleMethod.takeIf { it.isNotEmpty() },
        scaleBy = scaleBy.toFloatOrNull(),
        stopAtClipLayer = stopAtClipLayer.toIntOrNull(),
        nodeAttributeEdits = existing?.nodeAttributeEdits
    )
}
