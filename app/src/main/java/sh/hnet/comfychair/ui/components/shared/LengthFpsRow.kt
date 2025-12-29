package sh.hnet.comfychair.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider

/**
 * A row containing Length and FPS stepper fields with constraints
 * dynamically loaded from the actual mapped nodes in the workflow.
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param length Current length value
 * @param onLengthChange Callback when length changes
 * @param lengthError Error message for length field
 * @param showLength Whether to show the length field
 * @param fps Current FPS value
 * @param onFpsChange Callback when FPS changes
 * @param fpsError Error message for FPS field
 * @param showFps Whether to show the FPS field
 * @param modifier Modifier for the row
 */
@Composable
fun LengthFpsRow(
    workflowName: String,
    length: String,
    onLengthChange: (String) -> Unit,
    lengthError: String?,
    showLength: Boolean,
    fps: String,
    onFpsChange: (String) -> Unit,
    fpsError: String?,
    showFps: Boolean,
    modifier: Modifier = Modifier
) {
    val lengthConstraints = WorkflowConstraintsProvider.rememberConstraints("length", workflowName)
    val fpsConstraints = WorkflowConstraintsProvider.rememberConstraints("fps", workflowName)

    StepperInputRow(
        value1 = length,
        label1 = stringResource(R.string.length_label),
        onValue1Change = onLengthChange,
        error1 = lengthError,
        showField1 = showLength,
        min1 = lengthConstraints.min,
        max1 = lengthConstraints.max,
        step1 = lengthConstraints.step,
        decimalPlaces1 = lengthConstraints.decimalPlaces,
        value2 = fps,
        label2 = stringResource(R.string.fps_label),
        onValue2Change = onFpsChange,
        error2 = fpsError,
        showField2 = showFps,
        min2 = fpsConstraints.min,
        max2 = fpsConstraints.max,
        step2 = fpsConstraints.step,
        decimalPlaces2 = fpsConstraints.decimalPlaces,
        modifier = modifier
    )
}
