package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Progress bar for generation screens. Shows progress when generation is active.
 * Uses Material 3 wavy progress indicator for a modern look.
 *
 * @param progress Current progress value (0 to maxProgress)
 * @param maxProgress Maximum progress value
 * @param modifier Modifier for positioning (typically with Alignment.BottomCenter)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationProgressBar(
    progress: Int,
    maxProgress: Int,
    modifier: Modifier = Modifier
) {
    // Create a thicker stroke for better visibility
    val strokeWidth = with(LocalDensity.current) { 8.dp.toPx() }
    val thickStroke = remember(strokeWidth) {
        Stroke(width = strokeWidth, cap = StrokeCap.Round)
    }

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
            .padding(8.dp)
            .height(14.dp),  // Slightly taller to accommodate wave amplitude
        stroke = thickStroke,
        trackStroke = thickStroke
    )
}
