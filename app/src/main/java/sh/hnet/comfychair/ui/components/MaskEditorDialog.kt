package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.viewmodel.MaskPathData

/**
 * Fullscreen dialog for editing the inpainting mask with brush size slider
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaskEditorDialog(
    sourceImage: Bitmap,
    maskPaths: List<MaskPathData>,
    initialBrushSize: Float,
    isEraserMode: Boolean,
    onPathAdded: (AndroidPath, Boolean, Float) -> Unit,
    onClearMask: () -> Unit,
    onInvertMask: () -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onEraserModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var brushSize by remember { mutableFloatStateOf(initialBrushSize) }
    var eraserMode by remember { mutableStateOf(isEraserMode) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top bar
            TopAppBar(
                title = { Text(stringResource(R.string.edit_mask)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
                actions = {
                    // Clear mask button
                    IconButton(onClick = onClearMask) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_mask))
                    }
                    // Invert mask button
                    IconButton(onClick = onInvertMask) {
                        Icon(Icons.Default.InvertColors, contentDescription = stringResource(R.string.invert_mask))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Canvas area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                MaskPaintCanvas(
                    sourceImage = sourceImage,
                    maskPaths = maskPaths,
                    brushSize = brushSize,
                    isEraserMode = eraserMode,
                    onPathAdded = { path, isEraser, size ->
                        onPathAdded(path, isEraser, size)
                    },
                    scaleMode = ImageScaleMode.FIT_CENTER,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                // Mode toggle - MD3 Segmented Button
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = !eraserMode,
                        onClick = {
                            eraserMode = false
                            onEraserModeChange(false)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = !eraserMode) {
                                Icon(
                                    imageVector = Icons.Default.Brush,
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.paint_mode))
                    }
                    SegmentedButton(
                        selected = eraserMode,
                        onClick = {
                            eraserMode = true
                            onEraserModeChange(true)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = eraserMode) {
                                Icon(
                                    painter = painterResource(R.drawable.ink_eraser_24px),
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.eraser_mode))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Brush size slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LineWeight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = brushSize,
                        onValueChange = {
                            brushSize = it
                            onBrushSizeChange(it)
                        },
                        valueRange = 20f..200f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${brushSize.toInt()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
