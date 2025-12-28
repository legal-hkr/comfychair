package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.DimensionInputRow
import sh.hnet.comfychair.ui.components.shared.GenericWorkflowDropdown
import sh.hnet.comfychair.ui.components.shared.NumericInputRow
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState

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
        // Negative prompt (optional)
        if (uiState.currentWorkflowHasNegativePrompt) {
            OutlinedTextField(
                value = uiState.negativePrompt,
                onValueChange = onNegativePromptChange,
                label = { Text(stringResource(R.string.negative_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Workflow dropdown
        GenericWorkflowDropdown(
            label = stringResource(R.string.label_workflow),
            selectedWorkflow = uiState.selectedWorkflow,
            workflows = uiState.availableWorkflows,
            onWorkflowChange = onWorkflowChange,
            onViewWorkflow = onViewWorkflow
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Check if any model dropdowns are visible
        val hasAnyModels = uiState.currentWorkflowHasHighnoiseUnet ||
                uiState.currentWorkflowHasLownoiseUnet ||
                uiState.currentWorkflowHasHighnoiseLora ||
                uiState.currentWorkflowHasLownoiseLora ||
                uiState.currentWorkflowHasVaeName ||
                uiState.currentWorkflowHasClipName

        // Model selection section - only show if any models are visible
        if (hasAnyModels) {
            Text(
                text = stringResource(R.string.model_selection_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // High noise UNET dropdown (conditional - only show if mapped in workflow)
        if (uiState.currentWorkflowHasHighnoiseUnet) {
            ModelDropdown(
                label = stringResource(R.string.highnoise_unet_label),
                selectedValue = uiState.selectedHighnoiseUnet,
                options = uiState.availableUnets,
                onValueChange = onHighnoiseUnetChange
            )
        }

        // Low noise UNET dropdown - only show if workflow has it mapped
        if (uiState.currentWorkflowHasLownoiseUnet) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.lownoise_unet_label),
                selectedValue = uiState.selectedLownoiseUnet,
                options = uiState.availableUnets,
                onValueChange = onLownoiseUnetChange
            )
        }

        // High noise LoRA dropdown - only show if workflow has it mapped
        if (uiState.currentWorkflowHasHighnoiseLora) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.highnoise_lora_label),
                selectedValue = uiState.selectedHighnoiseLora,
                options = uiState.availableLoras,
                onValueChange = onHighnoiseLoraChange
            )
        }

        // Low noise LoRA dropdown - only show if workflow has both low noise UNET and low noise LoRA mapped
        if (uiState.currentWorkflowHasLownoiseUnet && uiState.currentWorkflowHasLownoiseLora) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.lownoise_lora_label),
                selectedValue = uiState.selectedLownoiseLora,
                options = uiState.availableLoras,
                onValueChange = onLownoiseLoraChange
            )
        }

        // VAE dropdown (optional)
        if (uiState.currentWorkflowHasVaeName) {
            Spacer(modifier = Modifier.height(12.dp))

            ModelDropdown(
                label = stringResource(R.string.label_vae),
                selectedValue = uiState.selectedVae,
                options = uiState.availableVaes,
                onValueChange = onVaeChange
            )
        }

        // CLIP dropdown (optional)
        if (uiState.currentWorkflowHasClipName) {
            Spacer(modifier = Modifier.height(12.dp))

            ModelDropdown(
                label = stringResource(R.string.label_clip),
                selectedValue = uiState.selectedClip,
                options = uiState.availableClips,
                onValueChange = onClipChange
            )
        }

        // Check if any video parameters are visible
        val hasAnyVideoParams = uiState.currentWorkflowHasWidth ||
                uiState.currentWorkflowHasHeight ||
                uiState.currentWorkflowHasLength ||
                uiState.currentWorkflowHasFrameRate

        if (hasAnyVideoParams) {
            Spacer(modifier = Modifier.height(16.dp))

            // Video parameters section
            Text(
                text = stringResource(R.string.video_parameters_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Width and Height row (show if either is present)
        if (uiState.currentWorkflowHasWidth || uiState.currentWorkflowHasHeight) {
            DimensionInputRow(
                width = uiState.width,
                onWidthChange = onWidthChange,
                widthError = uiState.widthError,
                height = uiState.height,
                onHeightChange = onHeightChange,
                heightError = uiState.heightError,
                showWidth = uiState.currentWorkflowHasWidth,
                showHeight = uiState.currentWorkflowHasHeight
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Length and FPS row (show if either is present)
        if (uiState.currentWorkflowHasLength || uiState.currentWorkflowHasFrameRate) {
            NumericInputRow(
                value1 = uiState.length,
                label1 = stringResource(R.string.length_label),
                onValue1Change = onLengthChange,
                error1 = uiState.lengthError,
                showField1 = uiState.currentWorkflowHasLength,
                value2 = uiState.fps,
                label2 = stringResource(R.string.fps_label),
                onValue2Change = onFpsChange,
                error2 = uiState.fpsError,
                showField2 = uiState.currentWorkflowHasFrameRate
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // High noise LoRA chain editor - only show if high noise UNET is mapped
        if (uiState.currentWorkflowHasHighnoiseUnet) {
            Spacer(modifier = Modifier.height(16.dp))
            LoraChainEditor(
                title = stringResource(R.string.highnoise_lora_chain_title),
                loraChain = uiState.highnoiseLoraChain,
                availableLoras = uiState.availableLoras,
                onAddLora = onAddHighnoiseLora,
                onRemoveLora = onRemoveHighnoiseLora,
                onLoraNameChange = onHighnoiseLoraChainNameChange,
                onLoraStrengthChange = onHighnoiseLoraChainStrengthChange
            )
        }

        // Low noise LoRA chain editor - only show if workflow has low noise UNET mapped
        if (uiState.currentWorkflowHasLownoiseUnet) {
            Spacer(modifier = Modifier.height(16.dp))
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
}
