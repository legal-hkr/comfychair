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
     * @param backAtStart Whether the curve may swing back behind [start] (hidden under a node's body); not from a
     * subgraph level's narrow Inputs strip, whose sockets are on its centre line
     * @param backAtEnd The same behind [end] (not into the Outputs strip)
     * @return RoutedPath containing the path and metadata
     */
    fun computeRoute(start: Offset, end: Offset, backAtStart: Boolean = true, backAtEnd: Boolean = true): RoutedPath
}
