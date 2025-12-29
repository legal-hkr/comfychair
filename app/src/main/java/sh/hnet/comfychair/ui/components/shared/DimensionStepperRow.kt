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

/**
 * A row containing Width and Height stepper fields.
 * Specialized for dimension inputs with step=8 for latent space alignment.
 *
 * @param width Current width value as string
 * @param onWidthChange Callback when width changes
 * @param widthError Width validation error message
 * @param height Current height value as string
 * @param onHeightChange Callback when height changes
 * @param heightError Height validation error message
 * @param showWidth Whether to show the width field
 * @param showHeight Whether to show the height field
 * @param step Step size for increment/decrement (default 8)
 * @param min Minimum dimension value (default 64)
 * @param max Maximum dimension value (default 4096)
 * @param modifier Modifier for the row
 */
@Composable
fun DimensionStepperRow(
    width: String,
    onWidthChange: (String) -> Unit,
    widthError: String?,
    height: String,
    onHeightChange: (String) -> Unit,
    heightError: String?,
    showWidth: Boolean = true,
    showHeight: Boolean = true,
    step: Int = 8,
    min: Int = 64,
    max: Int = 4096,
    modifier: Modifier = Modifier
) {
    if (!showWidth && !showHeight) return

    // Range hint shown below each field
    val rangeHint = stringResource(R.string.node_editor_range_min_max, min.toString(), max.toString())

    Row(modifier = modifier.fillMaxWidth()) {
        if (showWidth) {
            NumericStepperField(
                value = width,
                onValueChange = onWidthChange,
                label = stringResource(R.string.label_width),
                min = min.toFloat(),
                max = max.toFloat(),
                step = step.toFloat(),
                decimalPlaces = 0,
                error = widthError,
                hint = rangeHint,
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
                min = min.toFloat(),
                max = max.toFloat(),
                step = step.toFloat(),
                decimalPlaces = 0,
                error = heightError,
                hint = rangeHint,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
