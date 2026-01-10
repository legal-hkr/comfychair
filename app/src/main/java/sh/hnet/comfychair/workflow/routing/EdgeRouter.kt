package sh.hnet.comfychair.workflow.routing

import androidx.compose.ui.geometry.Offset

/**
 * Interface for edge routing algorithms in the workflow graph editor.
 * Implementations compute paths between connection points (output socket to input socket).
 */
interface EdgeRouter {
    /**
     * Unique identifier for this router (used in settings persistence).
     */
    val id: String

    /**
     * Human-readable name for UI display.
     */
    val displayName: String

    /**
     * Compute a route between two points.
     *
     * @param start Output socket position (right side of source node)
     * @param end Input socket position (left side of target node)
     * @return RoutedPath containing the path and metadata
     */
    fun computeRoute(start: Offset, end: Offset): RoutedPath
}
