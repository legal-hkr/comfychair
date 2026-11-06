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
import java.math.BigDecimal
import java.math.RoundingMode
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.StepperMath

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
 * @param hint Hint text to display below the field (shown when no error)
 * @param tooltip Help text that can be expanded by tapping the label
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
    hint: String? = null,
    tooltip: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    // From a value that isn't allowed (off the step grid or out of range), − and + go to the nearest allowed value
    // below or above it, not one step from it
    val currentValue = value.trim().toBigDecimalOrNull()
    // The bounds as written (0.01, not the nearest binary float to it)
    val minValue = min.toString().toBigDecimal()
    val maxValue = max.toString().toBigDecimal()
    val stepValue = step.toString().toBigDecimal()
    val decremented = currentValue?.let { StepperMath.decrement(it, minValue, maxValue, stepValue) }
    val incremented = currentValue?.let { StepperMath.increment(it, minValue, maxValue, stepValue) }
    val canDecrement = enabled && decremented != null
    val canIncrement = enabled && incremented != null
    var tooltipExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = {
                TooltipLabel(
                    text = label,
                    tooltip = tooltip,
                    expanded = tooltipExpanded,
                    onToggle = { tooltipExpanded = !tooltipExpanded }
                )
            },
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimalPlaces > 0) KeyboardType.Decimal else KeyboardType.Number
            ),
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = {
                IconButton(
                    onClick = { decremented?.let { onValueChange(formatValue(it, decimalPlaces)) } },
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
                    onClick = { incremented?.let { onValueChange(formatValue(it, decimalPlaces)) } },
                    enabled = canIncrement
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.content_description_increase)
                    )
                }
            }
        )

        // Hint row (just the hint text, no tooltip icon - that's now in the label)
        if (error == null && hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }

        // Expandable tooltip
        ExpandableTooltip(
            tooltip = tooltip,
            expanded = tooltipExpanded
        )
    }
}

private fun formatValue(value: BigDecimal, decimalPlaces: Int): String =
    value.setScale(decimalPlaces, RoundingMode.HALF_UP).toPlainString()
