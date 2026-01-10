package sh.hnet.comfychair.workflow.routing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cubic Bezier router with waypoint-based approach for smooth edge connections.
 *
 * Uses a 3-segment approach with waypoints to ensure horizontal exit/entry:
 * - P1: Start (output socket)
 * - P2: Waypoint to the right of start (same Y as start)
 * - P3: Waypoint to the left of end (same Y as end)
 * - P4: End (input socket)
 *
 * Each segment uses cubic Bezier curves with control points placed
 * to ensure smooth horizontal tangents at sockets and waypoints.
 */
class CubicBezierRouter : EdgeRouter {

    override val id = "bezier"
    override val displayName = "Bezier curve"

    companion object {
        private const val WAYPOINT_OFFSET = 5f  // Distance from socket to waypoint
        private const val MIN_CONTROL = 60f
    }

    override fun computeRoute(start: Offset, end: Offset): RoutedPath {
        // Waypoints at same height as their respective sockets
        val p1 = start
        val p2 = Offset(start.x + WAYPOINT_OFFSET, start.y)  // Right of output
        val p3 = Offset(end.x - WAYPOINT_OFFSET, end.y)      // Left of input
        val p4 = end

        // Calculate control point offsets based on distances
        val seg2Dist = distance(p2, p3)

        // Middle segment control offset - larger for curvier middle
        val controlMiddle = max(seg2Dist * 0.4f, MIN_CONTROL)

        // Socket segments use smaller control offset
        val controlSocket = MIN_CONTROL * 0.5f

        val path = Path().apply {
            moveTo(p1.x, p1.y)

            // Segment 1: P1 → P2 (horizontal exit)
            // Control points ensure horizontal tangent at both ends
            // Cap c2a so it doesn't go left of p1
            val c1a = Offset(p1.x + controlSocket, p1.y)
            val c2aX = maxOf(p1.x, p2.x - controlMiddle)
            val c2a = Offset(c2aX, p2.y)
            cubicTo(c1a.x, c1a.y, c2a.x, c2a.y, p2.x, p2.y)

            // Segment 2: P2 → P3 (main curve)
            // Control points placed horizontally for smooth curve
            val c1b = Offset(p2.x + controlMiddle, p2.y)
            val c2b = Offset(p3.x - controlMiddle, p3.y)
            cubicTo(c1b.x, c1b.y, c2b.x, c2b.y, p3.x, p3.y)

            // Segment 3: P3 → P4 (horizontal entry)
            // Cap c1c so it doesn't go right of p4
            val c1cX = minOf(p4.x, p3.x + controlMiddle)
            val c1c = Offset(c1cX, p3.y)
            val c2c = Offset(p4.x - controlSocket, p4.y)
            cubicTo(c1c.x, c1c.y, c2c.x, c2c.y, p4.x, p4.y)
        }

        return RoutedPath(path)
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
