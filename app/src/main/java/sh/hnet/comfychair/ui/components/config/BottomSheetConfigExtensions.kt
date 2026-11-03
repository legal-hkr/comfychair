package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.TextToImageUiState
import sh.hnet.comfychair.viewmodel.TextToVideoUiState

/**
 * Options sheet configuration from a screen's generation parameters.
 *
 * Every screen carries every field, so one builder serves all of them (and both Image to Image modes);
 * which fields are shown depends only on the workflow's placeholders (capabilities), never on the screen.
 */

// No-op callbacks for when a callback is not provided
private val noOpString: (String) -> Unit = {}

/**
 * Build the options sheet for the given parameters.
 *
 * @param availableWorkflows Workflows offered in the workflow dropdown
 * @param itiConfig Image to Image mode and reference images; null on the other screens
 */
fun CommonGenerationState.toBottomSheetConfig(
    availableWorkflows: List<WorkflowItemBase>,
    callbacks: UnifiedCallbacks,
    itiConfig: ItiConfig? = null
): BottomSheetConfig {
    val caps = capabilities
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
            hasNegativePrompt = caps.hasNegativePrompt
        ),
        itiConfig = itiConfig,
        models = buildCommonModelConfig(this, callbacks).copy(
            // Dual-model patterns (e.g. Wan 2.2)
            highnoiseUnet = if (caps.hasHighnoiseUnetName) ModelField(
                label = R.string.label_highnoise_unet,
                selectedValue = selectedHighnoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onHighnoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseUnet = if (caps.hasLownoiseUnetName) ModelField(
                label = R.string.label_lownoise_unet,
                selectedValue = selectedLownoiseUnet,
                options = availableUnets,
                filteredOptions = filteredUnets,
                onValueChange = callbacks.onLownoiseUnetChange ?: noOpString,
                isVisible = true
            ) else null,
            highnoiseLora = if (caps.hasHighnoiseLoraName) ModelField(
                label = R.string.label_highnoise_lora,
                selectedValue = selectedHighnoiseLora,
                options = availableLoras,
                filteredOptions = filteredLoras,
                onValueChange = callbacks.onHighnoiseLoraChange ?: noOpString,
                isVisible = true
            ) else null,
            lownoiseLora = if (caps.hasLownoiseLoraName) ModelField(
                label = R.string.label_lownoise_lora,
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
                isVisible = caps.hasWidth
            ),
            heightField = NumericField(
                value = height,
                onValueChange = callbacks.onHeightChange ?: noOpString,
                error = heightError,
                isVisible = caps.hasHeight
            ),
            megapixelsField = NumericField(
                value = megapixels,
                onValueChange = callbacks.onMegapixelsChange ?: noOpString,
                error = megapixelsError,
                isVisible = caps.hasMegapixels
            ),
            lengthField = NumericField(
                value = length,
                onValueChange = callbacks.onLengthChange ?: noOpString,
                error = lengthError,
                isVisible = caps.hasLength
            ),
            fpsField = NumericField(
                value = fps,
                onValueChange = callbacks.onFpsChange ?: noOpString,
                error = fpsError,
                isVisible = caps.hasFrameRate
            )
        ),
        lora = buildCommonLoraConfig(this, callbacks).copy(
            // Dual-model LoRA chains (e.g. Wan 2.2)
            highnoiseChain = buildVideoLoraChain(
                titleResId = R.string.title_highnoise_lora_chain,
                chain = highnoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = caps.hasHighnoiseLora,
                onAdd = callbacks.onAddHighnoiseLora,
                onRemove = callbacks.onRemoveHighnoiseLora,
                onNameChange = callbacks.onHighnoiseLoraNameChange,
                onStrengthChange = callbacks.onHighnoiseLoraStrengthChange
            ),
            lownoiseChain = buildVideoLoraChain(
                titleResId = R.string.title_lownoise_lora_chain,
                chain = lownoiseLoraChain,
                availableLoras = availableLoras,
                isVisible = caps.hasLownoiseLora,
                onAdd = callbacks.onAddLownoiseLora,
                onRemove = callbacks.onRemoveLownoiseLora,
                onNameChange = callbacks.onLownoiseLoraNameChange,
                onStrengthChange = callbacks.onLownoiseLoraStrengthChange
            )
        )
    )
}

fun TextToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig =
    toBottomSheetConfig(availableWorkflows, callbacks)

fun TextToVideoUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig =
    toBottomSheetConfig(availableWorkflows, callbacks)

fun ImageToVideoUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig =
    toBottomSheetConfig(availableWorkflows, callbacks)

/**
 * Image to Image: the current mode's workflows and parameters, plus the mode switch and reference images.
 */
fun ImageToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig =
    toBottomSheetConfig(
        availableWorkflows = currentModeWorkflows,
        callbacks = callbacks,
        itiConfig = ItiConfig(
            mode = mode,
            onModeChange = callbacks.onModeChange ?: {},
            referenceImage1 = referenceImage1,
            onReferenceImage1Change = callbacks.onReferenceImage1Change ?: {},
            onClearReferenceImage1 = callbacks.onClearReferenceImage1 ?: {},
            referenceImage2 = referenceImage2,
            onReferenceImage2Change = callbacks.onReferenceImage2Change ?: {},
            onClearReferenceImage2 = callbacks.onClearReferenceImage2 ?: {},
            hasReferenceImage1 = capabilities.hasReferenceImage1,
            hasReferenceImage2 = capabilities.hasReferenceImage2
        )
    )
