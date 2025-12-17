package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Progress bar for generation screens. Shows progress when generation is active.
 *
 * @param progress Current progress value (0 to maxProgress)
 * @param maxProgress Maximum progress value
 * @param modifier Modifier for positioning (typically with Alignment.BottomCenter)
 */
@Composable
fun GenerationProgressBar(
    progress: Int,
    maxProgress: Int,
    modifier: Modifier = Modifier
) {
    LinearProgressIndicator(
        progress = {
            if (maxProgress > 0) {
                progress.toFloat() / maxProgress
            } else {
                0f
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .height(8.dp)
    )
}
