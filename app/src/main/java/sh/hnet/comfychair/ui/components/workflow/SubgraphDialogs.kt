package sh.hnet.comfychair.ui.components.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FormDialog
import sh.hnet.comfychair.ui.components.shared.boldNamesResource
import sh.hnet.comfychair.workflow.SubgraphEditing

/*
 * The forms of subgraph editing (spec §19.4, §19.5): naming a new subgraph, renaming one, renaming its inputs and
 * outputs. Names are required, at most [SubgraphEditing.MAX_NAME_LENGTH] characters; errors show in the field.
 */

/** A name field: the counter below it, an error in it once the user tried to confirm. */
@Composable
private fun NameField(name: String, onChange: (String) -> Unit, error: String?, onDone: () -> Unit) {
    DialogTextField(
        value = name,
        onValueChange = { onChange(it.take(SubgraphEditing.MAX_NAME_LENGTH)) },
        label = stringResource(R.string.sg_name),
        error = error,
        supporting = stringResource(R.string.sg_name_count, name.length, SubgraphEditing.MAX_NAME_LENGTH),
        onDone = onDone
    )
}

/** Naming a subgraph made of the selection: what will happen, in numbers (spec §19.4.1). */
@Composable
fun NewSubgraphDialog(nodes: Int, inputs: Int, outputs: Int, onMake: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val error = if (attempted && name.isBlank()) stringResource(R.string.sg_error_name_empty) else null
    val submit = {
        attempted = true
        if (name.isNotBlank()) onMake(name.trim())
    }
    FormDialog(
        title = stringResource(R.string.sg_new_title),
        confirmLabel = stringResource(R.string.sg_make_confirm),
        onConfirm = submit,
        onDismiss = onDismiss
    ) {
        // What the subgraph will have, one count per line
        Column {
            listOf(R.string.sg_new_nodes to nodes, R.string.sg_new_inputs to inputs, R.string.sg_new_outputs to outputs).forEach { (label, count) ->
                Text(
                    stringResource(label, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        NameField(name, { name = it; attempted = false }, error, submit)
    }
}

/**
 * Renaming a subgraph: every instance gets the name (the count says how many); an instance with its own title can
 * drop it here ([ownTitle], spec §19.4.3).
 */
@Composable
fun RenameSubgraphDialog(
    current: String,
    usedCount: Int,
    ownTitle: String?,
    onRename: (name: String, dropOwnTitle: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(current) }
    var attempted by remember { mutableStateOf(false) }
    var dropOwnTitle by remember { mutableStateOf(false) }
    val error = if (attempted && name.isBlank()) stringResource(R.string.sg_error_name_empty) else null
    val submit = {
        attempted = true
        if (name.isNotBlank()) onRename(name.trim(), dropOwnTitle)
    }
    FormDialog(
        title = stringResource(R.string.sg_rename),
        confirmLabel = stringResource(R.string.button_rename),
        onConfirm = submit,
        onDismiss = onDismiss
    ) {
        if (usedCount > 1) {
            Text(
                stringResource(R.string.sg_rename_used, usedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        NameField(name, { name = it; attempted = false }, error, submit)
        if (ownTitle != null && !dropOwnTitle) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    boldNamesResource(R.string.sg_own_title, ownTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { dropOwnTitle = true }) { Text(stringResource(R.string.sg_use_subgraph_name)) }
            }
        }
    }
}

/** Renaming a subgraph's input or output: unique among its kind ([taken], the others' names). */
@Composable
fun RenameSubgraphPortDialog(input: Boolean, current: String, taken: Collection<String>, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    var attempted by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val isTaken = trimmed != current && trimmed in taken
    val error = when {
        !attempted -> null
        name.isBlank() -> stringResource(R.string.sg_error_name_empty)
        isTaken -> stringResource(if (input) R.string.sg_error_input_taken else R.string.sg_error_output_taken, trimmed)
        else -> null
    }
    val submit = {
        attempted = true
        if (name.isNotBlank() && !isTaken) onRename(trimmed)
    }
    FormDialog(
        title = stringResource(if (input) R.string.sg_rename_input else R.string.sg_rename_output),
        confirmLabel = stringResource(R.string.button_rename),
        onConfirm = submit,
        onDismiss = onDismiss
    ) {
        NameField(name, { name = it; attempted = false }, error, submit)
    }
}
