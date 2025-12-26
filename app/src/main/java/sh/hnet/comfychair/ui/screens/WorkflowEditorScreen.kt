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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import sh.hnet.comfychair.ui.components.NodeBrowserBottomSheet
import sh.hnet.comfychair.ui.components.WorkflowGraphCanvas
import sh.hnet.comfychair.viewmodel.WorkflowEditorViewModel
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.WorkflowMappingState
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalContext
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType

/**
 * Main screen for the workflow editor
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(
    viewModel: WorkflowEditorViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current
    val context = LocalContext.current

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
                            isFieldMappingMode = uiState.isFieldMappingMode,
                            highlightedNodeIds = uiState.highlightedNodeIds,
                            mappingState = uiState.mappingState,
                            selectedFieldKey = uiState.selectedFieldKey,
                            editingNodeId = uiState.selectedNodeForEditing?.id,
                            nodeAttributeEdits = uiState.nodeAttributeEdits,
                            editableInputNames = uiState.editableInputNames,
                            enableManualTransform = !uiState.isEditingNode,
                            isEditMode = uiState.isEditMode,
                            selectedNodeIds = uiState.selectedNodeIds,
                            connectionModeState = uiState.connectionModeState,
                            onNodeTapped = { nodeId ->
                                when {
                                    uiState.isFieldMappingMode -> {
                                        viewModel.onNodeTapped(nodeId)
                                    }
                                    uiState.connectionModeState != null -> {
                                        // In connection mode, tapping a node exits connection mode
                                        viewModel.exitConnectionMode()
                                    }
                                    uiState.isEditMode -> {
                                        // In edit mode, toggle node selection
                                        viewModel.toggleNodeSelection(nodeId)
                                    }
                                    else -> {
                                        // Normal mode - open node attribute editor
                                        val node = uiState.graph?.nodes?.find { it.id == nodeId }
                                        if (node != null) {
                                            viewModel.onNodeTappedForEditing(node)
                                        }
                                    }
                                }
                            },
                            onTapOutsideNodes = {
                                when {
                                    uiState.connectionModeState != null -> {
                                        // Exit connection mode when tapping outside
                                        viewModel.exitConnectionMode()
                                    }
                                    uiState.isEditingNode -> {
                                        // Close side sheet when tapping outside nodes
                                        viewModel.dismissNodeEditor()
                                    }
                                    uiState.isEditMode -> {
                                        // Clear selection when tapping outside nodes in edit mode
                                        viewModel.clearSelection()
                                    }
                                }
                            },
                            onOutputSlotTapped = { outputSlot ->
                                // Toggle connection mode on output slot tap
                                if (uiState.connectionModeState?.sourceOutputSlot?.nodeId == outputSlot.nodeId) {
                                    // Tapping the same output slot exits connection mode
                                    viewModel.exitConnectionMode()
                                } else {
                                    // Enter connection mode with this output
                                    viewModel.enterConnectionMode(outputSlot)
                                }
                            },
                            onInputSlotTapped = { inputSlot ->
                                // Connect to this input and exit connection mode
                                viewModel.connectToInput(inputSlot)
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
                    .padding(start = 16.dp, bottom = 104.dp, end = 16.dp)
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
                isEditMode = uiState.isEditMode,
                isCreateMode = uiState.isCreateMode,
                viewingWorkflowIsBuiltIn = uiState.viewingWorkflowIsBuiltIn,
                hasSelection = uiState.selectedNodeIds.isNotEmpty(),
                scale = uiState.scale,
                onConfirmMapping = {
                    // If we have a workflow ID (existing or in edit mode), save directly
                    // Otherwise (upload mode), return mappings to calling activity
                    if (uiState.editingWorkflowId != null || uiState.isCreateMode) {
                        viewModel.confirmMappingAndSave(context, WorkflowManager(context))
                    } else {
                        viewModel.confirmMapping()
                    }
                },
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
                onEnterEditMode = { viewModel.enterEditMode() },
                onExitEditMode = { viewModel.handleExitEditModeWithConfirmation() },
                onDeleteSelected = { viewModel.deleteSelectedNodes() },
                onDuplicateSelected = { viewModel.duplicateSelectedNodes() },
                onAddNode = {
                    // Calculate center of canvas in graph coordinates for node insertion
                    val centerX = (uiState.graphBounds.minX + uiState.graphBounds.maxX) / 2
                    val centerY = (uiState.graphBounds.minY + uiState.graphBounds.maxY) / 2
                    viewModel.showNodeBrowser(Offset(centerX, centerY))
                },
                onDone = { viewModel.showSaveDialog() }
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
                        uiState.isEditMode -> viewModel.handleEditExistingModeClose()
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

        // Node browser bottom sheet
        if (uiState.showNodeBrowser) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()

            NodeBrowserBottomSheet(
                nodeTypesByCategory = viewModel.getNodeTypesByCategory(),
                sheetState = sheetState,
                onNodeTypeSelected = { nodeType ->
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        viewModel.addNode(nodeType)
                    }
                },
                onDismiss = {
                    viewModel.hideNodeBrowser()
                }
            )
        }

        // Create mode dialogs

        // Discard confirmation dialog
        if (uiState.showDiscardConfirmation) {
            DiscardConfirmationDialog(
                onConfirm = { viewModel.confirmDiscard() },
                onDismiss = { viewModel.dismissDiscardConfirmation() }
            )
        }

        // Save new workflow dialog
        if (uiState.showSaveDialog) {
            SaveNewWorkflowDialog(
                selectedType = uiState.saveDialogSelectedType,
                onTypeSelected = { viewModel.onSaveDialogTypeSelected(it) },
                isTypeDropdownExpanded = uiState.saveDialogTypeDropdownExpanded,
                onToggleTypeDropdown = { viewModel.onSaveDialogToggleTypeDropdown() },
                name = uiState.saveDialogName,
                onNameChange = { viewModel.onSaveDialogNameChange(it) },
                nameError = uiState.saveDialogNameError,
                description = uiState.saveDialogDescription,
                onDescriptionChange = { viewModel.onSaveDialogDescriptionChange(it) },
                descriptionError = uiState.saveDialogDescriptionError,
                isValidating = uiState.isSaveValidating,
                onConfirm = {
                    viewModel.proceedWithSave(
                        context = context,
                        workflowManager = WorkflowManager(context)
                    )
                },
                onDismiss = { viewModel.cancelSaveDialog() }
            )
        }

        // Missing nodes dialog
        if (uiState.showMissingNodesDialog) {
            MissingNodesDialog(
                missingNodes = uiState.missingNodes,
                onDismiss = { viewModel.dismissMissingNodesDialog() }
            )
        }

        // Missing fields dialog
        if (uiState.showMissingFieldsDialog) {
            MissingFieldsDialog(
                missingFields = uiState.missingFields,
                onDismiss = { viewModel.dismissMissingFieldsDialog() }
            )
        }

        // Duplicate name dialog
        if (uiState.showDuplicateNameDialog) {
            DuplicateNameDialog(
                onDismiss = { viewModel.dismissDuplicateNameDialog() }
            )
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
    isEditMode: Boolean,
    isCreateMode: Boolean,
    viewingWorkflowIsBuiltIn: Boolean,
    hasSelection: Boolean,
    scale: Float,
    onConfirmMapping: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onDeleteSelected: () -> Unit,
    onDuplicateSelected: () -> Unit,
    onAddNode: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
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
            // Edit mode controls
            if (isEditMode) {
                // Wrap in Row with IntrinsicSize.Min so VerticalDivider sizes correctly
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Delete (enabled when nodes selected)
                    IconButton(
                        onClick = onDeleteSelected,
                        enabled = hasSelection
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.workflow_editor_delete_node),
                            tint = if (hasSelection)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Duplicate (enabled when nodes selected)
                    IconButton(
                        onClick = onDuplicateSelected,
                        enabled = hasSelection
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.workflow_editor_duplicate_node),
                            tint = if (hasSelection)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    // Add node
                    IconButton(onClick = onAddNode) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.workflow_editor_add_node)
                        )
                    }

                    // Divider between node actions (Delete/Clone/Add) and workflow actions (Cancel/Done)
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Cancel / Exit edit mode
                    IconButton(onClick = onExitEditMode) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.workflow_editor_exit_edit_mode),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    // Done button
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.workflow_editor_done),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                // Normal mode controls

                // Zoom out
                IconButton(onClick = onZoomOut) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = stringResource(R.string.workflow_editor_zoom_out)
                    )
                }

                // Zoom percentage
                Text(
                    text = stringResource(R.string.workflow_editor_zoom_percentage, (scale * 100).toInt()),
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

                // Confirm mapping button (only in field mapping mode)
                if (isFieldMappingMode) {
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
                }

                // Enter edit mode button (only when not in field mapping mode and not built-in)
                if (!isFieldMappingMode && !viewingWorkflowIsBuiltIn) {
                    IconButton(onClick = onEnterEditMode) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = stringResource(R.string.workflow_editor_enter_edit_mode)
                        )
                    }
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
                        text = stringResource(
                            R.string.workflow_editor_node_info,
                            selectedCandidate?.nodeName ?: "",
                            selectedCandidate?.classType ?: ""
                        ),
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
private fun DiscardConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_editor_discard_title)) },
        text = { Text(stringResource(R.string.workflow_editor_discard_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.button_discard),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveNewWorkflowDialog(
    selectedType: WorkflowType?,
    onTypeSelected: (WorkflowType) -> Unit,
    isTypeDropdownExpanded: Boolean,
    onToggleTypeDropdown: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    isValidating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val allTypes = listOf(
        WorkflowType.TTI_CHECKPOINT to stringResource(R.string.workflow_section_tti_checkpoint),
        WorkflowType.TTI_UNET to stringResource(R.string.workflow_section_tti_unet),
        WorkflowType.ITE_UNET to stringResource(R.string.workflow_section_ite_unet),
        WorkflowType.ITI_CHECKPOINT to stringResource(R.string.workflow_section_iti_checkpoint),
        WorkflowType.ITI_UNET to stringResource(R.string.workflow_section_iti_unet),
        WorkflowType.TTV_UNET to stringResource(R.string.workflow_section_ttv_unet),
        WorkflowType.ITV_UNET to stringResource(R.string.workflow_section_itv_unet)
    )

    val selectedTypeName = allTypes.find { it.first == selectedType }?.second ?: ""

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text(stringResource(R.string.save_workflow_title)) },
        text = {
            Column {
                // Type dropdown
                Text(
                    text = stringResource(R.string.workflow_type_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = isTypeDropdownExpanded,
                    onExpandedChange = { if (!isValidating) onToggleTypeDropdown() }
                ) {
                    OutlinedTextField(
                        value = selectedTypeName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTypeDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        enabled = !isValidating
                    )
                    ExposedDropdownMenu(
                        expanded = isTypeDropdownExpanded,
                        onDismissRequest = onToggleTypeDropdown
                    ) {
                        allTypes.forEach { (type, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = { onTypeSelected(type) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.workflow_name_label)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.workflow_description_label)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                if (isValidating) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.validating_workflow))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedType != null && name.isNotBlank() && !isValidating
            ) {
                Text(stringResource(R.string.button_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isValidating) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun MissingNodesDialog(
    missingNodes: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.missing_nodes_title)) },
        text = {
            Column {
                Text(stringResource(R.string.missing_nodes_message))
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(missingNodes) { node ->
                        Text(
                            text = "- $node",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

@Composable
private fun MissingFieldsDialog(
    missingFields: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.missing_fields_title)) },
        text = {
            Column {
                Text(stringResource(R.string.missing_fields_message))
                Spacer(modifier = Modifier.height(8.dp))
                missingFields.forEach { field ->
                    Text(
                        text = "- $field",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

@Composable
private fun DuplicateNameDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_name_title)) },
        text = { Text(stringResource(R.string.duplicate_name_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_ok))
            }
        }
    )
}

