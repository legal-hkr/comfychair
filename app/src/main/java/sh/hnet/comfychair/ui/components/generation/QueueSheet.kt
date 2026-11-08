package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.settings.SettingsActionRow
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.QueueSnapshot
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.viewmodel.GenerationViewModel

/** Whether the queue sheet is open. One screen is shown at a time, and it hosts the sheet. */
object QueueSheetController {
    var visible by mutableStateOf(false)
}

private val RowShape = RoundedCornerShape(16.dp)

/** Screen name and icon of a job's owner. */
private class OwnerInfo(val nameRes: Int, val iconRes: Int)

private fun ownerInfo(ownerId: String?): OwnerInfo? = when (ownerId) {
    "TEXT_TO_IMAGE" -> OwnerInfo(R.string.nav_text_to_image, R.drawable.text_to_image_24px)
    "IMAGE_TO_IMAGE" -> OwnerInfo(R.string.nav_image_to_image, R.drawable.image_to_image_24px)
    "TEXT_TO_VIDEO" -> OwnerInfo(R.string.nav_text_to_video, R.drawable.text_to_video_24px)
    "IMAGE_TO_VIDEO" -> OwnerInfo(R.string.nav_image_to_video, R.drawable.image_to_video_24px)
    "VIDEO_TO_VIDEO" -> OwnerInfo(R.string.nav_video_to_video, R.drawable.video_to_video_24px)
    else -> null
}

/**
 * The server queue: your running job (live frame, progress, cancel), another client's running job (no contents), your
 * waiting jobs (move to front, remove) and a single count of other clients' waiting jobs, whose contents are never
 * shown. Clearing the whole queue asks first and says how many other clients' jobs it removes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueSheet(generationViewModel: GenerationViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val queueState by JobRegistry.queueState.collectAsState()
    val generationState by generationViewModel.generationState.collectAsState()
    val livePreview by generationViewModel.livePreview.collectAsState()
    var snapshot by remember { mutableStateOf<QueueSnapshot?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() = generationViewModel.fetchQueueSnapshot { snapshot = it }
    fun reportFailure(success: Boolean) {
        if (!success) AppErrors.report(context, R.string.error_queue_action)
        refresh()
    }
    // Read again whenever the queue changes while the sheet is open
    LaunchedEffect(queueState.totalQueueSize, queueState.executingPromptId, queueState.ownJobs.keys) { refresh() }

    val own = queueState.ownJobs
    val current = snapshot
    val runningOwn = current?.running?.filter { it in own }.orEmpty()
    val runningOther = current?.running?.filterNot { it in own }.orEmpty()
    val waitingOwn = current?.pending?.filter { it in own }.orEmpty()
    val waitingOther = current?.pending?.filterNot { it in own }.orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            item {
                Text(
                    stringResource(R.string.title_queue),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (current != null && current.running.isEmpty() && current.pending.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.msg_queue_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            if (runningOwn.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.label_queue_running_yours)) }
                items(runningOwn, key = { "run-$it" }) { promptId ->
                    val job = own[promptId]
                    val owner = ownerInfo(job?.ownerId)
                    val description = JobRegistry.description(promptId)
                    val isCurrent = generationState.promptId == promptId
                    val step = if (isCurrent) generationState.progress else 0
                    val steps = if (isCurrent) generationState.maxProgress else 0
                    QueueRow(
                        leading = {
                            val frame = livePreview
                            if (frame != null) {
                                Image(
                                    frame.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                                )
                            } else OwnerIcon(owner)
                        },
                        headline = jobTitle(owner, description),
                        supporting = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (step > 0 && steps > 0) {
                                    LinearWavyProgressIndicator(progress = { step.toFloat() / steps }, modifier = Modifier.fillMaxWidth())
                                    Text(stringResource(R.string.label_step_of, step, steps), style = MaterialTheme.typography.labelMedium)
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text(stringResource(R.string.label_preparing), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        },
                        trailing = {
                            TooltipIconButton(Icons.Rounded.Close, stringResource(R.string.content_description_cancel_job), {
                                generationViewModel.cancelGeneration(promptId) { reportFailure(it) }
                            })
                        }
                    )
                }
            }

            // Other clients' jobs: each with its prompt, as the server shares it with every client; no actions (their
            // work), and no progress (the server sends it only to the client that queued the job)
            if (runningOther.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.label_queue_running_other)) }
                items(runningOther, key = { "run-other-$it" }) { promptId ->
                    QueueRow(
                        leading = { OtherClientIcon() },
                        headline = otherJobTitle(current?.prompts?.get(promptId)),
                        supporting = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
                    )
                }
            }

            if (waitingOwn.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.label_queue_waiting_yours, waitingOwn.size)) }
                items(waitingOwn, key = { "wait-$it" }) { promptId ->
                    val owner = ownerInfo(own[promptId]?.ownerId)
                    val title = jobTitle(owner, JobRegistry.description(promptId))
                    val position = current?.positionOf(promptId) ?: 0
                    QueueRow(
                        leading = { OwnerIcon(owner) },
                        headline = title,
                        supporting = {
                            Text(
                                stringResource(R.string.label_queue_position, owner?.let { stringResource(it.nameRes) }.orEmpty(), position),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailing = {
                            // The first waiting job can't move further forward
                            if (position > 1) {
                                TooltipIconButton(
                                    Icons.Rounded.VerticalAlignTop,
                                    stringResource(R.string.content_description_move_job_to_front, title),
                                    { generationViewModel.moveToFront(promptId) { reportFailure(it) } }
                                )
                            }
                            TooltipIconButton(
                                Icons.Rounded.Close,
                                stringResource(R.string.content_description_remove_job, title),
                                { generationViewModel.removeFromQueue(promptId) { reportFailure(it) } }
                            )
                        }
                    )
                }
            }

            if (waitingOther.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.label_queue_others)) }
                items(waitingOther, key = { "wait-other-$it" }) { promptId ->
                    QueueRow(
                        leading = { OtherClientIcon() },
                        headline = otherJobTitle(current?.prompts?.get(promptId)),
                        supporting = {
                            Text(
                                stringResource(R.string.label_queue_other_position, current?.positionOf(promptId) ?: 0),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                }
            }

            if (current != null && current.pending.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    // Apart, at the end, confirmed: as Delete workflow on a workflow's page
                    SettingsGroup {
                        row { shapes ->
                            SettingsActionRow(
                                shapes = shapes,
                                icon = Icons.Rounded.ClearAll,
                                title = stringResource(R.string.button_clear_queue_confirm),
                                destructive = true,
                                onClick = { confirmClear = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        val others = waitingOther.size
        ConfirmDialog(
            title = stringResource(R.string.title_clear_queue),
            text = if (others > 0) pluralStringResource(R.plurals.msg_clear_queue_others, others, others)
                else stringResource(R.string.msg_clear_queue_own),
            confirmLabel = stringResource(R.string.button_clear),
            icon = Icons.Rounded.ClearAll,
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                generationViewModel.clearQueue { success ->
                    val messageRes = if (success) R.string.msg_queue_cleared_success else R.string.error_queue_clear
                    AppErrors.notify(context, messageRes)
                    refresh()
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
    )
}

/** The job's prompt excerpt (kept in memory only), or its screen when there is none (e.g. after a restart). */
@Composable
private fun jobTitle(owner: OwnerInfo?, description: sh.hnet.comfychair.queue.JobDescription?): String = when {
    description != null && description.prompt.isNotBlank() -> description.prompt.lineSequence().first()
    owner != null -> stringResource(owner.nameRes)
    else -> stringResource(R.string.title_queue)
}

/** Another client's job by the first line of its prompt, or "Job" when none was found in its workflow. */
@Composable
private fun otherJobTitle(prompt: String?): String =
    prompt?.lineSequence()?.firstOrNull()?.takeIf(String::isNotBlank) ?: stringResource(R.string.label_queue_running_other_job)

/** Another client's job: the group icon where our jobs have their screen's. */
@Composable
private fun OtherClientIcon() {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OwnerIcon(owner: OwnerInfo?) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (owner != null) {
            Icon(painterResource(owner.iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun QueueRow(
    leading: @Composable () -> Unit,
    headline: String,
    supporting: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RowShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(headline, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                supporting()
            }
            trailing()
        }
    }
}
