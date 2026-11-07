package sh.hnet.comfychair.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider
import java.util.Locale

/** A numeric generation parameter: the input name its range is read from, and its label. */
enum class NumberParameter(val inputName: String, val labelRes: Int) {
    WIDTH("width", R.string.label_width),
    HEIGHT("height", R.string.label_height),
    MEGAPIXELS("megapixels", R.string.label_megapixels),
    LENGTH("length", R.string.label_length),
    FPS("fps", R.string.label_fps),
    STEPS("steps", R.string.label_steps),
    CFG("cfg", R.string.label_cfg),
    DENOISE("denoise", R.string.label_denoise),
    BATCH_SIZE("batch_size", R.string.label_batch_size),
    SCALE_BY("scale_by", R.string.label_scale_by),
    CLIP_LAYER("stop_at_clip_layer", R.string.label_stop_at_clip_layer)
}

/**
 * Stepper for a numeric parameter, with the range and step of the node input it maps to in the current workflow and
 * the range shown under it. Used in the options sheet and in the recipe chips' popups.
 */
@Composable
fun ConstrainedNumberField(
    parameter: NumberParameter,
    workflowName: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
    // The workflow's label for the field (spec §18.4), else the parameter's name
    label: String? = null
) {
    val constraints = WorkflowConstraintsProvider.rememberConstraints(parameter.inputName, workflowName)
    val hint = stringResource(
        R.string.node_editor_range_min_max,
        formatRangeValue(constraints.min, constraints.decimalPlaces),
        formatRangeValue(constraints.max, constraints.decimalPlaces)
    )
    NumericStepperField(
        value = value,
        onValueChange = onValueChange,
        label = label ?: stringResource(parameter.labelRes),
        min = constraints.min,
        max = constraints.max,
        step = constraints.step,
        decimalPlaces = constraints.decimalPlaces,
        error = error,
        hint = hint,
        modifier = modifier
    )
}

/** A range bound as shown: integers without decimals, others with the input's decimal places. */
private fun formatRangeValue(value: Float, decimalPlaces: Int): String =
    if (decimalPlaces == 0) value.toInt().toString() else String.format(Locale.US, "%.${decimalPlaces}f", value)
