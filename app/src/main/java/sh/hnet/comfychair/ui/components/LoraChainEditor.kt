package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.ui.components.shared.HierarchicalTreeItems
import sh.hnet.comfychair.ui.components.shared.ModelPathText
import sh.hnet.comfychair.ui.components.shared.buildModelTree
import sh.hnet.comfychair.ui.components.shared.folderPathOf
import sh.hnet.comfychair.ui.components.shared.modelTreeHasFolders

/**
 * Reusable component for editing a LoRA chain (0-5 LoRAs with per-LoRA strength)
 */
@Composable
fun LoraChainEditor(
    title: String,
    loraChain: List<LoraSelection>,
    availableLoras: List<String>,
    onAddLora: () -> Unit,
    onRemoveLora: (Int) -> Unit,
    onLoraNameChange: (Int, String) -> Unit,
    onLoraStrengthChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header with title and add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The title wraps if needed; the button keeps its one-line width, so every chain's header looks the same
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = onAddLora,
                enabled = loraChain.size < LoraSelection.MAX_CHAIN_LENGTH && availableLoras.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.button_add_lora), maxLines = 1, softWrap = false)
            }
        }

        if (loraChain.isEmpty()) {
            // Empty state
            Text(
                text = stringResource(R.string.msg_lora_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // LoRA entries as a segmented list
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                loraChain.forEachIndexed { index, lora ->
                    key(lora.name, index) {
                        LoraEntryItem(
                            index = index,
                            lora = lora,
                            shape = entryShape(index, loraChain.size),
                            availableLoras = availableLoras,
                            onNameChange = { name -> onLoraNameChange(index, name) },
                            onStrengthChange = { strength -> onLoraStrengthChange(index, strength) },
                            onRemove = { onRemoveLora(index) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Corner shape of an entry by its position, so the chain reads as one segmented group.
 */
private fun entryShape(index: Int, count: Int): RoundedCornerShape = when {
    count == 1 -> RoundedCornerShape(16.dp)
    index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    index == count - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    else -> RoundedCornerShape(4.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraEntryItem(
    index: Int,
    lora: LoraSelection,
    shape: RoundedCornerShape,
    availableLoras: List<String>,
    onNameChange: (String) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val tree = remember(availableLoras) { buildModelTree(availableLoras) }
    val hasFolders = remember(tree) { modelTreeHasFolders(tree) }
    var expandedPath by remember(lora.name) { mutableStateOf(folderPathOf(lora.name)) }

    // Label carries the position and folder, the field shows only the file name
    val folder = folderPathOf(lora.name)
    val entryLabel = if (folder.isEmpty()) {
        stringResource(R.string.label_lora_entry, index + 1)
    } else {
        stringResource(R.string.label_lora_entry_folder, index + 1, folder)
    }
    val fileName = lora.name.replace('\\', '/').substringAfterLast('/').removeSuffix(".safetensors")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // LoRA selection row with dropdown and remove button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // LoRA dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = it
                        if (it) expandedPath = folderPathOf(lora.name)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(entryLabel) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        singleLine = true
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        if (!hasFolders) {
                            availableLoras.forEach { option ->
                                DropdownMenuItem(
                                    text = { ModelPathText(option) },
                                    onClick = {
                                        onNameChange(option)
                                        expanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        } else {
                            HierarchicalTreeItems(
                                nodes = tree,
                                expandedPath = expandedPath,
                                selectedValue = lora.name,
                                depth = 0,
                                onExpandFolder = { expandedPath = it },
                                onSelectModel = {
                                    onNameChange(it)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Remove button
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.button_remove_lora)
                    )
                }
            }

            // Strength rows end where the dropdown ends (4dp spacing + 48dp remove button)
            LoraStrengthControl(
                entryKey = "$index:${lora.name}",
                strength = lora.strength,
                onStrengthChange = onStrengthChange,
                modifier = Modifier.padding(end = 52.dp)
            )
        }
    }
}
