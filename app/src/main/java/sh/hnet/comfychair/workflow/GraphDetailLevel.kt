package sh.hnet.comfychair.workflow

/**
 * How much of the graph is drawn at a zoom (spec §12.6), in two levels (the owner's choice; the spec's compact level
 * is left out): full detail, and below a threshold an overview of blocks. Nodes keep their shape and scale with the
 * zoom at both levels.
 */
enum class GraphDetailLevel {
    /** Title bar, all rows, pills, sockets with labels */
    FULL,
    /** The whole node in the title tint with the title */
    OVERVIEW;

    companion object {
        // About 40 % in the editor on a phone
        private const val OVERVIEW_BELOW = 0.27f
        /** Back up only 5 % beyond a threshold, so labels don't flicker while zooming around it */
        private const val HYSTERESIS = 1.05f
        /**
         * The zoom of the spec: dp on screen per spec unit. Graph units are half the spec's dp (a row is 40 units,
         * the spec's 20 dp), and at [scale] 1 one unit is one pixel.
         */
        fun specZoom(scale: Float, density: Float): Float = scale * 2f / density

        /** The level at [zoom] (see [specZoom]), coming from [current]. */
        fun next(zoom: Float, current: GraphDetailLevel?): GraphDetailLevel {
            val threshold = if (current == OVERVIEW) OVERVIEW_BELOW * HYSTERESIS else OVERVIEW_BELOW
            return if (zoom >= threshold) FULL else OVERVIEW
        }
    }
}
