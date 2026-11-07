@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.CustomField
import sh.hnet.comfychair.model.CustomFields
import sh.hnet.comfychair.model.FieldTarget
import sh.hnet.comfychair.model.FieldValueType
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FormDialog
import sh.hnet.comfychair.ui.components.settings.groupShapes
import sh.hnet.comfychair.ui.components.shared.boldNamesResource

/** A field's type in words, with its range when it has one ("whole number, 0 – 16384"). */
@Composable
fun fieldTypeDescription(type: FieldValueType, min: Number?, max: Number?): String {
    val words = stringResource(
        when (type) {
            FieldValueType.INT -> R.string.label_field_type_int
            FieldValueType.FLOAT -> R.string.label_field_type_float
            FieldValueType.BOOLEAN -> R.string.label_field_type_bool
            FieldValueType.LIST -> R.string.label_field_type_list
            FieldValueType.STRING -> R.string.label_field_type_text
        }
    )
    if (min == null || max == null || (type != FieldValueType.INT && type != FieldValueType.FLOAT)) return words
    val range = stringResource(R.string.node_editor_range_min_max, formatBound(min), formatBound(max))
    return stringResource(R.string.label_field_type_with_range, words, range)
}

private fun formatBound(value: Number): String {
    val d = value.toDouble()
    return if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else value.toString()
}

/**
 * The name of a field, checked in the field (spec §18.2.2): required, at most [CustomFields.MAX_NAME_LENGTH]
 * characters, not a name another field of the workflow has.
 */
@Composable
private fun rememberNameError(name: String, attempted: Boolean, isTaken: (String) -> Boolean): String? {
    val empty = stringResource(R.string.error_field_name_empty)
    val taken = stringResource(R.string.error_field_name_taken, name.trim())
    return when {
        !attempted -> null
        name.isBlank() -> empty
        isTaken(name) -> taken
        else -> null
    }
}

/** Naming a new field made from one value (spec §18.2.2). */
@Composable
fun NewFieldDialog(
    inputName: String,
    nodeTitle: String,
    typeDescription: String,
    isTaken: (String) -> Boolean,
    onAdd: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val error = rememberNameError(name, attempted, isTaken)
    val submit = {
        attempted = true
        if (name.isNotBlank() && !isTaken(name)) onAdd(name.trim())
    }
    FormDialog(
        title = stringResource(R.string.title_new_field),
        confirmLabel = stringResource(R.string.button_add),
        onConfirm = submit,
        onDismiss = onDismiss
    ) {
        Text(
            boldNamesResource(R.string.label_new_field_context, inputName, nodeTitle, typeDescription),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DialogTextField(
            value = name,
            onValueChange = {
                name = it.take(CustomFields.MAX_NAME_LENGTH)
                attempted = false
            },
            label = stringResource(R.string.label_field_name),
            error = error,
            supporting = stringResource(R.string.desc_field_name, name.length, CustomFields.MAX_NAME_LENGTH),
            onDone = submit
        )
    }
}

/**
 * Editing a custom field (spec §18.4): its name and the values it fills (each can be taken out; one has to stay),
 * Delete field.
 *
 * @param targetLines Each value with the node title and input name shown for it
 */
@Composable
fun EditFieldDialog(
    field: CustomField,
    typeDescription: String,
    targetLines: List<Pair<FieldTarget, Pair<String, String>>>,
    isTaken: (String) -> Boolean,
    onSave: (name: String, targets: List<FieldTarget>) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onAddValue: ((name: String, targets: List<FieldTarget>) -> Unit)? = null
) {
    var name by remember { mutableStateOf(field.name) }
    var attempted by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf(field.targets) }
    val error = rememberNameError(name, attempted, isTaken)
    val submit = {
        attempted = true
        if (name.isNotBlank() && !isTaken(name) && kept.isNotEmpty()) onSave(name.trim(), kept)
    }
    FormDialog(
        title = stringResource(R.string.title_edit_field),
        confirmLabel = stringResource(R.string.button_save),
        onConfirm = submit,
        onDismiss = onDismiss,
        confirmEnabled = kept.isNotEmpty(),
        destructiveLabel = stringResource(R.string.button_delete_field),
        onDestructive = onDelete
    ) {
        Text(typeDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DialogTextField(
            value = name,
            onValueChange = {
                name = it.take(CustomFields.MAX_NAME_LENGTH)
                attempted = false
            },
            label = stringResource(R.string.label_field_name),
            error = error,
            supporting = stringResource(R.string.desc_field_name, name.length, CustomFields.MAX_NAME_LENGTH)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.title_field_fills),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .semantics { heading() }
            )
            val shown = targetLines.filter { (target, _) -> target in kept }
            // Map fields: Add a value last in the group (picks one on the graph, spec §18.3)
            val rows = shown.size + if (onAddValue != null) 1 else 0
            shown.forEachIndexed { index, (target, line) ->
                val (nodeTitle, input) = line
                // Not buttons: segments of the group, each with its × to take the value out
                Surface(
                    shape = groupShapes(index, rows).shape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {}
                        ) {
                            Text(nodeTitle, style = MaterialTheme.typography.bodyLarge)
                            Text(input, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { kept = kept - target }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.content_description_remove_field_value, nodeTitle, input))
                        }
                    }
                }
            }
            onAddValue?.let { add ->
                SegmentedListItem(
                    onClick = { add(name, kept) },
                    shapes = groupShapes(rows - 1, rows),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_add_value))
                }
            }
            if (kept.isEmpty()) {
                Text(
                    stringResource(R.string.error_field_needs_value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
        }
    }
}

/**
 * A built-in field's label in this workflow (spec §18.4): the name it's shown by, the built-in name to go back to,
 * and the values it fills (read-only: built-in fields are assigned in Map fields).
 *
 * @param label The current label, null for none
 * @param onSave The new label, null for the built-in name
 */
@Composable
fun BuiltInFieldDialog(
    builtInName: String,
    label: String?,
    targetLines: List<Pair<String, String>>,
    isTaken: (String) -> Boolean,
    onSave: (label: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(label ?: builtInName) }
    var attempted by remember { mutableStateOf(false) }
    val isBuiltIn = name.isBlank() || name.trim().equals(builtInName, ignoreCase = false)
    // The built-in name (or none) is always allowed: it removes the label
    val error = rememberNameError(name.ifBlank { builtInName }, attempted) { !isBuiltIn && isTaken(it) }
    val submit = {
        attempted = true
        if (isBuiltIn) onSave(null) else if (!isTaken(name)) onSave(name.trim())
    }
    FormDialog(
        title = stringResource(R.string.title_edit_field),
        confirmLabel = stringResource(R.string.button_save),
        onConfirm = submit,
        onDismiss = onDismiss
    ) {
        Text(
            boldNamesResource(R.string.label_builtin_field_context, builtInName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DialogTextField(
            value = name,
            onValueChange = {
                name = it.take(CustomFields.MAX_NAME_LENGTH)
                attempted = false
            },
            label = stringResource(R.string.label_field_name),
            error = error,
            supporting = stringResource(R.string.desc_builtin_field_name, builtInName, name.length, CustomFields.MAX_NAME_LENGTH),
            onDone = submit,
            trailingAction = if (!isBuiltIn) {
                {
                    IconButton(onClick = { name = builtInName }) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.content_description_use_builtin_name, builtInName))
                    }
                }
            } else null
        )
        if (targetLines.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.title_field_fills),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .semantics { heading() }
                )
                targetLines.forEachIndexed { index, (nodeTitle, input) ->
                    Surface(
                        shape = groupShapes(index, targetLines.size).shape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .semantics(mergeDescendants = true) {}
                        ) {
                            Text(nodeTitle, style = MaterialTheme.typography.bodyLarge)
                            Text(input, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
