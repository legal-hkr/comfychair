package sh.hnet.comfychair.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.WorkflowConstraintsProvider

/**
 * A row containing Steps and CFG stepper fields with constraints
 * dynamically loaded from the actual mapped nodes in the workflow.
 *
 * @param workflowName The name of the currently selected workflow (for constraint lookup)
 * @param steps Current steps value
 * @param onStepsChange Callback when steps changes
 * @param stepsError Error message for steps field
 * @param showSteps Whether to show the steps field
 * @param cfg Current CFG value
 * @param onCfgChange Callback when CFG changes
 * @param cfgError Error message for CFG field
 * @param showCfg Whether to show the CFG field
 * @param modifier Modifier for the row
 */
@Composable
fun StepsCfgRow(
    workflowName: String,
    steps: String,
    onStepsChange: (String) -> Unit,
    stepsError: String?,
    showSteps: Boolean,
    cfg: String,
    onCfgChange: (String) -> Unit,
    cfgError: String?,
    showCfg: Boolean,
    modifier: Modifier = Modifier
) {
    val stepsConstraints = WorkflowConstraintsProvider.rememberConstraints("steps", workflowName)
    val cfgConstraints = WorkflowConstraintsProvider.rememberConstraints("cfg", workflowName)

    StepperInputRow(
        value1 = steps,
        label1 = stringResource(R.string.label_steps),
        onValue1Change = onStepsChange,
        error1 = stepsError,
        showField1 = showSteps,
        min1 = stepsConstraints.min,
        max1 = stepsConstraints.max,
        step1 = stepsConstraints.step,
        decimalPlaces1 = stepsConstraints.decimalPlaces,
        value2 = cfg,
        label2 = stringResource(R.string.label_cfg),
        onValue2Change = onCfgChange,
        error2 = cfgError,
        showField2 = showCfg,
        min2 = cfgConstraints.min,
        max2 = cfgConstraints.max,
        step2 = cfgConstraints.step,
        decimalPlaces2 = cfgConstraints.decimalPlaces,
        modifier = modifier
    )
}
