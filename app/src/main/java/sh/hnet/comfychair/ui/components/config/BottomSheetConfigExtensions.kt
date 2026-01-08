package sh.hnet.comfychair.ui.components.config

import androidx.compose.runtime.Stable
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.TextToImageUiState
import sh.hnet.comfychair.viewmodel.TextToVideoUiState

/**
 * Callback interfaces for each screen type.
 * These group all the callbacks needed by the ConfigBottomSheet.
 */
@Stable
data class TextToImageCallbacks(
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,
    val onCheckpointChange: (String) -> Unit,
    val onUnetChange: (String) -> Unit,
    val onVaeChange: (String) -> Unit,
    val onClipChange: (String) -> Unit,
    val onClip1Change: (String) -> Unit,
    val onClip2Change: (String) -> Unit,
    val onClip3Change: (String) -> Unit,
    val onClip4Change: (String) -> Unit,
    val onTextEncoderChange: (String) -> Unit,
    val onLatentUpscaleModelChange: (String) -> Unit,
    val onMandatoryLoraChange: (String) -> Unit,  // Mandatory LoRA dropdown (single selection)
    val onWidthChange: (String) -> Unit,
    val onHeightChange: (String) -> Unit,
    val onStepsChange: (String) -> Unit,
    val onCfgChange: (String) -> Unit,
    val onSamplerChange: (String) -> Unit,
    val onSchedulerChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val onDenoiseChange: (String) -> Unit,
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,
    val onStopAtClipLayerChange: (String) -> Unit,
    val onAddLora: () -> Unit,
    val onRemoveLora: (Int) -> Unit,
    val onLoraNameChange: (Int, String) -> Unit,  // LoRA chain item name change
    val onLoraStrengthChange: (Int, Float) -> Unit
)

@Stable
data class TextToVideoCallbacks(
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,
    // Single-model patterns (e.g., LTX 2.0)
    val onCheckpointChange: (String) -> Unit,
    val onUnetChange: (String) -> Unit,
    val onMandatoryLoraChange: (String) -> Unit,
    // Dual-model patterns (e.g., Wan 2.2)
    val onHighnoiseUnetChange: (String) -> Unit,
    val onLownoiseUnetChange: (String) -> Unit,
    val onHighnoiseLoraChange: (String) -> Unit,
    val onLownoiseLoraChange: (String) -> Unit,
    val onVaeChange: (String) -> Unit,
    val onClipChange: (String) -> Unit,
    val onClip1Change: (String) -> Unit,
    val onClip2Change: (String) -> Unit,
    val onClip3Change: (String) -> Unit,
    val onClip4Change: (String) -> Unit,
    val onTextEncoderChange: (String) -> Unit,
    val onLatentUpscaleModelChange: (String) -> Unit,
    val onWidthChange: (String) -> Unit,
    val onHeightChange: (String) -> Unit,
    val onMegapixelsChange: (String) -> Unit,
    val onLengthChange: (String) -> Unit,
    val onFpsChange: (String) -> Unit,
    val onStepsChange: (String) -> Unit,
    val onCfgChange: (String) -> Unit,
    val onSamplerChange: (String) -> Unit,
    val onSchedulerChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val onDenoiseChange: (String) -> Unit,
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,
    val onStopAtClipLayerChange: (String) -> Unit,
    // Dual-model LoRA chains
    val onAddHighnoiseLora: () -> Unit,
    val onRemoveHighnoiseLora: (Int) -> Unit,
    val onHighnoiseLoraNameChange: (Int, String) -> Unit,
    val onHighnoiseLoraStrengthChange: (Int, Float) -> Unit,
    val onAddLownoiseLora: () -> Unit,
    val onRemoveLownoiseLora: (Int) -> Unit,
    val onLownoiseLoraNameChange: (Int, String) -> Unit,
    val onLownoiseLoraStrengthChange: (Int, Float) -> Unit
)

@Stable
data class ImageToVideoCallbacks(
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,
    // Single-model patterns (e.g., LTX 2.0)
    val onCheckpointChange: (String) -> Unit,
    val onUnetChange: (String) -> Unit,
    val onMandatoryLoraChange: (String) -> Unit,
    // Dual-model patterns (e.g., Wan 2.2)
    val onHighnoiseUnetChange: (String) -> Unit,
    val onLownoiseUnetChange: (String) -> Unit,
    val onHighnoiseLoraChange: (String) -> Unit,
    val onLownoiseLoraChange: (String) -> Unit,
    val onVaeChange: (String) -> Unit,
    val onClipChange: (String) -> Unit,
    val onClip1Change: (String) -> Unit,
    val onClip2Change: (String) -> Unit,
    val onClip3Change: (String) -> Unit,
    val onClip4Change: (String) -> Unit,
    val onTextEncoderChange: (String) -> Unit,
    val onLatentUpscaleModelChange: (String) -> Unit,
    val onWidthChange: (String) -> Unit,
    val onHeightChange: (String) -> Unit,
    val onMegapixelsChange: (String) -> Unit,
    val onLengthChange: (String) -> Unit,
    val onFpsChange: (String) -> Unit,
    val onStepsChange: (String) -> Unit,
    val onCfgChange: (String) -> Unit,
    val onSamplerChange: (String) -> Unit,
    val onSchedulerChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val onDenoiseChange: (String) -> Unit,
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,
    val onStopAtClipLayerChange: (String) -> Unit,
    // Dual-model LoRA chains
    val onAddHighnoiseLora: () -> Unit,
    val onRemoveHighnoiseLora: (Int) -> Unit,
    val onHighnoiseLoraNameChange: (Int, String) -> Unit,
    val onHighnoiseLoraStrengthChange: (Int, Float) -> Unit,
    val onAddLownoiseLora: () -> Unit,
    val onRemoveLownoiseLora: (Int) -> Unit,
    val onLownoiseLoraNameChange: (Int, String) -> Unit,
    val onLownoiseLoraStrengthChange: (Int, Float) -> Unit
)

@Stable
data class ImageToImageCallbacks(
    // Mode switching
    val onModeChange: (sh.hnet.comfychair.viewmodel.ImageToImageMode) -> Unit,
    // Reference images (editing mode)
    val onReferenceImage1Change: (android.net.Uri) -> Unit,
    val onClearReferenceImage1: () -> Unit,
    val onReferenceImage2Change: (android.net.Uri) -> Unit,
    val onClearReferenceImage2: () -> Unit,
    // Inpainting workflow
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    // Editing workflow
    val onEditingWorkflowChange: (String) -> Unit,
    val onViewEditingWorkflow: () -> Unit,
    // Negative prompt
    val onNegativePromptChange: (String) -> Unit,
    // Inpainting models
    val onCheckpointChange: (String) -> Unit,
    val onUnetChange: (String) -> Unit,
    val onVaeChange: (String) -> Unit,
    val onClipChange: (String) -> Unit,
    val onClip1Change: (String) -> Unit,
    val onClip2Change: (String) -> Unit,
    val onClip3Change: (String) -> Unit,
    val onClip4Change: (String) -> Unit,
    val onTextEncoderChange: (String) -> Unit,
    val onLatentUpscaleModelChange: (String) -> Unit,
    // Editing models
    val onEditingUnetChange: (String) -> Unit,
    val onEditingLoraChange: (String) -> Unit,
    val onEditingVaeChange: (String) -> Unit,
    val onEditingClipChange: (String) -> Unit,
    val onEditingClip1Change: (String) -> Unit,
    val onEditingClip2Change: (String) -> Unit,
    val onEditingClip3Change: (String) -> Unit,
    val onEditingClip4Change: (String) -> Unit,
    val onEditingTextEncoderChange: (String) -> Unit,
    val onEditingLatentUpscaleModelChange: (String) -> Unit,
    // Inpainting parameters
    val onMegapixelsChange: (String) -> Unit,
    val onStepsChange: (String) -> Unit,
    val onCfgChange: (String) -> Unit,
    val onSamplerChange: (String) -> Unit,
    val onSchedulerChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val onDenoiseChange: (String) -> Unit,
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,
    val onStopAtClipLayerChange: (String) -> Unit,
    // Editing parameters
    val onEditingMegapixelsChange: (String) -> Unit,
    val onEditingStepsChange: (String) -> Unit,
    val onEditingCfgChange: (String) -> Unit,
    val onEditingSamplerChange: (String) -> Unit,
    val onEditingSchedulerChange: (String) -> Unit,
    val onEditingRandomSeedToggle: () -> Unit,
    val onEditingSeedChange: (String) -> Unit,
    val onEditingRandomizeSeed: () -> Unit,
    val onEditingDenoiseChange: (String) -> Unit,
    val onEditingBatchSizeChange: (String) -> Unit,
    val onEditingUpscaleMethodChange: (String) -> Unit,
    val onEditingScaleByChange: (String) -> Unit,
    val onEditingStopAtClipLayerChange: (String) -> Unit,
    // Inpainting LoRA chain
    val onAddLora: () -> Unit,
    val onRemoveLora: (Int) -> Unit,
    val onLoraNameChange: (Int, String) -> Unit,
    val onLoraStrengthChange: (Int, Float) -> Unit,
    // Editing LoRA chain
    val onAddEditingLora: () -> Unit,
    val onRemoveEditingLora: (Int) -> Unit,
    val onEditingLoraNameChange: (Int, String) -> Unit,
    val onEditingLoraStrengthChange: (Int, Float) -> Unit
)

/**
 * Convert TextToImageUiState to BottomSheetConfig.
 *
 * Uses unified fields - field visibility is controlled by capabilities (derived from placeholders).
 */
fun TextToImageUiState.toBottomSheetConfig(callbacks: TextToImageCallbacks): BottomSheetConfig {
    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = null,
        models = ModelConfig(
            checkpoint = if (capabilities.hasCheckpointName) ModelField(
                label = R.string.label_checkpoint,
                selectedValue = selectedCheckpoint,
                options = availableCheckpoints,
                filteredOptions = filteredCheckpoints,
                onValueChange = callbacks.onCheckpointChange,
                isVisible = true
            ) else null,
            unet = if (capabilities.hasUnetName) ModelField(
                label = R.string.label_unet,
                selectedValue = selectedUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onUnetChange,
                isVisible = true
            ) else null,
            vae = if (capabilities.hasVaeName) ModelField(
                label = R.string.label_vae,
                selectedValue = selectedVae,
                options = availableVaes,
                filteredOptions = filteredVaes,
                onValueChange = callbacks.onVaeChange,
                isVisible = true
            ) else null,
            clip = if (capabilities.hasClipName) ModelField(
                label = R.string.label_clip,
                selectedValue = selectedClip,
                options = availableClips,
                filteredOptions = filteredClips,
                onValueChange = callbacks.onClipChange,
                isVisible = true
            ) else null,
            clip1 = if (capabilities.hasClipName1) ModelField(
                label = R.string.label_clip1,
                selectedValue = selectedClip1,
                options = availableClips,
                filteredOptions = filteredClips1,
                onValueChange = callbacks.onClip1Change,
                isVisible = true
            ) else null,
            clip2 = if (capabilities.hasClipName2) ModelField(
                label = R.string.label_clip2,
                selectedValue = selectedClip2,
                options = availableClips,
                filteredOptions = filteredClips2,
                onValueChange = callbacks.onClip2Change,
                isVisible = true
            ) else null,
            clip3 = if (capabilities.hasClipName3) ModelField(
                label = R.string.label_clip3,
                selectedValue = selectedClip3,
                options = availableClips,
                filteredOptions = filteredClips3,
                onValueChange = callbacks.onClip3Change,
                isVisible = true
            ) else null,
            clip4 = if (capabilities.hasClipName4) ModelField(
                label = R.string.label_clip4,
                selectedValue = selectedClip4,
                options = availableClips,
                filteredOptions = filteredClips4,
                onValueChange = callbacks.onClip4Change,
                isVisible = true
            ) else null,
            textEncoder = if (capabilities.hasTextEncoderName) ModelField(
                label = R.string.label_text_encoder,
                selectedValue = selectedTextEncoder,
                options = availableTextEncoders,
                filteredOptions = filteredTextEncoders,
                onValueChange = callbacks.onTextEncoderChange,
                isVisible = true
            ) else null,
            latentUpscaleModel = if (capabilities.hasLatentUpscaleModel) ModelField(
                label = R.string.label_latent_upscale_model,
                selectedValue = selectedLatentUpscaleModel,
                options = availableLatentUpscaleModels,
                filteredOptions = filteredLatentUpscaleModels,
                onValueChange = callbacks.onLatentUpscaleModelChange,
                isVisible = true
            ) else null
        ),
        parameters = ParameterConfig(
            width = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            height = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            steps = NumericField(
                value = steps,
                onValueChange = callbacks.onStepsChange,
                error = stepsError,
                isVisible = capabilities.hasSteps
            ),
            cfg = NumericField(
                value = cfg,
                onValueChange = callbacks.onCfgChange,
                error = cfgError,
                isVisible = capabilities.hasCfg
            ),
            sampler = DropdownField(
                selectedValue = sampler,
                options = SamplerOptions.SAMPLERS,
                onValueChange = callbacks.onSamplerChange,
                isVisible = capabilities.hasSamplerName
            ),
            scheduler = DropdownField(
                selectedValue = scheduler,
                options = SamplerOptions.SCHEDULERS,
                onValueChange = callbacks.onSchedulerChange,
                isVisible = capabilities.hasScheduler
            ),
            seed = SeedConfig(
                randomSeed = randomSeed,
                onRandomSeedToggle = callbacks.onRandomSeedToggle,
                seed = seed,
                onSeedChange = callbacks.onSeedChange,
                onRandomizeSeed = callbacks.onRandomizeSeed,
                seedError = seedError,
                isVisible = capabilities.hasSeed
            ),
            denoise = NumericField(
                value = denoise,
                onValueChange = callbacks.onDenoiseChange,
                error = denoiseError,
                isVisible = capabilities.hasDenoise
            ),
            batchSize = NumericField(
                value = batchSize,
                onValueChange = callbacks.onBatchSizeChange,
                error = batchSizeError,
                isVisible = capabilities.hasBatchSize
            ),
            upscaleMethod = DropdownField(
                selectedValue = upscaleMethod,
                options = availableUpscaleMethods,
                onValueChange = callbacks.onUpscaleMethodChange,
                isVisible = capabilities.hasUpscaleMethod
            ),
            scaleBy = NumericField(
                value = scaleBy,
                onValueChange = callbacks.onScaleByChange,
                error = scaleByError,
                isVisible = capabilities.hasScaleBy
            ),
            stopAtClipLayer = NumericField(
                value = stopAtClipLayer,
                onValueChange = callbacks.onStopAtClipLayerChange,
                error = stopAtClipLayerError,
                isVisible = capabilities.hasStopAtClipLayer
            )
        ),
        lora = LoraConfig(
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                selectedValue = selectedLoraName,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onMandatoryLoraChange,
                isVisible = true
            ) else null,
            primaryChain = if (capabilities.hasLora) LoraChainField(
                title = R.string.lora_chain_title,
                chain = loraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLora,
                onRemove = callbacks.onRemoveLora,
                onNameChange = callbacks.onLoraNameChange,
                onStrengthChange = callbacks.onLoraStrengthChange,
                isVisible = true
            ) else null
        )
    )
}

/**
 * Convert TextToVideoUiState to BottomSheetConfig
 */
fun TextToVideoUiState.toBottomSheetConfig(callbacks: TextToVideoCallbacks): BottomSheetConfig {
    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = null,
        models = ModelConfig(
            // Single-model patterns (e.g., LTX 2.0)
            checkpoint = if (capabilities.hasCheckpointName) ModelField(
                label = R.string.label_checkpoint,
                selectedValue = selectedCheckpoint,
                options = availableCheckpoints,
                filteredOptions = filteredCheckpoints,
                onValueChange = callbacks.onCheckpointChange,
                isVisible = true
            ) else null,
            unet = if (capabilities.hasUnetName) ModelField(
                label = R.string.label_unet,
                selectedValue = selectedUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onUnetChange,
                isVisible = true
            ) else null,
            // Dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = if (capabilities.hasHighnoiseUnetName) ModelField(
                label = R.string.highnoise_unet_label,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange,
                isVisible = true
            ) else null,
            lownoiseUnet = if (capabilities.hasLownoiseUnetName) ModelField(
                label = R.string.lownoise_unet_label,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange,
                isVisible = true
            ) else null,
            highnoiseLora = if (capabilities.hasHighnoiseLoraName) ModelField(
                label = R.string.highnoise_lora_label,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onHighnoiseLoraChange,
                isVisible = true
            ) else null,
            lownoiseLora = if (capabilities.hasLownoiseLoraName) ModelField(
                label = R.string.lownoise_lora_label,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onLownoiseLoraChange,
                isVisible = true
            ) else null,
            vae = if (capabilities.hasVaeName) ModelField(
                label = R.string.label_vae,
                selectedValue = selectedVae,
                options = availableVaes,
                filteredOptions = filteredVaes,
                onValueChange = callbacks.onVaeChange,
                isVisible = true
            ) else null,
            clip = if (capabilities.hasClipName) ModelField(
                label = R.string.label_clip,
                selectedValue = selectedClip,
                options = availableClips,
                filteredOptions = filteredClips,
                onValueChange = callbacks.onClipChange,
                isVisible = true
            ) else null,
            clip1 = if (capabilities.hasClipName1) ModelField(
                label = R.string.label_clip1,
                selectedValue = selectedClip1,
                options = availableClips,
                filteredOptions = filteredClips1,
                onValueChange = callbacks.onClip1Change,
                isVisible = true
            ) else null,
            clip2 = if (capabilities.hasClipName2) ModelField(
                label = R.string.label_clip2,
                selectedValue = selectedClip2,
                options = availableClips,
                filteredOptions = filteredClips2,
                onValueChange = callbacks.onClip2Change,
                isVisible = true
            ) else null,
            clip3 = if (capabilities.hasClipName3) ModelField(
                label = R.string.label_clip3,
                selectedValue = selectedClip3,
                options = availableClips,
                filteredOptions = filteredClips3,
                onValueChange = callbacks.onClip3Change,
                isVisible = true
            ) else null,
            clip4 = if (capabilities.hasClipName4) ModelField(
                label = R.string.label_clip4,
                selectedValue = selectedClip4,
                options = availableClips,
                filteredOptions = filteredClips4,
                onValueChange = callbacks.onClip4Change,
                isVisible = true
            ) else null,
            textEncoder = if (capabilities.hasTextEncoderName) ModelField(
                label = R.string.label_text_encoder,
                selectedValue = selectedTextEncoder,
                options = availableTextEncoders,
                filteredOptions = filteredTextEncoders,
                onValueChange = callbacks.onTextEncoderChange,
                isVisible = true
            ) else null,
            latentUpscaleModel = if (capabilities.hasLatentUpscaleModel) ModelField(
                label = R.string.label_latent_upscale_model,
                selectedValue = selectedLatentUpscaleModel,
                options = availableLatentUpscaleModels,
                filteredOptions = filteredLatentUpscaleModels,
                onValueChange = callbacks.onLatentUpscaleModelChange,
                isVisible = true
            ) else null
        ),
        parameters = ParameterConfig(
            width = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            height = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            megapixels = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            ),
            length = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange,
                error = lengthError,
                isVisible = capabilities.hasLength
            ),
            fps = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange,
                error = fpsError,
                isVisible = capabilities.hasFrameRate
            ),
            steps = NumericField(
                value = steps,
                onValueChange = callbacks.onStepsChange,
                error = stepsError,
                isVisible = capabilities.hasSteps
            ),
            cfg = NumericField(
                value = cfg,
                onValueChange = callbacks.onCfgChange,
                error = cfgError,
                isVisible = capabilities.hasCfg
            ),
            sampler = DropdownField(
                selectedValue = sampler,
                options = SamplerOptions.SAMPLERS,
                onValueChange = callbacks.onSamplerChange,
                isVisible = capabilities.hasSamplerName
            ),
            scheduler = DropdownField(
                selectedValue = scheduler,
                options = SamplerOptions.SCHEDULERS,
                onValueChange = callbacks.onSchedulerChange,
                isVisible = capabilities.hasScheduler
            ),
            seed = SeedConfig(
                randomSeed = randomSeed,
                onRandomSeedToggle = callbacks.onRandomSeedToggle,
                seed = seed,
                onSeedChange = callbacks.onSeedChange,
                onRandomizeSeed = callbacks.onRandomizeSeed,
                seedError = seedError,
                isVisible = capabilities.hasSeed
            ),
            denoise = NumericField(
                value = denoise,
                onValueChange = callbacks.onDenoiseChange,
                error = denoiseError,
                isVisible = capabilities.hasDenoise
            ),
            batchSize = NumericField(
                value = batchSize,
                onValueChange = callbacks.onBatchSizeChange,
                error = batchSizeError,
                isVisible = capabilities.hasBatchSize
            ),
            upscaleMethod = DropdownField(
                selectedValue = upscaleMethod,
                options = availableUpscaleMethods,
                onValueChange = callbacks.onUpscaleMethodChange,
                isVisible = capabilities.hasUpscaleMethod
            ),
            scaleBy = NumericField(
                value = scaleBy,
                onValueChange = callbacks.onScaleByChange,
                error = scaleByError,
                isVisible = capabilities.hasScaleBy
            ),
            stopAtClipLayer = NumericField(
                value = stopAtClipLayer,
                onValueChange = callbacks.onStopAtClipLayerChange,
                error = stopAtClipLayerError,
                isVisible = capabilities.hasStopAtClipLayer
            )
        ),
        lora = LoraConfig(
            // Single-model LoRA (mandatory dropdown)
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                selectedValue = selectedLoraName,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onMandatoryLoraChange,
                isVisible = true
            ) else null,
            // Dual-model LoRA chains
            highnoiseChain = if (capabilities.hasHighnoiseLora) LoraChainField(
                title = R.string.highnoise_lora_chain_title,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange,
                isVisible = true
            ) else null,
            lownoiseChain = if (capabilities.hasLownoiseLora) LoraChainField(
                title = R.string.lownoise_lora_chain_title,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange,
                isVisible = true
            ) else null
        )
    )
}

/**
 * Convert ImageToVideoUiState to BottomSheetConfig
 */
fun ImageToVideoUiState.toBottomSheetConfig(callbacks: ImageToVideoCallbacks): BottomSheetConfig {
    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = null,
        models = ModelConfig(
            // Single-model patterns (e.g., LTX 2.0)
            checkpoint = if (capabilities.hasCheckpointName) ModelField(
                label = R.string.label_checkpoint,
                selectedValue = selectedCheckpoint,
                options = availableCheckpoints,
                filteredOptions = filteredCheckpoints,
                onValueChange = callbacks.onCheckpointChange,
                isVisible = true
            ) else null,
            unet = if (capabilities.hasUnetName) ModelField(
                label = R.string.label_unet,
                selectedValue = selectedUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onUnetChange,
                isVisible = true
            ) else null,
            // Dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = if (capabilities.hasHighnoiseUnetName) ModelField(
                label = R.string.highnoise_unet_label,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange,
                isVisible = true
            ) else null,
            lownoiseUnet = if (capabilities.hasLownoiseUnetName) ModelField(
                label = R.string.lownoise_unet_label,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange,
                isVisible = true
            ) else null,
            highnoiseLora = if (capabilities.hasHighnoiseLoraName) ModelField(
                label = R.string.highnoise_lora_label,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onHighnoiseLoraChange,
                isVisible = true
            ) else null,
            lownoiseLora = if (capabilities.hasLownoiseLoraName) ModelField(
                label = R.string.lownoise_lora_label,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onLownoiseLoraChange,
                isVisible = true
            ) else null,
            vae = if (capabilities.hasVaeName) ModelField(
                label = R.string.label_vae,
                selectedValue = selectedVae,
                options = availableVaes,
                filteredOptions = filteredVaes,
                onValueChange = callbacks.onVaeChange,
                isVisible = true
            ) else null,
            clip = if (capabilities.hasClipName) ModelField(
                label = R.string.label_clip,
                selectedValue = selectedClip,
                options = availableClips,
                filteredOptions = filteredClips,
                onValueChange = callbacks.onClipChange,
                isVisible = true
            ) else null,
            clip1 = if (capabilities.hasClipName1) ModelField(
                label = R.string.label_clip1,
                selectedValue = selectedClip1,
                options = availableClips,
                filteredOptions = filteredClips1,
                onValueChange = callbacks.onClip1Change,
                isVisible = true
            ) else null,
            clip2 = if (capabilities.hasClipName2) ModelField(
                label = R.string.label_clip2,
                selectedValue = selectedClip2,
                options = availableClips,
                filteredOptions = filteredClips2,
                onValueChange = callbacks.onClip2Change,
                isVisible = true
            ) else null,
            clip3 = if (capabilities.hasClipName3) ModelField(
                label = R.string.label_clip3,
                selectedValue = selectedClip3,
                options = availableClips,
                filteredOptions = filteredClips3,
                onValueChange = callbacks.onClip3Change,
                isVisible = true
            ) else null,
            clip4 = if (capabilities.hasClipName4) ModelField(
                label = R.string.label_clip4,
                selectedValue = selectedClip4,
                options = availableClips,
                filteredOptions = filteredClips4,
                onValueChange = callbacks.onClip4Change,
                isVisible = true
            ) else null,
            textEncoder = if (capabilities.hasTextEncoderName) ModelField(
                label = R.string.label_text_encoder,
                selectedValue = selectedTextEncoder,
                options = availableTextEncoders,
                filteredOptions = filteredTextEncoders,
                onValueChange = callbacks.onTextEncoderChange,
                isVisible = true
            ) else null,
            latentUpscaleModel = if (capabilities.hasLatentUpscaleModel) ModelField(
                label = R.string.label_latent_upscale_model,
                selectedValue = selectedLatentUpscaleModel,
                options = availableLatentUpscaleModels,
                filteredOptions = filteredLatentUpscaleModels,
                onValueChange = callbacks.onLatentUpscaleModelChange,
                isVisible = true
            ) else null
        ),
        parameters = ParameterConfig(
            width = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            height = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            megapixels = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            ),
            length = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange,
                error = lengthError,
                isVisible = capabilities.hasLength
            ),
            fps = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange,
                error = fpsError,
                isVisible = capabilities.hasFrameRate
            ),
            steps = NumericField(
                value = steps,
                onValueChange = callbacks.onStepsChange,
                error = stepsError,
                isVisible = capabilities.hasSteps
            ),
            cfg = NumericField(
                value = cfg,
                onValueChange = callbacks.onCfgChange,
                error = cfgError,
                isVisible = capabilities.hasCfg
            ),
            sampler = DropdownField(
                selectedValue = sampler,
                options = SamplerOptions.SAMPLERS,
                onValueChange = callbacks.onSamplerChange,
                isVisible = capabilities.hasSamplerName
            ),
            scheduler = DropdownField(
                selectedValue = scheduler,
                options = SamplerOptions.SCHEDULERS,
                onValueChange = callbacks.onSchedulerChange,
                isVisible = capabilities.hasScheduler
            ),
            seed = SeedConfig(
                randomSeed = randomSeed,
                onRandomSeedToggle = callbacks.onRandomSeedToggle,
                seed = seed,
                onSeedChange = callbacks.onSeedChange,
                onRandomizeSeed = callbacks.onRandomizeSeed,
                seedError = seedError,
                isVisible = capabilities.hasSeed
            ),
            denoise = NumericField(
                value = denoise,
                onValueChange = callbacks.onDenoiseChange,
                error = denoiseError,
                isVisible = capabilities.hasDenoise
            ),
            batchSize = NumericField(
                value = batchSize,
                onValueChange = callbacks.onBatchSizeChange,
                error = batchSizeError,
                isVisible = capabilities.hasBatchSize
            ),
            upscaleMethod = DropdownField(
                selectedValue = upscaleMethod,
                options = availableUpscaleMethods,
                onValueChange = callbacks.onUpscaleMethodChange,
                isVisible = capabilities.hasUpscaleMethod
            ),
            scaleBy = NumericField(
                value = scaleBy,
                onValueChange = callbacks.onScaleByChange,
                error = scaleByError,
                isVisible = capabilities.hasScaleBy
            ),
            stopAtClipLayer = NumericField(
                value = stopAtClipLayer,
                onValueChange = callbacks.onStopAtClipLayerChange,
                error = stopAtClipLayerError,
                isVisible = capabilities.hasStopAtClipLayer
            )
        ),
        lora = LoraConfig(
            // Single-model LoRA (mandatory dropdown)
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                selectedValue = selectedLoraName,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onMandatoryLoraChange,
                isVisible = true
            ) else null,
            // Dual-model LoRA chains
            highnoiseChain = if (capabilities.hasHighnoiseLora) LoraChainField(
                title = R.string.highnoise_lora_chain_title,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange,
                isVisible = true
            ) else null,
            lownoiseChain = if (capabilities.hasLownoiseLora) LoraChainField(
                title = R.string.lownoise_lora_chain_title,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange,
                isVisible = true
            ) else null
        )
    )
}

/**
 * Convert ImageToImageUiState to BottomSheetConfig.
 * Handles both EDITING and INPAINTING modes with unified field architecture.
 */
fun ImageToImageUiState.toBottomSheetConfig(callbacks: ImageToImageCallbacks): BottomSheetConfig {
    val isEditing = mode == sh.hnet.comfychair.viewmodel.ImageToImageMode.EDITING

    // Select appropriate workflow and callbacks based on mode
    val workflowName = if (isEditing) selectedEditingWorkflow else selectedWorkflow
    val workflows = if (isEditing) editingWorkflows else availableWorkflows
    val onWorkflowChange = if (isEditing) callbacks.onEditingWorkflowChange else callbacks.onWorkflowChange
    val onViewWorkflow = if (isEditing) callbacks.onViewEditingWorkflow else callbacks.onViewWorkflow

    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = workflowName,
            availableWorkflows = workflows,
            onWorkflowChange = onWorkflowChange,
            onViewWorkflow = onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = if (isEditing) editingNegativePrompt else negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = ItiConfig(
            mode = mode,
            onModeChange = callbacks.onModeChange,
            referenceImage1 = referenceImage1,
            onReferenceImage1Change = callbacks.onReferenceImage1Change,
            onClearReferenceImage1 = callbacks.onClearReferenceImage1,
            hasReferenceImage1 = capabilities.hasReferenceImage1,
            referenceImage2 = referenceImage2,
            onReferenceImage2Change = callbacks.onReferenceImage2Change,
            onClearReferenceImage2 = callbacks.onClearReferenceImage2,
            hasReferenceImage2 = capabilities.hasReferenceImage2
        ),
        models = if (isEditing) {
            // Editing mode models
            ModelConfig(
                unet = if (capabilities.hasUnetName) ModelField(
                    label = R.string.label_unet,
                    selectedValue = selectedEditingUnet,
                    options = unets,
                    filteredOptions = filteredUnets,
                    onValueChange = callbacks.onEditingUnetChange,
                    isVisible = true
                ) else null,
                vae = if (capabilities.hasVaeName) ModelField(
                    label = R.string.label_vae,
                    selectedValue = selectedEditingVae,
                    options = vaes,
                    filteredOptions = filteredVaes,
                    onValueChange = callbacks.onEditingVaeChange,
                    isVisible = true
                ) else null,
                clip = if (capabilities.hasClipName) ModelField(
                    label = R.string.label_clip,
                    selectedValue = selectedEditingClip,
                    options = clips,
                    filteredOptions = filteredClips,
                    onValueChange = callbacks.onEditingClipChange,
                    isVisible = true
                ) else null,
                clip1 = if (capabilities.hasClipName1) ModelField(
                    label = R.string.label_clip1,
                    selectedValue = selectedEditingClip1,
                    options = clips,
                    filteredOptions = filteredClips1,
                    onValueChange = callbacks.onEditingClip1Change,
                    isVisible = true
                ) else null,
                clip2 = if (capabilities.hasClipName2) ModelField(
                    label = R.string.label_clip2,
                    selectedValue = selectedEditingClip2,
                    options = clips,
                    filteredOptions = filteredClips2,
                    onValueChange = callbacks.onEditingClip2Change,
                    isVisible = true
                ) else null,
                clip3 = if (capabilities.hasClipName3) ModelField(
                    label = R.string.label_clip3,
                    selectedValue = selectedEditingClip3,
                    options = clips,
                    filteredOptions = filteredClips3,
                    onValueChange = callbacks.onEditingClip3Change,
                    isVisible = true
                ) else null,
                clip4 = if (capabilities.hasClipName4) ModelField(
                    label = R.string.label_clip4,
                    selectedValue = selectedEditingClip4,
                    options = clips,
                    filteredOptions = filteredClips4,
                    onValueChange = callbacks.onEditingClip4Change,
                    isVisible = true
                ) else null,
                textEncoder = if (capabilities.hasTextEncoderName) ModelField(
                    label = R.string.label_text_encoder,
                    selectedValue = selectedEditingTextEncoder,
                    options = textEncoders,
                    filteredOptions = filteredTextEncoders,
                    onValueChange = callbacks.onEditingTextEncoderChange,
                    isVisible = true
                ) else null,
                latentUpscaleModel = if (capabilities.hasLatentUpscaleModel) ModelField(
                    label = R.string.label_latent_upscale_model,
                    selectedValue = selectedEditingLatentUpscaleModel,
                    options = latentUpscaleModels,
                    filteredOptions = filteredLatentUpscaleModels,
                    onValueChange = callbacks.onEditingLatentUpscaleModelChange,
                    isVisible = true
                ) else null
            )
        } else {
            // Inpainting mode models
            ModelConfig(
                checkpoint = if (capabilities.hasCheckpointName) ModelField(
                    label = R.string.label_checkpoint,
                    selectedValue = selectedCheckpoint,
                    options = checkpoints,
                    filteredOptions = filteredCheckpoints,
                    onValueChange = callbacks.onCheckpointChange,
                    isVisible = true
                ) else null,
                unet = if (capabilities.hasUnetName) ModelField(
                    label = R.string.label_unet,
                    selectedValue = selectedUnet,
                    options = unets,
                    filteredOptions = filteredUnets,
                    onValueChange = callbacks.onUnetChange,
                    isVisible = true
                ) else null,
                vae = if (capabilities.hasVaeName) ModelField(
                    label = R.string.label_vae,
                    selectedValue = selectedVae,
                    options = vaes,
                    filteredOptions = filteredVaes,
                    onValueChange = callbacks.onVaeChange,
                    isVisible = true
                ) else null,
                clip = if (capabilities.hasClipName) ModelField(
                    label = R.string.label_clip,
                    selectedValue = selectedClip,
                    options = clips,
                    filteredOptions = filteredClips,
                    onValueChange = callbacks.onClipChange,
                    isVisible = true
                ) else null,
                clip1 = if (capabilities.hasClipName1) ModelField(
                    label = R.string.label_clip1,
                    selectedValue = selectedClip1,
                    options = clips,
                    filteredOptions = filteredClips1,
                    onValueChange = callbacks.onClip1Change,
                    isVisible = true
                ) else null,
                clip2 = if (capabilities.hasClipName2) ModelField(
                    label = R.string.label_clip2,
                    selectedValue = selectedClip2,
                    options = clips,
                    filteredOptions = filteredClips2,
                    onValueChange = callbacks.onClip2Change,
                    isVisible = true
                ) else null,
                clip3 = if (capabilities.hasClipName3) ModelField(
                    label = R.string.label_clip3,
                    selectedValue = selectedClip3,
                    options = clips,
                    filteredOptions = filteredClips3,
                    onValueChange = callbacks.onClip3Change,
                    isVisible = true
                ) else null,
                clip4 = if (capabilities.hasClipName4) ModelField(
                    label = R.string.label_clip4,
                    selectedValue = selectedClip4,
                    options = clips,
                    filteredOptions = filteredClips4,
                    onValueChange = callbacks.onClip4Change,
                    isVisible = true
                ) else null,
                textEncoder = if (capabilities.hasTextEncoderName) ModelField(
                    label = R.string.label_text_encoder,
                    selectedValue = selectedTextEncoder,
                    options = textEncoders,
                    filteredOptions = filteredTextEncoders,
                    onValueChange = callbacks.onTextEncoderChange,
                    isVisible = true
                ) else null,
                latentUpscaleModel = if (capabilities.hasLatentUpscaleModel) ModelField(
                    label = R.string.label_latent_upscale_model,
                    selectedValue = selectedLatentUpscaleModel,
                    options = latentUpscaleModels,
                    filteredOptions = filteredLatentUpscaleModels,
                    onValueChange = callbacks.onLatentUpscaleModelChange,
                    isVisible = true
                ) else null
            )
        },
        parameters = if (isEditing) {
            ParameterConfig(
                megapixels = NumericField(
                    value = editingMegapixels,
                    onValueChange = callbacks.onEditingMegapixelsChange,
                    error = megapixelsError,
                    isVisible = capabilities.hasMegapixels
                ),
                steps = NumericField(
                    value = editingSteps,
                    onValueChange = callbacks.onEditingStepsChange,
                    error = stepsError,
                    isVisible = capabilities.hasSteps
                ),
                cfg = NumericField(
                    value = editingCfg,
                    onValueChange = callbacks.onEditingCfgChange,
                    error = cfgError,
                    isVisible = capabilities.hasCfg
                ),
                sampler = DropdownField(
                    selectedValue = editingSampler,
                    options = SamplerOptions.SAMPLERS,
                    onValueChange = callbacks.onEditingSamplerChange,
                    isVisible = capabilities.hasSamplerName
                ),
                scheduler = DropdownField(
                    selectedValue = editingScheduler,
                    options = SamplerOptions.SCHEDULERS,
                    onValueChange = callbacks.onEditingSchedulerChange,
                    isVisible = capabilities.hasScheduler
                ),
                seed = SeedConfig(
                    randomSeed = editingRandomSeed,
                    onRandomSeedToggle = callbacks.onEditingRandomSeedToggle,
                    seed = editingSeed,
                    onSeedChange = callbacks.onEditingSeedChange,
                    onRandomizeSeed = callbacks.onEditingRandomizeSeed,
                    seedError = seedError,
                    isVisible = capabilities.hasSeed
                ),
                denoise = NumericField(
                    value = editingDenoise,
                    onValueChange = callbacks.onEditingDenoiseChange,
                    error = denoiseError,
                    isVisible = capabilities.hasDenoise
                ),
                batchSize = NumericField(
                    value = editingBatchSize,
                    onValueChange = callbacks.onEditingBatchSizeChange,
                    error = batchSizeError,
                    isVisible = capabilities.hasBatchSize
                ),
                upscaleMethod = DropdownField(
                    selectedValue = editingUpscaleMethod,
                    options = availableUpscaleMethods,
                    onValueChange = callbacks.onEditingUpscaleMethodChange,
                    isVisible = capabilities.hasUpscaleMethod
                ),
                scaleBy = NumericField(
                    value = editingScaleBy,
                    onValueChange = callbacks.onEditingScaleByChange,
                    error = scaleByError,
                    isVisible = capabilities.hasScaleBy
                ),
                stopAtClipLayer = NumericField(
                    value = editingStopAtClipLayer,
                    onValueChange = callbacks.onEditingStopAtClipLayerChange,
                    error = stopAtClipLayerError,
                    isVisible = capabilities.hasStopAtClipLayer
                )
            )
        } else {
            // Inpainting mode parameters - use unified fields
            ParameterConfig(
                megapixels = NumericField(
                    value = megapixels,
                    onValueChange = callbacks.onMegapixelsChange,
                    error = megapixelsError,
                    isVisible = capabilities.hasMegapixels
                ),
                steps = NumericField(
                    value = steps,
                    onValueChange = callbacks.onStepsChange,
                    error = stepsError,
                    isVisible = capabilities.hasSteps
                ),
                cfg = NumericField(
                    value = cfg,
                    onValueChange = callbacks.onCfgChange,
                    error = cfgError,
                    isVisible = capabilities.hasCfg
                ),
                sampler = DropdownField(
                    selectedValue = sampler,
                    options = SamplerOptions.SAMPLERS,
                    onValueChange = callbacks.onSamplerChange,
                    isVisible = capabilities.hasSamplerName
                ),
                scheduler = DropdownField(
                    selectedValue = scheduler,
                    options = SamplerOptions.SCHEDULERS,
                    onValueChange = callbacks.onSchedulerChange,
                    isVisible = capabilities.hasScheduler
                ),
                seed = SeedConfig(
                    randomSeed = randomSeed,
                    onRandomSeedToggle = callbacks.onRandomSeedToggle,
                    seed = seed,
                    onSeedChange = callbacks.onSeedChange,
                    onRandomizeSeed = callbacks.onRandomizeSeed,
                    seedError = seedError,
                    isVisible = capabilities.hasSeed
                ),
                denoise = NumericField(
                    value = denoise,
                    onValueChange = callbacks.onDenoiseChange,
                    error = denoiseError,
                    isVisible = capabilities.hasDenoise
                ),
                batchSize = NumericField(
                    value = batchSize,
                    onValueChange = callbacks.onBatchSizeChange,
                    error = batchSizeError,
                    isVisible = capabilities.hasBatchSize
                ),
                upscaleMethod = DropdownField(
                    selectedValue = upscaleMethod,
                    options = availableUpscaleMethods,
                    onValueChange = callbacks.onUpscaleMethodChange,
                    isVisible = capabilities.hasUpscaleMethod
                ),
                scaleBy = NumericField(
                    value = scaleBy,
                    onValueChange = callbacks.onScaleByChange,
                    error = scaleByError,
                    isVisible = capabilities.hasScaleBy
                ),
                stopAtClipLayer = NumericField(
                    value = stopAtClipLayer,
                    onValueChange = callbacks.onStopAtClipLayerChange,
                    error = stopAtClipLayerError,
                    isVisible = capabilities.hasStopAtClipLayer
                )
            )
        },
        lora = if (isEditing) {
            LoraConfig(
                loraName = if (capabilities.hasLoraName) ModelField(
                    label = R.string.label_lora,
                    selectedValue = selectedEditingLora,
                    options = availableLoras,
                    onValueChange = callbacks.onEditingLoraChange,
                    isVisible = true
                ) else null,
                primaryChain = if (capabilities.hasLora) LoraChainField(
                    title = R.string.lora_chain_title,
                    chain = editingLoraChain,
                    availableLoras = availableLoras,
                    onAdd = callbacks.onAddEditingLora,
                    onRemove = callbacks.onRemoveEditingLora,
                    onNameChange = callbacks.onEditingLoraNameChange,
                    onStrengthChange = callbacks.onEditingLoraStrengthChange,
                    isVisible = true
                ) else null
            )
        } else {
            // Inpainting LoRA - use unified chain
            LoraConfig(
                primaryChain = if (capabilities.hasLora) LoraChainField(
                    title = R.string.lora_chain_title,
                    chain = loraChain,
                    availableLoras = availableLoras,
                    onAdd = callbacks.onAddLora,
                    onRemove = callbacks.onRemoveLora,
                    onNameChange = callbacks.onLoraNameChange,
                    onStrengthChange = callbacks.onLoraStrengthChange,
                    isVisible = true
                ) else null
            )
        }
    )
}
