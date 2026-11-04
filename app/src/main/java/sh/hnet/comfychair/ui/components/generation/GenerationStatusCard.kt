package sh.hnet.comfychair.ui.components.generation

import sh.hnet.comfychair.queue.JobRegistry
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.formatPlaybackTime
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton

/**
 * Status of this screen's running job, floating near the bottom of the preview over the live frames: the step (or
 * "Preparing…" until the sampler reports one) with the elapsed time, a wavy progress bar (flat and indeterminate
 * while preparing), how many more of your jobs wait, and Cancel for this job only. Replaces the progress bar under
 * the top bar. TalkBack hears the progress at every quarter, not at every step.
 *
 * @param jobKey The running job's id; the elapsed time is counted from its start
 * @param step Current sampler step, 0 before the first one
 * @param steps Number of sampler steps
 * @param moreQueued Your other jobs waiting in the queue
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.GenerationStatusCard(
    jobKey: Any?,
    step: Int,
    steps: Int,
    moreQueued: Int,
    onCancel: () -> Unit
) {
    // Kept by JobRegistry, so leaving and returning to the screen doesn't restart the timer
    val startedAt = remember(jobKey) { (jobKey as? String)?.let { JobRegistry.executionStartedAt(it) } ?: SystemClock.elapsedRealtime() }
    val elapsedMs by produceState(0L, startedAt) {
        while (true) {
            value = SystemClock.elapsedRealtime() - startedAt
            delay(1000)
        }
    }
    val hasSteps = step > 0 && steps > 0
    val fraction = if (hasSteps) (step.toFloat() / steps).coerceIn(0f, 1f) else 0f
    val announcedPercent = (fraction * 4).toInt() * 25
    val progressDescription = stringResource(R.string.content_description_generating_progress, announcedPercent)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp)
            .widthIn(max = 312.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    // One polite announcement per quarter instead of every step
                    .clearAndSetSemantics {
                        contentDescription = progressDescription
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (hasSteps) stringResource(R.string.label_step_of, step, steps) else stringResource(R.string.label_preparing),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatPlaybackTime(elapsedMs),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasSteps) {
                    LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (moreQueued > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.label_more_queued, moreQueued, moreQueued),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TooltipIconButton(Icons.Rounded.Close, stringResource(R.string.content_description_cancel_job), onCancel)
        }
    }
}
