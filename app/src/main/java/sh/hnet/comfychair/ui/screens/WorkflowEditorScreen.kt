package sh.hnet.comfychair.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.NodeAttributeSideSheet
import sh.hnet.comfychair.ui.components.WorkflowGraphCanvas
import sh.hnet.comfychair.viewmodel.WorkflowEditorViewModel
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.WorkflowMappingState

/**
 * Main screen for the workflow editor
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkflowEditorScreen(
    viewModel: WorkflowEditorViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current

    // Flag to trigger toolbar-initiated zoom animation (vs instant gesture updates)
    val shouldAnimateZoomChange = remember { mutableStateOf(false) }
    // Flag to track if a zoom animation is currently in progress
    val isAnimatingZoom = remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            uiState.errorMessage != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.workflow_editor_error_loading),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            uiState.graph != null -> {
                // Animated values for smooth transitions
                val animatedOffset = remember { Animatable(uiState.offset, Offset.VectorConverter) }
                val animatedScale = remember { Animatable(uiState.scale) }

                // Track previous state - refs are updated INSIDE LaunchedEffect, not during composition
                // This allows synchronous transition detection during composition
                val wasEditingRef = remember { mutableStateOf(uiState.isEditingNode) }
                val previousNodeIdRef = remember { mutableStateOf<String?>(null) }
                val isAnimatingTransition = remember { mutableStateOf(false) }

                // Detect transition SYNCHRONOUSLY during composition by comparing current state
                // with refs that haven't been updated yet (they're updated in LaunchedEffect)
                val isStartingTransition = wasEditingRef.value != uiState.isEditingNode ||
                    (uiState.isEditingNode && previousNodeIdRef.value != uiState.selectedNodeForEditing?.id)

                // Animate during mode/node transitions
                LaunchedEffect(uiState.isEditingNode, uiState.selectedNodeForEditing?.id) {
                    val wasEditing = wasEditingRef.value
                    val previousNodeId = previousNodeIdRef.value
                    val isEditing = uiState.isEditingNode
                    val editingModeChanged = wasEditing != isEditing
                    val editingNodeChanged = isEditing && previousNodeId != uiState.selectedNodeForEditing?.id

                    if (editingModeChanged || editingNodeChanged) {
                        isAnimatingTransition.value = true

                        // Stop any running animations
                        animatedOffset.stop()
                        animatedScale.stop()

                        // Animate to new position
                        launch {
                            animatedOffset.animateTo(uiState.offset, tween(250))
                        }
                        launch {
                            animatedScale.animateTo(uiState.scale, tween(250))
                            isAnimatingTransition.value = false
                        }
                    }

                    // Update refs AFTER animation starts - this is critical for synchronous detection
                    wasEditingRef.value = isEditing
                    previousNodeIdRef.value = uiState.selectedNodeForEditing?.id
                }

                // Keep animated values synced in normal mode (for smooth transitions when entering editing)
                // Also handles animated zoom changes from toolbar buttons
                LaunchedEffect(uiState.offset, uiState.scale) {
                    if (!uiState.isEditingNode && !isAnimatingTransition.value) {
                        if (shouldAnimateZoomChange.value) {
                            // Toolbar button pressed - animate the change
                            shouldAnimateZoomChange.value = false
                            isAnimatingZoom.value = true
                            launch { animatedOffset.animateTo(uiState.offset, tween(250)) }
                            launch {
                                animatedScale.animateTo(uiState.scale, tween(250))
                                isAnimatingZoom.value = false
                            }
                        } else if (!isAnimatingZoom.value) {
                            // Gesture (and not during zoom animation) - snap immediately for responsiveness
                            animatedOffset.snapTo(uiState.offset)
                            animatedScale.snapTo(uiState.scale)
                        }
                    }
                }

                // Safety: reset shouldAnimateZoomChange if no state change occurred
                // (e.g., reset zoom pressed when already at default zoom)
                LaunchedEffect(shouldAnimateZoomChange.value) {
                    if (shouldAnimateZoomChange.value) {
                        kotlinx.coroutines.delay(50) // Short delay to allow normal processing
                        if (shouldAnimateZoomChange.value) {
                            // No state change happened, reset the flag
                            shouldAnimateZoomChange.value = false
                        }
                    }
                }

                // Display logic:
                // - Normal mode (no transition): raw values for responsive gestures
                // - Editing mode, during transition, or during zoom animation: animated values
                // Note: shouldAnimateZoomChange catches the FIRST frame (before LaunchedEffect sets isAnimatingZoom)
                val useAnimatedValues = uiState.isEditingNode || isStartingTransition ||
                    isAnimatingTransition.value || isAnimatingZoom.value || shouldAnimateZoomChange.value
                val displayScale = if (useAnimatedValues) animatedScale.value else uiState.scale
                val displayOffset = if (useAnimatedValues) animatedOffset.value else uiState.offset

                // Graph canvas with optional side sheet
                Row(modifier = Modifier.fillMaxSize()) {
                    // Canvas takes remaining space with padding for status bar and small bottom margin
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(bottom = 16.dp)
                    ) {
                        WorkflowGraphCanvas(
                            graph = uiState.graph!!,
                            scale = displayScale,
                            offset = displayOffset,
                            showTemplateHighlight = uiState.showTemplateHighlight && !uiState.isFieldMappingMode,
                            isFieldMappingMode = uiState.isFieldMappingMode,
                            highlightedNodeIds = uiState.highlightedNodeIds,
                            mappingState = uiState.mappingState,
                            selectedFieldKey = uiState.selectedFieldKey,
                            editingNodeId = uiState.selectedNodeForEditing?.id,
                            nodeAttributeEdits = uiState.nodeAttributeEdits,
                            editableInputNames = uiState.editableInputNames,
                            enableManualTransform = !uiState.isEditingNode,
                            onNodeTapped = { nodeId ->
                                if (uiState.isFieldMappingMode) {
                                    viewModel.onNodeTapped(nodeId)
                                } else {
                                    // Find the node and open editor
                                    val node = uiState.graph?.nodes?.find { it.id == nodeId }
                                    if (node != null) {
                                        viewModel.onNodeTappedForEditing(node)
                                    }
                                }
                            },
                            onTapOutsideNodes = {
                                // Close side sheet when tapping outside nodes (only in editing mode)
                                if (uiState.isEditingNode) {
                                    viewModel.dismissNodeEditor()
                                }
                            },
                            onTransform = { scale, offset ->
                                viewModel.onTransform(scale, offset)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { size ->
                                    with(density) {
                                        viewModel.setCanvasSize(
                                            size.width.toFloat(),
                                            size.height.toFloat()
                                        )
                                    }
                                }
                        )
                    }

                    // Side sheet for node attribute editing
                    AnimatedVisibility(
                        visible = uiState.isEditingNode && uiState.selectedNodeForEditing != null,
                        enter = slideInHorizontally(initialOffsetX = { it }),
                        exit = slideOutHorizontally(targetOffsetX = { it })
                    ) {
                        uiState.selectedNodeForEditing?.let { node ->
                            NodeAttributeSideSheet(
                                node = node,
                                nodeDefinition = uiState.nodeDefinitions[node.classType],
                                currentEdits = uiState.nodeAttributeEdits[node.id] ?: emptyMap(),
                                onEditChange = { inputName, value ->
                                    viewModel.updateNodeAttribute(node.id, inputName, value)
                                },
                                onResetToDefault = { inputName ->
                                    viewModel.resetNodeAttribute(node.id, inputName)
                                },
                                onDismiss = { viewModel.dismissNodeEditor() },
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.6f)
                            )
                        }
                    }
                }
            }
        }

        // Field mapping panel (only in mapping mode)
        if (uiState.isFieldMappingMode && uiState.mappingState != null) {
            FieldMappingPanel(
                mappingState = uiState.mappingState!!,
                selectedFieldKey = uiState.selectedFieldKey,
                onFieldSelected = { viewModel.selectFieldForMapping(it) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 88.dp, end = 16.dp)
            )
        }

        // Bottom padding for toolbar and FAB alignment
        val toolbarBottomPadding = 32.dp
        val fabBottomPadding = toolbarBottomPadding + 4.dp // FAB is 8dp smaller, so offset by 4dp

        // Floating toolbar - centered at bottom, hides when side sheet opens
        AnimatedVisibility(
            visible = !uiState.isEditingNode,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = toolbarBottomPadding)
        ) {
            WorkflowEditorFloatingToolbar(
                expanded = true,
                isFieldMappingMode = uiState.isFieldMappingMode,
                canConfirmMapping = uiState.canConfirmMapping,
                scale = uiState.scale,
                showHighlight = uiState.showTemplateHighlight,
                onConfirmMapping = { viewModel.confirmMapping() },
                onZoomIn = {
                    shouldAnimateZoomChange.value = true
                    viewModel.zoomIn()
                },
                onZoomOut = {
                    shouldAnimateZoomChange.value = true
                    viewModel.zoomOut()
                },
                onResetZoom = {
                    shouldAnimateZoomChange.value = true
                    viewModel.resetView()
                },
                onToggleHighlight = { viewModel.toggleTemplateHighlight() }
            )
        }

        // FAB - right side normally, moves to canvas center when side sheet opens
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPadding)
        ) {
            // When editing, FAB moves left to be 16dp from the side sheet edge
            // Side sheet takes 60% on the right, so FAB should be at (40% - 16dp - fabRadius)
            // Currently FAB is at (100% - 16dp - fabRadius), so offset = 60% of width
            val sideSheetWidthFraction = 0.6f
            val fabOffset = if (uiState.isEditingNode) {
                maxWidth * sideSheetWidthFraction
            } else {
                0.dp
            }
            val animatedFabOffset by animateDpAsState(
                targetValue = fabOffset,
                animationSpec = tween(durationMillis = 250),
                label = "fabOffset"
            )

            FloatingActionButton(
                onClick = {
                    when {
                        uiState.isEditingNode -> viewModel.dismissNodeEditor()
                        uiState.isFieldMappingMode -> viewModel.cancelMapping()
                        else -> onClose()
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.offset(x = -animatedFabOffset)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.content_description_back)
                )
            }
        }
    }
}

/**
 * Floating toolbar for the workflow editor with zoom controls and mode-specific actions.
 * This toolbar does NOT include the FAB - the FAB is rendered separately to maintain its size.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkflowEditorFloatingToolbar(
    expanded: Boolean,
    isFieldMappingMode: Boolean,
    canConfirmMapping: Boolean,
    scale: Float,
    showHighlight: Boolean,
    onConfirmMapping: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onToggleHighlight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val highlightColor = MaterialTheme.colorScheme.tertiary

    // Custom colors that properly follow the system theme
    val toolbarColors = FloatingToolbarColors(
        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        toolbarContentColor = MaterialTheme.colorScheme.onSurface,
        fabContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        fabContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )

    HorizontalFloatingToolbar(
        expanded = expanded,
        modifier = modifier,
        colors = toolbarColors,
        content = {
            // Zoom out
            IconButton(onClick = onZoomOut) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_out)
                )
            }

            // Zoom percentage
            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = 4.dp)
            )

            // Zoom in
            IconButton(onClick = onZoomIn) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_in)
                )
            }

            // Fit to screen
            IconButton(onClick = onResetZoom) {
                Icon(
                    Icons.Default.FitScreen,
                    contentDescription = stringResource(R.string.workflow_editor_reset_zoom)
                )
            }

            if (isFieldMappingMode) {
                // Confirm mapping button
                IconButton(
                    onClick = onConfirmMapping,
                    enabled = canConfirmMapping
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.button_confirm),
                        tint = if (canConfirmMapping)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            } else {
                // Template highlight toggle
                IconButton(onClick = onToggleHighlight) {
                    Icon(
                        if (showHighlight) Icons.Default.Highlight else Icons.Default.HighlightOff,
                        contentDescription = stringResource(R.string.workflow_editor_highlight_templates),
                        tint = if (showHighlight) highlightColor else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    )
}

@Composable
private fun FieldMappingPanel(
    mappingState: WorkflowMappingState,
    selectedFieldKey: String?,
    onFieldSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.field_mapping_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                items(
                    items = mappingState.fieldMappings,
                    key = { "${it.field.fieldKey}_${it.selectedCandidateIndex}" }
                ) { fieldMapping ->
                    FieldMappingRow(
                        fieldMapping = fieldMapping,
                        isSelected = fieldMapping.field.fieldKey == selectedFieldKey,
                        onSelect = {
                            val newKey = if (fieldMapping.field.fieldKey == selectedFieldKey) null else fieldMapping.field.fieldKey
                            onFieldSelected(newKey)
                        }
                    )
                }
            }

            if (mappingState.fieldMappings.any { it.hasMultipleCandidates }) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tap_node_to_change),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun FieldMappingRow(
    fieldMapping: FieldMappingState,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fieldMapping.field.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            when {
                fieldMapping.isMapped -> {
                    val selectedCandidate = fieldMapping.selectedCandidate
                    Text(
                        text = "${selectedCandidate?.nodeName} (${selectedCandidate?.classType})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                fieldMapping.needsRemapping -> {
                    Text(
                        text = stringResource(R.string.needs_remapping),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.no_matching_nodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (fieldMapping.isMapped) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

