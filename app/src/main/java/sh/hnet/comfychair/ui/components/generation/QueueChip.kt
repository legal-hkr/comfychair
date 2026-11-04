package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R

private val ChipHeight = 36.dp

/** What the queue chip shows. */
sealed interface QueueChipState {
    data object Offline : QueueChipState
    data object Connecting : QueueChipState
    /** @param own Jobs of this app still waiting or running; @param others Jobs of other clients */
    data class Counts(val own: Int, val others: Int) : QueueChipState
}

/**
 * Queue status at the top end of a generation screen: an icon when the queue is empty, otherwise a dot, the count of
 * your jobs and the count of other clients' jobs (a group is hidden at 0). Offline and connecting replace the counts.
 * Visual height 36 dp, touch target 48 dp.
 *
 * @param onClick Opens the queue; null while there is nothing to open
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueChip(state: QueueChipState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val description = queueDescription(state)
    val openLabel = stringResource(R.string.content_description_open_queue)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .height(ChipHeight)
                .widthIn(min = ChipHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            when (state) {
                QueueChipState.Offline -> {
                    Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.label_offline), style = MaterialTheme.typography.labelLarge)
                }
                QueueChipState.Connecting -> {
                    LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.button_connecting), style = MaterialTheme.typography.labelLarge)
                }
                is QueueChipState.Counts -> if (state.own == 0 && state.others == 0) {
                    Icon(Icons.Rounded.Queue, contentDescription = null, modifier = Modifier.size(20.dp))
                } else {
                    // Something is queued or running
                    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    if (state.own > 0) CountGroup(Icons.Rounded.Person, state.own)
                    if (state.others > 0) CountGroup(Icons.Rounded.Group, state.others)
                }
            }
        }
    }
    val chipModifier = modifier
        .minimumInteractiveComponentSize()
        .clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
            // Cleared semantics drop the click too, so it's set again here
            if (onClick != null) onClick(label = openLabel) { onClick(); true }
        }
    val color = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    if (onClick != null) {
        Surface(onClick = onClick, shape = CircleShape, color = color, contentColor = contentColor, modifier = chipModifier) { content() }
    } else {
        Surface(shape = CircleShape, color = color, contentColor = contentColor, modifier = chipModifier) { content() }
    }
}

@Composable
private fun CountGroup(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    )
}

@Composable
private fun queueDescription(state: QueueChipState): String = when (state) {
    QueueChipState.Offline -> stringResource(R.string.label_offline)
    QueueChipState.Connecting -> stringResource(R.string.button_connecting)
    is QueueChipState.Counts -> if (state.own == 0 && state.others == 0) {
        stringResource(R.string.content_description_queue_empty)
    } else {
        val parts = listOfNotNull(
            if (state.own > 0) pluralStringResource(R.plurals.label_queue_own_jobs, state.own, state.own) else null,
            if (state.others > 0) pluralStringResource(R.plurals.label_queue_other_jobs, state.others, state.others) else null
        )
        stringResource(R.string.content_description_queue, parts.joinToString(", "))
    }
}
