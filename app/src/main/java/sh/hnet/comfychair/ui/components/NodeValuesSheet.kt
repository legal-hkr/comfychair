@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.SamplerOptions
import sh.hnet.comfychair.ui.components.shared.ExpandableTooltip
import sh.hnet.comfychair.ui.components.shared.HierarchicalTreeItems
import sh.hnet.comfychair.ui.components.shared.ModelPathText
import sh.hnet.comfychair.ui.components.shared.NumericStepperField
import sh.hnet.comfychair.ui.components.shared.TooltipLabel
import sh.hnet.comfychair.ui.components.shared.buildModelTree
import sh.hnet.comfychair.ui.components.shared.folderPathOf
import sh.hnet.comfychair.ui.components.shared.modelTreeHasFolders
import sh.hnet.comfychair.ui.components.shared.segmentedItemShape
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.appManagedInputs
import sh.hnet.comfychair.workflow.changedInputNames
import sh.hnet.comfychair.workflow.editableInputNames
import sh.hnet.comfychair.workflow.nodeValuesEqual
import sh.hnet.comfychair.workflow.workflowValueOf

/**
 * An input in the values sheet: its definition, the value shown and the workflow's own value (what Reset goes back to).
 */
@Stable
data class EditableInput(
    val name: String,
    val definition: InputDefinition?,
    val currentValue: Any?,
    val originalValue: Any?
)

/**
 * A node's values, in the bottom sheet of the Node values mode: the node's title, class and how many values changed,
 * Reset node, then its values with the same controls as the options sheet (a changed one says so and can be reset),
 * and last the inputs the generation screen fills in, read-only, so it's clear why they can't be changed here.
 */
@Composable
fun NodeValuesSheetContent(
    node: WorkflowNode,
    nodeDefinition: NodeTypeDefinition?,
    currentEdits: Map<String, Any>,
    onEditChange: (inputName: String, value: Any) -> Unit,
    onResetToDefault: (inputName: String) -> Unit,
    onResetNode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val editableInputs = remember(node, nodeDefinition, currentEdits) {
        editableInputNames(node, nodeDefinition).map { name ->
            val definition = nodeDefinition?.inputs?.find { it.name == name }
            val original = workflowValueOf(node, definition, name)
            EditableInput(name, definition, currentEdits[name] ?: original, original)
        }
    }
    val linked = remember(node) { appManagedInputs(node) }
    val changed = remember(node, nodeDefinition, currentEdits) { changedInputNames(node, nodeDefinition, currentEdits).size }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(node.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (changed > 0) "${node.classType} – " + pluralStringResource(R.plurals.label_node_changed_count, changed, changed)
                        else node.classType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onResetNode, enabled = changed > 0) {
                    Text(stringResource(R.string.button_reset_node))
                }
            }
        }
        if (editableInputs.isEmpty() && linked.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.node_editor_no_editable_inputs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(editableInputs, key = { it.name }) { input ->
            InputEditor(
                input = input,
                onValueChange = { value -> onEditChange(input.name, value) },
                onReset = { onResetToDefault(input.name) },
                context = context
            )
        }
        if (linked.isNotEmpty()) {
            item(key = "linked") { LinkedInputs(linked) }
        }
    }
}

/** Inputs the generation screen fills in: their name and the app's field, no controls. */
@Composable
private fun LinkedInputs(inputs: List<Pair<String, String>>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.title_from_generation_screen),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            stringResource(R.string.desc_from_generation_screen),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        inputs.forEachIndexed { index, (name, value) ->
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text(appFieldLabel(context, value)) },
                trailingContent = { Icon(Icons.Rounded.Link, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.clip(segmentedItemShape(index, inputs.size))
            )
        }
    }
}

/** The app's field behind a placeholder ("{{seed}}") or an uploaded image marker. */
private fun appFieldLabel(context: android.content.Context, value: String): String = when {
    value.startsWith("uploaded_image") -> context.getString(R.string.label_pip_source)
    value.startsWith("reference_image_1") -> context.getString(R.string.content_description_reference_image_1)
    value.startsWith("reference_image_2") -> context.getString(R.string.content_description_reference_image_2)
    else -> Regex("""\{\{(\w+)\}\}""").find(value)?.groupValues?.get(1)
        ?.let { FieldDisplayRegistry.getDisplayName(context, it) } ?: value
}

/**
 * Editor for a single input; a changed value says what the workflow's value is and can go back to it.
 */
@Composable
private fun InputEditor(
    input: EditableInput,
    onValueChange: (Any) -> Unit,
    onReset: () -> Unit,
    context: android.content.Context
) {
    val definition = input.definition
    val type = definition?.type ?: guessType(input.currentValue)
    val changed = !nodeValuesEqual(input.currentValue, input.originalValue, type)

    // Check for options from definition or fall back to field-name-based defaults
    val effectiveOptions = remember(definition, input.name) {
        definition?.options ?: getDefaultOptionsForField(input.name)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            type == "ENUM" || effectiveOptions != null -> {
                val options = effectiveOptions ?: emptyList()
                if (isImageOptions(options)) {
                    ImageEnumEditor(
                        label = input.name,
                        value = input.currentValue?.toString() ?: "",
                        options = options,
                        tooltip = definition?.tooltip,
                        onValueChange = { onValueChange(it) }
                    )
                } else {
                    EnumEditor(
                        label = input.name,
                        value = input.currentValue?.toString() ?: "",
                        options = options,
                        tooltip = definition?.tooltip,
                        onValueChange = { onValueChange(it) }
                    )
                }
            }
            type == "BOOLEAN" || input.currentValue is Boolean -> BooleanEditor(
                label = input.name,
                value = (input.currentValue as? Boolean) ?: false,
                tooltip = definition?.tooltip,
                onValueChange = { onValueChange(it) }
            )
            type == "INT" -> IntEditor(
                label = input.name,
                value = input.currentValue?.toString() ?: "",
                min = definition?.min?.toInt(),
                max = definition?.max?.toInt(),
                step = definition?.step?.toInt(),
                tooltip = definition?.tooltip,
                onValueChange = { onValueChange(it) },
                context = context
            )
            type == "FLOAT" -> FloatEditor(
                label = input.name,
                value = input.currentValue?.toString() ?: "",
                min = definition?.min?.toFloat(),
                max = definition?.max?.toFloat(),
                step = definition?.step?.toFloat(),
                tooltip = definition?.tooltip,
                onValueChange = { onValueChange(it) },
                context = context
            )
            else -> StringEditor(
                label = input.name,
                value = input.currentValue?.toString() ?: "",
                multiline = type == "STRING" && definition?.multiline == true,
                tooltip = if (type == "STRING") definition?.tooltip else null,
                onValueChange = { onValueChange(it) },
                context = context
            )
        }

        if (changed) {
            val original = input.originalValue?.toString() ?: ""
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    stringResource(R.string.label_value_changed, original),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(onClick = onReset) {
                    Icon(
                        Icons.Rounded.RestartAlt,
                        contentDescription = stringResource(R.string.content_description_reset_value, input.name, original)
                    )
                }
            }
        }
    }
}

/**
 * Guess the type of a value when definition is not available.
 */
private fun guessType(value: Any?): String {
    return when (value) {
        is Boolean -> "BOOLEAN"
        is Int, is Long -> "INT"
        is Float, is Double -> "FLOAT"
        is String -> "STRING"
        else -> "STRING"
    }
}

/**
 * Get default options for known field names when server definition is missing.
 */
private fun getDefaultOptionsForField(fieldName: String): List<String>? {
    return when (fieldName) {
        "sampler_name" -> SamplerOptions.SAMPLERS
        "scheduler" -> SamplerOptions.SCHEDULERS
        else -> null
    }
}

/**
 * Editor for enum/dropdown values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumEditor(
    label: String,
    value: String,
    options: List<String>,
    tooltip: String?,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var tooltipExpanded by remember { mutableStateOf(false) }

    val tree = remember(options) { buildModelTree(options) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(value) { mutableStateOf(folderPathOf(value)) }

    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                expanded = it
                if (it) expandedPath = folderPathOf(value)
            }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = {
                    TooltipLabel(
                        text = label,
                        tooltip = tooltip,
                        expanded = tooltipExpanded,
                        onToggle = { tooltipExpanded = !tooltipExpanded }
                    )
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (!hasFolders) {
                    options.forEach { option ->
                        key(option) {
                            DropdownMenuItem(
                                text = { ModelPathText(option) },
                                onClick = {
                                    onValueChange(option)
                                    expanded = false
                                }
                            )
                        }
                    }
                } else {
                    HierarchicalTreeItems(
                        nodes = tree,
                        expandedPath = expandedPath,
                        selectedValue = value,
                        depth = 0,
                        onExpandFolder = { expandedPath = it },
                        onSelectModel = {
                            onValueChange(it)
                            expanded = false
                        }
                    )
                }
            }
        }

        ExpandableTooltip(
            tooltip = tooltip,
            expanded = tooltipExpanded
        )
    }
}

/**
 * Editor for boolean values.
 */
@Composable
private fun BooleanEditor(
    label: String,
    value: Boolean,
    tooltip: String?,
    onValueChange: (Boolean) -> Unit
) {
    var tooltipExpanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TooltipLabel(
                text = label,
                tooltip = tooltip,
                expanded = tooltipExpanded,
                onToggle = { tooltipExpanded = !tooltipExpanded },
                modifier = Modifier.weight(1f)
            )
            Switch(checked = value, onCheckedChange = onValueChange)
        }

        ExpandableTooltip(
            tooltip = tooltip,
            expanded = tooltipExpanded
        )
    }
}

/**
 * Editor for integer values.
 */
@Composable
private fun IntEditor(
    label: String,
    value: String,
    min: Int?,
    max: Int?,
    step: Int?,
    tooltip: String?,
    onValueChange: (Int) -> Unit,
    context: android.content.Context
) {
    var textValue by remember(value) { mutableStateOf(value) }
    val error = ValidationUtils.validateInt(textValue, context, min, max)

    // Construct range hint
    val hint = when {
        min != null && max != null -> context.getString(R.string.node_editor_range_min_max, min.toString(), max.toString())
        min != null -> context.getString(R.string.node_editor_range_min, min.toString())
        max != null -> context.getString(R.string.node_editor_range_max, max.toString())
        else -> null
    }

    NumericStepperField(
        value = textValue,
        onValueChange = { newValue ->
            textValue = newValue
            newValue.toIntOrNull()?.let { onValueChange(it) }
        },
        label = label,
        min = (min ?: Int.MIN_VALUE).toFloat(),
        max = (max ?: Int.MAX_VALUE).toFloat(),
        step = (step ?: 1).toFloat(),
        decimalPlaces = 0,
        error = error,
        hint = hint,
        tooltip = tooltip,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Editor for float values.
 */
@Composable
private fun FloatEditor(
    label: String,
    value: String,
    min: Float?,
    max: Float?,
    step: Float?,
    tooltip: String?,
    onValueChange: (Float) -> Unit,
    context: android.content.Context
) {
    var textValue by remember(value) { mutableStateOf(value) }
    val error = ValidationUtils.validateFloat(textValue, context, min, max)

    // Construct range hint
    val hint = when {
        min != null && max != null -> context.getString(R.string.node_editor_range_min_max, min.toString(), max.toString())
        min != null -> context.getString(R.string.node_editor_range_min, min.toString())
        max != null -> context.getString(R.string.node_editor_range_max, max.toString())
        else -> null
    }

    NumericStepperField(
        value = textValue,
        onValueChange = { newValue ->
            textValue = newValue
            newValue.toFloatOrNull()?.let { onValueChange(it) }
        },
        label = label,
        min = min ?: Float.MIN_VALUE,
        max = max ?: Float.MAX_VALUE,
        step = step ?: 0.1f,
        decimalPlaces = 2,
        error = error,
        hint = hint,
        tooltip = tooltip,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Editor for string values.
 */
@Composable
private fun StringEditor(
    label: String,
    value: String,
    multiline: Boolean,
    tooltip: String?,
    onValueChange: (String) -> Unit,
    context: android.content.Context
) {
    var textValue by remember(value) { mutableStateOf(value) }
    var tooltipExpanded by remember { mutableStateOf(false) }
    val error = ValidationUtils.validateString(textValue, context = context)

    Column {
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                onValueChange(newValue)
            },
            label = {
                TooltipLabel(
                    text = label,
                    tooltip = tooltip,
                    expanded = tooltipExpanded,
                    onToggle = { tooltipExpanded = !tooltipExpanded }
                )
            },
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else null,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 10 else 1,
            modifier = Modifier.fillMaxWidth()
        )

        ExpandableTooltip(
            tooltip = tooltip,
            expanded = tooltipExpanded
        )
    }
}

private const val TAG = "ImagePreview"

/**
 * Check if the options list contains image filenames.
 */
private fun isImageOptions(options: List<String>): Boolean {
    if (options.isEmpty()) return false
    val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
    // Check if at least one option has an image extension
    val result = options.any { option ->
        imageExtensions.any { ext -> option.lowercase().endsWith(ext) }
    }
    DebugLogger.d(TAG, "isImageOptions: options=${options.take(3)}, result=$result")
    return result
}


/**
 * Editor for enum values that represent images, with preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageEnumEditor(
    label: String,
    value: String,
    options: List<String>,
    tooltip: String?,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var tooltipExpanded by remember { mutableStateOf(false) }

    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = {
                    TooltipLabel(
                        text = label,
                        tooltip = tooltip,
                        expanded = tooltipExpanded,
                        onToggle = { tooltipExpanded = !tooltipExpanded }
                    )
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    key(option) {
                        DropdownMenuItem(
                            text = { ModelPathText(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        ExpandableTooltip(
            tooltip = tooltip,
            expanded = tooltipExpanded
        )

        // Image preview
        if (value.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            ImagePreview(
                filename = value,
                contentDescription = value
            )
        }
    }
}

/**
 * Image loading state for async fetch.
 */
private sealed class ImageLoadState {
    data object Loading : ImageLoadState()
    data class Success(val bitmap: Bitmap) : ImageLoadState()
    data object Error : ImageLoadState()
}

/**
 * Async image preview using ComfyUIClient for proper SSL handling.
 */
@Composable
private fun ImagePreview(
    filename: String,
    contentDescription: String
) {
    var loadState by remember { mutableStateOf<ImageLoadState>(ImageLoadState.Loading) }

    // Fetch image using ComfyUIClient
    LaunchedEffect(filename) {
        loadState = ImageLoadState.Loading
        DebugLogger.d(TAG, "ImagePreview: Fetching image filename=${Obfuscator.filename(filename)}")

        val client = ConnectionManager.clientOrNull
        if (client == null) {
            DebugLogger.e(TAG, "ImagePreview: No ComfyUIClient available")
            loadState = ImageLoadState.Error
            return@LaunchedEffect
        }

        client.fetchImage(
            filename = filename,
            subfolder = "",
            type = "input"
        ) { bitmap, _ ->
            loadState = if (bitmap != null) {
                DebugLogger.d(TAG, "ImagePreview: Successfully loaded image")
                ImageLoadState.Success(bitmap)
            } else {
                DebugLogger.e(TAG, "ImagePreview: Failed to load image")
                ImageLoadState.Error
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        when (val state = loadState) {
            is ImageLoadState.Loading -> {
                LoadingIndicator(modifier = Modifier.size(40.dp))
            }
            is ImageLoadState.Success -> {
                androidx.compose.foundation.Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is ImageLoadState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.node_editor_image_load_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
