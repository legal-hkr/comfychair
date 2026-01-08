package sh.hnet.comfychair.ui.components.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.LoraChainEditor
import sh.hnet.comfychair.ui.components.shared.DimensionStepperRow
import sh.hnet.comfychair.ui.components.shared.GenericWorkflowDropdown
import sh.hnet.comfychair.ui.components.shared.LengthFpsRow
import sh.hnet.comfychair.ui.components.shared.MegapixelsField
import sh.hnet.comfychair.ui.components.shared.ClipLayerField
import sh.hnet.comfychair.ui.components.shared.DenoiseBatchRow
import sh.hnet.comfychair.ui.components.shared.ModelDropdown
import sh.hnet.comfychair.ui.components.shared.ReferenceImageThumbnail
import sh.hnet.comfychair.ui.components.shared.ScaleByField
import sh.hnet.comfychair.ui.components.shared.SeedRow
import sh.hnet.comfychair.ui.components.shared.StepsCfgRow
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/**
 * Unified configuration bottom sheet content.
 * All fields are driven by the BottomSheetConfig data class.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheetContent(
    config: BottomSheetConfig,
    workflowName: String,
    modifier: Modifier = Modifier
) {
    NoOverscrollContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 1. Negative Prompt (at top)
            if (config.prompts.hasNegativePrompt) {
                NegativePromptSection(config.prompts)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 2. ITI Reference Images (if applicable)
            config.itiConfig?.let { itiConfig ->
                ReferenceImagesSection(itiConfig)
            }

            // 3. ITI Mode Selector (if applicable)
            config.itiConfig?.let { itiConfig ->
                ModeSelector(itiConfig)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 4. Workflow Dropdown
            WorkflowSection(config.workflow)
            Spacer(modifier = Modifier.height(16.dp))

            // 5. Model Selection Section
            ModelSelectionSection(config.models)

            // 6. Generation Parameters Section
            ParametersSection(config.parameters, workflowName)

            // 7. LoRA Section (including single LoRA dropdowns at the bottom)
            LoraSection(config.lora, config.models)
        }
    }
}

@Composable
private fun NegativePromptSection(prompts: PromptConfig) {
    OutlinedTextField(
        value = prompts.negativePrompt,
        onValueChange = prompts.onNegativePromptChange,
        label = { Text(stringResource(R.string.negative_prompt_hint)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4
    )
}

@Composable
private fun ReferenceImagesSection(itiConfig: ItiConfig) {
    val hasAnyReferenceImages = itiConfig.hasReferenceImage1 || itiConfig.hasReferenceImage2
    if (itiConfig.mode == ImageToImageMode.EDITING && hasAnyReferenceImages) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (itiConfig.hasReferenceImage1) {
                ReferenceImageThumbnail(
                    image = itiConfig.referenceImage1,
                    contentDescription = stringResource(R.string.content_description_reference_image_1),
                    onImageSelected = itiConfig.onReferenceImage1Change,
                    onClear = itiConfig.onClearReferenceImage1,
                    modifier = Modifier.weight(1f)
                )
            }
            if (itiConfig.hasReferenceImage2) {
                ReferenceImageThumbnail(
                    image = itiConfig.referenceImage2,
                    contentDescription = stringResource(R.string.content_description_reference_image_2),
                    onImageSelected = itiConfig.onReferenceImage2Change,
                    onClear = itiConfig.onClearReferenceImage2,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ModeSelector(itiConfig: ItiConfig) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = itiConfig.mode == ImageToImageMode.EDITING,
            onClick = { itiConfig.onModeChange(ImageToImageMode.EDITING) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) { Text(stringResource(R.string.mode_editing)) }
        SegmentedButton(
            selected = itiConfig.mode == ImageToImageMode.INPAINTING,
            onClick = { itiConfig.onModeChange(ImageToImageMode.INPAINTING) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) { Text(stringResource(R.string.mode_inpainting)) }
    }
}

@Composable
private fun WorkflowSection(workflow: WorkflowConfig) {
    GenericWorkflowDropdown(
        label = stringResource(R.string.label_workflow),
        selectedWorkflow = workflow.selectedWorkflow,
        workflows = workflow.availableWorkflows,
        onWorkflowChange = workflow.onWorkflowChange,
        onViewWorkflow = workflow.onViewWorkflow
    )
}

@Composable
private fun ModelSelectionSection(models: ModelConfig) {
    // Note: Single LoRA dropdowns (highnoiseLora, lownoiseLora) are rendered in LoraSection
    val visibleModels = remember(models) {
        listOfNotNull(
            models.checkpoint?.takeIf { it.isVisible },
            models.unet?.takeIf { it.isVisible },
            models.latentUpscaleModel?.takeIf { it.isVisible },
            models.highnoiseUnet?.takeIf { it.isVisible },
            models.lownoiseUnet?.takeIf { it.isVisible },
            models.vae?.takeIf { it.isVisible },
            models.clip?.takeIf { it.isVisible },
            models.clip1?.takeIf { it.isVisible },
            models.clip2?.takeIf { it.isVisible },
            models.clip3?.takeIf { it.isVisible },
            models.clip4?.takeIf { it.isVisible },
            models.textEncoder?.takeIf { it.isVisible }
        )
    }

    if (visibleModels.isEmpty()) return

    // Section title
    Text(
        text = stringResource(R.string.model_selection_title),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    // Render each visible model field in order
    models.checkpoint?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.unet?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.latentUpscaleModel?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.highnoiseUnet?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.lownoiseUnet?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.vae?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip1?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip2?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip3?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.clip4?.takeIf { it.isVisible }?.let { RenderModelField(it) }
    models.textEncoder?.takeIf { it.isVisible }?.let { RenderModelField(it) }
}

@Composable
private fun RenderModelField(field: ModelField) {
    Spacer(modifier = Modifier.height(12.dp))
    ModelDropdown(
        label = stringResource(field.label),
        selectedValue = field.selectedValue,
        options = field.filteredOptions ?: field.options,
        onValueChange = field.onValueChange
    )
}

@Composable
private fun ParametersSection(params: ParameterConfig, workflowName: String) {
    val hasAnyParams = remember(params) {
        listOfNotNull(
            params.width?.takeIf { it.isVisible },
            params.height?.takeIf { it.isVisible },
            params.megapixels?.takeIf { it.isVisible },
            params.steps?.takeIf { it.isVisible },
            params.cfg?.takeIf { it.isVisible },
            params.length?.takeIf { it.isVisible },
            params.fps?.takeIf { it.isVisible },
            params.sampler?.takeIf { it.isVisible },
            params.scheduler?.takeIf { it.isVisible },
            params.seed?.takeIf { it.isVisible },
            params.denoise?.takeIf { it.isVisible },
            params.batchSize?.takeIf { it.isVisible },
            params.upscaleMethod?.takeIf { it.isVisible },
            params.scaleBy?.takeIf { it.isVisible },
            params.stopAtClipLayer?.takeIf { it.isVisible }
        ).isNotEmpty()
    }

    if (!hasAnyParams) return

    Spacer(modifier = Modifier.height(16.dp))

    // Dimensions row (width/height)
    val showWidth = params.width?.isVisible == true
    val showHeight = params.height?.isVisible == true
    if (showWidth || showHeight) {
        DimensionStepperRow(
            workflowName = workflowName,
            width = params.width?.value ?: "",
            onWidthChange = params.width?.onValueChange ?: {},
            widthError = params.width?.error,
            height = params.height?.value ?: "",
            onHeightChange = params.height?.onValueChange ?: {},
            heightError = params.height?.error,
            showWidth = showWidth,
            showHeight = showHeight
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Megapixels
    params.megapixels?.takeIf { it.isVisible }?.let { field ->
        MegapixelsField(
            workflowName = workflowName,
            value = field.value,
            onValueChange = field.onValueChange,
            error = field.error,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Steps/CFG row
    val showSteps = params.steps?.isVisible == true
    val showCfg = params.cfg?.isVisible == true
    if (showSteps || showCfg) {
        StepsCfgRow(
            workflowName = workflowName,
            steps = params.steps?.value ?: "",
            onStepsChange = params.steps?.onValueChange ?: {},
            stepsError = params.steps?.error,
            showSteps = showSteps,
            cfg = params.cfg?.value ?: "",
            onCfgChange = params.cfg?.onValueChange ?: {},
            cfgError = params.cfg?.error,
            showCfg = showCfg
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Length/FPS row
    val showLength = params.length?.isVisible == true
    val showFps = params.fps?.isVisible == true
    if (showLength || showFps) {
        LengthFpsRow(
            workflowName = workflowName,
            length = params.length?.value ?: "",
            onLengthChange = params.length?.onValueChange ?: {},
            lengthError = params.length?.error,
            showLength = showLength,
            fps = params.fps?.value ?: "",
            onFpsChange = params.fps?.onValueChange ?: {},
            fpsError = params.fps?.error,
            showFps = showFps
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Sampler dropdown
    params.sampler?.takeIf { it.isVisible }?.let { field ->
        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = field.selectedValue,
            options = field.options,
            onValueChange = field.onValueChange
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Scheduler dropdown
    params.scheduler?.takeIf { it.isVisible }?.let { field ->
        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = field.selectedValue,
            options = field.options,
            onValueChange = field.onValueChange
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Seed row (toggle + field + randomize button)
    params.seed?.takeIf { it.isVisible }?.let { seedConfig ->
        SeedRow(
            workflowName = workflowName,
            randomSeed = seedConfig.randomSeed,
            onRandomSeedToggle = seedConfig.onRandomSeedToggle,
            seed = seedConfig.seed,
            onSeedChange = seedConfig.onSeedChange,
            onRandomizeSeed = seedConfig.onRandomizeSeed,
            seedError = seedConfig.seedError
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Denoise / Batch size row
    val showDenoise = params.denoise?.isVisible == true
    val showBatchSize = params.batchSize?.isVisible == true
    if (showDenoise || showBatchSize) {
        DenoiseBatchRow(
            workflowName = workflowName,
            denoise = params.denoise?.value ?: "",
            onDenoiseChange = params.denoise?.onValueChange ?: {},
            denoiseError = params.denoise?.error,
            showDenoise = showDenoise,
            batchSize = params.batchSize?.value ?: "",
            onBatchSizeChange = params.batchSize?.onValueChange ?: {},
            batchSizeError = params.batchSize?.error,
            showBatchSize = showBatchSize
        )
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Upscale method / Scale by row
    val showUpscaleMethod = params.upscaleMethod?.isVisible == true
    val showScaleBy = params.scaleBy?.isVisible == true
    if (showUpscaleMethod || showScaleBy) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showUpscaleMethod) {
                params.upscaleMethod?.let { field ->
                    ModelDropdown(
                        label = stringResource(R.string.label_upscale_method),
                        selectedValue = field.selectedValue,
                        options = field.options,
                        onValueChange = field.onValueChange,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (showScaleBy) {
                params.scaleBy?.let { field ->
                    ScaleByField(
                        workflowName = workflowName,
                        value = field.value,
                        onValueChange = field.onValueChange,
                        error = field.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // CLIP layer
    params.stopAtClipLayer?.takeIf { it.isVisible }?.let { field ->
        ClipLayerField(
            workflowName = workflowName,
            value = field.value,
            onValueChange = field.onValueChange,
            error = field.error,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LoraSection(lora: LoraConfig, models: ModelConfig) {
    // Single LoRA dropdowns (in order: LoRA, High noise LoRA, Low noise LoRA)
    lora.loraName?.takeIf { it.isVisible }?.let { field ->
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(field.label),
            selectedValue = field.selectedValue,
            options = field.filteredOptions ?: field.options,
            onValueChange = field.onValueChange
        )
    }

    models.highnoiseLora?.takeIf { it.isVisible }?.let { field ->
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(field.label),
            selectedValue = field.selectedValue,
            options = field.filteredOptions ?: field.options,
            onValueChange = field.onValueChange
        )
    }

    models.lownoiseLora?.takeIf { it.isVisible }?.let { field ->
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(field.label),
            selectedValue = field.selectedValue,
            options = field.filteredOptions ?: field.options,
            onValueChange = field.onValueChange
        )
    }

    // LoRA chain editors
    lora.primaryChain?.takeIf { it.isVisible }?.let { chain ->
        RenderLoraChain(chain)
    }

    lora.highnoiseChain?.takeIf { it.isVisible }?.let { chain ->
        RenderLoraChain(chain)
    }

    lora.lownoiseChain?.takeIf { it.isVisible }?.let { chain ->
        RenderLoraChain(chain)
    }
}

@Composable
private fun RenderLoraChain(chain: LoraChainField) {
    Spacer(modifier = Modifier.height(16.dp))
    LoraChainEditor(
        title = stringResource(chain.title),
        loraChain = chain.chain,
        availableLoras = chain.availableLoras,
        onAddLora = chain.onAdd,
        onRemoveLora = chain.onRemove,
        onLoraNameChange = chain.onNameChange,
        onLoraStrengthChange = chain.onStrengthChange
    )
}
