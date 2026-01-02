package sh.hnet.comfychair.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider

/**
 * A row containing Denoise and Batch Size stepper fields with constraints
 * dynamically loaded from the actual mapped nodes in the workflow.
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param denoise Current denoise value
 * @param onDenoiseChange Callback when denoise changes
 * @param denoiseError Error message for denoise field
 * @param showDenoise Whether to show the denoise field
 * @param batchSize Current batch size value
 * @param onBatchSizeChange Callback when batch size changes
 * @param batchSizeError Error message for batch size field
 * @param showBatchSize Whether to show the batch size field
 * @param modifier Modifier for the row
 */
@Composable
fun DenoiseBatchRow(
    workflowName: String,
    denoise: String,
    onDenoiseChange: (String) -> Unit,
    denoiseError: String?,
    showDenoise: Boolean,
    batchSize: String,
    onBatchSizeChange: (String) -> Unit,
    batchSizeError: String?,
    showBatchSize: Boolean,
    modifier: Modifier = Modifier
) {
    val denoiseConstraints = WorkflowConstraintsProvider.rememberConstraints("denoise", workflowName)
    val batchSizeConstraints = WorkflowConstraintsProvider.rememberConstraints("batch_size", workflowName)

    StepperInputRow(
        value1 = denoise,
        label1 = stringResource(R.string.label_denoise),
        onValue1Change = onDenoiseChange,
        error1 = denoiseError,
        showField1 = showDenoise,
        min1 = denoiseConstraints.min,
        max1 = denoiseConstraints.max,
        step1 = denoiseConstraints.step,
        decimalPlaces1 = denoiseConstraints.decimalPlaces,
        value2 = batchSize,
        label2 = stringResource(R.string.label_batch_size),
        onValue2Change = onBatchSizeChange,
        error2 = batchSizeError,
        showField2 = showBatchSize,
        min2 = batchSizeConstraints.min,
        max2 = batchSizeConstraints.max,
        step2 = batchSizeConstraints.step,
        decimalPlaces2 = batchSizeConstraints.decimalPlaces,
        modifier = modifier
    )
}
