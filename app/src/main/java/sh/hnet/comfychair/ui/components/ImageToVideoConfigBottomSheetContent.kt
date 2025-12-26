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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.ItvWorkflowItem

/**
 * Content for the image-to-video configuration bottom sheet
 */
@Composable
fun ImageToVideoConfigBottomSheetContent(
    uiState: ImageToVideoUiState,
    onWorkflowChange: (String) -> Unit,
    onViewWorkflow: () -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onHighnoiseUnetChange: (String) -> Unit,
    onLownoiseUnetChange: (String) -> Unit,
    onHighnoiseLoraChange: (String) -> Unit,
    onLownoiseLoraChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onLengthChange: (String) -> Unit,
    onFpsChange: (String) -> Unit,
    // High noise LoRA chain callbacks
    onAddHighnoiseLora: () -> Unit,
    onRemoveHighnoiseLora: (Int) -> Unit,
    onHighnoiseLoraChainNameChange: (Int, String) -> Unit,
    onHighnoiseLoraChainStrengthChange: (Int, Float) -> Unit,
    // Low noise LoRA chain callbacks
    onAddLownoiseLora: () -> Unit,
    onRemoveLownoiseLora: (Int) -> Unit,
    onLownoiseLoraChainNameChange: (Int, String) -> Unit,
    onLownoiseLoraChainStrengthChange: (Int, Float) -> Unit
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
            value = uiState.negativePrompt,
            onValueChange = onNegativePromptChange,
            label = { Text(stringResource(R.string.negative_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Workflow dropdown
        ItvWorkflowDropdown(
            label = stringResource(R.string.label_workflow),
            selectedWorkflow = uiState.selectedWorkflow,
            workflows = uiState.availableWorkflows,
            onWorkflowChange = onWorkflowChange,
            onViewWorkflow = onViewWorkflow
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Model selection section
        Text(
            text = stringResource(R.string.model_selection_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // High noise UNET dropdown
        ModelDropdown(
            label = stringResource(R.string.highnoise_unet_label),
            selectedValue = uiState.selectedHighnoiseUnet,
            options = uiState.availableUnets,
            onValueChange = onHighnoiseUnetChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Low noise UNET dropdown
        ModelDropdown(
            label = stringResource(R.string.lownoise_unet_label),
            selectedValue = uiState.selectedLownoiseUnet,
            options = uiState.availableUnets,
            onValueChange = onLownoiseUnetChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        // High noise LoRA dropdown
        ModelDropdown(
            label = stringResource(R.string.highnoise_lora_label),
            selectedValue = uiState.selectedHighnoiseLora,
            options = uiState.availableLoras,
            onValueChange = onHighnoiseLoraChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Low noise LoRA dropdown
        ModelDropdown(
            label = stringResource(R.string.lownoise_lora_label),
            selectedValue = uiState.selectedLownoiseLora,
            options = uiState.availableLoras,
            onValueChange = onLownoiseLoraChange
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

        // CLIP dropdown
        ModelDropdown(
            label = stringResource(R.string.label_clip),
            selectedValue = uiState.selectedClip,
            options = uiState.availableClips,
            onValueChange = onClipChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Video parameters section
        Text(
            text = stringResource(R.string.video_parameters_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Width and Height
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = uiState.width,
                onValueChange = onWidthChange,
                label = { Text(stringResource(R.string.label_width)) },
                isError = uiState.widthError != null,
                supportingText = uiState.widthError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = uiState.height,
                onValueChange = onHeightChange,
                label = { Text(stringResource(R.string.label_height)) },
                isError = uiState.heightError != null,
                supportingText = uiState.heightError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Length and FPS
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = uiState.length,
                onValueChange = onLengthChange,
                label = { Text(stringResource(R.string.length_label)) },
                isError = uiState.lengthError != null,
                supportingText = uiState.lengthError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = uiState.fps,
                onValueChange = onFpsChange,
                label = { Text(stringResource(R.string.fps_label)) },
                isError = uiState.fpsError != null,
                supportingText = uiState.fpsError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // High noise LoRA chain editor
        LoraChainEditor(
            title = stringResource(R.string.highnoise_lora_chain_title),
            loraChain = uiState.highnoiseLoraChain,
            availableLoras = uiState.availableLoras,
            onAddLora = onAddHighnoiseLora,
            onRemoveLora = onRemoveHighnoiseLora,
            onLoraNameChange = onHighnoiseLoraChainNameChange,
            onLoraStrengthChange = onHighnoiseLoraChainStrengthChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Low noise LoRA chain editor
        LoraChainEditor(
            title = stringResource(R.string.lownoise_lora_chain_title),
            loraChain = uiState.lownoiseLoraChain,
            availableLoras = uiState.availableLoras,
            onAddLora = onAddLownoiseLora,
            onRemoveLora = onRemoveLownoiseLora,
            onLoraNameChange = onLownoiseLoraChainNameChange,
            onLoraStrengthChange = onLownoiseLoraChainStrengthChange
        )
    }
}

/**
 * Dropdown for selecting Image-to-Video workflows with displayName shown
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ItvWorkflowDropdown(
    label: String,
    selectedWorkflow: String,
    workflows: List<ItvWorkflowItem>,
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
                        }
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
