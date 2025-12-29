package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import java.util.Locale

/**
 * A numeric input field with increment/decrement stepper buttons inside the text field.
 * Supports both integer and floating-point values with configurable step size.
 *
 * @param value The current value as a string
 * @param onValueChange Callback when the value changes
 * @param label The field label
 * @param min Minimum allowed value
 * @param max Maximum allowed value
 * @param step Step size for increment/decrement
 * @param decimalPlaces Number of decimal places (0 for integers)
 * @param error Error message to display, or null
 * @param enabled Whether the field and buttons are enabled
 * @param modifier Modifier for the text field
 */
@Composable
fun NumericStepperField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    min: Float,
    max: Float,
    step: Float,
    decimalPlaces: Int = 0,
    error: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentValue = value.toFloatOrNull()
    val canDecrement = enabled && currentValue != null && currentValue > min
    val canIncrement = enabled && currentValue != null && currentValue < max

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimalPlaces > 0) KeyboardType.Decimal else KeyboardType.Number
        ),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = modifier,
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        leadingIcon = {
            IconButton(
                onClick = { performDecrement(value, min, step, decimalPlaces, onValueChange) },
                enabled = canDecrement
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.content_description_decrease)
                )
            }
        },
        trailingIcon = {
            IconButton(
                onClick = { performIncrement(value, max, step, decimalPlaces, onValueChange) },
                enabled = canIncrement
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.content_description_increase)
                )
            }
        }
    )
}

private fun performDecrement(
    value: String,
    min: Float,
    step: Float,
    decimalPlaces: Int,
    onValueChange: (String) -> Unit
) {
    val currentValue = value.toFloatOrNull() ?: return
    val newValue = (currentValue - step).coerceAtLeast(min)
    onValueChange(formatValue(newValue, decimalPlaces))
}

private fun performIncrement(
    value: String,
    max: Float,
    step: Float,
    decimalPlaces: Int,
    onValueChange: (String) -> Unit
) {
    val currentValue = value.toFloatOrNull() ?: return
    val newValue = (currentValue + step).coerceAtMost(max)
    onValueChange(formatValue(newValue, decimalPlaces))
}

private fun formatValue(value: Float, decimalPlaces: Int): String {
    return if (decimalPlaces == 0) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.${decimalPlaces}f", value)
    }
}
