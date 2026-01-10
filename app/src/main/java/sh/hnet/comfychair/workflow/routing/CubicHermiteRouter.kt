package sh.hnet.comfychair.workflow.routing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.max

/**
 * Cubic Hermite spline router for smooth edge connections.
 *
 * Uses a 3-segment approach with waypoints to ensure horizontal exit/entry:
 * - P1: Start (output socket)
 * - P2: Waypoint to the right of start (same Y as start)
 * - P3: Waypoint to the left of end (same Y as end)
 * - P4: End (input socket)
 *
 * Each segment uses Hermite splines with explicit horizontal tangents,
 * ensuring the curve goes straight through waypoints before bending.
 *
 * Hermite to Bezier conversion: C1 = P0 + M0/3, C2 = P1 - M1/3
 */
class CubicHermiteRouter : EdgeRouter {

    override val id = "hermite"
    override val displayName = "Hermite spline"

    companion object {
        private const val WAYPOINT_OFFSET = 5f  // Distance from socket to waypoint
        private const val MIN_TANGENT = 60f
    }

    override fun computeRoute(start: Offset, end: Offset): RoutedPath {
        // Waypoints at same height as their respective sockets
        val p1 = start
        val p2 = Offset(start.x + WAYPOINT_OFFSET, start.y)  // Right of output
        val p3 = Offset(end.x - WAYPOINT_OFFSET, end.y)      // Left of input
        val p4 = end

        // Calculate tangent magnitudes based on distances
        val seg2Dist = distance(p2, p3)

        // Middle segment tangent - the main curve
        val magMiddle = max(seg2Dist * 0.8f, MIN_TANGENT * 2)

        // Use same magnitude at waypoints for smooth transitions (C1 continuity)
        // Socket ends use smaller magnitude for tighter connection
        val magSocket = MIN_TANGENT

        val path = Path().apply {
            moveTo(p1.x, p1.y)

            // Segment 1: P1 → P2 (small at socket, matches middle at waypoint)
            val (c1a, c2a) = hermiteToBezier(p1, p2, Offset(magSocket, 0f), Offset(magMiddle, 0f))
            cubicTo(c1a.x, c1a.y, c2a.x, c2a.y, p2.x, p2.y)

            // Segment 2: P2 → P3 (main curve with matching tangents)
            val (c1b, c2b) = hermiteToBezier(p2, p3, Offset(magMiddle, 0f), Offset(magMiddle, 0f))
            cubicTo(c1b.x, c1b.y, c2b.x, c2b.y, p3.x, p3.y)

            // Segment 3: P3 → P4 (matches middle at waypoint, small at socket)
            val (c1c, c2c) = hermiteToBezier(p3, p4, Offset(magMiddle, 0f), Offset(magSocket, 0f))
            cubicTo(c1c.x, c1c.y, c2c.x, c2c.y, p4.x, p4.y)
        }

        return RoutedPath(path)
    }

    /**
     * Convert Hermite spline to Bezier control points.
     *
     * For Hermite with endpoints P0, P1 and tangents M0, M1:
     * - C1 = P0 + M0/3
     * - C2 = P1 - M1/3
     */
    private fun hermiteToBezier(
        p0: Offset,
        p1: Offset,
        m0: Offset,
        m1: Offset
    ): Pair<Offset, Offset> {
        val c1 = Offset(p0.x + m0.x / 3f, p0.y + m0.y / 3f)
        val c2 = Offset(p1.x - m1.x / 3f, p1.y - m1.y / 3f)
        return Pair(c1, c2)
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
