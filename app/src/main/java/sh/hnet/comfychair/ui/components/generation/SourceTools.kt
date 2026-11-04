package sh.hnet.comfychair.ui.components.generation

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton

private val ReferenceSlotSize = 52.dp
private val ReferenceSlotShape = RoundedCornerShape(12.dp)

/** Mask tools of the source toolbar, for Inpainting. */
class MaskTools(
    val hasMask: Boolean,
    val maskVisible: Boolean,
    val onToggleMaskVisible: () -> Unit,
    val onClearMask: () -> Unit,
    val onEditMask: () -> Unit
)

/**
 * Tools for the source shown full size: a floating toolbar centred near the bottom of the preview. Replace and remove
 * the source; in Inpainting also clear the mask and show or hide it, with Edit mask as the toolbar's FAB (the step that
 * can't be skipped).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.SourceToolbar(
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    maskTools: MaskTools? = null
) {
    val modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 12.dp)
    val replace: @Composable () -> Unit = {
        TooltipIconButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.content_description_replace_source), onReplace)
    }

    if (maskTools == null) {
        HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
            replace()
            TooltipIconButton(Icons.Rounded.HideImage, stringResource(R.string.content_description_remove_source), onRemove)
        }
    } else {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = modifier,
            floatingActionButton = {
                val editMask = stringResource(R.string.button_edit_mask)
                // Same colours as the result action FAB (primary container), so it reads as the main action
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = maskTools.onEditMask,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = editMask }
                ) {
                    Icon(Icons.Rounded.Brush, contentDescription = null)
                }
            }
        ) {
            replace()
            TooltipIconButton(Icons.Rounded.HideImage, stringResource(R.string.content_description_remove_source), onRemove)
            TooltipIconButton(
                Icons.Rounded.LayersClear,
                stringResource(R.string.button_clear_mask),
                maskTools.onClearMask,
                enabled = maskTools.hasMask
            )
            TooltipIconButton(
                if (maskTools.maskVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                stringResource(if (maskTools.maskVisible) R.string.content_description_hide_mask else R.string.content_description_show_mask),
                maskTools.onToggleMaskVisible,
                enabled = maskTools.hasMask
            )
        }
    }
}

/** Small "Mask" label at the top end while the mask is drawn over the source. */
@Composable
fun BoxScope.MaskLabel() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.label_mask),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Empty source: the preview itself is the picker – a dashed outline, one large button and a hint that results can be
 * sent here from their action menu.
 */
@Composable
fun EmptySourcePicker(onPick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .fillMaxSize()
            .padding(12.dp)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(
                Icons.Rounded.AddPhotoAlternate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.button_pick_source_image))
            }
            Spacer(Modifier.height(12.dp))
            SourceHint()
        }
    }
}

/** "or send a result here with ⋯", with the result menu's icon inline. */
@Composable
private fun SourceHint() {
    val marker = "\u0000"
    val parts = stringResource(R.string.msg_source_hint, marker).split(marker)
    val text = buildAnnotatedString {
        append(parts.first())
        if (parts.size > 1) {
            appendInlineContent("icon", "⋯")
            append(parts[1])
        }
    }
    val inline = mapOf(
        "icon" to InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Rounded.MoreHoriz, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    )
    Text(
        text = text,
        inlineContent = inline,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** One reference image slot of an Editing workflow. */
class ReferenceSlot(
    val image: Bitmap?,
    val description: String,
    val onPick: (Uri) -> Unit,
    val onClear: () -> Unit
)

/**
 * Reference image slots stacked at the top end of the source view, with a "Refs n/m" label. An empty slot picks an
 * image; a filled one opens a menu with Replace and Remove.
 */
@Composable
fun BoxScope.ReferenceSlots(slots: List<ReferenceSlot>) {
    if (slots.isEmpty()) return
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        slots.forEachIndexed { index, slot -> ReferenceSlotItem(slot, index + 1) }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = stringResource(R.string.label_reference_count, slots.count { it.image != null }, slots.size),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ReferenceSlotItem(slot: ReferenceSlot, number: Int) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(slot.onPick) }
    var menuOpen by remember { mutableStateOf(false) }
    val image = remember(slot.image) { slot.image?.asImageBitmap() }
    val addLabel = stringResource(R.string.content_description_add_reference, number)

    Box {
        Box(
            Modifier
                .size(ReferenceSlotSize)
                .clip(ReferenceSlotShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(role = Role.Button, onClickLabel = if (image == null) addLabel else null) {
                    if (image == null) launcher.launch("image/*") else menuOpen = true
                },
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(image, contentDescription = slot.description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = addLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.button_replace)) },
                leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    launcher.launch("image/*")
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.button_remove)) },
                leadingIcon = { Icon(Icons.Rounded.HideImage, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    slot.onClear()
                }
            )
        }
    }
}
