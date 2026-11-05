@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R

/*
 * One dialog pattern for the whole app (spec §11): the component's own container, 28 dp corners and scrim; a headline
 * (a question for confirmations, the task for forms); text buttons only, labelled with verbs, the confirming one last
 * and in the error colour only when it removes data or lowers security. Variants: A confirmation, B form, C choice,
 * D information with a list, E full screen.
 */

/** The confirming text button: in the error colour for removing or risky actions; a loading indicator while busy. */
@Composable
private fun ConfirmButton(label: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, busy: Boolean = false) {
    TextButton(onClick = onClick, enabled = enabled && !busy) {
        if (busy) {
            LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(24.dp))
        } else {
            Text(label, color = if (destructive && enabled) MaterialTheme.colorScheme.error else LocalContentColor.current)
        }
    }
}

/** An icon above the headline: only for destructive, risky or error dialogs. */
@Composable
private fun DialogIcon(icon: ImageVector, risky: Boolean) {
    Icon(icon, contentDescription = null, tint = if (risky) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
}

/**
 * A · Confirmation: the question, its consequence, Cancel and the verb.
 *
 * @param icon Only for destructive or risky actions
 * @param destructive The verb removes data or lowers security: in the error colour
 * @param dismissLabel Cancel, or a verb when the choice is two-sided ("Keep editing")
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    dismissLabel: String = stringResource(R.string.button_cancel)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { DialogIcon(it, destructive) } },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            ConfirmButton(confirmLabel, onClick = {
                onDismiss()
                onConfirm()
            }, destructive = destructive)
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        modifier = Modifier.semantics { paneTitle = title }
    )
}

/**
 * B · Form: up to three outlined fields under the task as headline. The verb stays disabled until the form is valid;
 * while a request runs it shows a loading indicator and the dialog can't be dismissed.
 *
 * @param supportingText One line under the headline (e.g. the file being imported)
 */
@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(title) },
        text = {
            // Scrolls when the keyboard leaves too little room
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                content()
            }
        },
        confirmButton = { ConfirmButton(confirmLabel, onConfirm, enabled = confirmEnabled, busy = busy) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.button_cancel)) } },
        modifier = Modifier.semantics { paneTitle = title }
    )
}

/**
 * An outlined field of a form. An error shows inline: the error state, a trailing icon and the message below.
 *
 * @param multiLine 3 to 8 lines, then scrolls
 * @param optional Says "Optional" as its placeholder
 * @param onDone The keyboard's Done on the last field (submits when the form is valid); null gives Next
 */
@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    multiLine: Boolean = false,
    optional: Boolean = false,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (optional) ({ Text(stringResource(R.string.hint_optional)) }) else null,
        isError = error != null,
        trailingIcon = error?.let { { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) } },
        supportingText = error?.let { { Text(it) } },
        singleLine = !multiLine,
        minLines = if (multiLine) 3 else 1,
        maxLines = if (multiLine) 8 else 1,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else if (multiLine) ImeAction.Default else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth()
    )
}

/** A choice inside a form, as an outlined exposed dropdown. */
@Composable
fun <T> DialogDropdownField(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

/**
 * D · Information: what happened (icon and headline), what to do (text), optional details as a compact list of
 * selectable identifiers, a dismiss verb and at most one helper.
 *
 * @param error The icon in the error colour (something failed or is risky)
 */
@Composable
fun InfoDialog(
    icon: ImageVector,
    title: String,
    text: String,
    onDismiss: () -> Unit,
    details: List<String> = emptyList(),
    error: Boolean = false,
    dismissLabel: String = stringResource(R.string.button_dismiss),
    helperLabel: String? = null,
    onHelper: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { DialogIcon(icon, error) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text)
                if (details.isNotEmpty()) {
                    SelectionContainer {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            details.forEachIndexed { index, detail ->
                                Surface(
                                    shape = ListItemDefaults.segmentedShapes(index, details.size).shape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        // The dismiss verb last, the helper before it
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        dismissButton = if (helperLabel != null && onHelper != null) {
            { TextButton(onClick = onHelper) { Text(helperLabel) } }
        } else null,
        modifier = Modifier.semantics { paneTitle = title }
    )
}
