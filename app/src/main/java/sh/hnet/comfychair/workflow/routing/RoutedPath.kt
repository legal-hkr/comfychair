package sh.hnet.comfychair.workflow.routing

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Path

/**
 * Result of a routing computation.
 * Contains the Path for drawing plus metadata for potential future use.
 */
@Immutable
data class RoutedPath(
    /**
     * The computed path ready for drawing.
     */
    val path: Path,

    /**
     * Approximate total arc length of the path.
     * Reserved for future use (e.g., custom segment animation).
     */
    val totalLength: Float = 0f
)
