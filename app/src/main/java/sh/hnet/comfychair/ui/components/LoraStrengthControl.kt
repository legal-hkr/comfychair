package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.LoraStrengthMath

/**
 * Strength control of a LoRA chain entry: "Strength" label and a stepper (−10 | value | +10)
 * on the first row, a fine slider over the current 10-wide window on the second.
 *
 * The ±10 buttons move both the value and the window; the slider changes the value within the
 * window in steps of 0.1; any value from −100 to 100 can be typed with 0.01 precision.
 * Negative values can be reached with −10 and the slider, so no sign toggle is needed.
 *
 * @param entryKey Stable key of the entry, for the per-entry window state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoraStrengthControl(
    entryKey: String,
    strength: Float,
    onStrengthChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val value = strength.toDouble()
    val windowSize = LoraStrengthMath.COARSE_STEP

    // Window start is UI-only state per entry; recomputed when the value lands outside it
    // (restored values, preset or workflow change)
    var windowStart by rememberSaveable(entryKey) { mutableDoubleStateOf(LoraStrengthMath.windowFor(value)) }
    LaunchedEffect(value) {
        if (value < windowStart || value > windowStart + windowSize) {
            windowStart = LoraStrengthMath.windowFor(value)
        }
    }

    var focused by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val isError = focused && LoraStrengthMath.isError(draft.text)

    fun commit() {
        // Invalid or empty input reverts: the field falls back to the current strength
        LoraStrengthMath.parse(draft.text)?.let { parsed ->
            val committed = LoraStrengthMath.clamp(parsed)
            windowStart = LoraStrengthMath.windowFor(committed)
            onStrengthChange(committed.toFloat())
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isError) {
                    stringResource(
                        R.string.error_lora_strength_range,
                        LoraStrengthMath.formatBound(LoraStrengthMath.MIN),
                        LoraStrengthMath.formatBound(LoraStrengthMath.MAX)
                    )
                } else {
                    stringResource(R.string.label_lora_strength)
                },
                style = if (isError) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )

            StrengthStepper(
                text = if (focused) draft else TextFieldValue(LoraStrengthMath.format(value)),
                isNegative = !focused && value < 0,
                isError = isError,
                canDecrease = LoraStrengthMath.canStepDown(windowStart),
                canIncrease = LoraStrengthMath.canStepUp(windowStart),
                onDecrease = {
                    val (newValue, newStart) = LoraStrengthMath.stepDown(value, windowStart)
                    windowStart = newStart
                    onStrengthChange(newValue.toFloat())
                },
                onIncrease = {
                    val (newValue, newStart) = LoraStrengthMath.stepUp(value, windowStart)
                    windowStart = newStart
                    onStrengthChange(newValue.toFloat())
                },
                onTextChange = { draft = it.copy(text = LoraStrengthMath.filterInput(it.text)) },
                onFocusChange = { isFocused ->
                    if (isFocused && !focused) {
                        // Start editing with the whole value selected
                        val text = LoraStrengthMath.draftOf(value)
                        draft = TextFieldValue(text, TextRange(0, text.length))
                    }
                    if (!isFocused && focused) commit()
                    focused = isFocused
                }
            )
        }

        Column {
            val lowerBound = LoraStrengthMath.formatBound(windowStart)
            val upperBound = LoraStrengthMath.formatBound(windowStart + windowSize)
            val sliderDescription = stringResource(R.string.content_description_lora_strength_slider, lowerBound, upperBound)
            // Negative windows fill from their end, i.e. from zero
            val anchorAtEnd = windowStart + windowSize <= 0.0

            val range = 0f..windowSize.toFloat()
            val sliderState = remember(range) { SliderState(trackRange = range) }
            sliderState.value = (value - windowStart).toFloat().coerceIn(0f, windowSize.toFloat())
            Slider(
                state = sliderState,
                onValueChange = { relative ->
                    onStrengthChange(LoraStrengthMath.fromSlider(windowStart, relative.toDouble()).toFloat())
                },
                track = { state -> ZeroAnchoredTrack(state, anchorAtEnd = anchorAtEnd, tickEvery = 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = sliderDescription
                        stateDescription = LoraStrengthMath.format(value)
                    }
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val boundStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                Text(lowerBound, style = boundStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(upperBound, style = boundStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Stepper with the look of the Steps/CFG fields (outlined, 28dp corners, centered value), with
 * "−10" / "+10" text buttons. Separate from NumericStepperField because editing needs a
 * TextFieldValue (whole value selected on focus) and commit on focus loss.
 */
@Composable
private fun StrengthStepper(
    text: TextFieldValue,
    isNegative: Boolean,
    isError: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val stepLabelStyle = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    )
    val fieldDescription = stringResource(R.string.label_lora_strength)
    val decreaseDescription = stringResource(R.string.content_description_lora_strength_decrease, STEP_LABEL_AMOUNT)
    val increaseDescription = stringResource(R.string.content_description_lora_strength_increase, STEP_LABEL_AMOUNT)

    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .width(196.dp)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .semantics { contentDescription = fieldDescription },
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(28.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            textAlign = TextAlign.Center,
            fontFeatureSettings = "tnum",
            color = if (isNegative) colors.tertiary else colors.onSurface
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        leadingIcon = {
            IconButton(
                onClick = onDecrease,
                enabled = canDecrease,
                modifier = Modifier.semantics { contentDescription = decreaseDescription }
            ) {
                Text("−$STEP_LABEL_AMOUNT", style = stepLabelStyle)
            }
        },
        trailingIcon = {
            IconButton(
                onClick = onIncrease,
                enabled = canIncrease,
                modifier = Modifier.semantics { contentDescription = increaseDescription }
            ) {
                Text("+$STEP_LABEL_AMOUNT", style = stepLabelStyle)
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedLeadingIconColor = colors.onSurfaceVariant,
            focusedLeadingIconColor = colors.onSurfaceVariant,
            errorLeadingIconColor = colors.onSurfaceVariant,
            unfocusedTrailingIconColor = colors.onSurfaceVariant,
            focusedTrailingIconColor = colors.onSurfaceVariant,
            errorTrailingIconColor = colors.onSurfaceVariant
        )
    )
}

private val STEP_LABEL_AMOUNT = LoraStrengthMath.COARSE_STEP.toInt()

/**
 * Expressive-style slider track whose active part grows from the window end nearest zero:
 * from the start for windows >= 0, from the end for windows <= 0. SliderDefaults.Track always
 * fills from the start, hence the custom drawing. Leaves a gap around the thumb and draws a
 * stop dot every [tickEvery].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZeroAnchoredTrack(
    state: SliderState,
    anchorAtEnd: Boolean,
    tickEvery: Float,
    trackHeight: Dp = 16.dp,
    thumbGap: Dp = 6.dp,
    thumbWidth: Dp = 4.dp
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.secondaryContainer
    val tickOnActiveColor = MaterialTheme.colorScheme.onPrimary
    val tickOnInactiveColor = MaterialTheme.colorScheme.onSecondaryContainer
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val range = state.trackRange
    val span = range.endInclusive - range.start
    val fraction = ((state.value - range.start) / span).coerceIn(0f, 1f)

    Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
        val height = size.height
        val radius = height / 2
        val innerRadius = 2.dp.toPx()
        val gap = thumbGap.toPx() + thumbWidth.toPx() / 2
        fun xOf(f: Float) = if (isRtl) size.width * (1 - f) else size.width * f
        val thumbX = xOf(fraction)

        val leftEnd = (thumbX - gap).coerceAtLeast(0f)
        val rightStart = (thumbX + gap).coerceAtMost(size.width)
        // The active side is the visual side containing the zero anchor
        val activeOnLeft = anchorAtEnd == isRtl
        val leftColor = if (activeOnLeft) activeColor else inactiveColor
        val rightColor = if (activeOnLeft) inactiveColor else activeColor

        if (leftEnd > 0f) {
            drawRoundRect(leftColor, Offset(0f, 0f), Size(leftEnd, height), CornerRadius(radius))
        }
        if (rightStart < size.width) {
            drawRoundRect(rightColor, Offset(rightStart, 0f), Size(size.width - rightStart, height), CornerRadius(radius))
        }
        // Squarer inner corners next to the thumb
        if (leftEnd > radius) {
            drawRoundRect(leftColor, Offset(leftEnd - radius, 0f), Size(radius, height), CornerRadius(innerRadius))
        }
        if (size.width - rightStart > radius) {
            drawRoundRect(rightColor, Offset(rightStart, 0f), Size(radius, height), CornerRadius(innerRadius))
        }

        val dotRadius = 2.dp.toPx()
        var tick = range.start + tickEvery
        while (tick < range.endInclusive - 1e-4f) {
            val x = xOf((tick - range.start) / span)
            if (x < thumbX - gap || x > thumbX + gap) {
                val onActive = if (activeOnLeft) x < thumbX else x > thumbX
                drawCircle(if (onActive) tickOnActiveColor else tickOnInactiveColor, dotRadius, Offset(x, height / 2))
            }
            tick += tickEvery
        }
    }
}
