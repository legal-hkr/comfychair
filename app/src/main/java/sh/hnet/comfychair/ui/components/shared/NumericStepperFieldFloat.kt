package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 */
@Composable
fun NumericStepperFieldFloat(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    min: Float,
    max: Float,
    step: Float,
    decimalPlaces: Int = 0,
    modifier: Modifier = Modifier
) {
    var currentValue by remember { mutableStateOf(value.toString()) };
    NumericStepperField(
        value = currentValue,
        onValueChange = { newText ->
            currentValue = newText;
            newText.toFloatOrNull()?.let { rawFloat ->
                if (rawFloat > max) {
                    onValueChange(max);
                    currentValue = max.toString();
                    return@let;
                } else if (rawFloat < min) {
                    onValueChange(min);
                    currentValue = min.toString();
                    return@let;
                }
                onValueChange(rawFloat);
            }
        },
        label = label,
        min = min,
        max = max,
        step = step,
        decimalPlaces = decimalPlaces,
        modifier = modifier
    )
}
