package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.ui.components.shared.ConstrainedNumberField
import sh.hnet.comfychair.ui.components.shared.NumberParameter
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

private val PopupWidth = 280.dp

/**
 * The dock's parameter chips, after the workflow chip: one fixed set in a fixed order (seed, steps, size or
 * megapixels, length, scale), each only when the workflow has that field – the options sheet's quick fields. A chip shows the value; tapping it opens
 * a small popup above it with the same control as the options sheet. Values apply at once; tapping outside closes it.
 */
@Composable
fun ParameterChips(state: CommonGenerationState, viewModel: BaseGenerationViewModel<*>) {
    val caps = state.capabilities
    val workflowName = state.selectedWorkflow

    if (caps.hasSeed) SeedChip(state, viewModel)
    if (caps.hasSteps) {
        val steps = state.steps.toIntOrNull()
        ParameterChip(
            title = stringResource(R.string.label_steps),
            label = steps?.let { pluralStringResource(R.plurals.label_steps_count, it, it) } ?: state.steps,
            isError = state.stepsError != null
        ) {
            ConstrainedNumberField(NumberParameter.STEPS, workflowName, state.steps, viewModel::onStepsChange, state.stepsError)
        }
    }
    if (caps.hasWidth && caps.hasHeight) {
        ParameterChip(
            title = stringResource(R.string.label_size),
            label = "${state.width}×${state.height}",
            isError = state.widthError != null || state.heightError != null
        ) {
            // One under the other: side by side, a menu-wide popup leaves too little room for four digits
            ConstrainedNumberField(NumberParameter.WIDTH, workflowName, state.width, viewModel::onWidthChange, state.widthError)
            ConstrainedNumberField(NumberParameter.HEIGHT, workflowName, state.height, viewModel::onHeightChange, state.heightError)
        }
    } else if (caps.hasMegapixels) {
        ParameterChip(
            title = stringResource(R.string.label_megapixels),
            label = stringResource(R.string.label_megapixels_value, state.megapixels),
            isError = state.megapixelsError != null
        ) {
            ConstrainedNumberField(
                NumberParameter.MEGAPIXELS, workflowName, state.megapixels, viewModel::onMegapixelsChange, state.megapixelsError
            )
        }
    }
    if (caps.hasLength) {
        val length = state.length.toIntOrNull()
        ParameterChip(
            title = stringResource(R.string.label_length),
            label = length?.let { pluralStringResource(R.plurals.label_frames_count, it, it) } ?: state.length,
            isError = state.lengthError != null
        ) {
            ConstrainedNumberField(NumberParameter.LENGTH, workflowName, state.length, viewModel::onLengthChange, state.lengthError)
        }
    }
    if (caps.hasScaleBy) {
        ParameterChip(
            title = stringResource(R.string.label_scale_by),
            label = stringResource(R.string.label_scale_value, state.scaleBy),
            isError = state.scaleByError != null
        ) {
            ConstrainedNumberField(NumberParameter.SCALE_BY, workflowName, state.scaleBy, viewModel::onScaleByChange, state.scaleByError)
        }
    }
}

/** Seed: a die when random, a lock and the number when fixed; the popup switches between them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeedChip(state: CommonGenerationState, viewModel: BaseGenerationViewModel<*>) {
    val lastSeed by viewModel.lastSeed.collectAsState()
    ParameterChip(
        title = stringResource(R.string.label_seed),
        label = if (state.randomSeed) stringResource(R.string.option_seed_random) else state.seed,
        icon = if (state.randomSeed) Icons.Rounded.Casino else Icons.Rounded.Lock,
        isError = !state.randomSeed && state.seedError != null
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = state.randomSeed,
                onCheckedChange = { if (it && !state.randomSeed) viewModel.onRandomSeedToggle() },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.option_seed_random)) }
            ToggleButton(
                checked = !state.randomSeed,
                onCheckedChange = { if (it && state.randomSeed) viewModel.onRandomSeedToggle() },
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.option_seed_fixed)) }
        }
        // A plain field across the popup: a seed has up to 19 digits, and stepping it by one means nothing
        OutlinedTextField(
            value = state.seed,
            onValueChange = viewModel::onSeedChange,
            label = { Text(stringResource(R.string.label_seed)) },
            enabled = !state.randomSeed,
            singleLine = true,
            isError = state.seedError != null,
            supportingText = state.seedError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            trailingIcon = {
                TooltipIconButton(
                    Icons.Rounded.Casino,
                    stringResource(R.string.label_randomize_seed),
                    viewModel::onRandomizeSeed,
                    enabled = !state.randomSeed
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        // The seed the last job of this screen actually got, also when it was random
        TextButton(onClick = viewModel::onReuseLastSeed, enabled = lastSeed != null) {
            Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.button_reuse_last_seed),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * A chip with a value that opens a small popup above it; the chip looks selected while the popup is open.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ParameterChip(
    title: String,
    label: String,
    isError: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = open,
            onClick = { open = true },
            label = {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) } },
            modifier = Modifier.semantics { contentDescription = "$title, $label" }
        )
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                Column(
                    modifier = Modifier
                        .width(PopupWidth)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                    content()
                }
            }
        }
    }
}
