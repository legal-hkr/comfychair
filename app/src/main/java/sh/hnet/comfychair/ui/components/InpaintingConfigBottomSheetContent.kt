package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import sh.hnet.comfychair.viewmodel.InpaintingConfigMode
import sh.hnet.comfychair.viewmodel.InpaintingUiState

/**
 * Content for the inpainting configuration bottom sheet
 */
@Composable
fun InpaintingConfigBottomSheetContent(
    uiState: InpaintingUiState,
    onConfigModeChange: (InpaintingConfigMode) -> Unit,
    onCheckpointWorkflowChange: (String) -> Unit,
    onCheckpointChange: (String) -> Unit,
    onMegapixelsChange: (String) -> Unit,
    onCheckpointStepsChange: (String) -> Unit,
    onUnetWorkflowChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onUnetStepsChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.config_panel_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Mode toggle (Checkpoint / UNET)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
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

        when (uiState.configMode) {
            InpaintingConfigMode.CHECKPOINT -> {
                CheckpointModeContent(
                    uiState = uiState,
                    onWorkflowChange = onCheckpointWorkflowChange,
                    onCheckpointChange = onCheckpointChange,
                    onMegapixelsChange = onMegapixelsChange,
                    onStepsChange = onCheckpointStepsChange
                )
            }
            InpaintingConfigMode.UNET -> {
                UnetModeContent(
                    uiState = uiState,
                    onWorkflowChange = onUnetWorkflowChange,
                    onUnetChange = onUnetChange,
                    onVaeChange = onVaeChange,
                    onClipChange = onClipChange,
                    onStepsChange = onUnetStepsChange
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
    onStepsChange: (String) -> Unit
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

    // Steps
    OutlinedTextField(
        value = uiState.checkpointSteps,
        onValueChange = onStepsChange,
        label = { Text(stringResource(R.string.label_steps)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun UnetModeContent(
    uiState: InpaintingUiState,
    onWorkflowChange: (String) -> Unit,
    onUnetChange: (String) -> Unit,
    onVaeChange: (String) -> Unit,
    onClipChange: (String) -> Unit,
    onStepsChange: (String) -> Unit
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

    // Steps
    OutlinedTextField(
        value = uiState.unetSteps,
        onValueChange = onStepsChange,
        label = { Text(stringResource(R.string.label_steps)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
