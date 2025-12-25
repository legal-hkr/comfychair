package sh.hnet.comfychair.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(
    viewModel: WorkflowEditorViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.workflowName.ifEmpty { stringResource(R.string.workflow_editor_title) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isFieldMappingMode) {
                            viewModel.cancelMapping()
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back)
                        )
                    }
                },
                actions = {
                    if (uiState.isFieldMappingMode) {
                        IconButton(onClick = { viewModel.cancelMapping() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.button_cancel)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.confirmMapping() },
                            enabled = uiState.canConfirmMapping
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.button_confirm),
                                tint = if (uiState.canConfirmMapping)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                    LaunchedEffect(uiState.offset, uiState.scale) {
                        if (!uiState.isEditingNode && !isAnimatingTransition.value) {
                            animatedOffset.snapTo(uiState.offset)
                            animatedScale.snapTo(uiState.scale)
                        }
                    }

                    // Display logic:
                    // - Normal mode (no transition): raw values for responsive gestures
                    // - Editing mode or during transition: animated values for smooth animation
                    val useAnimatedValues = uiState.isEditingNode || isStartingTransition || isAnimatingTransition.value
                    val displayScale = if (useAnimatedValues) animatedScale.value else uiState.scale
                    val displayOffset = if (useAnimatedValues) animatedOffset.value else uiState.offset

                    // Graph canvas with optional side sheet
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Canvas takes remaining space
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
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

            // Bottom controls
            AnimatedVisibility(
                visible = uiState.graph != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (uiState.isFieldMappingMode) {
                    MappingBottomControls(
                        scale = uiState.scale,
                        onZoomIn = { viewModel.zoomIn() },
                        onZoomOut = { viewModel.zoomOut() },
                        onResetZoom = { viewModel.resetView() }
                    )
                } else {
                    BottomControls(
                        scale = uiState.scale,
                        showHighlight = uiState.showTemplateHighlight,
                        onZoomIn = { viewModel.zoomIn() },
                        onZoomOut = { viewModel.zoomOut() },
                        onResetZoom = { viewModel.resetView() },
                        onToggleHighlight = { viewModel.toggleTemplateHighlight() }
                    )
                }
            }
        }
    }
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

@Composable
private fun BottomControls(
    scale: Float,
    showHighlight: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onToggleHighlight: () -> Unit
) {
    val highlightColor = MaterialTheme.colorScheme.tertiary

    Surface(
        modifier = Modifier
            .padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zoom out button
            FilledIconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_out)
                )
            }

            // Zoom percentage
            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Zoom in button
            FilledIconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_in)
                )
            }

            // Fit to screen button
            FilledIconButton(
                onClick = onResetZoom,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.Default.FitScreen,
                    contentDescription = stringResource(R.string.workflow_editor_reset_zoom)
                )
            }

            // Template highlight toggle
            FilledIconButton(
                onClick = onToggleHighlight,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (showHighlight) highlightColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (showHighlight) highlightColor else MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    if (showHighlight) Icons.Default.Highlight else Icons.Default.HighlightOff,
                    contentDescription = stringResource(R.string.workflow_editor_highlight_templates)
                )
            }
        }
    }
}

@Composable
private fun MappingBottomControls(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zoom controls
            FilledIconButton(
                onClick = onZoomOut,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.Remove, contentDescription = null)
            }

            Text(
                text = "${(scale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium
            )

            FilledIconButton(
                onClick = onZoomIn,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }

            FilledIconButton(
                onClick = onResetZoom,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(Icons.Default.FitScreen, contentDescription = null)
            }
        }
    }
}
