package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Progress bar for generation screens. Shows progress when generation is active.
 * Uses Material 3 wavy progress indicator for a modern look.
 *
 * @param progress Current progress value (0 to maxProgress)
 * @param maxProgress Maximum progress value
 * @param modifier Modifier for positioning
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationProgressBar(
    progress: Int,
    maxProgress: Int,
    modifier: Modifier = Modifier
) {
    LinearWavyProgressIndicator(
        progress = {
            if (maxProgress > 0) {
                progress.toFloat() / maxProgress
            } else {
                0f
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    )
}
