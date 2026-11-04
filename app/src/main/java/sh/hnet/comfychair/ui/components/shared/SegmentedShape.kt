package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shape of one item in a segmented group (rows 2 dp apart, as one block): the block's outer corners are round, the
 * corners between items small.
 */
fun segmentedItemShape(index: Int, count: Int): Shape {
    val outer = 20.dp
    val inner = 6.dp
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner, topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner, bottomEnd = if (last) outer else inner
    )
}
