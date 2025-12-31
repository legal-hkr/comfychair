package sh.hnet.comfychair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MaskEditorStateHolder
import sh.hnet.comfychair.ui.components.ImageScaleMode
import sh.hnet.comfychair.ui.components.MaskPaintCanvas

/**
 * Fullscreen screen for editing the inpainting mask with floating toolbar and FAB.
 * Uses MaskEditorStateHolder to communicate with the calling screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaskEditorScreen(
    onClose: () -> Unit
) {
    // Get state from holder - these are observable Compose states
    val sourceImage = MaskEditorStateHolder.sourceImage ?: return
    val maskPaths = MaskEditorStateHolder.maskPaths
    val brushSize = MaskEditorStateHolder.brushSize
    val eraserMode = MaskEditorStateHolder.isEraserMode

    var showBrushSizePopup by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Canvas fills entire screen
        MaskPaintCanvas(
            sourceImage = sourceImage,
            maskPaths = maskPaths,
            brushSize = brushSize,
            isEraserMode = eraserMode,
            onPathAdded = { path, isEraser, size ->
                MaskEditorStateHolder.onPathAdded?.invoke(path, isEraser, size)
            },
            scaleMode = ImageScaleMode.FIT_CENTER,
            modifier = Modifier.fillMaxSize()
        )

        // Floating Toolbar (bottom-center)
        HorizontalFloatingToolbar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            expanded = true,
            colors = FloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                toolbarContentColor = MaterialTheme.colorScheme.onSurface,
                fabContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                fabContentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Paint/Eraser Connected Button Group
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    // Paint button
                    ToggleButton(
                        checked = !eraserMode,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                MaskEditorStateHolder.updateEraserMode(false)
                                MaskEditorStateHolder.onEraserModeChange?.invoke(false)
                            }
                        },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Brush,
                            contentDescription = stringResource(R.string.paint_mode)
                        )
                    }

                    // Eraser button
                    ToggleButton(
                        checked = eraserMode,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                MaskEditorStateHolder.updateEraserMode(true)
                                MaskEditorStateHolder.onEraserModeChange?.invoke(true)
                            }
                        },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ink_eraser_24px),
                            contentDescription = stringResource(R.string.eraser_mode)
                        )
                    }
                }

                // Brush Size Dropdown
                Box {
                    IconButton(onClick = { showBrushSizePopup = true }) {
                        Icon(
                            imageVector = Icons.Default.LineWeight,
                            contentDescription = stringResource(R.string.brush_size)
                        )
                    }

                    DropdownMenu(
                        expanded = showBrushSizePopup,
                        onDismissRequest = { showBrushSizePopup = false }
                    ) {
                        Column(modifier = Modifier.padding(16.dp).width(200.dp)) {
                            Text(
                                text = "${stringResource(R.string.brush_size)}: ${brushSize.toInt()}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = brushSize,
                                onValueChange = {
                                    MaskEditorStateHolder.updateBrushSize(it)
                                    MaskEditorStateHolder.onBrushSizeChange?.invoke(it)
                                },
                                valueRange = 20f..200f
                            )
                        }
                    }
                }

                // Vertical Divider
                VerticalDivider(modifier = Modifier.padding(horizontal = 8.dp))

                // More menu (Clear and Invert)
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options)
                        )
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_mask)) },
                            onClick = {
                                MaskEditorStateHolder.onClearMask?.invoke()
                                showMoreMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.invert_mask)) },
                            onClick = {
                                MaskEditorStateHolder.onInvertMask?.invoke()
                                showMoreMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.InvertColors,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }

        // FAB (bottom-right) - Back button
        FloatingActionButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 32.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.content_description_back)
            )
        }
    }
}
