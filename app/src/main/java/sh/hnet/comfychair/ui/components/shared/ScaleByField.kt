package sh.hnet.comfychair.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import java.util.Locale

/**
 * A single scale by stepper field with constraints dynamically loaded
 * from the actual mapped node in the workflow.
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param value Current scale by value
 * @param onValueChange Callback when value changes
 * @param error Error message for the field
 * @param modifier Modifier for the field
 */
@Composable
fun ScaleByField(
    workflowName: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    val constraints = WorkflowConstraintsProvider.rememberConstraints("scale_by", workflowName)

    val hint = stringResource(
        R.string.node_editor_range_min_max,
        formatRangeValue(constraints.min, constraints.decimalPlaces),
        formatRangeValue(constraints.max, constraints.decimalPlaces)
    )

    NumericStepperField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.label_scale_by),
        min = constraints.min,
        max = constraints.max,
        step = constraints.step,
        decimalPlaces = constraints.decimalPlaces,
        error = error,
        hint = hint,
        modifier = modifier
    )
}

/**
 * Formats a range value for display, showing integers without decimals
 * and floats with the specified number of decimal places.
 */
private fun formatRangeValue(value: Float, decimalPlaces: Int): String {
    return if (decimalPlaces == 0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.${decimalPlaces}f", value)
    }
}
