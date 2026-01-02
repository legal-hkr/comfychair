package sh.hnet.comfychair.ui.components.config

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
    val onLoraNameChange: (Int, String) -> Unit,
    val onLoraStrengthChange: (Int, Float) -> Unit
)

data class TextToVideoCallbacks(
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,
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
    val onWidthChange: (String) -> Unit,
    val onHeightChange: (String) -> Unit,
    val onLengthChange: (String) -> Unit,
    val onFpsChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val onDenoiseChange: (String) -> Unit,
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,
    val onStopAtClipLayerChange: (String) -> Unit,
    val onAddHighnoiseLora: () -> Unit,
    val onRemoveHighnoiseLora: (Int) -> Unit,
    val onHighnoiseLoraNameChange: (Int, String) -> Unit,
    val onHighnoiseLoraStrengthChange: (Int, Float) -> Unit,
    val onAddLownoiseLora: () -> Unit,
    val onRemoveLownoiseLora: (Int) -> Unit,
    val onLownoiseLoraNameChange: (Int, String) -> Unit,
    val onLownoiseLoraStrengthChange: (Int, Float) -> Unit
)

data class ImageToVideoCallbacks(
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,
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
    val onWidthChange: (String) -> Unit,
    val onHeightChange: (String) -> Unit,
    val onLengthChange: (String) -> Unit,
    val onFpsChange: (String) -> Unit,
    val onRandomSeedToggle: () -> Unit,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val onDenoiseChange: (String) -> Unit,
    val onBatchSizeChange: (String) -> Unit,
    val onUpscaleMethodChange: (String) -> Unit,
    val onScaleByChange: (String) -> Unit,
    val onStopAtClipLayerChange: (String) -> Unit,
    val onAddHighnoiseLora: () -> Unit,
    val onRemoveHighnoiseLora: (Int) -> Unit,
    val onHighnoiseLoraNameChange: (Int, String) -> Unit,
    val onHighnoiseLoraStrengthChange: (Int, Float) -> Unit,
    val onAddLownoiseLora: () -> Unit,
    val onRemoveLownoiseLora: (Int) -> Unit,
    val onLownoiseLoraNameChange: (Int, String) -> Unit,
    val onLownoiseLoraStrengthChange: (Int, Float) -> Unit
)

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
    // Editing models
    val onEditingUnetChange: (String) -> Unit,
    val onEditingLoraChange: (String) -> Unit,
    val onEditingVaeChange: (String) -> Unit,
    val onEditingClipChange: (String) -> Unit,
    val onEditingClip1Change: (String) -> Unit,
    val onEditingClip2Change: (String) -> Unit,
    val onEditingClip3Change: (String) -> Unit,
    val onEditingClip4Change: (String) -> Unit,
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
 * Convert TextToImageUiState to BottomSheetConfig
 */
fun TextToImageUiState.toBottomSheetConfig(callbacks: TextToImageCallbacks): BottomSheetConfig {
    val isCheckpoint = this.isCheckpointMode

    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            onViewWorkflow = callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = if (isCheckpoint) checkpointNegativePrompt else unetNegativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = currentWorkflowHasNegativePrompt
        ),
        itiConfig = null,
        models = ModelConfig(
            checkpoint = if (currentWorkflowHasCheckpointName) ModelField(
                label = R.string.label_checkpoint,
                selectedValue = selectedCheckpoint,
                options = availableCheckpoints,
                filteredOptions = filteredCheckpoints,
                onValueChange = callbacks.onCheckpointChange,
                isVisible = true
            ) else null,
            unet = if (currentWorkflowHasUnetName) ModelField(
                label = R.string.label_unet,
                selectedValue = selectedUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onUnetChange,
                isVisible = true
            ) else null,
            vae = if (currentWorkflowHasVaeName) ModelField(
                label = R.string.label_vae,
                selectedValue = selectedVae,
                options = availableVaes,
                filteredOptions = filteredVaes,
                onValueChange = callbacks.onVaeChange,
                isVisible = true
            ) else null,
            clip = if (currentWorkflowHasClipName) ModelField(
                label = R.string.label_clip,
                selectedValue = selectedClip,
                options = availableClips,
                filteredOptions = filteredClips,
                onValueChange = callbacks.onClipChange,
                isVisible = true
            ) else null,
            clip1 = if (currentWorkflowHasClipName1) ModelField(
                label = R.string.label_clip1,
                selectedValue = selectedClip1,
                options = availableClips,
                filteredOptions = filteredClips1,
                onValueChange = callbacks.onClip1Change,
                isVisible = true
            ) else null,
            clip2 = if (currentWorkflowHasClipName2) ModelField(
                label = R.string.label_clip2,
                selectedValue = selectedClip2,
                options = availableClips,
                filteredOptions = filteredClips2,
                onValueChange = callbacks.onClip2Change,
                isVisible = true
            ) else null,
            clip3 = if (currentWorkflowHasClipName3) ModelField(
                label = R.string.label_clip3,
                selectedValue = selectedClip3,
                options = availableClips,
                filteredOptions = filteredClips3,
                onValueChange = callbacks.onClip3Change,
                isVisible = true
            ) else null,
            clip4 = if (currentWorkflowHasClipName4) ModelField(
                label = R.string.label_clip4,
                selectedValue = selectedClip4,
                options = availableClips,
                filteredOptions = filteredClips4,
                onValueChange = callbacks.onClip4Change,
                isVisible = true
            ) else null
        ),
        parameters = ParameterConfig(
            width = NumericField(
                value = if (isCheckpoint) checkpointWidth else unetWidth,
                onValueChange = callbacks.onWidthChange,
                error = widthError,
                isVisible = currentWorkflowHasWidth
            ),
            height = NumericField(
                value = if (isCheckpoint) checkpointHeight else unetHeight,
                onValueChange = callbacks.onHeightChange,
                error = heightError,
                isVisible = currentWorkflowHasHeight
            ),
            steps = NumericField(
                value = if (isCheckpoint) checkpointSteps else unetSteps,
                onValueChange = callbacks.onStepsChange,
                error = stepsError,
                isVisible = currentWorkflowHasSteps
            ),
            cfg = NumericField(
                value = if (isCheckpoint) checkpointCfg else unetCfg,
                onValueChange = callbacks.onCfgChange,
                error = cfgError,
                isVisible = currentWorkflowHasCfg
            ),
            sampler = DropdownField(
                selectedValue = if (isCheckpoint) checkpointSampler else unetSampler,
                options = SamplerOptions.SAMPLERS,
                onValueChange = callbacks.onSamplerChange,
                isVisible = currentWorkflowHasSamplerName
            ),
            scheduler = DropdownField(
                selectedValue = if (isCheckpoint) checkpointScheduler else unetScheduler,
                options = SamplerOptions.SCHEDULERS,
                onValueChange = callbacks.onSchedulerChange,
                isVisible = currentWorkflowHasScheduler
            ),
            seed = SeedConfig(
                randomSeed = if (isCheckpoint) checkpointRandomSeed else unetRandomSeed,
                onRandomSeedToggle = callbacks.onRandomSeedToggle,
                seed = if (isCheckpoint) checkpointSeed else unetSeed,
                onSeedChange = callbacks.onSeedChange,
                onRandomizeSeed = callbacks.onRandomizeSeed,
                seedError = seedError,
                isVisible = currentWorkflowHasSeed
            ),
            denoise = NumericField(
                value = if (isCheckpoint) checkpointDenoise else unetDenoise,
                onValueChange = callbacks.onDenoiseChange,
                error = denoiseError,
                isVisible = currentWorkflowHasDenoise
            ),
            batchSize = NumericField(
                value = if (isCheckpoint) checkpointBatchSize else unetBatchSize,
                onValueChange = callbacks.onBatchSizeChange,
                error = batchSizeError,
                isVisible = currentWorkflowHasBatchSize
            ),
            upscaleMethod = DropdownField(
                selectedValue = if (isCheckpoint) checkpointUpscaleMethod else unetUpscaleMethod,
                options = availableUpscaleMethods,
                onValueChange = callbacks.onUpscaleMethodChange,
                isVisible = currentWorkflowHasUpscaleMethod
            ),
            scaleBy = NumericField(
                value = if (isCheckpoint) checkpointScaleBy else unetScaleBy,
                onValueChange = callbacks.onScaleByChange,
                error = scaleByError,
                isVisible = currentWorkflowHasScaleBy
            ),
            stopAtClipLayer = NumericField(
                value = if (isCheckpoint) checkpointStopAtClipLayer else unetStopAtClipLayer,
                onValueChange = callbacks.onStopAtClipLayerChange,
                error = stopAtClipLayerError,
                isVisible = currentWorkflowHasStopAtClipLayer
            )
        ),
        lora = LoraConfig(
            primaryChain = if (currentWorkflowHasLora) LoraChainField(
                title = R.string.lora_chain_title,
                chain = if (isCheckpoint) checkpointLoraChain else unetLoraChain,
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
            hasNegativePrompt = currentWorkflowHasNegativePrompt
        ),
        itiConfig = null,
        models = ModelConfig(
            highnoiseUnet = if (currentWorkflowHasHighnoiseUnet) ModelField(
                label = R.string.highnoise_unet_label,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange,
                isVisible = true
            ) else null,
            lownoiseUnet = if (currentWorkflowHasLownoiseUnet) ModelField(
                label = R.string.lownoise_unet_label,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange,
                isVisible = true
            ) else null,
            highnoiseLora = if (currentWorkflowHasHighnoiseLora) ModelField(
                label = R.string.highnoise_lora_label,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onHighnoiseLoraChange,
                isVisible = true
            ) else null,
            lownoiseLora = if (currentWorkflowHasLownoiseLora) ModelField(
                label = R.string.lownoise_lora_label,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onLownoiseLoraChange,
                isVisible = true
            ) else null,
            vae = if (currentWorkflowHasVaeName) ModelField(
                label = R.string.label_vae,
                selectedValue = selectedVae,
                options = availableVaes,
                filteredOptions = filteredVaes,
                onValueChange = callbacks.onVaeChange,
                isVisible = true
            ) else null,
            clip = if (currentWorkflowHasClipName) ModelField(
                label = R.string.label_clip,
                selectedValue = selectedClip,
                options = availableClips,
                filteredOptions = filteredClips,
                onValueChange = callbacks.onClipChange,
                isVisible = true
            ) else null,
            clip1 = if (currentWorkflowHasClipName1) ModelField(
                label = R.string.label_clip1,
                selectedValue = selectedClip1,
                options = availableClips,
                filteredOptions = filteredClips1,
                onValueChange = callbacks.onClip1Change,
                isVisible = true
            ) else null,
            clip2 = if (currentWorkflowHasClipName2) ModelField(
                label = R.string.label_clip2,
                selectedValue = selectedClip2,
                options = availableClips,
                filteredOptions = filteredClips2,
                onValueChange = callbacks.onClip2Change,
                isVisible = true
            ) else null,
            clip3 = if (currentWorkflowHasClipName3) ModelField(
                label = R.string.label_clip3,
                selectedValue = selectedClip3,
                options = availableClips,
                filteredOptions = filteredClips3,
                onValueChange = callbacks.onClip3Change,
                isVisible = true
            ) else null,
            clip4 = if (currentWorkflowHasClipName4) ModelField(
                label = R.string.label_clip4,
                selectedValue = selectedClip4,
                options = availableClips,
                filteredOptions = filteredClips4,
                onValueChange = callbacks.onClip4Change,
                isVisible = true
            ) else null
        ),
        parameters = ParameterConfig(
            width = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange,
                error = widthError,
                isVisible = currentWorkflowHasWidth
            ),
            height = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange,
                error = heightError,
                isVisible = currentWorkflowHasHeight
            ),
            length = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange,
                error = lengthError,
                isVisible = currentWorkflowHasLength
            ),
            fps = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange,
                error = fpsError,
                isVisible = currentWorkflowHasFrameRate
            ),
            seed = SeedConfig(
                randomSeed = randomSeed,
                onRandomSeedToggle = callbacks.onRandomSeedToggle,
                seed = seed,
                onSeedChange = callbacks.onSeedChange,
                onRandomizeSeed = callbacks.onRandomizeSeed,
                seedError = seedError,
                isVisible = currentWorkflowHasSeed
            ),
            denoise = NumericField(
                value = denoise,
                onValueChange = callbacks.onDenoiseChange,
                error = denoiseError,
                isVisible = currentWorkflowHasDenoise
            ),
            batchSize = NumericField(
                value = batchSize,
                onValueChange = callbacks.onBatchSizeChange,
                error = batchSizeError,
                isVisible = currentWorkflowHasBatchSize
            ),
            upscaleMethod = DropdownField(
                selectedValue = upscaleMethod,
                options = availableUpscaleMethods,
                onValueChange = callbacks.onUpscaleMethodChange,
                isVisible = currentWorkflowHasUpscaleMethod
            ),
            scaleBy = NumericField(
                value = scaleBy,
                onValueChange = callbacks.onScaleByChange,
                error = scaleByError,
                isVisible = currentWorkflowHasScaleBy
            ),
            stopAtClipLayer = NumericField(
                value = stopAtClipLayer,
                onValueChange = callbacks.onStopAtClipLayerChange,
                error = stopAtClipLayerError,
                isVisible = currentWorkflowHasStopAtClipLayer
            )
        ),
        lora = LoraConfig(
            highnoiseChain = if (currentWorkflowHasHighnoiseUnet) LoraChainField(
                title = R.string.highnoise_lora_chain_title,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange,
                isVisible = true
            ) else null,
            lownoiseChain = if (currentWorkflowHasLownoiseUnet) LoraChainField(
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
            hasNegativePrompt = currentWorkflowHasNegativePrompt
        ),
        itiConfig = null,
        models = ModelConfig(
            highnoiseUnet = if (currentWorkflowHasHighnoiseUnet) ModelField(
                label = R.string.highnoise_unet_label,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange,
                isVisible = true
            ) else null,
            lownoiseUnet = if (currentWorkflowHasLownoiseUnet) ModelField(
                label = R.string.lownoise_unet_label,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange,
                isVisible = true
            ) else null,
            highnoiseLora = if (currentWorkflowHasHighnoiseLora) ModelField(
                label = R.string.highnoise_lora_label,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onHighnoiseLoraChange,
                isVisible = true
            ) else null,
            lownoiseLora = if (currentWorkflowHasLownoiseLora) ModelField(
                label = R.string.lownoise_lora_label,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                onValueChange = callbacks.onLownoiseLoraChange,
                isVisible = true
            ) else null,
            vae = if (currentWorkflowHasVaeName) ModelField(
                label = R.string.label_vae,
                selectedValue = selectedVae,
                options = availableVaes,
                filteredOptions = filteredVaes,
                onValueChange = callbacks.onVaeChange,
                isVisible = true
            ) else null,
            clip = if (currentWorkflowHasClipName) ModelField(
                label = R.string.label_clip,
                selectedValue = selectedClip,
                options = availableClips,
                filteredOptions = filteredClips,
                onValueChange = callbacks.onClipChange,
                isVisible = true
            ) else null,
            clip1 = if (currentWorkflowHasClipName1) ModelField(
                label = R.string.label_clip1,
                selectedValue = selectedClip1,
                options = availableClips,
                filteredOptions = filteredClips1,
                onValueChange = callbacks.onClip1Change,
                isVisible = true
            ) else null,
            clip2 = if (currentWorkflowHasClipName2) ModelField(
                label = R.string.label_clip2,
                selectedValue = selectedClip2,
                options = availableClips,
                filteredOptions = filteredClips2,
                onValueChange = callbacks.onClip2Change,
                isVisible = true
            ) else null,
            clip3 = if (currentWorkflowHasClipName3) ModelField(
                label = R.string.label_clip3,
                selectedValue = selectedClip3,
                options = availableClips,
                filteredOptions = filteredClips3,
                onValueChange = callbacks.onClip3Change,
                isVisible = true
            ) else null,
            clip4 = if (currentWorkflowHasClipName4) ModelField(
                label = R.string.label_clip4,
                selectedValue = selectedClip4,
                options = availableClips,
                filteredOptions = filteredClips4,
                onValueChange = callbacks.onClip4Change,
                isVisible = true
            ) else null
        ),
        parameters = ParameterConfig(
            width = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange,
                error = widthError,
                isVisible = currentWorkflowHasWidth
            ),
            height = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange,
                error = heightError,
                isVisible = currentWorkflowHasHeight
            ),
            length = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange,
                error = lengthError,
                isVisible = currentWorkflowHasLength
            ),
            fps = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange,
                error = fpsError,
                isVisible = currentWorkflowHasFrameRate
            ),
            seed = SeedConfig(
                randomSeed = randomSeed,
                onRandomSeedToggle = callbacks.onRandomSeedToggle,
                seed = seed,
                onSeedChange = callbacks.onSeedChange,
                onRandomizeSeed = callbacks.onRandomizeSeed,
                seedError = seedError,
                isVisible = currentWorkflowHasSeed
            ),
            denoise = NumericField(
                value = denoise,
                onValueChange = callbacks.onDenoiseChange,
                error = denoiseError,
                isVisible = currentWorkflowHasDenoise
            ),
            batchSize = NumericField(
                value = batchSize,
                onValueChange = callbacks.onBatchSizeChange,
                error = batchSizeError,
                isVisible = currentWorkflowHasBatchSize
            ),
            upscaleMethod = DropdownField(
                selectedValue = upscaleMethod,
                options = availableUpscaleMethods,
                onValueChange = callbacks.onUpscaleMethodChange,
                isVisible = currentWorkflowHasUpscaleMethod
            ),
            scaleBy = NumericField(
                value = scaleBy,
                onValueChange = callbacks.onScaleByChange,
                error = scaleByError,
                isVisible = currentWorkflowHasScaleBy
            ),
            stopAtClipLayer = NumericField(
                value = stopAtClipLayer,
                onValueChange = callbacks.onStopAtClipLayerChange,
                error = stopAtClipLayerError,
                isVisible = currentWorkflowHasStopAtClipLayer
            )
        ),
        lora = LoraConfig(
            highnoiseChain = if (currentWorkflowHasHighnoiseUnet) LoraChainField(
                title = R.string.highnoise_lora_chain_title,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange,
                isVisible = true
            ) else null,
            lownoiseChain = if (currentWorkflowHasLownoiseUnet) LoraChainField(
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
 * Convert ImageToImageUiState to BottomSheetConfig
 * This is the most complex because it handles both EDITING and INPAINTING modes.
 */
fun ImageToImageUiState.toBottomSheetConfig(callbacks: ImageToImageCallbacks): BottomSheetConfig {
    val isEditing = mode == sh.hnet.comfychair.viewmodel.ImageToImageMode.EDITING
    val isCheckpoint = isCheckpointMode

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
            negativePrompt = if (isEditing) editingNegativePrompt else {
                if (isCheckpoint) checkpointNegativePrompt else unetNegativePrompt
            },
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = currentWorkflowHasNegativePrompt
        ),
        itiConfig = ItiConfig(
            mode = mode,
            onModeChange = callbacks.onModeChange,
            referenceImage1 = referenceImage1,
            onReferenceImage1Change = callbacks.onReferenceImage1Change,
            onClearReferenceImage1 = callbacks.onClearReferenceImage1,
            hasReferenceImage1 = currentWorkflowHasReferenceImage1,
            referenceImage2 = referenceImage2,
            onReferenceImage2Change = callbacks.onReferenceImage2Change,
            onClearReferenceImage2 = callbacks.onClearReferenceImage2,
            hasReferenceImage2 = currentWorkflowHasReferenceImage2
        ),
        models = if (isEditing) {
            // Editing mode models
            ModelConfig(
                unet = if (currentWorkflowHasUnetName) ModelField(
                    label = R.string.label_unet,
                    selectedValue = selectedEditingUnet,
                    options = unets,
                    filteredOptions = filteredUnets,
                    onValueChange = callbacks.onEditingUnetChange,
                    isVisible = true
                ) else null,
                vae = if (currentWorkflowHasVaeName) ModelField(
                    label = R.string.label_vae,
                    selectedValue = selectedEditingVae,
                    options = vaes,
                    filteredOptions = filteredVaes,
                    onValueChange = callbacks.onEditingVaeChange,
                    isVisible = true
                ) else null,
                clip = if (currentWorkflowHasClipName) ModelField(
                    label = R.string.label_clip,
                    selectedValue = selectedEditingClip,
                    options = clips,
                    filteredOptions = filteredClips,
                    onValueChange = callbacks.onEditingClipChange,
                    isVisible = true
                ) else null,
                clip1 = if (currentWorkflowHasClipName1) ModelField(
                    label = R.string.label_clip1,
                    selectedValue = selectedEditingClip1,
                    options = clips,
                    filteredOptions = filteredClips1,
                    onValueChange = callbacks.onEditingClip1Change,
                    isVisible = true
                ) else null,
                clip2 = if (currentWorkflowHasClipName2) ModelField(
                    label = R.string.label_clip2,
                    selectedValue = selectedEditingClip2,
                    options = clips,
                    filteredOptions = filteredClips2,
                    onValueChange = callbacks.onEditingClip2Change,
                    isVisible = true
                ) else null,
                clip3 = if (currentWorkflowHasClipName3) ModelField(
                    label = R.string.label_clip3,
                    selectedValue = selectedEditingClip3,
                    options = clips,
                    filteredOptions = filteredClips3,
                    onValueChange = callbacks.onEditingClip3Change,
                    isVisible = true
                ) else null,
                clip4 = if (currentWorkflowHasClipName4) ModelField(
                    label = R.string.label_clip4,
                    selectedValue = selectedEditingClip4,
                    options = clips,
                    filteredOptions = filteredClips4,
                    onValueChange = callbacks.onEditingClip4Change,
                    isVisible = true
                ) else null
            )
        } else {
            // Inpainting mode models
            ModelConfig(
                checkpoint = if (currentWorkflowHasCheckpointName) ModelField(
                    label = R.string.label_checkpoint,
                    selectedValue = selectedCheckpoint,
                    options = checkpoints,
                    filteredOptions = filteredCheckpoints,
                    onValueChange = callbacks.onCheckpointChange,
                    isVisible = true
                ) else null,
                unet = if (currentWorkflowHasUnetName) ModelField(
                    label = R.string.label_unet,
                    selectedValue = selectedUnet,
                    options = unets,
                    filteredOptions = filteredUnets,
                    onValueChange = callbacks.onUnetChange,
                    isVisible = true
                ) else null,
                vae = if (currentWorkflowHasVaeName) ModelField(
                    label = R.string.label_vae,
                    selectedValue = selectedVae,
                    options = vaes,
                    filteredOptions = filteredVaes,
                    onValueChange = callbacks.onVaeChange,
                    isVisible = true
                ) else null,
                clip = if (currentWorkflowHasClipName) ModelField(
                    label = R.string.label_clip,
                    selectedValue = selectedClip,
                    options = clips,
                    filteredOptions = filteredClips,
                    onValueChange = callbacks.onClipChange,
                    isVisible = true
                ) else null,
                clip1 = if (currentWorkflowHasClipName1) ModelField(
                    label = R.string.label_clip1,
                    selectedValue = selectedClip1,
                    options = clips,
                    filteredOptions = filteredClips1,
                    onValueChange = callbacks.onClip1Change,
                    isVisible = true
                ) else null,
                clip2 = if (currentWorkflowHasClipName2) ModelField(
                    label = R.string.label_clip2,
                    selectedValue = selectedClip2,
                    options = clips,
                    filteredOptions = filteredClips2,
                    onValueChange = callbacks.onClip2Change,
                    isVisible = true
                ) else null,
                clip3 = if (currentWorkflowHasClipName3) ModelField(
                    label = R.string.label_clip3,
                    selectedValue = selectedClip3,
                    options = clips,
                    filteredOptions = filteredClips3,
                    onValueChange = callbacks.onClip3Change,
                    isVisible = true
                ) else null,
                clip4 = if (currentWorkflowHasClipName4) ModelField(
                    label = R.string.label_clip4,
                    selectedValue = selectedClip4,
                    options = clips,
                    filteredOptions = filteredClips4,
                    onValueChange = callbacks.onClip4Change,
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
                    isVisible = currentWorkflowHasMegapixels
                ),
                steps = NumericField(
                    value = editingSteps,
                    onValueChange = callbacks.onEditingStepsChange,
                    error = stepsError,
                    isVisible = currentWorkflowHasSteps
                ),
                cfg = NumericField(
                    value = editingCfg,
                    onValueChange = callbacks.onEditingCfgChange,
                    error = cfgError,
                    isVisible = currentWorkflowHasCfg
                ),
                sampler = DropdownField(
                    selectedValue = editingSampler,
                    options = SamplerOptions.SAMPLERS,
                    onValueChange = callbacks.onEditingSamplerChange,
                    isVisible = currentWorkflowHasSamplerName
                ),
                scheduler = DropdownField(
                    selectedValue = editingScheduler,
                    options = SamplerOptions.SCHEDULERS,
                    onValueChange = callbacks.onEditingSchedulerChange,
                    isVisible = currentWorkflowHasScheduler
                ),
                seed = SeedConfig(
                    randomSeed = editingRandomSeed,
                    onRandomSeedToggle = callbacks.onEditingRandomSeedToggle,
                    seed = editingSeed,
                    onSeedChange = callbacks.onEditingSeedChange,
                    onRandomizeSeed = callbacks.onEditingRandomizeSeed,
                    seedError = seedError,
                    isVisible = currentWorkflowHasSeed
                ),
                denoise = NumericField(
                    value = editingDenoise,
                    onValueChange = callbacks.onEditingDenoiseChange,
                    error = denoiseError,
                    isVisible = currentWorkflowHasDenoise
                ),
                batchSize = NumericField(
                    value = editingBatchSize,
                    onValueChange = callbacks.onEditingBatchSizeChange,
                    error = batchSizeError,
                    isVisible = currentWorkflowHasBatchSize
                ),
                upscaleMethod = DropdownField(
                    selectedValue = editingUpscaleMethod,
                    options = availableUpscaleMethods,
                    onValueChange = callbacks.onEditingUpscaleMethodChange,
                    isVisible = currentWorkflowHasUpscaleMethod
                ),
                scaleBy = NumericField(
                    value = editingScaleBy,
                    onValueChange = callbacks.onEditingScaleByChange,
                    error = scaleByError,
                    isVisible = currentWorkflowHasScaleBy
                ),
                stopAtClipLayer = NumericField(
                    value = editingStopAtClipLayer,
                    onValueChange = callbacks.onEditingStopAtClipLayerChange,
                    error = stopAtClipLayerError,
                    isVisible = currentWorkflowHasStopAtClipLayer
                )
            )
        } else {
            ParameterConfig(
                megapixels = NumericField(
                    value = megapixels,
                    onValueChange = callbacks.onMegapixelsChange,
                    error = megapixelsError,
                    isVisible = currentWorkflowHasMegapixels
                ),
                steps = NumericField(
                    value = if (isCheckpoint) checkpointSteps else unetSteps,
                    onValueChange = callbacks.onStepsChange,
                    error = stepsError,
                    isVisible = currentWorkflowHasSteps
                ),
                cfg = NumericField(
                    value = if (isCheckpoint) checkpointCfg else unetCfg,
                    onValueChange = callbacks.onCfgChange,
                    error = cfgError,
                    isVisible = currentWorkflowHasCfg
                ),
                sampler = DropdownField(
                    selectedValue = if (isCheckpoint) checkpointSampler else unetSampler,
                    options = SamplerOptions.SAMPLERS,
                    onValueChange = callbacks.onSamplerChange,
                    isVisible = currentWorkflowHasSamplerName
                ),
                scheduler = DropdownField(
                    selectedValue = if (isCheckpoint) checkpointScheduler else unetScheduler,
                    options = SamplerOptions.SCHEDULERS,
                    onValueChange = callbacks.onSchedulerChange,
                    isVisible = currentWorkflowHasScheduler
                ),
                seed = SeedConfig(
                    randomSeed = if (isCheckpoint) checkpointRandomSeed else unetRandomSeed,
                    onRandomSeedToggle = callbacks.onRandomSeedToggle,
                    seed = if (isCheckpoint) checkpointSeed else unetSeed,
                    onSeedChange = callbacks.onSeedChange,
                    onRandomizeSeed = callbacks.onRandomizeSeed,
                    seedError = seedError,
                    isVisible = currentWorkflowHasSeed
                ),
                denoise = NumericField(
                    value = if (isCheckpoint) checkpointDenoise else unetDenoise,
                    onValueChange = callbacks.onDenoiseChange,
                    error = denoiseError,
                    isVisible = currentWorkflowHasDenoise
                ),
                batchSize = NumericField(
                    value = if (isCheckpoint) checkpointBatchSize else unetBatchSize,
                    onValueChange = callbacks.onBatchSizeChange,
                    error = batchSizeError,
                    isVisible = currentWorkflowHasBatchSize
                ),
                upscaleMethod = DropdownField(
                    selectedValue = if (isCheckpoint) checkpointUpscaleMethod else unetUpscaleMethod,
                    options = availableUpscaleMethods,
                    onValueChange = callbacks.onUpscaleMethodChange,
                    isVisible = currentWorkflowHasUpscaleMethod
                ),
                scaleBy = NumericField(
                    value = if (isCheckpoint) checkpointScaleBy else unetScaleBy,
                    onValueChange = callbacks.onScaleByChange,
                    error = scaleByError,
                    isVisible = currentWorkflowHasScaleBy
                ),
                stopAtClipLayer = NumericField(
                    value = if (isCheckpoint) checkpointStopAtClipLayer else unetStopAtClipLayer,
                    onValueChange = callbacks.onStopAtClipLayerChange,
                    error = stopAtClipLayerError,
                    isVisible = currentWorkflowHasStopAtClipLayer
                )
            )
        },
        lora = if (isEditing) {
            LoraConfig(
                editingLora = if (currentWorkflowHasLora) ModelField(
                    label = R.string.label_lora,
                    selectedValue = selectedEditingLora,
                    options = availableLoras,
                    onValueChange = callbacks.onEditingLoraChange,
                    isVisible = true
                ) else null,
                primaryChain = if (currentWorkflowHasLora) LoraChainField(
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
            LoraConfig(
                primaryChain = if (currentWorkflowHasLora) LoraChainField(
                    title = R.string.lora_chain_title,
                    chain = if (isCheckpoint) checkpointLoraChain else unetLoraChain,
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
