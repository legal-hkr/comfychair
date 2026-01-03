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
import androidx.compose.material3.Slider
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
import sh.hnet.comfychair.ui.components.shared.ModelPathText
import java.util.Locale

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
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
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
                Text(stringResource(R.string.add_lora_button))
            }
        }

        if (loraChain.isEmpty()) {
            // Empty state
            Text(
                text = stringResource(R.string.lora_empty_state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // LoRA entries
            loraChain.forEachIndexed { index, lora ->
                key(lora.name, index) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LoraEntryItem(
                        index = index,
                        lora = lora,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraEntryItem(
    index: Int,
    lora: LoraSelection,
    availableLoras: List<String>,
    onNameChange: (String) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // LoRA selection row with dropdown and remove button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index indicator
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(24.dp)
            )

            // LoRA dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = lora.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.label_lora)) },
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
                }
            }

            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_lora)
                )
            }
        }

        // Strength slider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.lora_strength_label),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(64.dp)
            )

            Slider(
                value = lora.strength,
                onValueChange = onStrengthChange,
                valueRange = LoraSelection.MIN_STRENGTH..LoraSelection.MAX_STRENGTH,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = String.format(Locale.US, "%.1f", lora.strength),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(32.dp)
            )
        }
    }
}
