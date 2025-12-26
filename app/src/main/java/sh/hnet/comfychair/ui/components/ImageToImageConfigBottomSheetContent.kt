package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageUiState
import sh.hnet.comfychair.viewmodel.IteWorkflowItem
import sh.hnet.comfychair.viewmodel.ItiWorkflowItem

/**
 * Content for the Image-to-image configuration bottom sheet.
 * Supports both Inpainting and Editing modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToImageConfigBottomSheetContent(
    uiState: ImageToImageUiState,
    // Mode selection callback
    onModeChange: (ImageToImageMode) -> Unit,
    // Reference image callbacks (for Editing mode)
    onReferenceImage1Change: (Uri) -> Unit,
    onReferenceImage2Change: (Uri) -> Unit,
    onClearReferenceImage1: () -> Unit,
    onClearReferenceImage2: () -> Unit,
    // Inpainting workflow callback
    onWorkflowChange: (String) -> Unit,
    onViewWorkflow: () -> Unit,
    // Model selection callbacks (Inpainting mode)
    onCheckpointChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    // Unified parameter callbacks (Inpainting mode)
    onNegativePromptChange: (String) -> Unit,
    onMegapixelsChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onCfgChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onSchedulerChange: (String) -> Unit,
    // Inpainting LoRA chain callbacks
    onAddLora: () -> Unit,
    onRemoveLora: (Int) -> Unit,
    onLoraNameChange: (Int, String) -> Unit,
    onLoraStrengthChange: (Int, Float) -> Unit,
    // Editing mode callbacks
    onEditingWorkflowChange: (String) -> Unit,
    onViewEditingWorkflow: () -> Unit,
    onEditingUnetChange: (String) -> Unit,
    onEditingLoraChange: (String) -> Unit,
    onEditingVaeChange: (String) -> Unit,
    onEditingClipChange: (String) -> Unit,
    onEditingNegativePromptChange: (String) -> Unit,
    onEditingMegapixelsChange: (String) -> Unit,
    onEditingStepsChange: (String) -> Unit,
    onEditingCfgChange: (String) -> Unit,
    onEditingSamplerChange: (String) -> Unit,
    onEditingSchedulerChange: (String) -> Unit,
    onAddEditingLora: () -> Unit,
    onRemoveEditingLora: (Int) -> Unit,
    onEditingLoraNameChange: (Int, String) -> Unit,
    onEditingLoraStrengthChange: (Int, Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Negative prompt (shared between modes, displayed above mode selector)
        OutlinedTextField(
            value = when {
                uiState.mode == ImageToImageMode.EDITING -> uiState.editingNegativePrompt
                uiState.isCheckpointMode -> uiState.checkpointNegativePrompt
                else -> uiState.unetNegativePrompt
            },
            onValueChange = if (uiState.mode == ImageToImageMode.EDITING) onEditingNegativePromptChange else onNegativePromptChange,
            label = { Text(stringResource(R.string.negative_prompt_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Reference images (only in Editing mode, displayed below negative prompt)
        if (uiState.mode == ImageToImageMode.EDITING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReferenceImageThumbnail(
                    image = uiState.referenceImage1,
                    contentDescription = stringResource(R.string.content_description_reference_image_1),
                    onImageSelected = onReferenceImage1Change,
                    onClear = onClearReferenceImage1,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(16.dp))

                ReferenceImageThumbnail(
                    image = uiState.referenceImage2,
                    contentDescription = stringResource(R.string.content_description_reference_image_2),
                    onImageSelected = onReferenceImage2Change,
                    onClear = onClearReferenceImage2,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Mode selection segment button (Editing first, then Inpainting)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = uiState.mode == ImageToImageMode.EDITING,
                onClick = { onModeChange(ImageToImageMode.EDITING) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.mode_editing))
            }
            SegmentedButton(
                selected = uiState.mode == ImageToImageMode.INPAINTING,
                onClick = { onModeChange(ImageToImageMode.INPAINTING) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.mode_inpainting))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (uiState.mode) {
            ImageToImageMode.EDITING -> {
                // Editing mode content
                EditingModeContent(
                    uiState = uiState,
                    onEditingWorkflowChange = onEditingWorkflowChange,
                    onViewEditingWorkflow = onViewEditingWorkflow,
                    onEditingUnetChange = onEditingUnetChange,
                    onEditingLoraChange = onEditingLoraChange,
                    onEditingVaeChange = onEditingVaeChange,
                    onEditingClipChange = onEditingClipChange,
                    onEditingMegapixelsChange = onEditingMegapixelsChange,
                    onEditingStepsChange = onEditingStepsChange,
                    onEditingCfgChange = onEditingCfgChange,
                    onEditingSamplerChange = onEditingSamplerChange,
                    onEditingSchedulerChange = onEditingSchedulerChange,
                    onAddEditingLora = onAddEditingLora,
                    onRemoveEditingLora = onRemoveEditingLora,
                    onEditingLoraNameChange = onEditingLoraNameChange,
                    onEditingLoraStrengthChange = onEditingLoraStrengthChange
                )
            }
            ImageToImageMode.INPAINTING -> {
                // Inpainting mode content
                InpaintingModeContent(
                    uiState = uiState,
                    onWorkflowChange = onWorkflowChange,
                    onViewWorkflow = onViewWorkflow,
                    onCheckpointChange = onCheckpointChange,
                    onUnetChange = onUnetChange,
                    onVaeChange = onVaeChange,
                    onClipChange = onClipChange,
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
            }
        }
    }
}

/**
 * Content for Inpainting mode (existing functionality)
 */
@Composable
private fun InpaintingModeContent(
    uiState: ImageToImageUiState,
    onWorkflowChange: (String) -> Unit,
    onViewWorkflow: () -> Unit,
    onCheckpointChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
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
    // Unified Workflow Dropdown (shows all workflows with type prefix)
    ItiWorkflowDropdown(
        label = stringResource(R.string.label_workflow),
        selectedWorkflow = uiState.selectedWorkflow,
        workflows = uiState.availableWorkflows,
        onWorkflowChange = onWorkflowChange,
        onViewWorkflow = onViewWorkflow
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

/**
 * Content for Editing mode
 */
@Composable
private fun EditingModeContent(
    uiState: ImageToImageUiState,
    onEditingWorkflowChange: (String) -> Unit,
    onViewEditingWorkflow: () -> Unit,
    onEditingUnetChange: (String) -> Unit,
    onEditingLoraChange: (String) -> Unit,
    onEditingVaeChange: (String) -> Unit,
    onEditingClipChange: (String) -> Unit,
    onEditingMegapixelsChange: (String) -> Unit,
    onEditingStepsChange: (String) -> Unit,
    onEditingCfgChange: (String) -> Unit,
    onEditingSamplerChange: (String) -> Unit,
    onEditingSchedulerChange: (String) -> Unit,
    onAddEditingLora: () -> Unit,
    onRemoveEditingLora: (Int) -> Unit,
    onEditingLoraNameChange: (Int, String) -> Unit,
    onEditingLoraStrengthChange: (Int, Float) -> Unit
) {
    // Editing Workflow Dropdown
    IteWorkflowDropdown(
        label = stringResource(R.string.label_workflow),
        selectedWorkflow = uiState.selectedEditingWorkflow,
        workflows = uiState.editingWorkflows,
        onWorkflowChange = onEditingWorkflowChange,
        onViewWorkflow = onViewEditingWorkflow
    )

    Spacer(modifier = Modifier.height(16.dp))

    // UNET dropdown
    ModelDropdown(
        label = stringResource(R.string.label_unet),
        options = uiState.unets,
        selectedValue = uiState.selectedEditingUnet,
        onValueChange = onEditingUnetChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // LoRA dropdown (mandatory)
    ModelDropdown(
        label = stringResource(R.string.label_lora),
        options = uiState.availableLoras,
        selectedValue = uiState.selectedEditingLora,
        onValueChange = onEditingLoraChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // VAE dropdown
    ModelDropdown(
        label = stringResource(R.string.label_vae),
        options = uiState.vaes,
        selectedValue = uiState.selectedEditingVae,
        onValueChange = onEditingVaeChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // CLIP dropdown
    ModelDropdown(
        label = stringResource(R.string.label_clip),
        options = uiState.clips,
        selectedValue = uiState.selectedEditingClip,
        onValueChange = onEditingClipChange
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
        value = uiState.editingMegapixels,
        onValueChange = onEditingMegapixelsChange,
        label = { Text(stringResource(R.string.megapixels_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = uiState.megapixelsError != null && uiState.mode == ImageToImageMode.EDITING,
        supportingText = if (uiState.megapixelsError != null && uiState.mode == ImageToImageMode.EDITING) {
            { Text(uiState.megapixelsError!!) }
        } else null,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Steps and CFG
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = uiState.editingSteps,
            onValueChange = onEditingStepsChange,
            label = { Text(stringResource(R.string.label_steps)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = uiState.editingCfg,
            onValueChange = onEditingCfgChange,
            label = { Text(stringResource(R.string.label_cfg)) },
            isError = uiState.cfgError != null && uiState.mode == ImageToImageMode.EDITING,
            supportingText = if (uiState.cfgError != null && uiState.mode == ImageToImageMode.EDITING) {
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
        selectedValue = uiState.editingSampler,
        options = SamplerOptions.SAMPLERS,
        onValueChange = onEditingSamplerChange
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Scheduler dropdown
    ModelDropdown(
        label = stringResource(R.string.label_scheduler),
        selectedValue = uiState.editingScheduler,
        options = SamplerOptions.SCHEDULERS,
        onValueChange = onEditingSchedulerChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Optional LoRA chain editor (in addition to mandatory LoRA)
    LoraChainEditor(
        title = stringResource(R.string.lora_chain_title),
        loraChain = uiState.editingLoraChain,
        availableLoras = uiState.availableLoras,
        onAddLora = onAddEditingLora,
        onRemoveLora = onRemoveEditingLora,
        onLoraNameChange = onEditingLoraNameChange,
        onLoraStrengthChange = onEditingLoraStrengthChange
    )
}

/**
 * Reference image thumbnail with delete button
 */
@Composable
private fun ReferenceImageThumbnail(
    image: Bitmap?,
    contentDescription: String,
    onImageSelected: (Uri) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImageSelected(it) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { launcher.launch("image/*") },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            // Delete button in top-right corner
            IconButton(
                onClick = onClear,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.content_description_clear),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        } else {
            // Plus icon placeholder
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Dropdown for ITE (Editing) workflow selection
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IteWorkflowDropdown(
    label: String,
    selectedWorkflow: String,
    workflows: List<IteWorkflowItem>,
    onWorkflowChange: (String) -> Unit,
    onViewWorkflow: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ItiWorkflowDropdown(
    label: String,
    selectedWorkflow: String,
    workflows: List<ItiWorkflowItem>,
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
