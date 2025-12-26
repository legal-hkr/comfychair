package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.WorkflowNode

/**
 * Represents an editable input for the side sheet.
 */
data class EditableInput(
    val name: String,
    val definition: InputDefinition?,
    val currentValue: Any?,
    val originalValue: Any?
)

/**
 * Side sheet for editing node attributes.
 */
@Composable
fun NodeAttributeSideSheet(
    node: WorkflowNode,
    nodeDefinition: NodeTypeDefinition?,
    currentEdits: Map<String, Any>,
    onEditChange: (inputName: String, value: Any) -> Unit,
    onResetToDefault: (inputName: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Build list of editable inputs
    val editableInputs = remember(node, nodeDefinition, currentEdits) {
        buildEditableInputs(node, nodeDefinition, currentEdits)
    }

    Surface(
        modifier = modifier,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp)
        ) {
            // Header
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = node.classType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Content
            if (editableInputs.isEmpty()) {
                Text(
                    text = stringResource(R.string.node_editor_no_editable_inputs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(editableInputs, key = { it.name }) { input ->
                        InputEditor(
                            input = input,
                            onValueChange = { value -> onEditChange(input.name, value) },
                            onReset = { onResetToDefault(input.name) },
                            context = context
                        )
                    }
                }
            }
        }
    }
}

/**
 * Build list of editable inputs from node data and definition.
 */
private fun buildEditableInputs(
    node: WorkflowNode,
    nodeDefinition: NodeTypeDefinition?,
    currentEdits: Map<String, Any>
): List<EditableInput> {
    val result = mutableListOf<EditableInput>()

    node.inputs.forEach { (name, value) ->
        // Skip connections
        if (value is InputValue.Connection) return@forEach

        // Skip template variables
        if (value is InputValue.Literal) {
            val strValue = value.value.toString()
            if (strValue.contains("{{") && strValue.contains("}}")) return@forEach
        }

        // Get input definition from node type registry
        val definition = nodeDefinition?.inputs?.find { it.name == name }

        // Skip force-input fields (must be connected)
        if (definition?.forceInput == true) return@forEach

        // Get original value from workflow
        val originalValue = when (value) {
            is InputValue.Literal -> value.value
            else -> null
        }

        // Get current value (edit if exists, otherwise original)
        val currentValue = currentEdits[name] ?: originalValue

        result.add(
            EditableInput(
                name = name,
                definition = definition,
                currentValue = currentValue,
                originalValue = originalValue
            )
        )
    }

    return result
}

/**
 * Editor component for a single input.
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

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = input.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Reset button (only show if different from original)
            if (input.currentValue != input.originalValue) {
                IconButton(onClick = onReset) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.node_editor_reset),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        when {
            type == "ENUM" || definition?.options != null -> {
                val options = definition?.options ?: emptyList()
                val isImageSelector = isImageOptions(options)

                if (isImageSelector) {
                    ImageEnumEditor(
                        value = input.currentValue?.toString() ?: "",
                        options = options,
                        onValueChange = { onValueChange(it) }
                    )
                } else {
                    EnumEditor(
                        value = input.currentValue?.toString() ?: "",
                        options = options,
                        onValueChange = { onValueChange(it) }
                    )
                }
            }
            type == "BOOLEAN" || input.currentValue is Boolean -> {
                BooleanEditor(
                    value = (input.currentValue as? Boolean) ?: false,
                    onValueChange = { onValueChange(it) }
                )
            }
            type == "INT" -> {
                IntEditor(
                    value = input.currentValue?.toString() ?: "",
                    min = definition?.min?.toInt(),
                    max = definition?.max?.toInt(),
                    onValueChange = { onValueChange(it) },
                    context = context
                )
            }
            type == "FLOAT" -> {
                FloatEditor(
                    value = input.currentValue?.toString() ?: "",
                    min = definition?.min?.toFloat(),
                    max = definition?.max?.toFloat(),
                    onValueChange = { onValueChange(it) },
                    context = context
                )
            }
            type == "STRING" -> {
                StringEditor(
                    value = input.currentValue?.toString() ?: "",
                    multiline = definition?.multiline ?: false,
                    onValueChange = { onValueChange(it) },
                    context = context
                )
            }
            else -> {
                // Fallback: treat as string
                StringEditor(
                    value = input.currentValue?.toString() ?: "",
                    multiline = false,
                    onValueChange = { onValueChange(it) },
                    context = context
                )
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
 * Editor for enum/dropdown values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumEditor(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Editor for boolean values.
 */
@Composable
private fun BooleanEditor(
    value: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = value,
            onCheckedChange = onValueChange
        )
    }
}

/**
 * Editor for integer values.
 */
@Composable
private fun IntEditor(
    value: String,
    min: Int?,
    max: Int?,
    onValueChange: (Int) -> Unit,
    context: android.content.Context
) {
    var textValue by remember(value) { mutableStateOf(value) }
    val error = ValidationUtils.validateInt(textValue, context, min, max)

    OutlinedTextField(
        value = textValue,
        onValueChange = { newValue ->
            textValue = newValue
            newValue.toIntOrNull()?.let { onValueChange(it) }
        },
        isError = error != null,
        supportingText = {
            if (error != null) {
                Text(error)
            } else if (min != null || max != null) {
                val rangeText = when {
                    min != null && max != null -> stringResource(R.string.node_editor_range_min_max, min.toString(), max.toString())
                    min != null -> stringResource(R.string.node_editor_range_min, min.toString())
                    else -> stringResource(R.string.node_editor_range_max, max.toString())
                }
                Text(rangeText)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Editor for float values.
 */
@Composable
private fun FloatEditor(
    value: String,
    min: Float?,
    max: Float?,
    onValueChange: (Float) -> Unit,
    context: android.content.Context
) {
    var textValue by remember(value) { mutableStateOf(value) }
    val error = ValidationUtils.validateFloat(textValue, context, min, max)

    OutlinedTextField(
        value = textValue,
        onValueChange = { newValue ->
            textValue = newValue
            newValue.toFloatOrNull()?.let { onValueChange(it) }
        },
        isError = error != null,
        supportingText = {
            if (error != null) {
                Text(error)
            } else if (min != null || max != null) {
                val rangeText = when {
                    min != null && max != null -> stringResource(R.string.node_editor_range_min_max, min.toString(), max.toString())
                    min != null -> stringResource(R.string.node_editor_range_min, min.toString())
                    else -> stringResource(R.string.node_editor_range_max, max.toString())
                }
                Text(rangeText)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Editor for string values.
 */
@Composable
private fun StringEditor(
    value: String,
    multiline: Boolean,
    onValueChange: (String) -> Unit,
    context: android.content.Context
) {
    var textValue by remember(value) { mutableStateOf(value) }
    val error = ValidationUtils.validateString(textValue, context = context)

    OutlinedTextField(
        value = textValue,
        onValueChange = { newValue ->
            textValue = newValue
            onValueChange(newValue)
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
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }

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
        DebugLogger.d(TAG, "ImagePreview: Fetching image filename=$filename")

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
        ) { bitmap ->
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
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary
                )
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
