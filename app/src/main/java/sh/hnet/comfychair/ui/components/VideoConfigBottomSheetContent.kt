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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.viewmodel.TextToVideoUiState

/**
 * Content for the video configuration bottom sheet
 */
@Composable
fun VideoConfigBottomSheetContent(
    uiState: TextToVideoUiState,
    onWorkflowChange: (String) -> Unit,
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
        // Workflow dropdown
        ModelDropdown(
            label = stringResource(R.string.label_workflow),
            selectedValue = uiState.selectedWorkflow,
            options = uiState.availableWorkflows,
            onValueChange = onWorkflowChange
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
