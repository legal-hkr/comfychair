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
import java.util.Locale

/**
 * A row containing two NumericStepperField components side by side.
 * Useful for Steps/CFG, Length/FPS, and similar paired inputs.
 *
 * @param value1 First field value
 * @param label1 First field label
 * @param onValue1Change First field change callback
 * @param error1 First field error message
 * @param showField1 Whether to show first field
 * @param min1 First field minimum value
 * @param max1 First field maximum value
 * @param step1 First field step size
 * @param decimalPlaces1 First field decimal places
 * @param value2 Second field value
 * @param label2 Second field label
 * @param onValue2Change Second field change callback
 * @param error2 Second field error message
 * @param showField2 Whether to show second field
 * @param min2 Second field minimum value
 * @param max2 Second field maximum value
 * @param step2 Second field step size
 * @param decimalPlaces2 Second field decimal places
 * @param modifier Modifier for the row
 */
@Composable
fun StepperInputRow(
    value1: String,
    label1: String,
    onValue1Change: (String) -> Unit,
    error1: String?,
    showField1: Boolean,
    min1: Float,
    max1: Float,
    step1: Float,
    decimalPlaces1: Int = 0,
    value2: String,
    label2: String,
    onValue2Change: (String) -> Unit,
    error2: String?,
    showField2: Boolean,
    min2: Float,
    max2: Float,
    step2: Float,
    decimalPlaces2: Int = 0,
    modifier: Modifier = Modifier
) {
    if (!showField1 && !showField2) return

    // Format range hints based on decimal places
    val hint1 = stringResource(
        R.string.node_editor_range_min_max,
        formatRangeValue(min1, decimalPlaces1),
        formatRangeValue(max1, decimalPlaces1)
    )
    val hint2 = stringResource(
        R.string.node_editor_range_min_max,
        formatRangeValue(min2, decimalPlaces2),
        formatRangeValue(max2, decimalPlaces2)
    )

    Row(modifier = modifier.fillMaxWidth()) {
        if (showField1) {
            NumericStepperField(
                value = value1,
                onValueChange = onValue1Change,
                label = label1,
                min = min1,
                max = max1,
                step = step1,
                decimalPlaces = decimalPlaces1,
                error = error1,
                hint = hint1,
                modifier = Modifier.weight(1f)
            )
        }

        if (showField1 && showField2) {
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (showField2) {
            NumericStepperField(
                value = value2,
                onValueChange = onValue2Change,
                label = label2,
                min = min2,
                max = max2,
                step = step2,
                decimalPlaces = decimalPlaces2,
                error = error2,
                hint = hint2,
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
