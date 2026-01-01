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
import sh.hnet.comfychair.ui.components.shared.DimensionStepperRow
import sh.hnet.comfychair.ui.components.shared.GenericWorkflowDropdown
import sh.hnet.comfychair.ui.components.shared.LengthFpsRow
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
    onClip1Change: (String) -> Unit,
    onClip2Change: (String) -> Unit,
    onClip3Change: (String) -> Unit,
    onClip4Change: (String) -> Unit,
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
                uiState.currentWorkflowHasClipName ||
                uiState.currentWorkflowHasClipName1 ||
                uiState.currentWorkflowHasClipName2 ||
                uiState.currentWorkflowHasClipName3 ||
                uiState.currentWorkflowHasClipName4

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
                options = uiState.filteredUnets ?: uiState.availableUnets,
                onValueChange = onHighnoiseUnetChange
            )
        }

        // Low noise UNET dropdown - only show if workflow has it mapped
        if (uiState.currentWorkflowHasLownoiseUnet) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.lownoise_unet_label),
                selectedValue = uiState.selectedLownoiseUnet,
                options = uiState.filteredUnets ?: uiState.availableUnets,
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
                options = uiState.filteredVaes ?: uiState.availableVaes,
                onValueChange = onVaeChange
            )
        }

        // CLIP dropdown(s) - each shown independently based on workflow mapping
        if (uiState.currentWorkflowHasClipName) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.label_clip),
                selectedValue = uiState.selectedClip,
                options = uiState.filteredClips ?: uiState.availableClips,
                onValueChange = onClipChange
            )
        }

        if (uiState.currentWorkflowHasClipName1) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.label_clip1),
                selectedValue = uiState.selectedClip1,
                options = uiState.filteredClips1 ?: uiState.availableClips,
                onValueChange = onClip1Change
            )
        }

        if (uiState.currentWorkflowHasClipName2) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.label_clip2),
                selectedValue = uiState.selectedClip2,
                options = uiState.filteredClips2 ?: uiState.availableClips,
                onValueChange = onClip2Change
            )
        }

        if (uiState.currentWorkflowHasClipName3) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.label_clip3),
                selectedValue = uiState.selectedClip3,
                options = uiState.filteredClips3 ?: uiState.availableClips,
                onValueChange = onClip3Change
            )
        }

        if (uiState.currentWorkflowHasClipName4) {
            Spacer(modifier = Modifier.height(12.dp))
            ModelDropdown(
                label = stringResource(R.string.label_clip4),
                selectedValue = uiState.selectedClip4,
                options = uiState.filteredClips4 ?: uiState.availableClips,
                onValueChange = onClip4Change
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
            DimensionStepperRow(
                workflowName = uiState.selectedWorkflow,
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
            LengthFpsRow(
                workflowName = uiState.selectedWorkflow,
                length = uiState.length,
                onLengthChange = onLengthChange,
                lengthError = uiState.lengthError,
                showLength = uiState.currentWorkflowHasLength,
                fps = uiState.fps,
                onFpsChange = onFpsChange,
                fpsError = uiState.fpsError,
                showFps = uiState.currentWorkflowHasFrameRate
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
