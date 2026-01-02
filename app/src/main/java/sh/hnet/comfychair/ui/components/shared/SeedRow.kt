package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider

/**
 * A row containing the seed stepper field with random toggle and randomize buttons.
 * Constraints are dynamically loaded from the actual mapped node in the workflow.
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param randomSeed Whether random seed mode is enabled
 * @param onRandomSeedToggle Callback when random seed toggle changes
 * @param seed Current seed value
 * @param onSeedChange Callback when seed changes
 * @param onRandomizeSeed Callback when randomize button is clicked
 * @param seedError Error message for seed field
 * @param modifier Modifier for the row
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SeedRow(
    workflowName: String,
    randomSeed: Boolean,
    onRandomSeedToggle: () -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    onRandomizeSeed: () -> Unit,
    seedError: String?,
    modifier: Modifier = Modifier
) {
    val constraints = WorkflowConstraintsProvider.rememberConstraints("seed", workflowName)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Random seed toggle
        OutlinedIconToggleButton(
            checked = randomSeed,
            onCheckedChange = { onRandomSeedToggle() },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.label_random_seed)
            )
        }
        // Seed stepper field
        NumericStepperField(
            value = seed,
            onValueChange = onSeedChange,
            label = stringResource(R.string.label_seed),
            min = constraints.min,
            max = constraints.max,
            step = constraints.step,
            decimalPlaces = constraints.decimalPlaces,
            error = seedError,
            enabled = !randomSeed,
            modifier = Modifier.weight(1f)
        )
        // Randomize button
        OutlinedIconButton(
            onClick = onRandomizeSeed,
            enabled = !randomSeed,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Casino,
                contentDescription = stringResource(R.string.label_randomize_seed)
            )
        }
    }
}
