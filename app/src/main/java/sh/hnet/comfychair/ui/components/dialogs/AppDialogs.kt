@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
 * @param content What the decision is about, under the text (e.g. a certificate's fingerprint)
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
    dismissLabel: String = stringResource(R.string.button_cancel),
    content: (@Composable () -> Unit)? = null
) = ConfirmDialog(title, AnnotatedString(text), confirmLabel, onConfirm, onDismiss, icon, destructive, dismissLabel, content)

/** [ConfirmDialog] with styled text, e.g. names in bold ([sh.hnet.comfychair.ui.components.shared.boldNamesResource]). */
@Composable
fun ConfirmDialog(
    title: String,
    text: AnnotatedString,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    dismissLabel: String = stringResource(R.string.button_cancel),
    content: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { DialogIcon(it, destructive) } },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(text)
                content?.invoke()
            }
        },
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
 * B · Form: up to three outlined fields under the task as headline; optionally a destructive action of the form
 * ([destructiveLabel], e.g. Delete field) before Cancel, in the error colour. The verb stays disabled until the form is valid;
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
    destructiveLabel: String? = null,
    onDestructive: () -> Unit = {},
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
        dismissButton = {
            // A destructive action of the form (e.g. Delete field) comes first, in the error colour
            Row {
                destructiveLabel?.let { label ->
                    TextButton(
                        onClick = onDestructive,
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(label) }
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.button_cancel)) }
            }
        },
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
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    // An example value; an optional field says "Optional" instead
    placeholder: String? = null,
    onDone: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = when {
            optional -> ({ Text(stringResource(R.string.hint_optional)) })
            placeholder != null -> ({ Text(placeholder) })
            else -> null
        },
        isError = error != null,
        trailingIcon = error?.let { { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) } },
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        singleLine = !multiLine,
        minLines = if (multiLine) 3 else 1,
        maxLines = if (multiLine) 8 else 1,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (onDone != null) ImeAction.Done else if (multiLine) ImeAction.Default else ImeAction.Next
        ),
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
                if (text.isNotEmpty()) Text(text)
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

/** An option of a [ChoiceDialog]: its label and a second line (e.g. the slot type). */
class DialogChoice<T>(val value: T, val label: String, val supporting: String? = null)

/**
 * C · Choice: a question, one line of context and radio rows. Choosing is the action, so a tap commits and closes; the
 * only button is Cancel.
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    text: String,
    choices: List<DialogChoice<T>>,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, modifier = Modifier.padding(bottom = 8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = false, role = Role.RadioButton, onClick = { onChoose(choice.value) })
                        ) {
                            RadioButton(selected = false, onClick = null)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                                choice.supporting?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        modifier = Modifier.semantics { paneTitle = title }
    )
}

/**
 * A secure field (password, token): no keyboard suggestions or learning, no copy, and a toggle to show the text.
 *
 * @param supporting Shown under the field when there's no error (e.g. how it's stored)
 */
@Composable
fun DialogSecureField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.content_description_hide_password else R.string.content_description_show_password
                    )
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/** An action of an [ActionDialog]: a text button; the confirming one goes last. */
class DialogAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val busy: Boolean = false,
    val enabled: Boolean = true
)

/**
 * A dialog with an icon, headline, text and its actions as text buttons, wrapping to more rows when they don't fit
 * (e.g. Return to login, Work offline, Reconnect). The last action is the confirming one.
 *
 * @param onDismissRequest Back and a tap outside; null keeps the dialog until an action is chosen
 */
@Composable
fun ActionDialog(
    icon: ImageVector,
    title: String,
    text: String,
    actions: List<DialogAction>,
    onDismissRequest: (() -> Unit)?,
    error: Boolean = false
) {
    val busy = actions.any { it.busy }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismissRequest != null && !busy,
            dismissOnClickOutside = onDismissRequest != null && !busy
        ),
        icon = { DialogIcon(icon, error) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                actions.forEach { action ->
                    ConfirmButton(
                        action.label,
                        action.onClick,
                        enabled = action.enabled && !(busy && !action.busy),
                        destructive = action.destructive,
                        busy = action.busy
                    )
                }
            }
        },
        modifier = Modifier.semantics { paneTitle = title }
    )
}

/**
 * E · Full-screen form: × and the title at the top, outlined fields, and the action as a full-width button pinned at
 * the bottom above the keyboard (in thumb reach). Closing with changes asks first.
 *
 * @param hasChanges Something was edited: closing asks "Discard changes?"
 */
@Composable
fun FullScreenFormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    hasChanges: Boolean,
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val close = { if (hasChanges) confirmDiscard = true else onDismiss() }
    Dialog(
        onDismissRequest = { if (!busy) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        FormPage(
            title = title,
            navigationIcon = Icons.Rounded.Close,
            navigationLabel = stringResource(R.string.content_description_close),
            onNavigate = close,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            confirmEnabled = confirmEnabled,
            busy = busy,
            content = content
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.title_discard_changes),
            text = stringResource(R.string.msg_discard_changes),
            confirmLabel = stringResource(R.string.button_discard),
            dismissLabel = stringResource(R.string.button_keep_editing),
            icon = Icons.Rounded.Warning,
            destructive = true,
            onConfirm = onDismiss,
            onDismiss = { confirmDiscard = false }
        )
    }
}


/**
 * A full page with one task: a top bar (navigation and title; none when [title] is null), content that scrolls and one
 * full-width filled button at the bottom, which rides on the keyboard. The full-screen form dialogs and the welcome
 * wizard's pages are built on it.
 *
 * @param busy The button shows a loading indicator and the navigation is disabled (e.g. while saving)
 * @param confirmIcon Shown before the button's label
 * @param contentSpacing Space between the content's items (form fields need room for their floating labels)
 */
@Composable
fun FormPage(
    title: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    navigationIcon: ImageVector? = null,
    navigationLabel: String? = null,
    onNavigate: () -> Unit = {},
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    confirmIcon: ImageVector? = null,
    contentSpacing: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().semantics { title?.let { paneTitle = it } }) {
        // Safe drawing includes the keyboard, so the button stays above it
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    if (navigationIcon != null) {
                        IconButton(onClick = onNavigate, enabled = !busy) {
                            Icon(navigationIcon, contentDescription = navigationLabel)
                        }
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .semantics { heading() }
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                content = content
            )
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp)
            ) {
                if (busy) {
                    LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(24.dp))
                } else {
                    if (confirmIcon != null) {
                        Icon(confirmIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(confirmLabel)
                }
            }
        }
    }
}

