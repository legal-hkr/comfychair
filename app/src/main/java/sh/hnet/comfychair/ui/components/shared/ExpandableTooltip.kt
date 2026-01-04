package sh.hnet.comfychair.ui.components.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Animated expandable tooltip text, typically shown below a field.
 *
 * @param tooltip The tooltip text to display
 * @param expanded Whether the tooltip is currently visible
 * @param modifier Modifier for the text
 */
@Composable
fun ExpandableTooltip(
    tooltip: String?,
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = expanded && tooltip != null,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Text(
            text = tooltip ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)
        )
    }
}
