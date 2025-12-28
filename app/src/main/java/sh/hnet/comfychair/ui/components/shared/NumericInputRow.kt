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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * A generic row containing two numeric input fields.
 * Useful for Steps/CFG, Length/FPS, and similar paired inputs.
 */
@Composable
fun NumericInputRow(
    value1: String,
    label1: String,
    onValue1Change: (String) -> Unit,
    error1: String?,
    showField1: Boolean,
    value2: String,
    label2: String,
    onValue2Change: (String) -> Unit,
    error2: String?,
    showField2: Boolean,
    keyboardType1: KeyboardType = KeyboardType.Number,
    keyboardType2: KeyboardType = KeyboardType.Number,
    modifier: Modifier = Modifier
) {
    if (!showField1 && !showField2) return

    Row(modifier = modifier.fillMaxWidth()) {
        if (showField1) {
            OutlinedTextField(
                value = value1,
                onValueChange = onValue1Change,
                label = { Text(label1) },
                isError = error1 != null,
                supportingText = error1?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType1),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        if (showField1 && showField2) {
            Spacer(modifier = Modifier.width(8.dp))
        }

        if (showField2) {
            OutlinedTextField(
                value = value2,
                onValueChange = onValue2Change,
                label = { Text(label2) },
                isError = error2 != null,
                supportingText = error2?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType2),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
    }
}
