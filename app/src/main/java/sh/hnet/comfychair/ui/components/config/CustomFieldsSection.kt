@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.CustomField
import sh.hnet.comfychair.model.FieldValueType
import sh.hnet.comfychair.ui.components.shared.ChoiceRow
import sh.hnet.comfychair.ui.components.shared.NumericStepperField
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.InputDefinition

/** A workflow's custom fields with the definition of the input each one fills first (range, step, options…). */
data class CustomFieldEntry(val field: CustomField, val definition: InputDefinition?)

/**
 * The custom fields of a workflow, in creation order, each with its input's definition from the server (null while
 * the server's node definitions aren't loaded: the field then has no range, and a list only its value).
 */
fun customFieldEntries(workflowId: String): List<CustomFieldEntry> {
    val workflow = WorkflowManager.getWorkflowById(workflowId) ?: return emptyList()
    val fields = workflow.customFields
    if (fields.isEmpty()) return emptyList()
    val nodes = runCatching { JSONObject(workflow.jsonContent).let { it.optJSONObject("nodes") ?: it } }.getOrNull()
    val registry = ConnectionManager.nodeTypeRegistry
    return fields.map { field ->
        val target = field.targets.first()
        val classType = nodes?.optJSONObject(target.nodeId)?.optString("class_type").orEmpty()
        val definition = if (classType.isNotEmpty() && registry.isPopulated()) registry.getInputDefinition(classType, target.input) else null
        CustomFieldEntry(field, definition)
    }
}

/** A collapsed Custom section: the fields with their values ("Duration: 5, Mode: fast"). */
@Composable
fun customFieldsSummary(entries: List<CustomFieldEntry>, values: Map<String, String>): String =
    entries.map { (field, _) -> stringResource(R.string.label_name_value, field.name, values[field.id] ?: field.default) }
        .joinToString(", ")

/**
 * One custom field on the options sheet (spec §18.6), by type: numbers – the app's − / + field with the node's range;
 * on/off – a switch row; a list – the choice row; text – a text field (multi-line when the node's input is).
 *
 * @param shape The row's shape within the section's group (switch and choice rows)
 */
@Composable
fun CustomFieldControl(
    entry: CustomFieldEntry,
    value: String,
    error: String?,
    onValueChange: (value: String, error: String?) -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp)
) {
    val context = LocalContext.current
    @Suppress("NAME_SHADOWING") val modifier = modifier.fillMaxWidth()
    val (field, definition) = entry
    val name = field.name
    when (field.type) {
        FieldValueType.INT -> {
            val min = definition?.min?.toLong()
            val max = definition?.max?.toLong()
            NumericStepperField(
                value = value,
                onValueChange = { onValueChange(it, ValidationUtils.validateWholeNumber(it, context, min, max)) },
                label = name,
                min = min,
                max = max,
                step = definition?.step?.toLong()?.takeIf { it > 0 } ?: 1L,
                decimalPlaces = 0,
                error = error,
                hint = rangeHint(min, max),
                modifier = modifier
            )
        }
        FieldValueType.FLOAT -> {
            val min = definition?.min?.toDouble()
            val max = definition?.max?.toDouble()
            val step = definition?.step?.toDouble()?.takeIf { it > 0 } ?: 0.1
            NumericStepperField(
                value = value,
                onValueChange = { onValueChange(it, ValidationUtils.validateFloat(it, context, min?.toFloat(), max?.toFloat())) },
                label = name,
                min = min,
                max = max,
                step = step,
                // As many decimal places as the node's step has (at least one)
                decimalPlaces = remember(step) { BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(1, 6) },
                error = error,
                hint = rangeHint(definition?.min, definition?.max),
                modifier = modifier
            )
        }
        FieldValueType.BOOLEAN -> {
            val checked = value.trim().lowercase() == "true"
            SegmentedListItem(
                onClick = { onValueChange((!checked).toString(), null) },
                shapes = ListItemDefaults.shapes(shape = shape),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                trailingContent = { Switch(checked = checked, onCheckedChange = null) },
                modifier = modifier
            ) {
                Text(name)
            }
        }
        FieldValueType.LIST -> ChoiceRow(
            role = name,
            selectedValue = value,
            options = definition?.options ?: listOf(value),
            onValueChange = { onValueChange(it, null) },
            shape = shape,
            modifier = modifier,
            // Files (models and the like) by name, with their folder under it, as on the model rows
            isFile = definition?.options.orEmpty().any { '/' in it || '\\' in it }
        )
        FieldValueType.STRING -> {
            val multiline = definition?.multiline == true
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it, ValidationUtils.validateString(it, context = context)) },
                label = { Text(name) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                maxLines = if (multiline) 8 else 1,
                shape = RoundedCornerShape(if (multiline) 24.dp else 28.dp),
                modifier = modifier
            )
        }
    }
}

@Composable
private fun rangeHint(min: Number?, max: Number?): String? = when {
    min != null && max != null -> stringResource(R.string.node_editor_range_min_max, bound(min), bound(max))
    min != null -> stringResource(R.string.node_editor_range_min, bound(min))
    max != null -> stringResource(R.string.node_editor_range_max, bound(max))
    else -> null
}

private fun bound(value: Number): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

/** Rows of switch and choice controls group like the sheet's other choices; numbers and text stand alone. */
fun CustomFieldEntry.isListRow(): Boolean = field.type == FieldValueType.BOOLEAN || field.type == FieldValueType.LIST
