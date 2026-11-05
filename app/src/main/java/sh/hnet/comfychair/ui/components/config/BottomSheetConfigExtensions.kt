package sh.hnet.comfychair.ui.components.config

import androidx.compose.ui.graphics.vector.ImageVector
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.generation.WorkflowGroup
import sh.hnet.comfychair.ui.components.generation.WorkflowIcons
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.viewmodel.ImageToImageMode
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
    itiConfig: ItiConfig? = null,
    workflowGroups: List<WorkflowGroup> = singleWorkflowGroup(availableWorkflows, callbacks.onWorkflowChange),
    workflowIcon: ImageVector = WorkflowIcons.default
): BottomSheetConfig {
    val caps = capabilities
    return BottomSheetConfig(
        workflow = WorkflowConfig(
            selectedWorkflow = selectedWorkflow,
            availableWorkflows = availableWorkflows,
            onWorkflowChange = callbacks.onWorkflowChange,
            groups = workflowGroups,
            icon = workflowIcon
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

/** The workflow menu of a screen with one kind of workflow: one untitled group. */
fun singleWorkflowGroup(workflows: List<WorkflowItemBase>, onWorkflowChange: (String) -> Unit): List<WorkflowGroup> =
    listOf(WorkflowGroup(null, workflows, isCurrent = true, icon = WorkflowIcons.default, onSelect = onWorkflowChange))

/**
 * Image to Image's workflow menu: Editing and Inpainting workflows in their own groups. Choosing a workflow of the
 * other group switches the mode first, so the mode is chosen with the workflow.
 */
fun ImageToImageUiState.workflowGroups(
    onModeChange: (ImageToImageMode) -> Unit,
    onWorkflowChange: (String) -> Unit
): List<WorkflowGroup> = listOf(
    ImageToImageMode.EDITING to editingWorkflows,
    ImageToImageMode.INPAINTING to availableWorkflows
).map { (groupMode, workflows) ->
    WorkflowGroup(
        titleRes = if (groupMode == ImageToImageMode.EDITING) R.string.option_mode_editing else R.string.option_mode_inpainting,
        workflows = workflows,
        isCurrent = mode == groupMode,
        icon = if (groupMode == ImageToImageMode.EDITING) WorkflowIcons.editing else WorkflowIcons.inpainting
    ) { name ->
        if (mode != groupMode) onModeChange(groupMode)
        onWorkflowChange(name)
    }
}

/** Icon of Image to Image's current kind of workflow. */
val ImageToImageUiState.workflowIcon: ImageVector
    get() = if (mode == ImageToImageMode.EDITING) WorkflowIcons.editing else WorkflowIcons.inpainting

/**
 * Image to Image: the current mode's workflows and parameters, plus the reference images; the workflow menu holds
 * both modes.
 */
fun ImageToImageUiState.toBottomSheetConfig(callbacks: UnifiedCallbacks): BottomSheetConfig =
    toBottomSheetConfig(
        availableWorkflows = currentModeWorkflows,
        callbacks = callbacks,
        workflowGroups = workflowGroups(callbacks.onModeChange ?: {}, callbacks.onWorkflowChange),
        workflowIcon = workflowIcon,
        itiConfig = ItiConfig(
            mode = mode,
            onModeChange = callbacks.onModeChange ?: {}
        )
    )
