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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.LayersClear
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.thumbnailFrame

private val ReferenceSlotSize = 52.dp
/**
 * Inset of the thumbnails over the preview (result or source, references) from its edges: their rings, 4 dp beyond
 * them, stay 16 dp from the edges as the dock does, clear of the empty source's dashed outline at 12 dp.
 */
internal val PreviewThumbnailInset = 20.dp

private val ReferenceSlotCorner = 12.dp
private val ReferenceSlotShape = RoundedCornerShape(ReferenceSlotCorner)

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
    maskTools: MaskTools? = null,
    // Secondary replace through the system file API (DocumentsUI), covering Downloads and file managers
    onReplaceFromFiles: (() -> Unit)? = null
) {
    val modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 12.dp)
    val replace: @Composable () -> Unit = {
        TooltipIconButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.content_description_replace_source), onReplace)
    }
    val replaceFromFiles: (@Composable () -> Unit)? = onReplaceFromFiles?.let {
        { TooltipIconButton(Icons.Rounded.FolderOpen, stringResource(R.string.content_description_replace_source_from_files), it) }
    }

    // The toolbar's container in the same role as the frames of thumbnails over the preview and empty reference slots
    val colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainer
    )
    if (maskTools == null) {
        HorizontalFloatingToolbar(expanded = true, modifier = modifier, colors = colors) {
            replace()
            replaceFromFiles?.invoke()
            TooltipIconButton(Icons.Rounded.HideImage, stringResource(R.string.content_description_remove_source), onRemove)
        }
    } else {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = modifier,
            colors = colors,
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
            replaceFromFiles?.invoke()
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
 * Empty source: the preview itself is the picker – a dashed outline and one large button.
 */
@Composable
fun EmptySourcePicker(onPick: () -> Unit, modifier: Modifier = Modifier, onPickFromFiles: (() -> Unit)? = null) {
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
                // The drawing (about 5/6 of the icon) matches the height of the logo in the empty results state
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.button_pick_source_image))
                }
                if (onPickFromFiles != null) {
                    OutlinedButton(onClick = onPickFromFiles, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.button_pick_source_image_from_files))
                    }
                }
            }
        }
    }
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
            .padding(PreviewThumbnailInset),
        // Against the edge, as the result thumbnail on the other side
        horizontalAlignment = Alignment.End,
        // Each slot's frame reaches 3 dp beyond it, so the frames stay 10 dp apart
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        slots.forEachIndexed { index, slot -> ReferenceSlotItem(slot, index + 1) }
        // Centred under the slots, even when wider than they are (then reaching out equally on both sides)
        Box(Modifier.width(ReferenceSlotSize), contentAlignment = Alignment.Center) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.wrapContentWidth(unbounded = true)
            ) {
                Text(
                    text = stringResource(R.string.label_reference_count, slots.count { it.image != null }, slots.size),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
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
                // Set apart from the image under it; empty, filled with the same colour (that of the source toolbar)
                .thumbnailFrame(ReferenceSlotCorner, MaterialTheme.colorScheme.surfaceContainer)
                .clip(ReferenceSlotShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
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
