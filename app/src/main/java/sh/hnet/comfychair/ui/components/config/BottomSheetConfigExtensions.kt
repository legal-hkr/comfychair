package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.TextToImageUiState
import sh.hnet.comfychair.viewmodel.TextToVideoUiState

/**
 * Extension functions to convert UiState classes to BottomSheetConfig.
 *
 * These functions use shared helpers from BottomSheetConfigHelpers.kt to reduce duplication.
 * All screens now use UnifiedCallbacks instead of screen-specific callback classes.
 */

// No-op callbacks for when a callback is not provided
private val noOpString: (String) -> Unit = {}
private val noOpUnit: () -> Unit = {}

/**
 * Convert TextToImageUiState to BottomSheetConfig.
 *
 * Uses unified fields - field visibility is controlled by capabilities (derived from placeholders).
 */
fun TextToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
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
        models = buildCommonModelConfig(this, callbacks),
        parameters = buildCommonParameterConfig(
            state = this,
            callbacks = callbacks,
            widthField = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange ?: noOpString,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = capabilities.hasHeight
            )
        ),
        lora = buildCommonLoraConfig(this, callbacks)
    )
}

/**
 * Convert TextToVideoUiState to BottomSheetConfig.
 *
 * Video screens have additional dual-model patterns (highnoise/lownoise).
 */
fun TextToVideoUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    // Start with common model config and add video-specific fields
    val commonModels = buildCommonModelConfig(this, callbacks)

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
        models = commonModels.copy(
            // Add dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = if (capabilities.hasHighnoiseUnetName) ModelField(
                label = R.string.highnoise_unet_label,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseUnet = if (capabilities.hasLownoiseUnetName) ModelField(
                label = R.string.lownoise_unet_label,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            highnoiseLora = if (capabilities.hasHighnoiseLoraName) ModelField(
                label = R.string.highnoise_lora_label,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onHighnoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseLora = if (capabilities.hasLownoiseLoraName) ModelField(
                label = R.string.lownoise_lora_label,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onLownoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null
        ),
        parameters = buildCommonParameterConfig(
            state = this,
            callbacks = callbacks,
            widthField = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange ?: noOpString,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            megapixelsField = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            ),
            lengthField = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange ?: noOpString,
                error = lengthError,
                isVisible = capabilities.hasLength
            ),
            fpsField = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange ?: noOpString,
                error = fpsError,
                isVisible = capabilities.hasFrameRate
            )
        ),
        lora = LoraConfig(
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                selectedValue = selectedLoraName,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onMandatoryLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
            primaryChain = if (capabilities.hasLora) LoraChainField(
                title = R.string.lora_chain_title,
                chain = loraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLora ?: {},
                onRemove = callbacks.onRemoveLora ?: {},
                onNameChange = callbacks.onLoraNameChange ?: { _, _ -> },
                onStrengthChange = callbacks.onLoraStrengthChange ?: { _, _ -> },
                isVisible = true
            ) else null,
            // Dual-model LoRA chains (for Wan 2.2 style workflows)
            highnoiseChain = buildVideoLoraChain(
                titleResId = R.string.highnoise_lora_chain_title,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasHighnoiseLora,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange
            ),
            lownoiseChain = buildVideoLoraChain(
                titleResId = R.string.lownoise_lora_chain_title,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasLownoiseLora,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange
            )
        )
    )
}

/**
 * Convert ImageToVideoUiState to BottomSheetConfig.
 *
 * Very similar to TextToVideoUiState - shares dual-model patterns.
 */
fun ImageToVideoUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    // Start with common model config and add video-specific fields
    val commonModels = buildCommonModelConfig(this, callbacks)

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
        models = commonModels.copy(
            // Add dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = if (capabilities.hasHighnoiseUnetName) ModelField(
                label = R.string.highnoise_unet_label,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseUnet = if (capabilities.hasLownoiseUnetName) ModelField(
                label = R.string.lownoise_unet_label,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            highnoiseLora = if (capabilities.hasHighnoiseLoraName) ModelField(
                label = R.string.highnoise_lora_label,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onHighnoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseLora = if (capabilities.hasLownoiseLoraName) ModelField(
                label = R.string.lownoise_lora_label,
                selectedValue = selectedLownoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onLownoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null
        ),
        parameters = buildCommonParameterConfig(
            state = this,
            callbacks = callbacks,
            widthField = NumericField(
                value = width,
                onValueChange = callbacks.onWidthChange ?: noOpString,
                error = widthError,
                isVisible = capabilities.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = capabilities.hasHeight
            ),
            megapixelsField = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                error = megapixelsError,
                isVisible = capabilities.hasMegapixels
            ),
            lengthField = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange ?: noOpString,
                error = lengthError,
                isVisible = capabilities.hasLength
            ),
            fpsField = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange ?: noOpString,
                error = fpsError,
                isVisible = capabilities.hasFrameRate
            )
        ),
        lora = LoraConfig(
            loraName = if (capabilities.hasLoraName) ModelField(
                label = R.string.label_lora,
                selectedValue = selectedLoraName,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onMandatoryLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            // Primary LoRA chain for single-model workflows (e.g., LTX 2.0)
            primaryChain = if (capabilities.hasLora) LoraChainField(
                title = R.string.lora_chain_title,
                chain = loraChain,
                availableLoras = availableLoras,
                onAdd = callbacks.onAddLora ?: {},
                onRemove = callbacks.onRemoveLora ?: {},
                onNameChange = callbacks.onLoraNameChange ?: { _, _ -> },
                onStrengthChange = callbacks.onLoraStrengthChange ?: { _, _ -> },
                isVisible = true
            ) else null,
            // Dual-model LoRA chains (for Wan 2.2 style workflows)
            highnoiseChain = buildVideoLoraChain(
                titleResId = R.string.highnoise_lora_chain_title,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasHighnoiseLora,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange
            ),
            lownoiseChain = buildVideoLoraChain(
                titleResId = R.string.lownoise_lora_chain_title,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = capabilities.hasLownoiseLora,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange
            )
        )
    )
}

/**
 * Convert ImageToImageUiState to BottomSheetConfig.
 *
 * Image-to-Image has two modes (Editing and Inpainting) with different workflows and parameters.
 * Note: Editing mode uses the same capabilities field as inpainting mode for field visibility.
 */
fun ImageToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig {
    val isEditing = mode == ImageToImageMode.EDITING

    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = if (isEditing) selectedEditingWorkflow else selectedWorkflow,
            availableWorkflows = if (isEditing) editingWorkflows else availableWorkflows,
            onWorkflowChange = if (isEditing) {
                callbacks.onEditingWorkflowChange ?: noOpString
            } else callbacks.onWorkflowChange,
            onViewWorkflow = if (isEditing) {
                callbacks.onViewEditingWorkflow ?: noOpUnit
            } else callbacks.onViewWorkflow
        ),
        prompts = PromptConfig(
            negativePrompt = if (isEditing) editingNegativePrompt else negativePrompt,
            onNegativePromptChange = callbacks.onNegativePromptChange,
            hasNegativePrompt = capabilities.hasNegativePrompt
        ),
        itiConfig = ItiConfig(
            mode = mode,
            onModeChange = callbacks.onModeChange ?: {},
            referenceImage1 = referenceImage1,
            onReferenceImage1Change = callbacks.onReferenceImage1Change ?: {},
            onClearReferenceImage1 = callbacks.onClearReferenceImage1 ?: noOpUnit,
            referenceImage2 = referenceImage2,
            onReferenceImage2Change = callbacks.onReferenceImage2Change ?: {},
            onClearReferenceImage2 = callbacks.onClearReferenceImage2 ?: noOpUnit,
            hasReferenceImage1 = capabilities.hasReferenceImage1,
            hasReferenceImage2 = capabilities.hasReferenceImage2
        ),
        models = if (isEditing) {
            // Editing mode models
            buildEditingModeModelConfig(this, capabilities, callbacks)
        } else {
            // Inpainting mode - use common helper
            buildCommonModelConfig(this, callbacks)
        },
        parameters = if (isEditing) {
            // Editing mode parameters
            buildEditingModeParameterConfig(this, capabilities, callbacks)
        } else {
            // Inpainting mode - use common helper
            buildCommonParameterConfig(
                state = this,
                callbacks = callbacks,
                megapixelsField = NumericField(
                    value = megapixels,
                    onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                    error = megapixelsError,
                    isVisible = capabilities.hasMegapixels
                )
            )
        },
        lora = if (isEditing) {
            // Editing mode LoRA
            buildEditingModeLoraConfig(this, capabilities, callbacks)
        } else {
            // Inpainting mode - use common helper
            buildCommonLoraConfig(this, callbacks)
        }
    )
}

// ========== ITI Editing Mode Helper Functions ==========

/**
 * Build ModelConfig for ITI editing mode.
 */
private fun buildEditingModeModelConfig(
    state: ImageToImageUiState,
    caps: sh.hnet.comfychair.model.WorkflowCapabilities,
    callbacks: UnifiedCallbacks
): ModelConfig {
    return ModelConfig(
        unet = if (caps.hasUnetName) ModelField(
            label = R.string.label_unet,
            selectedValue = state.selectedEditingUnet,
            options = state.availableUnets,
            filteredOptions = state.filteredUnets,
            onValueChange = callbacks.onEditingUnetChange ?: noOpString,
            isVisible = true
        ) else null,
        vae = if (caps.hasVaeName) ModelField(
            label = R.string.label_vae,
            selectedValue = state.selectedEditingVae,
            options = state.availableVaes,
            filteredOptions = state.filteredVaes,
            onValueChange = callbacks.onEditingVaeChange ?: noOpString,
            isVisible = true
        ) else null,
        clip = if (caps.hasClipName) ModelField(
            label = R.string.label_clip,
            selectedValue = state.selectedEditingClip,
            options = state.availableClips,
            filteredOptions = state.filteredClips,
            onValueChange = callbacks.onEditingClipChange ?: noOpString,
            isVisible = true
        ) else null,
        clip1 = if (caps.hasClipName1) ModelField(
            label = R.string.label_clip1,
            selectedValue = state.selectedEditingClip1,
            options = state.availableClips,
            filteredOptions = state.filteredClips1,
            onValueChange = callbacks.onEditingClip1Change ?: noOpString,
            isVisible = true
        ) else null,
        clip2 = if (caps.hasClipName2) ModelField(
            label = R.string.label_clip2,
            selectedValue = state.selectedEditingClip2,
            options = state.availableClips,
            filteredOptions = state.filteredClips2,
            onValueChange = callbacks.onEditingClip2Change ?: noOpString,
            isVisible = true
        ) else null,
        clip3 = if (caps.hasClipName3) ModelField(
            label = R.string.label_clip3,
            selectedValue = state.selectedEditingClip3,
            options = state.availableClips,
            filteredOptions = state.filteredClips3,
            onValueChange = callbacks.onEditingClip3Change ?: noOpString,
            isVisible = true
        ) else null,
        clip4 = if (caps.hasClipName4) ModelField(
            label = R.string.label_clip4,
            selectedValue = state.selectedEditingClip4,
            options = state.availableClips,
            filteredOptions = state.filteredClips4,
            onValueChange = callbacks.onEditingClip4Change ?: noOpString,
            isVisible = true
        ) else null,
        textEncoder = if (caps.hasTextEncoderName) ModelField(
            label = R.string.label_text_encoder,
            selectedValue = state.selectedEditingTextEncoder,
            options = state.availableTextEncoders,
            filteredOptions = state.filteredTextEncoders,
            onValueChange = callbacks.onEditingTextEncoderChange ?: noOpString,
            isVisible = true
        ) else null,
        latentUpscaleModel = if (caps.hasLatentUpscaleModel) ModelField(
            label = R.string.label_latent_upscale_model,
            selectedValue = state.selectedEditingLatentUpscaleModel,
            options = state.availableLatentUpscaleModels,
            filteredOptions = state.filteredLatentUpscaleModels,
            onValueChange = callbacks.onEditingLatentUpscaleModelChange ?: noOpString,
            isVisible = true
        ) else null
    )
}

/**
 * Build ParameterConfig for ITI editing mode.
 */
private fun buildEditingModeParameterConfig(
    state: ImageToImageUiState,
    caps: sh.hnet.comfychair.model.WorkflowCapabilities,
    callbacks: UnifiedCallbacks
): ParameterConfig {
    return ParameterConfig(
        megapixels = NumericField(
            value = state.editingMegapixels,
            onValueChange = callbacks.onEditingMegapixelsChange ?: noOpString,
            error = state.megapixelsError,
            isVisible = caps.hasMegapixels
        ),
        steps = NumericField(
            value = state.editingSteps,
            onValueChange = callbacks.onEditingStepsChange ?: noOpString,
            error = state.stepsError,
            isVisible = caps.hasSteps
        ),
        cfg = NumericField(
            value = state.editingCfg,
            onValueChange = callbacks.onEditingCfgChange ?: noOpString,
            error = state.cfgError,
            isVisible = caps.hasCfg
        ),
        sampler = DropdownField(
            selectedValue = state.editingSampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = callbacks.onEditingSamplerChange ?: noOpString,
            isVisible = caps.hasSamplerName
        ),
        scheduler = DropdownField(
            selectedValue = state.editingScheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = callbacks.onEditingSchedulerChange ?: noOpString,
            isVisible = caps.hasScheduler
        ),
        seed = SeedConfig(
            randomSeed = state.editingRandomSeed,
            onRandomSeedToggle = callbacks.onEditingRandomSeedToggle ?: noOpUnit,
            seed = state.editingSeed,
            onSeedChange = callbacks.onEditingSeedChange ?: noOpString,
            onRandomizeSeed = callbacks.onEditingRandomizeSeed ?: noOpUnit,
            seedError = state.seedError,
            isVisible = caps.hasSeed
        ),
        denoise = NumericField(
            value = state.editingDenoise,
            onValueChange = callbacks.onEditingDenoiseChange ?: noOpString,
            error = state.denoiseError,
            isVisible = caps.hasDenoise
        ),
        batchSize = NumericField(
            value = state.editingBatchSize,
            onValueChange = callbacks.onEditingBatchSizeChange ?: noOpString,
            error = state.batchSizeError,
            isVisible = caps.hasBatchSize
        ),
        upscaleMethod = DropdownField(
            selectedValue = state.editingUpscaleMethod,
            options = state.availableUpscaleMethods,
            onValueChange = callbacks.onEditingUpscaleMethodChange ?: noOpString,
            isVisible = caps.hasUpscaleMethod
        ),
        scaleBy = NumericField(
            value = state.editingScaleBy,
            onValueChange = callbacks.onEditingScaleByChange ?: noOpString,
            error = state.scaleByError,
            isVisible = caps.hasScaleBy
        ),
        stopAtClipLayer = NumericField(
            value = state.editingStopAtClipLayer,
            onValueChange = callbacks.onEditingStopAtClipLayerChange ?: noOpString,
            error = state.stopAtClipLayerError,
            isVisible = caps.hasStopAtClipLayer
        )
    )
}

/**
 * Build LoraConfig for ITI editing mode.
 */
private fun buildEditingModeLoraConfig(
    state: ImageToImageUiState,
    caps: sh.hnet.comfychair.model.WorkflowCapabilities,
    callbacks: UnifiedCallbacks
): LoraConfig {
    // No-op callbacks
    val noOpInt: (Int) -> Unit = {}
    val noOpIntString: (Int, String) -> Unit = { _, _ -> }
    val noOpIntFloat: (Int, Float) -> Unit = { _, _ -> }

    return LoraConfig(
        loraName = if (caps.hasLoraName) ModelField(
            label = R.string.label_lora,
            selectedValue = state.selectedEditingLora,
            options = state.availableLoras,
            filteredOptions = state.filteredLoras,
            onValueChange = callbacks.onEditingLoraChange ?: noOpString,
            isVisible = true
        ) else null,
        primaryChain = if (caps.hasLora) LoraChainField(
            title = R.string.lora_chain_title,
            chain = state.editingLoraChain,
            availableLoras = state.availableLoras,
            onAdd = callbacks.onAddEditingLora ?: noOpUnit,
            onRemove = callbacks.onRemoveEditingLora ?: noOpInt,
            onNameChange = callbacks.onEditingLoraNameChange ?: noOpIntString,
            onStrengthChange = callbacks.onEditingLoraStrengthChange ?: noOpIntFloat,
            isVisible = true
        ) else null
    )
}
