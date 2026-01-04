package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

/**
 * A label composable with an optional Info icon for tooltips.
 * The entire label row is clickable when a tooltip is present.
 *
 * @param text The label text
 * @param tooltip Optional tooltip text - if present, shows Info icon and makes label clickable
 * @param expanded Whether the tooltip is currently expanded (controls icon color)
 * @param onToggle Callback when the label/icon is tapped to toggle tooltip
 * @param modifier Modifier for the row
 */
@Composable
fun TooltipLabel(
    text: String,
    tooltip: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasTooltip = tooltip != null
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .then(
                if (hasTooltip) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onToggle
                    )
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (hasTooltip) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.content_description_show_help),
                modifier = Modifier.size(16.dp),
                tint = if (expanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
