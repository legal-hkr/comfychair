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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.viewmodel.TextToImageUiState
import sh.hnet.comfychair.viewmodel.WorkflowItem

/**
 * Content for the configuration bottom sheet.
 * Mode (Checkpoint/UNET) is automatically derived from the selected workflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheetContent(
    uiState: TextToImageUiState,
    // Unified workflow callback
    onWorkflowChange: (String) -> Unit,
    // Model selection callbacks
    onCheckpointChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onClip1Change: (String) -> Unit,
    onClip2Change: (String) -> Unit,
    // Unified parameter callbacks (route internally based on mode)
    onNegativePromptChange: (String) -> Unit,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
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
        // Negative prompt (per-workflow) - hidden for Flux workflows
        val showNegativePrompt = uiState.isCheckpointMode || uiState.currentWorkflowHasNegativePrompt
        if (showNegativePrompt) {
            OutlinedTextField(
                value = if (uiState.isCheckpointMode) uiState.checkpointNegativePrompt else uiState.unetNegativePrompt,
                onValueChange = onNegativePromptChange,
                label = { Text(stringResource(R.string.negative_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Unified Workflow Dropdown (shows all workflows with type prefix)
        WorkflowDropdown(
            label = stringResource(R.string.label_workflow),
            selectedWorkflow = uiState.selectedWorkflow,
            workflows = uiState.availableWorkflows,
            onWorkflowChange = onWorkflowChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.isCheckpointMode) {
            // Checkpoint mode configuration
            CheckpointModeContent(
                uiState = uiState,
                onCheckpointChange = onCheckpointChange,
                onWidthChange = onWidthChange,
                onHeightChange = onHeightChange,
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
                onClip1Change = onClip1Change,
                onClip2Change = onClip2Change,
                onWidthChange = onWidthChange,
                onHeightChange = onHeightChange,
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
    uiState: TextToImageUiState,
    onCheckpointChange: (String) -> Unit,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
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
        selectedValue = uiState.selectedCheckpoint,
        options = uiState.availableCheckpoints,
        onValueChange = onCheckpointChange
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Width and Height
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.checkpointWidth,
            onValueChange = onWidthChange,
            label = { Text(stringResource(R.string.label_width)) },
            isError = uiState.widthError != null && uiState.isCheckpointMode,
            supportingText = if (uiState.widthError != null && uiState.isCheckpointMode) {
                { Text(uiState.widthError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = uiState.checkpointHeight,
            onValueChange = onHeightChange,
            label = { Text(stringResource(R.string.label_height)) },
            isError = uiState.heightError != null && uiState.isCheckpointMode,
            supportingText = if (uiState.heightError != null && uiState.isCheckpointMode) {
                { Text(uiState.heightError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Steps and CFG
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.checkpointSteps,
            onValueChange = onStepsChange,
            label = { Text(stringResource(R.string.label_steps)) },
            isError = uiState.stepsError != null && uiState.isCheckpointMode,
            supportingText = if (uiState.stepsError != null && uiState.isCheckpointMode) {
                { Text(uiState.stepsError!!) }
            } else null,
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
    uiState: TextToImageUiState,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onClip1Change: (String) -> Unit,
    onClip2Change: (String) -> Unit,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
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
        selectedValue = uiState.selectedUnet,
        options = uiState.availableUnets,
        onValueChange = onUnetChange
    )

    Spacer(modifier = Modifier.height(12.dp))

    // VAE dropdown
    ModelDropdown(
        label = stringResource(R.string.label_vae),
        selectedValue = uiState.selectedVae,
        options = uiState.availableVaes,
        onValueChange = onVaeChange
    )

    Spacer(modifier = Modifier.height(12.dp))

    // CLIP dropdown(s) - single or dual based on workflow
    if (uiState.currentWorkflowHasDualClip) {
        // Dual CLIP for Flux workflows
        ModelDropdown(
            label = stringResource(R.string.label_clip1),
            selectedValue = uiState.selectedClip1,
            options = uiState.availableClips,
            onValueChange = onClip1Change
        )

        Spacer(modifier = Modifier.height(12.dp))

        ModelDropdown(
            label = stringResource(R.string.label_clip2),
            selectedValue = uiState.selectedClip2,
            options = uiState.availableClips,
            onValueChange = onClip2Change
        )
    } else {
        // Single CLIP for standard UNET workflows
        ModelDropdown(
            label = stringResource(R.string.label_clip),
            selectedValue = uiState.selectedClip,
            options = uiState.availableClips,
            onValueChange = onClipChange
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Width and Height
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.unetWidth,
            onValueChange = onWidthChange,
            label = { Text(stringResource(R.string.label_width)) },
            isError = uiState.widthError != null && !uiState.isCheckpointMode,
            supportingText = if (uiState.widthError != null && !uiState.isCheckpointMode) {
                { Text(uiState.widthError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = uiState.unetHeight,
            onValueChange = onHeightChange,
            label = { Text(stringResource(R.string.label_height)) },
            isError = uiState.heightError != null && !uiState.isCheckpointMode,
            supportingText = if (uiState.heightError != null && !uiState.isCheckpointMode) {
                { Text(uiState.heightError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Steps and CFG (CFG hidden for Flux workflows)
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.unetSteps,
            onValueChange = onStepsChange,
            label = { Text(stringResource(R.string.label_steps)) },
            isError = uiState.stepsError != null && !uiState.isCheckpointMode,
            supportingText = if (uiState.stepsError != null && !uiState.isCheckpointMode) {
                { Text(uiState.stepsError!!) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        if (uiState.currentWorkflowHasCfg) {
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
fun WorkflowDropdown(
    label: String,
    selectedWorkflow: String,
    workflows: List<WorkflowItem>,
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

/**
 * Reusable dropdown component for model selection.
 * Displays directory paths in a dimmed color for better readability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedValue,
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
            options.forEach { option ->
                DropdownMenuItem(
                    text = { ModelPathText(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

/**
 * Displays a model path with the directory portion dimmed.
 * Handles both forward slashes (Unix) and backslashes (Windows).
 */
@Composable
fun ModelPathText(path: String) {
    // Find the last separator (either / or \)
    val lastSlashIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    if (lastSlashIndex > 0) {
        val directoryPart = path.substring(0, lastSlashIndex + 1)
        val filenamePart = path.substring(lastSlashIndex + 1)

        val dimmedColor = LocalContentColor.current.copy(alpha = 0.5f)

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = dimmedColor)) {
                    append(directoryPart)
                }
                append(filenamePart)
            }
        )
    } else {
        Text(path)
    }
}
