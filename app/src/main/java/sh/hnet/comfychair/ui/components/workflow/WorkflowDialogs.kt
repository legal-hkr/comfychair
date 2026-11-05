package sh.hnet.comfychair.ui.components.workflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExtensionOff
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.WorkflowTypeDisplay
import sh.hnet.comfychair.ui.components.dialogs.DialogDropdownField
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FormDialog
import sh.hnet.comfychair.ui.components.dialogs.InfoDialog

/*
 * Dialogs shared by the Workflow Editor and the workflow list (save/import, validation problems), in the app's
 * dialog pattern (ui/components/dialogs).
 */

/**
 * Type, name and description of a workflow being saved from the editor or imported (form). A name already taken
 * shows as the name field's error.
 *
 * @param titleRes The task: import or save
 * @param confirmLabelRes The verb: Continue (import goes on to the server checks) or Save
 * @param fileName The file being imported, under the headline
 * @param isValidating The server checks run: the verb shows a loading indicator, the dialog stays
 */
@Composable
fun WorkflowDetailsDialog(
    titleRes: Int,
    confirmLabelRes: Int,
    selectedType: WorkflowType?,
    onTypeSelected: (WorkflowType) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    isValidating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    fileName: String? = null
) {
    val valid = selectedType != null && name.isNotBlank()
    FormDialog(
        title = stringResource(titleRes),
        supportingText = fileName,
        confirmLabel = stringResource(confirmLabelRes),
        confirmEnabled = valid,
        busy = isValidating,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        DialogDropdownField(
            label = stringResource(R.string.label_workflow_type),
            options = WorkflowTypeDisplay.allTypes.map { (type, nameRes) -> type to stringResource(nameRes) },
            selected = selectedType,
            onSelect = onTypeSelected,
            enabled = !isValidating
        )
        DialogTextField(
            value = name,
            onValueChange = onNameChange,
            label = stringResource(R.string.label_workflow_name),
            error = nameError,
            enabled = !isValidating
        )
        DialogTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = stringResource(R.string.label_workflow_description),
            multiLine = true,
            optional = true,
            enabled = !isValidating
        )
    }
}

/** The workflow uses node types the server doesn't have: their names (selectable, copyable) and what to do. */
@Composable
fun MissingNodesDialog(
    missingNodes: List<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    InfoDialog(
        icon = Icons.Rounded.ExtensionOff,
        title = stringResource(R.string.title_missing_on_server),
        text = pluralStringResource(R.plurals.msg_missing_nodes_count, missingNodes.size, missingNodes.size),
        details = missingNodes,
        error = true,
        onDismiss = onDismiss,
        helperLabel = stringResource(R.string.button_copy_names),
        // Node class names only: nothing private
        onHelper = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("nodes", missingNodes.joinToString("\n")))
            Toast.makeText(context, R.string.msg_names_copied, Toast.LENGTH_SHORT).show()
        }
    )
}

/**
 * Fields the type's screen needs have no node in the workflow to come from, so the workflow can't be used as that type.
 *
 * @param type The type chosen, whose screen needs the fields
 */
@Composable
fun MissingFieldsDialog(
    missingFields: List<String>,
    type: WorkflowType?,
    onDismiss: () -> Unit
) {
    val typeName = type?.let { stringResource(WorkflowTypeDisplay.getDisplayNameResId(it)) } ?: ""
    InfoDialog(
        icon = Icons.Rounded.Rule,
        title = stringResource(R.string.title_missing_fields),
        text = stringResource(R.string.msg_missing_fields_none, typeName),
        details = missingFields,
        error = true,
        onDismiss = onDismiss
    )
}
