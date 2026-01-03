package sh.hnet.comfychair.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.util.GenerationMetadata

/**
 * Bottom sheet displaying generation metadata from ComfyUI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataBottomSheet(
    metadata: GenerationMetadata?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.generation_parameters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when {
                isLoading -> {
                    // Loading state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.metadata_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                metadata == null -> {
                    // No metadata available
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.metadata_not_available),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    // Display metadata
                    val items = remember(metadata) { buildMetadataItems(metadata, context) }
                    LazyColumn {
                        items(items, key = { "${it.label}_${it.value.hashCode()}" }) { item ->
                            MetadataRow(
                                label = item.label,
                                value = item.value,
                                onCopy = { copyToClipboard(context, item.value) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single row in the metadata sheet.
 */
@Composable
private fun MetadataRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.copy_to_clipboard),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Data class for metadata item display.
 */
private data class MetadataItem(
    val label: String,
    val value: String
)

/**
 * Build list of metadata items to display.
 */
private fun buildMetadataItems(metadata: GenerationMetadata, context: Context): List<MetadataItem> {
    val items = mutableListOf<MetadataItem>()

    metadata.positivePrompt?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_positive_prompt), it))
    }

    metadata.negativePrompt?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_negative_prompt), it))
    }

    metadata.models.forEach { model ->
        items.add(MetadataItem(context.getString(R.string.metadata_model), model))
    }

    metadata.unets.forEach { unet ->
        items.add(MetadataItem(context.getString(R.string.metadata_unet), unet))
    }

    metadata.loras.forEach { lora ->
        val loraValue = "${lora.name} (${String.format("%.2f", lora.strength)})"
        items.add(MetadataItem(context.getString(R.string.metadata_lora), loraValue))
    }

    metadata.seed?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_seed), it.toString()))
    }

    metadata.steps?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_steps), it.toString()))
    }

    metadata.cfg?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_cfg), String.format("%.1f", it)))
    }

    metadata.sampler?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_sampler), it))
    }

    metadata.scheduler?.let {
        items.add(MetadataItem(context.getString(R.string.metadata_scheduler), it))
    }

    if (metadata.width != null && metadata.height != null) {
        items.add(MetadataItem(
            context.getString(R.string.metadata_size),
            "${metadata.width} x ${metadata.height}"
        ))
    }

    metadata.vaes.forEach { vae ->
        items.add(MetadataItem(context.getString(R.string.metadata_vae), vae))
    }

    return items
}

/**
 * Copy text to clipboard and show toast.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("metadata", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}
