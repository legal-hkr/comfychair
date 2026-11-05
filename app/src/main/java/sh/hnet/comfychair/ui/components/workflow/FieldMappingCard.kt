package sh.hnet.comfychair.ui.components.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.segmentedItemShape
import sh.hnet.comfychair.workflow.FieldMappingState

/*
 * Map fields (spec §10.3): one field at a time on a card above the toolbar, the full list in a sheet.
 */

/**
 * The current field: "Field n of m" with All fields, the progress, the field and its match between Previous and Next,
 * and what to do. Tapping a highlighted node assigns it and moves on to the next unmapped field.
 *
 * @param fields In the card's order (required first)
 */
@Composable
fun FieldMappingCard(
    fields: List<FieldMappingState>,
    currentKey: String?,
    onSelectField: (String) -> Unit,
    onAllFields: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (fields.isEmpty()) return
    val index = fields.indexOfFirst { it.field.fieldKey == currentKey }.coerceAtLeast(0)
    val current = fields[index]
    val mapped = fields.count { it.isMapped }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.label_field_n_of_m, index + 1, fields.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onAllFields) { Text(stringResource(R.string.button_all_fields)) }
            }
            LinearProgressIndicator(
                progress = { mapped.toFloat() / fields.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp, bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSelectField(fields[(index - 1).mod(fields.size)].field.fieldKey) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.content_description_previous_field))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        current.field.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MatchText(current)
                }
                IconButton(onClick = { onSelectField(fields[(index + 1).mod(fields.size)].field.fieldKey) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.content_description_next_field))
                }
            }
            if (current.candidates.isNotEmpty()) {
                Text(
                    stringResource(if (current.isRequiredField) R.string.hint_mapping_required else R.string.hint_mapping_optional),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                )
            }
        }
    }
}

/** The field's node, or why there is none. */
@Composable
private fun MatchText(field: FieldMappingState) {
    val (text, color) = when {
        field.selectedCandidate != null -> field.selectedCandidate!!.nodeName to MaterialTheme.colorScheme.primary
        field.candidates.isEmpty() -> stringResource(R.string.msg_no_matching_nodes) to
            if (field.isRequiredField) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.label_not_chosen) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * All fields, required first: each with its match and a status icon; tapping one makes it current. Mappings can be
 * cleared one by one or all at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllFieldsSheet(
    fields: List<FieldMappingState>,
    onSelectField: (String) -> Unit,
    onClearField: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.button_all_fields), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearAll, enabled = fields.any { it.isMapped }) {
                        Text(stringResource(R.string.button_clear_all_mapping))
                    }
                }
            }
            listOf(true to R.string.section_necessary, false to R.string.section_optional).forEach { (required, titleRes) ->
                val group = fields.filter { it.isRequiredField == required }
                if (group.isNotEmpty()) {
                    item(key = "title_$required") {
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }
                    item(key = "group_$required") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            group.forEachIndexed { index, field ->
                                FieldRow(field, index, group.size, onSelectField, onClearField)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    field: FieldMappingState,
    index: Int,
    count: Int,
    onSelectField: (String) -> Unit,
    onClearField: (String) -> Unit
) {
    val key = field.field.fieldKey
    Surface(
        onClick = { onSelectField(key) },
        shape = segmentedItemShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(field.field.displayName) },
            supportingContent = { MatchText(field) },
            leadingContent = {
                when {
                    field.isMapped -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    field.isRequiredField -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            trailingContent = if (field.isMapped) {
                {
                    IconButton(onClick = { onClearField(key) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.content_description_clear))
                    }
                }
            } else null,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.clip(segmentedItemShape(index, count))
        )
    }
}
