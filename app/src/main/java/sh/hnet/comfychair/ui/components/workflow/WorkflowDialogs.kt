@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowTypeDisplay
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer

/*
 * Dialogs shared by the Workflow Editor and the workflow list (save/import, validation problems).
 */

/**
 * Type, name and description of a workflow being saved from the editor or imported.
 *
 * @param titleRes Dialog title (save or import)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowDetailsDialog(
    titleRes: Int,
    selectedType: WorkflowType?,
    onTypeSelected: (WorkflowType) -> Unit,
    isTypeDropdownExpanded: Boolean,
    onToggleTypeDropdown: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    isValidating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedTypeName = selectedType?.let { stringResource(WorkflowTypeDisplay.getDisplayNameResId(it)) } ?: ""

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                // Type dropdown
                Text(
                    text = stringResource(R.string.label_workflow_type),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = isTypeDropdownExpanded,
                    onExpandedChange = { if (!isValidating) onToggleTypeDropdown() }
                ) {
                    OutlinedTextField(
                        value = selectedTypeName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTypeDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        enabled = !isValidating
                    )
                    ExposedDropdownMenu(
                        expanded = isTypeDropdownExpanded,
                        onDismissRequest = onToggleTypeDropdown
                    ) {
                        WorkflowTypeDisplay.allTypes.forEach { (type, displayNameResId) ->
                            key(type) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(displayNameResId)) },
                                    onClick = { onTypeSelected(type) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_workflow_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_workflow_description)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                if (isValidating) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LoadingIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.msg_validating_workflow))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = selectedType != null && name.isNotBlank() && !isValidating
            ) {
                Text(stringResource(R.string.button_continue))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !isValidating) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

/**
 * The workflow uses node types the server doesn't have.
 */
@Composable
fun MissingNodesDialog(
    missingNodes: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_missing_nodes)) },
        text = {
            Column {
                Text(stringResource(R.string.msg_missing_nodes))
                Spacer(modifier = Modifier.height(8.dp))
                NoOverscrollContainer {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(missingNodes, key = { it }) { node ->
                            Text(
                                text = stringResource(R.string.list_item_bullet, node),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

/**
 * Required fields of the workflow type aren't mapped to any node input.
 */
@Composable
fun MissingFieldsDialog(
    missingFields: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_missing_fields)) },
        text = {
            Column {
                Text(stringResource(R.string.msg_missing_fields))
                Spacer(modifier = Modifier.height(8.dp))
                missingFields.forEach { field ->
                    key(field) {
                        Text(
                            text = stringResource(R.string.list_item_bullet, field),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

/**
 * A workflow with this name already exists.
 */
@Composable
fun DuplicateNameDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_duplicate_name)) },
        text = { Text(stringResource(R.string.msg_duplicate_name)) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.button_ok))
            }
        }
    )
}
