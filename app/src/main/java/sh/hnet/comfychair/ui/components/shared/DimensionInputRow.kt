package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

/**
 * A row containing Width and Height input fields.
 * Handles conditional visibility for each field.
 */
@Composable
fun DimensionInputRow(
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

    Row(modifier = modifier.fillMaxWidth()) {
        if (showWidth) {
            OutlinedTextField(
                value = width,
                onValueChange = onWidthChange,
                label = { Text(stringResource(R.string.label_width)) },
                isError = widthError != null,
                supportingText = widthError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        if (showWidth && showHeight) {
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (showHeight) {
            OutlinedTextField(
                value = height,
                onValueChange = onHeightChange,
                label = { Text(stringResource(R.string.label_height)) },
                isError = heightError != null,
                supportingText = heightError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
    }
}
