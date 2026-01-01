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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import sh.hnet.comfychair.ui.components.shared.GenericWorkflowDropdown
import sh.hnet.comfychair.ui.components.shared.MegapixelsField
import sh.hnet.comfychair.ui.components.shared.StepsCfgRow
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageUiState

/**
 * Content for the Image-to-image configuration bottom sheet.
 * Supports both Inpainting and Editing modes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    onClip1Change: (String) -> Unit,
    onClip2Change: (String) -> Unit,
    onClip3Change: (String) -> Unit,
    onClip4Change: (String) -> Unit,
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
    onEditingClip1Change: (String) -> Unit,
    onEditingClip2Change: (String) -> Unit,
    onEditingClip3Change: (String) -> Unit,
    onEditingClip4Change: (String) -> Unit,
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
        if (uiState.currentWorkflowHasNegativePrompt) {
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
        }

        // Reference images (only in Editing mode, only if workflow supports them)
        val hasAnyReferenceImages = uiState.currentWorkflowHasReferenceImage1 || uiState.currentWorkflowHasReferenceImage2
        if (uiState.mode == ImageToImageMode.EDITING && hasAnyReferenceImages) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.currentWorkflowHasReferenceImage1) {
                    ReferenceImageThumbnail(
                        image = uiState.referenceImage1,
                        contentDescription = stringResource(R.string.content_description_reference_image_1),
                        onImageSelected = onReferenceImage1Change,
                        onClear = onClearReferenceImage1,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (uiState.currentWorkflowHasReferenceImage1 && uiState.currentWorkflowHasReferenceImage2) {
                    Spacer(modifier = Modifier.width(16.dp))
                }

                if (uiState.currentWorkflowHasReferenceImage2) {
                    ReferenceImageThumbnail(
                        image = uiState.referenceImage2,
                        contentDescription = stringResource(R.string.content_description_reference_image_2),
                        onImageSelected = onReferenceImage2Change,
                        onClear = onClearReferenceImage2,
                        modifier = Modifier.weight(1f)
                    )
                }
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
                    onEditingClip1Change = onEditingClip1Change,
                    onEditingClip2Change = onEditingClip2Change,
                    onEditingClip3Change = onEditingClip3Change,
                    onEditingClip4Change = onEditingClip4Change,
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
                    onClip1Change = onClip1Change,
                    onClip2Change = onClip2Change,
                    onClip3Change = onClip3Change,
                    onClip4Change = onClip4Change,
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
    onClip1Change: (String) -> Unit,
    onClip2Change: (String) -> Unit,
    onClip3Change: (String) -> Unit,
    onClip4Change: (String) -> Unit,
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
    GenericWorkflowDropdown(
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
            onClip1Change = onClip1Change,
            onClip2Change = onClip2Change,
            onClip3Change = onClip3Change,
            onClip4Change = onClip4Change,
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
    onEditingClip1Change: (String) -> Unit,
    onEditingClip2Change: (String) -> Unit,
    onEditingClip3Change: (String) -> Unit,
    onEditingClip4Change: (String) -> Unit,
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
    GenericWorkflowDropdown(
        label = stringResource(R.string.label_workflow),
        selectedWorkflow = uiState.selectedEditingWorkflow,
        workflows = uiState.editingWorkflows,
        onWorkflowChange = onEditingWorkflowChange,
        onViewWorkflow = onViewEditingWorkflow
    )

    Spacer(modifier = Modifier.height(16.dp))

    // UNET dropdown (conditional - only show if mapped in workflow)
    if (uiState.currentWorkflowHasUnetName) {
        ModelDropdown(
            label = stringResource(R.string.label_unet),
            options = uiState.filteredUnets ?: uiState.unets,
            selectedValue = uiState.selectedEditingUnet,
            onValueChange = onEditingUnetChange
        )
    }

    // LoRA dropdown (optional)
    if (uiState.currentWorkflowHasLoraName) {
        Spacer(modifier = Modifier.height(16.dp))

        ModelDropdown(
            label = stringResource(R.string.label_lora),
            options = uiState.availableLoras,
            selectedValue = uiState.selectedEditingLora,
            onValueChange = onEditingLoraChange
        )
    }

    // VAE dropdown (optional)
    if (uiState.currentWorkflowHasVaeName) {
        Spacer(modifier = Modifier.height(16.dp))

        ModelDropdown(
            label = stringResource(R.string.label_vae),
            options = uiState.filteredVaes ?: uiState.vaes,
            selectedValue = uiState.selectedEditingVae,
            onValueChange = onEditingVaeChange
        )
    }

    // CLIP dropdown(s) - each shown independently based on workflow mapping
    if (uiState.currentWorkflowHasClipName) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip),
            options = uiState.filteredClips ?: uiState.clips,
            selectedValue = uiState.selectedEditingClip,
            onValueChange = onEditingClipChange
        )
    }

    if (uiState.currentWorkflowHasClipName1) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip1),
            options = uiState.filteredClips1 ?: uiState.clips,
            selectedValue = uiState.selectedEditingClip1,
            onValueChange = onEditingClip1Change
        )
    }

    if (uiState.currentWorkflowHasClipName2) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip2),
            options = uiState.filteredClips2 ?: uiState.clips,
            selectedValue = uiState.selectedEditingClip2,
            onValueChange = onEditingClip2Change
        )
    }

    if (uiState.currentWorkflowHasClipName3) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip3),
            options = uiState.filteredClips3 ?: uiState.clips,
            selectedValue = uiState.selectedEditingClip3,
            onValueChange = onEditingClip3Change
        )
    }

    if (uiState.currentWorkflowHasClipName4) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip4),
            options = uiState.filteredClips4 ?: uiState.clips,
            selectedValue = uiState.selectedEditingClip4,
            onValueChange = onEditingClip4Change
        )
    }

    // Check if any image parameters are visible
    val hasAnyImageParams = uiState.currentWorkflowHasMegapixels ||
            uiState.currentWorkflowHasSteps ||
            uiState.currentWorkflowHasCfg ||
            uiState.currentWorkflowHasSamplerName ||
            uiState.currentWorkflowHasScheduler

    if (hasAnyImageParams) {
        Spacer(modifier = Modifier.height(16.dp))

        // Image parameters title
        Text(
            text = stringResource(R.string.image_parameters_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    // Megapixels (optional)
    if (uiState.currentWorkflowHasMegapixels) {
        MegapixelsField(
            workflowName = uiState.selectedEditingWorkflow,
            value = uiState.editingMegapixels,
            onValueChange = onEditingMegapixelsChange,
            error = if (uiState.mode == ImageToImageMode.EDITING) uiState.megapixelsError else null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Steps and CFG row (show if either is present)
    if (uiState.currentWorkflowHasSteps || uiState.currentWorkflowHasCfg) {
        StepsCfgRow(
            workflowName = uiState.selectedEditingWorkflow,
            steps = uiState.editingSteps,
            onStepsChange = onEditingStepsChange,
            stepsError = if (uiState.mode == ImageToImageMode.EDITING) uiState.stepsError else null,
            showSteps = uiState.currentWorkflowHasSteps,
            cfg = uiState.editingCfg,
            onCfgChange = onEditingCfgChange,
            cfgError = if (uiState.mode == ImageToImageMode.EDITING) uiState.cfgError else null,
            showCfg = uiState.currentWorkflowHasCfg
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Sampler dropdown (optional)
    if (uiState.currentWorkflowHasSamplerName) {
        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = uiState.editingSampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = onEditingSamplerChange
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Scheduler dropdown (optional)
    if (uiState.currentWorkflowHasScheduler) {
        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = uiState.editingScheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = onEditingSchedulerChange
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Optional LoRA chain editor (in addition to primary LoRA)
    if (uiState.currentWorkflowHasLoraName) {
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
}

/**
 * Reference image thumbnail with delete button
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            FilledIconButton(
                onClick = onClear,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(40.dp),
                shape = IconButtonDefaults.smallSquareShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.content_description_clear)
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
    // Checkpoint dropdown (conditional - only show if mapped in workflow)
    if (uiState.currentWorkflowHasCheckpointName) {
        ModelDropdown(
            label = stringResource(R.string.label_checkpoint),
            options = uiState.filteredCheckpoints ?: uiState.checkpoints,
            selectedValue = uiState.selectedCheckpoint,
            onValueChange = onCheckpointChange
        )
    }

    // Check if any image parameters are visible
    val hasAnyImageParams = uiState.currentWorkflowHasMegapixels ||
            uiState.currentWorkflowHasSteps ||
            uiState.currentWorkflowHasCfg ||
            uiState.currentWorkflowHasSamplerName ||
            uiState.currentWorkflowHasScheduler

    if (hasAnyImageParams) {
        Spacer(modifier = Modifier.height(16.dp))

        // Image parameters title
        Text(
            text = stringResource(R.string.image_parameters_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    // Megapixels (optional)
    if (uiState.currentWorkflowHasMegapixels) {
        MegapixelsField(
            workflowName = uiState.selectedWorkflow,
            value = uiState.megapixels,
            onValueChange = onMegapixelsChange,
            error = uiState.megapixelsError,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Steps and CFG row (show if either is present)
    if (uiState.currentWorkflowHasSteps || uiState.currentWorkflowHasCfg) {
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

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Sampler dropdown (optional)
    if (uiState.currentWorkflowHasSamplerName) {
        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = uiState.checkpointSampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = onSamplerChange
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Scheduler dropdown (optional)
    if (uiState.currentWorkflowHasScheduler) {
        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = uiState.checkpointScheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = onSchedulerChange
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // LoRA chain editor (optional)
    if (uiState.currentWorkflowHasLoraName) {
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
    uiState: ImageToImageUiState,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onClip1Change: (String) -> Unit,
    onClip2Change: (String) -> Unit,
    onClip3Change: (String) -> Unit,
    onClip4Change: (String) -> Unit,
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
            options = uiState.filteredUnets ?: uiState.unets,
            selectedValue = uiState.selectedUnet,
            onValueChange = onUnetChange
        )
    }

    // VAE dropdown (optional)
    if (uiState.currentWorkflowHasVaeName) {
        Spacer(modifier = Modifier.height(16.dp))

        ModelDropdown(
            label = stringResource(R.string.label_vae),
            options = uiState.filteredVaes ?: uiState.vaes,
            selectedValue = uiState.selectedVae,
            onValueChange = onVaeChange
        )
    }

    // CLIP dropdown(s) - each shown independently based on workflow mapping
    if (uiState.currentWorkflowHasClipName) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip),
            options = uiState.filteredClips ?: uiState.clips,
            selectedValue = uiState.selectedClip,
            onValueChange = onClipChange
        )
    }

    if (uiState.currentWorkflowHasClipName1) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip1),
            options = uiState.filteredClips1 ?: uiState.clips,
            selectedValue = uiState.selectedClip1,
            onValueChange = onClip1Change
        )
    }

    if (uiState.currentWorkflowHasClipName2) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip2),
            options = uiState.filteredClips2 ?: uiState.clips,
            selectedValue = uiState.selectedClip2,
            onValueChange = onClip2Change
        )
    }

    if (uiState.currentWorkflowHasClipName3) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip3),
            options = uiState.filteredClips3 ?: uiState.clips,
            selectedValue = uiState.selectedClip3,
            onValueChange = onClip3Change
        )
    }

    if (uiState.currentWorkflowHasClipName4) {
        Spacer(modifier = Modifier.height(16.dp))
        ModelDropdown(
            label = stringResource(R.string.label_clip4),
            options = uiState.filteredClips4 ?: uiState.clips,
            selectedValue = uiState.selectedClip4,
            onValueChange = onClip4Change
        )
    }

    // Check if any image parameters are visible
    val hasAnyImageParams = uiState.currentWorkflowHasSteps ||
            uiState.currentWorkflowHasCfg ||
            uiState.currentWorkflowHasSamplerName ||
            uiState.currentWorkflowHasScheduler

    if (hasAnyImageParams) {
        Spacer(modifier = Modifier.height(16.dp))

        // Image parameters title
        Text(
            text = stringResource(R.string.image_parameters_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }

    // Steps and CFG row (show if either is present)
    if (uiState.currentWorkflowHasSteps || uiState.currentWorkflowHasCfg) {
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

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Sampler dropdown (optional)
    if (uiState.currentWorkflowHasSamplerName) {
        ModelDropdown(
            label = stringResource(R.string.label_sampler),
            selectedValue = uiState.unetSampler,
            options = SamplerOptions.SAMPLERS,
            onValueChange = onSamplerChange
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    // Scheduler dropdown (optional)
    if (uiState.currentWorkflowHasScheduler) {
        ModelDropdown(
            label = stringResource(R.string.label_scheduler),
            selectedValue = uiState.unetScheduler,
            options = SamplerOptions.SCHEDULERS,
            onValueChange = onSchedulerChange
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // LoRA chain editor (optional)
    if (uiState.currentWorkflowHasLoraName) {
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
