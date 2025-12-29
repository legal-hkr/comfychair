package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import java.util.Locale

/**
 * A row containing Width and Height stepper fields with constraints
 * dynamically loaded from the actual mapped nodes in the workflow.
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param width Current width value as string
 * @param onWidthChange Callback when width changes
 * @param widthError Width validation error message
 * @param height Current height value as string
 * @param onHeightChange Callback when height changes
 * @param heightError Height validation error message
 * @param showWidth Whether to show the width field
 * @param showHeight Whether to show the height field
 * @param modifier Modifier for the row
 */
@Composable
fun DimensionStepperRow(
    workflowName: String,
    width: String,
    onWidthChange: (String) -> Unit,
    widthError: String?,
    height: String,
    onHeightChange: (String) -> Unit,
    heightError: String?,
    showWidth: Boolean = true,
    showHeight: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (!showWidth && !showHeight) return

    val widthConstraints = WorkflowConstraintsProvider.rememberConstraints("width", workflowName)
    val heightConstraints = WorkflowConstraintsProvider.rememberConstraints("height", workflowName)

    // Range hints shown below each field
    val widthRangeHint = stringResource(
        R.string.node_editor_range_min_max,
        formatRangeValue(widthConstraints.min, widthConstraints.decimalPlaces),
        formatRangeValue(widthConstraints.max, widthConstraints.decimalPlaces)
    )
    val heightRangeHint = stringResource(
        R.string.node_editor_range_min_max,
        formatRangeValue(heightConstraints.min, heightConstraints.decimalPlaces),
        formatRangeValue(heightConstraints.max, heightConstraints.decimalPlaces)
    )

    Row(modifier = modifier.fillMaxWidth()) {
        if (showWidth) {
            NumericStepperField(
                value = width,
                onValueChange = onWidthChange,
                label = stringResource(R.string.label_width),
                min = widthConstraints.min,
                max = widthConstraints.max,
                step = widthConstraints.step,
                decimalPlaces = widthConstraints.decimalPlaces,
                error = widthError,
                hint = widthRangeHint,
                modifier = Modifier.weight(1f)
            )
        }

        if (showWidth && showHeight) {
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (showHeight) {
            NumericStepperField(
                value = height,
                onValueChange = onHeightChange,
                label = stringResource(R.string.label_height),
                min = heightConstraints.min,
                max = heightConstraints.max,
                step = heightConstraints.step,
                decimalPlaces = heightConstraints.decimalPlaces,
                error = heightError,
                hint = heightRangeHint,
                modifier = Modifier.weight(1f)
            )
        }
    }
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
