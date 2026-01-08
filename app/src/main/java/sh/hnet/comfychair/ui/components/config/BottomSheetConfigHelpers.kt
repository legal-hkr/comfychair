package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions

/**
 * Shared helper functions for building BottomSheetConfig components.
 *
 * These functions extract common patterns from the 4 separate toBottomSheetConfig()
 * extension functions, reducing code duplication from ~1,333 lines to ~600 lines.
 */

// No-op callbacks for when a callback is not provided
private val noOpString: (String) -> Unit = {}
private val noOpUnit: () -> Unit = {}
private val noOpInt: (Int) -> Unit = {}
private val noOpIntString: (Int, String) -> Unit = { _, _ -> }
private val noOpIntFloat: (Int, Float) -> Unit = { _, _ -> }

/**
 * Build the common ModelConfig from CommonGenerationState.
 *
 * Handles: checkpoint, unet, vae, clip, clip1-4, textEncoder, latentUpscaleModel
 * Does NOT handle: highnoise/lownoise variants (video-specific)
 */
internal fun buildCommonModelConfig(
    state: CommonGenerationState,
    callbacks: UnifiedCallbacks
): ModelConfig {
    val caps = state.capabilities
    return ModelConfig(
        checkpoint = if (caps.hasCheckpointName) ModelField(
            label = R.string.label_checkpoint,
            selectedValue = state.selectedCheckpoint,
            options = state.availableCheckpoints,
            filteredOptions = state.filteredCheckpoints,
            onValueChange = callbacks.onCheckpointChange ?: noOpString,
            isVisible = true
        ) else null,

        unet = if (caps.hasUnetName) ModelField(
            label = R.string.label_unet,
            selectedValue = state.selectedUnet,
            options = state.availableUnets,
            filteredOptions = state.filteredUnets,
            onValueChange = callbacks.onUnetChange ?: noOpString,
            isVisible = true
        ) else null,

        vae = if (caps.hasVaeName) ModelField(
            label = R.string.label_vae,
            selectedValue = state.selectedVae,
            options = state.availableVaes,
            filteredOptions = state.filteredVaes,
            onValueChange = callbacks.onVaeChange ?: noOpString,
            isVisible = true
        ) else null,

        clip = if (caps.hasClipName) ModelField(
            label = R.string.label_clip,
            selectedValue = state.selectedClip,
            options = state.availableClips,
            filteredOptions = state.filteredClips,
            onValueChange = callbacks.onClipChange ?: noOpString,
            isVisible = true
        ) else null,

        clip1 = if (caps.hasClipName1) ModelField(
            label = R.string.label_clip1,
            selectedValue = state.selectedClip1,
            options = state.availableClips,
            filteredOptions = state.filteredClips1,
            onValueChange = callbacks.onClip1Change ?: noOpString,
            isVisible = true
        ) else null,

        clip2 = if (caps.hasClipName2) ModelField(
            label = R.string.label_clip2,
            selectedValue = state.selectedClip2,
            options = state.availableClips,
            filteredOptions = state.filteredClips2,
            onValueChange = callbacks.onClip2Change ?: noOpString,
            isVisible = true
        ) else null,

        clip3 = if (caps.hasClipName3) ModelField(
            label = R.string.label_clip3,
            selectedValue = state.selectedClip3,
            options = state.availableClips,
            filteredOptions = state.filteredClips3,
            onValueChange = callbacks.onClip3Change ?: noOpString,
            isVisible = true
        ) else null,

        clip4 = if (caps.hasClipName4) ModelField(
            label = R.string.label_clip4,
            selectedValue = state.selectedClip4,
            options = state.availableClips,
            filteredOptions = state.filteredClips4,
            onValueChange = callbacks.onClip4Change ?: noOpString,
            isVisible = true
        ) else null,

        textEncoder = if (caps.hasTextEncoderName) ModelField(
            label = R.string.label_text_encoder,
            selectedValue = state.selectedTextEncoder,
            options = state.availableTextEncoders,
            filteredOptions = state.filteredTextEncoders,
            onValueChange = callbacks.onTextEncoderChange ?: noOpString,
            isVisible = true
        ) else null,

        latentUpscaleModel = if (caps.hasLatentUpscaleModel) ModelField(
            label = R.string.label_latent_upscale_model,
            selectedValue = state.selectedLatentUpscaleModel,
            options = state.availableLatentUpscaleModels,
            filteredOptions = state.filteredLatentUpscaleModels,
            onValueChange = callbacks.onLatentUpscaleModelChange ?: noOpString,
            isVisible = true
        ) else null
    )
}

/**
 * Build the common ParameterConfig from CommonGenerationState.
 *
 * Handles: steps, cfg, sampler, scheduler, seed, denoise, batchSize, upscaleMethod, scaleBy, stopAtClipLayer
 * Does NOT handle: width, height, megapixels, length, fps (screen-specific, passed as overrides)
 *
 * @param widthField Optional width field (TTI/TTV have different error fields)
 * @param heightField Optional height field
 * @param megapixelsField Optional megapixels field (video screens)
 * @param lengthField Optional length field (video screens)
 * @param fpsField Optional fps field (video screens)
 */
internal fun buildCommonParameterConfig(
    state: CommonGenerationState,
    callbacks: UnifiedCallbacks,
    widthField: NumericField? = null,
    heightField: NumericField? = null,
    megapixelsField: NumericField? = null,
    lengthField: NumericField? = null,
    fpsField: NumericField? = null
): ParameterConfig {
    val caps = state.capabilities
    return ParameterConfig(
        width = widthField,
        height = heightField,
        megapixels = megapixelsField,
        length = lengthField,
        fps = fpsField,

        steps = NumericField(
            value = state.steps,
            onValueChange = callbacks.onStepsChange ?: noOpString,
            error = state.stepsError,
            isVisible = caps.hasSteps
        ),

        cfg = NumericField(
            value = state.cfg,
            onValueChange = callbacks.onCfgChange ?: noOpString,
            error = state.cfgError,
            isVisible = caps.hasCfg
        ),

        sampler = DropdownField(
            selectedValue = state.sampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = callbacks.onSamplerChange ?: noOpString,
            isVisible = caps.hasSamplerName
        ),

        scheduler = DropdownField(
            selectedValue = state.scheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = callbacks.onSchedulerChange ?: noOpString,
            isVisible = caps.hasScheduler
        ),

        seed = SeedConfig(
            randomSeed = state.randomSeed,
            onRandomSeedToggle = callbacks.onRandomSeedToggle ?: noOpUnit,
            seed = state.seed,
            onSeedChange = callbacks.onSeedChange ?: noOpString,
            onRandomizeSeed = callbacks.onRandomizeSeed ?: noOpUnit,
            seedError = state.seedError,
            isVisible = caps.hasSeed
        ),

        denoise = NumericField(
            value = state.denoise,
            onValueChange = callbacks.onDenoiseChange ?: noOpString,
            error = state.denoiseError,
            isVisible = caps.hasDenoise
        ),

        batchSize = NumericField(
            value = state.batchSize,
            onValueChange = callbacks.onBatchSizeChange ?: noOpString,
            error = state.batchSizeError,
            isVisible = caps.hasBatchSize
        ),

        upscaleMethod = DropdownField(
            selectedValue = state.upscaleMethod,
            options = state.availableUpscaleMethods,
            onValueChange = callbacks.onUpscaleMethodChange ?: noOpString,
            isVisible = caps.hasUpscaleMethod
        ),

        scaleBy = NumericField(
            value = state.scaleBy,
            onValueChange = callbacks.onScaleByChange ?: noOpString,
            error = state.scaleByError,
            isVisible = caps.hasScaleBy
        ),

        stopAtClipLayer = NumericField(
            value = state.stopAtClipLayer,
            onValueChange = callbacks.onStopAtClipLayerChange ?: noOpString,
            error = state.stopAtClipLayerError,
            isVisible = caps.hasStopAtClipLayer
        )
    )
}

/**
 * Build the common LoraConfig from CommonGenerationState.
 *
 * Handles: loraName (mandatory dropdown), primaryChain
 * Does NOT handle: highnoiseChain, lownoiseChain (video-specific)
 */
internal fun buildCommonLoraConfig(
    state: CommonGenerationState,
    callbacks: UnifiedCallbacks
): LoraConfig {
    val caps = state.capabilities
    return LoraConfig(
        loraName = if (caps.hasLoraName) ModelField(
            label = R.string.label_lora,
            selectedValue = state.selectedLoraName,
            options = state.availableLoras,
            filteredOptions = state.filteredLoras,
            onValueChange = callbacks.onMandatoryLoraChange ?: noOpString,
            isVisible = true
        ) else null,

        primaryChain = if (caps.hasLora) LoraChainField(
            title = R.string.lora_chain_title,
            chain = state.loraChain,
            availableLoras = state.availableLoras,
            onAdd = callbacks.onAddLora ?: noOpUnit,
            onRemove = callbacks.onRemoveLora ?: noOpInt,
            onNameChange = callbacks.onLoraNameChange ?: noOpIntString,
            onStrengthChange = callbacks.onLoraStrengthChange ?: noOpIntFloat,
            isVisible = true
        ) else null
    )
}

/**
 * Build a LoraChainField for video dual-model patterns.
 *
 * Used by TTV/ITV screens for highnoise and lownoise LoRA chains.
 */
internal fun buildVideoLoraChain(
    titleResId: Int,
    chain: List<sh.hnet.comfychair.model.LoraSelection>,
    availableLoras: List<String>,
    isVisible: Boolean,
    onAdd: (() -> Unit)?,
    onRemove: ((Int) -> Unit)?,
    onNameChange: ((Int, String) -> Unit)?,
    onStrengthChange: ((Int, Float) -> Unit)?
): LoraChainField? {
    if (!isVisible) return null
    return LoraChainField(
        title = titleResId,
        chain = chain,
        availableLoras = availableLoras,
        onAdd = onAdd ?: noOpUnit,
        onRemove = onRemove ?: noOpInt,
        onNameChange = onNameChange ?: noOpIntString,
        onStrengthChange = onStrengthChange ?: noOpIntFloat,
        isVisible = true
    )
}
