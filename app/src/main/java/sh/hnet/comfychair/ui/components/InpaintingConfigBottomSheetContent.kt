package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.viewmodel.InpaintingConfigMode
import sh.hnet.comfychair.viewmodel.InpaintingUiState

/**
 * Content for the inpainting configuration bottom sheet
 */
@Composable
fun InpaintingConfigBottomSheetContent(
    uiState: InpaintingUiState,
    onConfigModeChange: (InpaintingConfigMode) -> Unit,
    onCheckpointNegativePromptChange: (String) -> Unit,
    onCheckpointWorkflowChange: (String) -> Unit,
    onCheckpointChange: (String) -> Unit,
    onMegapixelsChange: (String) -> Unit,
    onCheckpointStepsChange: (String) -> Unit,
    onCheckpointCfgChange: (String) -> Unit,
    onCheckpointSamplerChange: (String) -> Unit,
    onCheckpointSchedulerChange: (String) -> Unit,
    onUnetNegativePromptChange: (String) -> Unit,
    onUnetWorkflowChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onUnetStepsChange: (String) -> Unit,
    onUnetCfgChange: (String) -> Unit,
    onUnetSamplerChange: (String) -> Unit,
    onUnetSchedulerChange: (String) -> Unit,
    // Checkpoint LoRA chain callbacks
    onAddCheckpointLora: () -> Unit,
    onRemoveCheckpointLora: (Int) -> Unit,
    onCheckpointLoraNameChange: (Int, String) -> Unit,
    onCheckpointLoraStrengthChange: (Int, Float) -> Unit,
    // UNET LoRA chain callbacks
    onAddUnetLora: () -> Unit,
    onRemoveUnetLora: (Int) -> Unit,
    onUnetLoraNameChange: (Int, String) -> Unit,
    onUnetLoraStrengthChange: (Int, Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Negative prompt (per-workflow)
        OutlinedTextField(
            value = if (uiState.configMode == InpaintingConfigMode.CHECKPOINT) uiState.checkpointNegativePrompt else uiState.unetNegativePrompt,
            onValueChange = if (uiState.configMode == InpaintingConfigMode.CHECKPOINT) onCheckpointNegativePromptChange else onUnetNegativePromptChange,
            label = { Text(stringResource(R.string.negative_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Mode label
        Text(
            text = stringResource(R.string.label_mode),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Mode toggle (Checkpoint / UNET)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = uiState.configMode == InpaintingConfigMode.CHECKPOINT,
                onClick = { onConfigModeChange(InpaintingConfigMode.CHECKPOINT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.mode_checkpoint))
            }
            SegmentedButton(
                selected = uiState.configMode == InpaintingConfigMode.UNET,
                onClick = { onConfigModeChange(InpaintingConfigMode.UNET) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.mode_unet))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (uiState.configMode) {
            InpaintingConfigMode.CHECKPOINT -> {
                CheckpointModeContent(
                    uiState = uiState,
                    onWorkflowChange = onCheckpointWorkflowChange,
                    onCheckpointChange = onCheckpointChange,
                    onMegapixelsChange = onMegapixelsChange,
                    onStepsChange = onCheckpointStepsChange,
                    onCfgChange = onCheckpointCfgChange,
                    onSamplerChange = onCheckpointSamplerChange,
                    onSchedulerChange = onCheckpointSchedulerChange,
                    onAddLora = onAddCheckpointLora,
                    onRemoveLora = onRemoveCheckpointLora,
                    onLoraNameChange = onCheckpointLoraNameChange,
                    onLoraStrengthChange = onCheckpointLoraStrengthChange
                )
            }
            InpaintingConfigMode.UNET -> {
                UnetModeContent(
                    uiState = uiState,
                    onWorkflowChange = onUnetWorkflowChange,
                    onUnetChange = onUnetChange,
                    onVaeChange = onVaeChange,
                    onClipChange = onClipChange,
                    onStepsChange = onUnetStepsChange,
                    onCfgChange = onUnetCfgChange,
                    onSamplerChange = onUnetSamplerChange,
                    onSchedulerChange = onUnetSchedulerChange,
                    onAddLora = onAddUnetLora,
                    onRemoveLora = onRemoveUnetLora,
                    onLoraNameChange = onUnetLoraNameChange,
                    onLoraStrengthChange = onUnetLoraStrengthChange
                )
            }
        }
    }
}

@Composable
private fun CheckpointModeContent(
    uiState: InpaintingUiState,
    onWorkflowChange: (String) -> Unit,
    onCheckpointChange: (String) -> Unit,
    onMegapixelsChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onCfgChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onSchedulerChange: (String) -> Unit,
    onAddLora: () -> Unit,
    onRemoveLora: (Int) -> Unit,
    onLoraNameChange: (Int, String) -> Unit,
    onLoraStrengthChange: (Int, Float) -> Unit
) {
    // Workflow dropdown
    ModelDropdown(
        label = stringResource(R.string.label_workflow),
        options = uiState.checkpointWorkflows,
        selectedValue = uiState.selectedCheckpointWorkflow,
        onValueChange = onWorkflowChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Checkpoint dropdown
    ModelDropdown(
        label = stringResource(R.string.label_checkpoint),
        options = uiState.checkpoints,
        selectedValue = uiState.selectedCheckpoint,
        onValueChange = onCheckpointChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Image parameters title
    Text(
        text = stringResource(R.string.image_parameters_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    // Megapixels
    OutlinedTextField(
        value = uiState.megapixels,
        onValueChange = onMegapixelsChange,
        label = { Text(stringResource(R.string.megapixels_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = uiState.megapixelsError != null,
        supportingText = uiState.megapixelsError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Steps and CFG
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.checkpointSteps,
            onValueChange = onStepsChange,
            label = { Text(stringResource(R.string.label_steps)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = uiState.checkpointCfg,
            onValueChange = onCfgChange,
            label = { Text(stringResource(R.string.label_cfg)) },
            isError = uiState.cfgError != null && uiState.configMode == InpaintingConfigMode.CHECKPOINT,
            supportingText = if (uiState.cfgError != null && uiState.configMode == InpaintingConfigMode.CHECKPOINT) {
                { Text(uiState.cfgError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Sampler dropdown
    ModelDropdown(
        label = stringResource(R.string.label_sampler),
        selectedValue = uiState.checkpointSampler,
        options = SamplerOptions.SAMPLERS,
        onValueChange = onSamplerChange
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Scheduler dropdown
    ModelDropdown(
        label = stringResource(R.string.label_scheduler),
        selectedValue = uiState.checkpointScheduler,
        options = SamplerOptions.SCHEDULERS,
        onValueChange = onSchedulerChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // LoRA chain editor (Checkpoint mode)
    LoraChainEditor(
        title = stringResource(R.string.lora_chain_title),
        loraChain = uiState.checkpointLoraChain,
        availableLoras = uiState.availableLoras,
        onAddLora = onAddLora,
        onRemoveLora = onRemoveLora,
        onLoraNameChange = onLoraNameChange,
        onLoraStrengthChange = onLoraStrengthChange
    )
}

@Composable
private fun UnetModeContent(
    uiState: InpaintingUiState,
    onWorkflowChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onCfgChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onSchedulerChange: (String) -> Unit,
    onAddLora: () -> Unit,
    onRemoveLora: (Int) -> Unit,
    onLoraNameChange: (Int, String) -> Unit,
    onLoraStrengthChange: (Int, Float) -> Unit
) {
    // Workflow dropdown
    ModelDropdown(
        label = stringResource(R.string.label_workflow),
        options = uiState.unetWorkflows,
        selectedValue = uiState.selectedUnetWorkflow,
        onValueChange = onWorkflowChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // UNET dropdown
    ModelDropdown(
        label = stringResource(R.string.label_unet),
        options = uiState.unets,
        selectedValue = uiState.selectedUnet,
        onValueChange = onUnetChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // VAE dropdown
    ModelDropdown(
        label = stringResource(R.string.label_vae),
        options = uiState.vaes,
        selectedValue = uiState.selectedVae,
        onValueChange = onVaeChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // CLIP dropdown
    ModelDropdown(
        label = stringResource(R.string.label_clip),
        options = uiState.clips,
        selectedValue = uiState.selectedClip,
        onValueChange = onClipChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Image parameters title
    Text(
        text = stringResource(R.string.image_parameters_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    // Steps and CFG
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.unetSteps,
            onValueChange = onStepsChange,
            label = { Text(stringResource(R.string.label_steps)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = uiState.unetCfg,
            onValueChange = onCfgChange,
            label = { Text(stringResource(R.string.label_cfg)) },
            isError = uiState.cfgError != null && uiState.configMode == InpaintingConfigMode.UNET,
            supportingText = if (uiState.cfgError != null && uiState.configMode == InpaintingConfigMode.UNET) {
                { Text(uiState.cfgError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Sampler dropdown
    ModelDropdown(
        label = stringResource(R.string.label_sampler),
        selectedValue = uiState.unetSampler,
        options = SamplerOptions.SAMPLERS,
        onValueChange = onSamplerChange
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Scheduler dropdown
    ModelDropdown(
        label = stringResource(R.string.label_scheduler),
        selectedValue = uiState.unetScheduler,
        options = SamplerOptions.SCHEDULERS,
        onValueChange = onSchedulerChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // LoRA chain editor (UNET mode)
    LoraChainEditor(
        title = stringResource(R.string.lora_chain_title),
        loraChain = uiState.unetLoraChain,
        availableLoras = uiState.availableLoras,
        onAddLora = onAddLora,
        onRemoveLora = onRemoveLora,
        onLoraNameChange = onLoraNameChange,
        onLoraStrengthChange = onLoraStrengthChange
    )
}
