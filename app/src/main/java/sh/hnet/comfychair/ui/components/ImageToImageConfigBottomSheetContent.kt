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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.ItiWorkflowItem

/**
 * Content for the Image-to-image configuration bottom sheet.
 * Mode (Checkpoint/UNET) is automatically derived from the selected workflow.
 */
@Composable
fun ImageToImageConfigBottomSheetContent(
    uiState: ImageToImageUiState,
    // Unified workflow callback
    onWorkflowChange: (String) -> Unit,
    // Model selection callbacks
    onCheckpointChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    // Unified parameter callbacks
    onNegativePromptChange: (String) -> Unit,
    onMegapixelsChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onCfgChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onSchedulerChange: (String) -> Unit,
    // Unified LoRA chain callbacks
    onAddLora: () -> Unit,
    onRemoveLora: (Int) -> Unit,
    onLoraNameChange: (Int, String) -> Unit,
    onLoraStrengthChange: (Int, Float) -> Unit
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
            value = if (uiState.isCheckpointMode) uiState.checkpointNegativePrompt else uiState.unetNegativePrompt,
            onValueChange = onNegativePromptChange,
            label = { Text(stringResource(R.string.negative_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Unified Workflow Dropdown (shows all workflows with type prefix)
        ItiWorkflowDropdown(
            label = stringResource(R.string.label_workflow),
            selectedWorkflow = uiState.selectedWorkflow,
            workflows = uiState.availableWorkflows,
            onWorkflowChange = onWorkflowChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isCheckpointMode) {
            // Checkpoint mode configuration
            CheckpointModeContent(
                uiState = uiState,
                onCheckpointChange = onCheckpointChange,
                onMegapixelsChange = onMegapixelsChange,
                onStepsChange = onStepsChange,
                onCfgChange = onCfgChange,
                onSamplerChange = onSamplerChange,
                onSchedulerChange = onSchedulerChange,
                onAddLora = onAddLora,
                onRemoveLora = onRemoveLora,
                onLoraNameChange = onLoraNameChange,
                onLoraStrengthChange = onLoraStrengthChange
            )
        } else {
            // UNET mode configuration
            UnetModeContent(
                uiState = uiState,
                onUnetChange = onUnetChange,
                onVaeChange = onVaeChange,
                onClipChange = onClipChange,
                onStepsChange = onStepsChange,
                onCfgChange = onCfgChange,
                onSamplerChange = onSamplerChange,
                onSchedulerChange = onSchedulerChange,
                onAddLora = onAddLora,
                onRemoveLora = onRemoveLora,
                onLoraNameChange = onLoraNameChange,
                onLoraStrengthChange = onLoraStrengthChange
            )
        }
    }
}

@Composable
private fun CheckpointModeContent(
    uiState: ImageToImageUiState,
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
            isError = uiState.cfgError != null && uiState.isCheckpointMode,
            supportingText = if (uiState.cfgError != null && uiState.isCheckpointMode) {
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
    uiState: ImageToImageUiState,
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
            isError = uiState.cfgError != null && !uiState.isCheckpointMode,
            supportingText = if (uiState.cfgError != null && !uiState.isCheckpointMode) {
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

/**
 * Dropdown for unified workflow selection with type prefix display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItiWorkflowDropdown(
    label: String,
    selectedWorkflow: String,
    workflows: List<ItiWorkflowItem>,
    onWorkflowChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Find display name for selected workflow
    val selectedDisplayName = workflows.find { it.name == selectedWorkflow }?.displayName ?: selectedWorkflow

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            workflows.forEach { workflow ->
                DropdownMenuItem(
                    text = { Text(workflow.displayName) },
                    onClick = {
                        onWorkflowChange(workflow.name)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
