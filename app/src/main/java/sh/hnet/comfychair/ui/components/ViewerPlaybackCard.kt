package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRightAlt
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.hnet.comfychair.R

/**
 * The media viewer's playback card for a video page (spec §14.3): a seek slider over the full width, then the time,
 * previous frame / play-pause / next frame centred, and the sound and loop toggles. It takes every touch inside it, so
 * seeking never swipes the page, closes the viewer or hides the overlays.
 *
 * @param onInteraction Any use of a control (restarts the overlays' auto-hide)
 * @param onDraggingChange The slider is held (no auto-hide meanwhile)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerPlaybackCard(
    onInteraction: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by SharedVideoPlayer.state.collectAsState()
    var positionMs by remember { mutableLongStateOf(SharedVideoPlayer.positionMs()) }
    // The position while the slider is dragged, shown instead of the player's until it's let go
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    // Whether the video played when a drag of the slider began: it pauses while scrubbing and plays on after
    var playedBeforeDrag by remember { mutableStateOf(false) }
    // A gesture that began on the slider belongs to the slider; the card takes every other touch (read in the card's
    // handler, set by the slider's before it: not state, nothing to draw)
    val gestureOnSlider = remember { BooleanArray(1) }
    // A step or seek changes the position while paused: read it again right after
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.isPlaying, state.durationMs, state.ended, refresh) {
        do {
            positionMs = SharedVideoPlayer.positionMs()
            if (state.isPlaying) delay(100)
        } while (state.isPlaying)
    }

    // Loading is the first load of the video (its length still unknown): seeking buffers briefly, and must neither
    // disable the slider under the finger nor turn play/pause into a spinner
    val loading = state.durationMs == null
    val durationMs = state.durationMs
    val shownMs = dragPosition?.toLong() ?: positionMs
    val elapsed = formatPlaybackTime(shownMs)
    val total = durationMs?.let { formatPlaybackTime(it, roundUp = true) } ?: UNKNOWN_DURATION
    val frameMs = 1000f / (state.frameRate ?: 24f)
    val atStart = shownMs <= 0L
    val atEnd = state.ended || (durationMs != null && shownMs >= durationMs - frameMs)

    fun act(action: () -> Unit) {
        onInteraction()
        action()
        refresh++
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = FloatingToolbarDefaults.ContainerExpandedElevation,
        modifier = modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            // Every touch inside the card stays here: the pager, swipe-to-close and the overlay tap never see it. Except a
            // drag of the slider: a slider gives up a drag that someone else consumed before it crossed the touch slop,
            // so its gestures are left to it (once dragging, it consumes them itself, and the pager doesn't move)
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (!gestureOnSlider[0]) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    gestureOnSlider[0] = false
                }
            }
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            // Row 1: seeking
            val positionLabel = stringResource(R.string.video_position)
            val positionState = stringResource(R.string.content_description_video_position, elapsed, total)
            val back5 = stringResource(R.string.video_back_5s)
            val forward5 = stringResource(R.string.video_forward_5s)
            val positionRange = 0f..(durationMs ?: 1L).toFloat()
            val positionSlider = remember(positionRange) { SliderState(trackRange = positionRange) }
            positionSlider.value = shownMs.toFloat().coerceIn(0f, (durationMs ?: 1L).toFloat())
            Slider(
                state = positionSlider,
                // Scrubbing: the picture follows the finger (paused meanwhile), landing exactly on its frame at the end
                onValueChange = { value ->
                    if (dragPosition == null) {
                        onDraggingChange(true)
                        playedBeforeDrag = state.isPlaying
                        if (state.isPlaying) SharedVideoPlayer.togglePlayback()
                        SharedVideoPlayer.startScrub()
                    }
                    dragPosition = value
                    SharedVideoPlayer.scrubTo(value.toLong())
                },
                onValueChangeFinished = {
                    dragPosition?.let { SharedVideoPlayer.endScrub(it.toLong()) }
                    dragPosition = null
                    if (playedBeforeDrag) SharedVideoPlayer.togglePlayback()
                    playedBeforeDrag = false
                    onDraggingChange(false)
                    act {}
                },
                enabled = !loading && durationMs != null,
                track = { sliderState ->
                    SliderDefaults.Track(sliderState = sliderState, modifier = Modifier.height(16.dp), drawStopIndicator = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    // Marks a gesture that begins here as the slider's, before the card's handler sees it (spec §14.3.3)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            gestureOnSlider[0] = true
                        }
                    }
                    .semantics {
                        contentDescription = positionLabel
                        stateDescription = positionState
                        if (durationMs != null) {
                            customActions = listOf(
                                CustomAccessibilityAction(back5) {
                                    act { SharedVideoPlayer.seekTo((positionMs - 5000).coerceAtLeast(0L)) }; true
                                },
                                CustomAccessibilityAction(forward5) {
                                    act { SharedVideoPlayer.seekTo((positionMs + 5000).coerceAtMost(durationMs)) }; true
                                }
                            )
                        }
                    }
            )

            // Row 2: time | frame back, play/pause, frame forward | sound, loop – play/pause always centred
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp)
            ) {
                Box(Modifier.weight(1f).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = buildAnnotatedString {
                            append(elapsed)
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" / $total") }
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        maxLines = 2,
                        // The slider announces the position
                        modifier = Modifier.semantics { contentDescription = "" }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FrameStepButton(
                        icon = Icons.Rounded.ChevronLeft,
                        label = stringResource(R.string.video_previous_frame),
                        enabled = !loading && !atStart,
                        onStep = { act { SharedVideoPlayer.stepFrame(-1) } }
                    )
                    val playLabel = stringResource(
                        if (state.isPlaying) R.string.content_description_pause_video else R.string.content_description_play_video
                    )
                    FilledIconButton(
                        onClick = { act { SharedVideoPlayer.togglePlayback() } },
                        enabled = !loading,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (loading) {
                            LoadingIndicator(Modifier.size(24.dp))
                        } else {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = playLabel
                            )
                        }
                    }
                    FrameStepButton(
                        icon = Icons.Rounded.ChevronRight,
                        label = stringResource(R.string.video_next_frame),
                        enabled = !loading && !atEnd,
                        onStep = { act { SharedVideoPlayer.stepFrame(1) } }
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    val toggleColors = IconButtonDefaults.iconToggleButtonColors(
                        checkedContentColor = MaterialTheme.colorScheme.onSurface
                    )
                    val noSound = state.hasAudio == false
                    val soundLabel = stringResource(if (noSound) R.string.video_no_sound else R.string.video_sound)
                    val soundState = stringResource(if (state.muted) R.string.video_sound_off else R.string.video_sound_on)
                    IconToggleButton(
                        checked = !state.muted && !noSound,
                        onCheckedChange = { soundOn -> act { SharedVideoPlayer.setMuted(!soundOn) } },
                        enabled = state.hasAudio == true,
                        colors = toggleColors,
                        modifier = Modifier.semantics {
                            contentDescription = soundLabel
                            if (!noSound) stateDescription = soundState
                        }
                    ) {
                        Icon(
                            imageVector = if (state.muted || noSound) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = null
                        )
                    }
                    val loopLabel = stringResource(R.string.video_loop)
                    val loopState = stringResource(if (state.loop) R.string.video_loop_on else R.string.video_loop_off)
                    IconToggleButton(
                        checked = state.loop,
                        onCheckedChange = { loop -> act { SharedVideoPlayer.setViewerLoop(context, loop) } },
                        colors = toggleColors,
                        modifier = Modifier.semantics {
                            contentDescription = loopLabel
                            stateDescription = loopState
                        }
                    ) {
                        Icon(
                            // A straight arrow when it plays once: repeat_on's filled box would read as a container
                            imageVector = if (state.loop) Icons.Rounded.Repeat else Icons.AutoMirrored.Rounded.ArrowRightAlt,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

/**
 * A frame step (chevrons: a single small step; skip_previous/next would mean another recording, fast_rewind/forward
 * a fast seek): one step per tap (with a haptic tick); held, it repeats after 400 ms every 80 ms until let go.
 * TalkBack's activation, which doesn't press, steps once through the click.
 */
@Composable
private fun FrameStepButton(icon: ImageVector, label: String, enabled: Boolean, onStep: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Whether the press already stepped, so its click doesn't step again
    var steppedByPress by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        steppedByPress = true
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onStep()
        delay(400)
        while (true) {
            onStep()
            delay(80)
        }
    }
    IconButton(
        onClick = {
            if (!steppedByPress) onStep()
            steppedByPress = false
        },
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        Icon(icon, contentDescription = label)
    }
}
