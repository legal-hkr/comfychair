package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R

/**
 * Generate/Cancel button for generation screens.
 *
 * Shows "Generate" in primary color when ready to generate,
 * or "Cancel" in error color when generation is in progress.
 *
 * @param isGenerating Whether this screen is currently generating
 * @param isEnabled Whether the button should be enabled
 * @param onGenerate Callback when Generate is clicked
 * @param onCancel Callback when Cancel is clicked
 * @param modifier Modifier for the button
 */
@Composable
fun GenerationButton(
    isGenerating: Boolean,
    isEnabled: Boolean,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedButton(
        onClick = {
            if (isGenerating) {
                onCancel()
            } else {
                onGenerate()
            }
        },
        modifier = modifier.height(56.dp),
        enabled = isEnabled,
        colors = if (isGenerating) {
            ButtonDefaults.elevatedButtonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) {
        Text(
            text = if (isGenerating) {
                stringResource(R.string.button_cancel_generation)
            } else {
                stringResource(R.string.button_generate)
            },
            fontSize = 18.sp
        )
    }
}
