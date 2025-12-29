package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
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
import sh.hnet.comfychair.ui.components.shared.DimensionStepperRow
import sh.hnet.comfychair.ui.components.shared.StepsCfgRow
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
    onViewWorkflow: () -> Unit,
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
        // Negative prompt (per-workflow) - only show if mapped in workflow
        if (uiState.currentWorkflowHasNegativePrompt) {
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
            onWorkflowChange = onWorkflowChange,
            onViewWorkflow = onViewWorkflow
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
    // Checkpoint dropdown (conditional - only show if mapped in workflow)
    if (uiState.currentWorkflowHasCheckpointName) {
        ModelDropdown(
            label = stringResource(R.string.label_checkpoint),
            selectedValue = uiState.selectedCheckpoint,
            options = uiState.availableCheckpoints,
            onValueChange = onCheckpointChange
        )
    }

    // Width and Height (conditional)
    if (uiState.currentWorkflowHasWidth || uiState.currentWorkflowHasHeight) {
        Spacer(modifier = Modifier.height(12.dp))

        DimensionStepperRow(
            workflowName = uiState.selectedWorkflow,
            width = uiState.checkpointWidth,
            onWidthChange = onWidthChange,
            widthError = if (uiState.isCheckpointMode) uiState.widthError else null,
            height = uiState.checkpointHeight,
            onHeightChange = onHeightChange,
            heightError = if (uiState.isCheckpointMode) uiState.heightError else null,
            showWidth = uiState.currentWorkflowHasWidth,
            showHeight = uiState.currentWorkflowHasHeight
        )
    }

    // Steps and CFG (conditional)
    if (uiState.currentWorkflowHasSteps || uiState.currentWorkflowHasCfg) {
        Spacer(modifier = Modifier.height(12.dp))

        StepsCfgRow(
            workflowName = uiState.selectedWorkflow,
            steps = uiState.checkpointSteps,
            onStepsChange = onStepsChange,
            stepsError = if (uiState.isCheckpointMode) uiState.stepsError else null,
            showSteps = uiState.currentWorkflowHasSteps,
            cfg = uiState.checkpointCfg,
            onCfgChange = onCfgChange,
            cfgError = if (uiState.isCheckpointMode) uiState.cfgError else null,
            showCfg = uiState.currentWorkflowHasCfg
        )
    }

    // Sampler dropdown (conditional)
    if (uiState.currentWorkflowHasSamplerName) {
        Spacer(modifier = Modifier.height(12.dp))

        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = uiState.checkpointSampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = onSamplerChange
        )
    }

    // Scheduler dropdown (conditional)
    if (uiState.currentWorkflowHasScheduler) {
        Spacer(modifier = Modifier.height(12.dp))

        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = uiState.checkpointScheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = onSchedulerChange
        )
    }

    // LoRA chain editor (conditional)
    if (uiState.currentWorkflowHasLoraName) {
        Spacer(modifier = Modifier.height(16.dp))

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
    // UNET dropdown (conditional - only show if mapped in workflow)
    if (uiState.currentWorkflowHasUnetName) {
        ModelDropdown(
            label = stringResource(R.string.label_unet),
            selectedValue = uiState.selectedUnet,
            options = uiState.availableUnets,
            onValueChange = onUnetChange
        )
    }

    // VAE dropdown (conditional)
    if (uiState.currentWorkflowHasVaeName) {
        Spacer(modifier = Modifier.height(12.dp))

        ModelDropdown(
            label = stringResource(R.string.label_vae),
            selectedValue = uiState.selectedVae,
            options = uiState.availableVaes,
            onValueChange = onVaeChange
        )
    }

    // CLIP dropdown(s) - single or dual based on workflow (conditional)
    if (uiState.currentWorkflowHasClipName) {
        Spacer(modifier = Modifier.height(12.dp))

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
    }

    // Width and Height (conditional)
    if (uiState.currentWorkflowHasWidth || uiState.currentWorkflowHasHeight) {
        Spacer(modifier = Modifier.height(12.dp))

        DimensionStepperRow(
            workflowName = uiState.selectedWorkflow,
            width = uiState.unetWidth,
            onWidthChange = onWidthChange,
            widthError = if (!uiState.isCheckpointMode) uiState.widthError else null,
            height = uiState.unetHeight,
            onHeightChange = onHeightChange,
            heightError = if (!uiState.isCheckpointMode) uiState.heightError else null,
            showWidth = uiState.currentWorkflowHasWidth,
            showHeight = uiState.currentWorkflowHasHeight
        )
    }

    // Steps and CFG (conditional)
    if (uiState.currentWorkflowHasSteps || uiState.currentWorkflowHasCfg) {
        Spacer(modifier = Modifier.height(12.dp))

        StepsCfgRow(
            workflowName = uiState.selectedWorkflow,
            steps = uiState.unetSteps,
            onStepsChange = onStepsChange,
            stepsError = if (!uiState.isCheckpointMode) uiState.stepsError else null,
            showSteps = uiState.currentWorkflowHasSteps,
            cfg = uiState.unetCfg,
            onCfgChange = onCfgChange,
            cfgError = if (!uiState.isCheckpointMode) uiState.cfgError else null,
            showCfg = uiState.currentWorkflowHasCfg
        )
    }

    // Sampler dropdown (conditional)
    if (uiState.currentWorkflowHasSamplerName) {
        Spacer(modifier = Modifier.height(12.dp))

        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = uiState.unetSampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = onSamplerChange
        )
    }

    // Scheduler dropdown (conditional)
    if (uiState.currentWorkflowHasScheduler) {
        Spacer(modifier = Modifier.height(12.dp))

        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = uiState.unetScheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = onSchedulerChange
        )
    }

    // LoRA chain editor (conditional)
    if (uiState.currentWorkflowHasLoraName) {
        Spacer(modifier = Modifier.height(16.dp))

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
}

/**
 * Dropdown for unified workflow selection with type prefix display
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkflowDropdown(
    label: String,
    selectedWorkflow: String,
    workflows: List<WorkflowItem>,
    onWorkflowChange: (String) -> Unit,
    onViewWorkflow: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Find display name for selected workflow
    val selectedDisplayName = workflows.find { it.name == selectedWorkflow }?.displayName ?: selectedWorkflow

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
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

        if (onViewWorkflow != null) {
            OutlinedIconButton(
                onClick = onViewWorkflow,
                modifier = Modifier.size(56.dp),
                shape = ButtonDefaults.squareShape
            ) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = stringResource(R.string.view_workflow)
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
