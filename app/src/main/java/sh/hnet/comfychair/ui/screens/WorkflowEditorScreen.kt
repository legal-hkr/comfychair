@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.NodeAttributeSideSheet
import sh.hnet.comfychair.ui.components.NodeBrowserBottomSheet
import sh.hnet.comfychair.ui.components.WorkflowGraphCanvas
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.workflow.EditorEditActions
import sh.hnet.comfychair.ui.components.workflow.EditorMode
import sh.hnet.comfychair.ui.components.workflow.EditorStatus
import sh.hnet.comfychair.ui.components.workflow.EditorToolbar
import sh.hnet.comfychair.ui.components.workflow.EditorToolbarActions
import sh.hnet.comfychair.ui.components.workflow.EditorTopRow
import sh.hnet.comfychair.ui.components.workflow.MissingFieldsDialog
import sh.hnet.comfychair.ui.components.workflow.MissingNodesDialog
import sh.hnet.comfychair.ui.components.workflow.WorkflowDetailsDialog
import sh.hnet.comfychair.viewmodel.WorkflowEditorViewModel
import sh.hnet.comfychair.workflow.ConnectionDirection
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState

/**
 * Main screen for the workflow editor
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(
    viewModel: WorkflowEditorViewModel,
    onClose: () -> Unit,
    // Opened only to look at (the workflows page's graph card): no node attributes to change, no way into edit mode
    readOnly: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val density = LocalDensity.current
    val context = LocalContext.current
    val resources = LocalResources.current

    // Show hint Toast when entering edit mode
    val wasEditMode = remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isEditMode) {
        if (uiState.isEditMode && !wasEditMode.value) {
            Toast.makeText(
                context,
                resources.getString(R.string.workflow_editor_edit_mode_hint),
                Toast.LENGTH_SHORT
            ).show()
        }
        wasEditMode.value = uiState.isEditMode
    }

    // Flag to trigger toolbar-initiated zoom animation (vs instant gesture updates)
    val shouldAnimateZoomChange = remember { mutableStateOf(false) }
    // Flag to track if a zoom animation is currently in progress
    val isAnimatingZoom = remember { mutableStateOf(false) }

    // Node rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDialogText by remember { mutableStateOf("") }
    var renamingNodeId by remember { mutableStateOf<String?>(null) }

    // Group rename dialog state
    var showGroupRenameDialog by remember { mutableStateOf(false) }
    var groupRenameDialogText by remember { mutableStateOf("") }
    var renamingGroupId by remember { mutableStateOf<Int?>(null) }

    // Note edit dialog state (combined title + content)
    var showEditNoteDialog by remember { mutableStateOf(false) }
    var editNoteDialogTitle by remember { mutableStateOf("") }
    var editNoteDialogContent by remember { mutableStateOf("") }
    var editingNoteId by remember { mutableStateOf<Int?>(null) }

    // Node browser bottom sheet state (hoisted outside conditional for animation stability)
    val nodeBrowserSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val mode = when {
        uiState.isFieldMappingMode -> EditorMode.MAPPING
        uiState.isEditMode -> EditorMode.EDIT
        readOnly -> EditorMode.GRAPH
        else -> EditorMode.NODE_VALUES
    }
    val connection = uiState.connectionModeState
    val status = when {
        connection != null -> EditorStatus.Connecting(
            connection.sourceSlot.slotType ?: "",
            fromOutput = connection.direction == ConnectionDirection.OUTPUT_TO_INPUT
        )
        mode == EditorMode.MAPPING -> EditorStatus.Mapping
        mode == EditorMode.EDIT -> EditorStatus.Editing(uiState.hasUnsavedChanges, uiState.isManualPlacementMode)
        mode == EditorMode.GRAPH -> EditorStatus.Graph
        else -> EditorStatus.NodeValues(uiState.nodeAttributeEdits.values.sumOf { it.size })
    }

    // Back by mode (spec §10.2): a waiting connection or the values of a node first; structure editing asks before
    // dropping changes. The system Back and the Back button do the same.
    val goBack = {
        when {
            connection != null -> viewModel.exitConnectionMode()
            uiState.isEditingNode -> viewModel.dismissNodeEditor()
            uiState.isFieldMappingMode -> viewModel.cancelMapping()
            uiState.isCreateMode -> viewModel.handleCreateModeClose()
            uiState.isEditMode -> viewModel.handleEditExistingModeClose()
            else -> onClose()
        }
    }
    BackHandler(onBack = goBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when {
            uiState.isLoading -> {
                LoadingIndicator(
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

                // Calculate rendered groups with computed bounds
                val layoutEngine = remember { WorkflowLayoutEngine() }
                val renderedGroups = remember(uiState.graph?.groups, uiState.graph?.nodes, uiState.graph?.notes) {
                    uiState.graph?.let { graph ->
                        layoutEngine.calculateRenderedGroups(graph.groups, graph.nodes, graph.notes)
                    } ?: emptyList()
                }

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
                            enableManualTransform = !uiState.isEditingNode && uiState.draggingNodeId == null && uiState.draggingNoteId == null,
                            isEditMode = uiState.isEditMode,
                            selectedNodeIds = uiState.selectedNodeIds,
                            connectionModeState = uiState.connectionModeState,
                            renderedGroups = renderedGroups,
                            nodeDefinitions = uiState.nodeDefinitions,
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
                                    // Read-only (the workflows page's graph card): nothing to change
                                    readOnly -> Unit
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
                                val connectionState = uiState.connectionModeState
                                when {
                                    // In INPUT_TO_OUTPUT mode: complete connection to this output
                                    connectionState?.direction == ConnectionDirection.INPUT_TO_OUTPUT ->
                                        viewModel.connectToTarget(outputSlot)
                                    // Tapping the same output slot exits connection mode
                                    connectionState?.sourceSlot?.nodeId == outputSlot.nodeId &&
                                            connectionState.sourceSlot.outputIndex == outputSlot.outputIndex ->
                                        viewModel.exitConnectionMode()
                                    // Enter connection mode with this output
                                    else -> viewModel.enterConnectionMode(outputSlot)
                                }
                            },
                            onInputSlotTapped = { inputSlot ->
                                val connectionState = uiState.connectionModeState
                                when {
                                    // In OUTPUT_TO_INPUT mode: complete connection to this input
                                    connectionState?.direction == ConnectionDirection.OUTPUT_TO_INPUT ->
                                        viewModel.connectToTarget(inputSlot)
                                    // Tapping the same input slot exits reverse connection mode
                                    connectionState?.sourceSlot?.nodeId == inputSlot.nodeId &&
                                            connectionState.sourceSlot.slotName == inputSlot.slotName ->
                                        viewModel.exitConnectionMode()
                                    // Enter reverse connection mode with this input
                                    else -> viewModel.enterReverseConnectionMode(inputSlot)
                                }
                            },
                            onRenameNodeTapped = { nodeId ->
                                // Find the node and open rename dialog
                                val node = uiState.graph?.nodes?.find { it.id == nodeId }
                                if (node != null) {
                                    renamingNodeId = nodeId
                                    renameDialogText = node.title
                                    showRenameDialog = true
                                }
                            },
                            onRenameGroupTapped = { groupId ->
                                // Find the group and open rename dialog
                                val group = uiState.graph?.groups?.find { it.id == groupId }
                                if (group != null) {
                                    renamingGroupId = groupId
                                    groupRenameDialogText = group.title
                                    showGroupRenameDialog = true
                                }
                            },
                            notes = uiState.graph?.notes ?: emptyList(),
                            selectedNoteIds = uiState.selectedNoteIds,
                            onNoteTapped = { noteId ->
                                viewModel.toggleNoteSelection(noteId)
                            },
                            onRenameNoteTapped = { noteId ->
                                // Find the note and open combined edit dialog
                                val note = viewModel.getNote(noteId)
                                if (note != null) {
                                    editingNoteId = noteId
                                    editNoteDialogTitle = note.title
                                    editNoteDialogContent = note.content
                                    showEditNoteDialog = true
                                }
                            },
                            onNoteHeightsChanged = {
                                // Note heights were measured during drawing, trigger relayout
                                viewModel.relayoutGraph()
                            },
                            longPressSourceSlot = uiState.longPressSourceSlot,
                            onOutputSlotLongPressed = { slot ->
                                viewModel.startLongPressConnection(slot)
                            },
                            onInputSlotLongPressed = { slot ->
                                viewModel.startLongPressConnection(slot)
                            },
                            // Manual node placement
                            showGrid = uiState.isManualPlacementMode && uiState.isEditMode,
                            isManualPlacementEnabled = uiState.isManualPlacementMode,
                            draggingNodeId = uiState.draggingNodeId,
                            dragOffset = uiState.dragOffset,
                            onNodeDragStart = { nodeId ->
                                viewModel.startNodeDrag(nodeId)
                            },
                            onNodeDrag = { delta ->
                                viewModel.updateNodeDrag(delta)
                            },
                            onNodeDragEnd = {
                                viewModel.endNodeDrag()
                            },
                            onNodeDragCancel = {
                                viewModel.cancelNodeDrag()
                            },
                            justDroppedNodeId = uiState.justDroppedNodeId,
                            onJustDroppedNodeRendered = {
                                viewModel.clearJustDroppedNode()
                            },
                            // Manual note placement
                            draggingNoteId = uiState.draggingNoteId,
                            onNoteDragStart = { noteId ->
                                viewModel.startNoteDrag(noteId)
                            },
                            onNoteDragEnd = {
                                viewModel.endNoteDrag()
                            },
                            justDroppedNoteId = uiState.justDroppedNoteId,
                            onJustDroppedNoteRendered = {
                                viewModel.clearJustDroppedNote()
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

                        EditorTopRow(
                            workflowName = uiState.workflowName,
                            status = status,
                            onBack = goBack,
                            onCancelConnection = { viewModel.exitConnectionMode() },
                            onEditStructure = if (mode == EditorMode.NODE_VALUES && !uiState.viewingWorkflowIsBuiltIn) {
                                { viewModel.enterEditMode() }
                            } else null,
                            selectedCount = if (mode == EditorMode.EDIT) uiState.selectedNodeIds.size + uiState.selectedNoteIds.size else 0,
                            onClearSelection = { viewModel.clearSelection() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
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
                onClearFieldMapping = { viewModel.clearFieldMapping(it) },
                onClearAllMappings = { viewModel.clearAllMappings() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 104.dp, end = 16.dp)
            )
        }

        // One floating toolbar, centred above the navigation bar; hidden while a node's values are open
        AnimatedVisibility(
            visible = !uiState.isEditingNode,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            val zoom = EditorToolbarActions(
                scale = uiState.scale,
                onZoomIn = {
                    shouldAnimateZoomChange.value = true
                    viewModel.zoomIn()
                },
                onZoomOut = {
                    shouldAnimateZoomChange.value = true
                    viewModel.zoomOut()
                },
                onZoom100 = {
                    shouldAnimateZoomChange.value = true
                    viewModel.setZoom100()
                },
                onFit = {
                    shouldAnimateZoomChange.value = true
                    viewModel.resetView()
                }
            )
            EditorToolbar(
                zoom = zoom,
                // While a connection waits, only the zoom group
                edit = if (mode == EditorMode.EDIT && connection == null) EditorEditActions(
                    selectedNodes = uiState.selectedNodeIds.size,
                    selectedItems = uiState.selectedNodeIds.size + uiState.selectedNoteIds.size,
                    canUngroup = viewModel.isSelectedNodeInGroup(),
                    placement = uiState.isManualPlacementMode,
                    onAddNode = {
                        // Centre of the graph in graph coordinates
                        val centerX = (uiState.graphBounds.minX + uiState.graphBounds.maxX) / 2
                        val centerY = (uiState.graphBounds.minY + uiState.graphBounds.maxY) / 2
                        viewModel.showNodeBrowser(Offset(centerX, centerY))
                    },
                    onAddNote = { if (uiState.isManualPlacementMode) viewModel.addNoteManual() else viewModel.addNote() },
                    onCleanUp = { viewModel.relayoutGraph() },
                    onTogglePlacement = { viewModel.toggleManualPlacementMode() },
                    onDuplicate = { viewModel.duplicateSelectedNodes() },
                    onBypass = {
                        uiState.selectedNodeIds.forEach { nodeId ->
                            uiState.graph?.nodes?.find { it.id == nodeId }?.let { viewModel.toggleNodeBypass(nodeId, !it.isBypassed) }
                        }
                    },
                    onGroup = { viewModel.createGroupFromSelection() },
                    onUngroup = { viewModel.ungroupSelectedNode() },
                    onDelete = { viewModel.deleteSelectedNodes() },
                    onSave = { viewModel.showSaveDialog(context) }
                ) else null,
                onFinishMapping = if (mode == EditorMode.MAPPING) {
                    {
                        // A workflow being created or edited saves here; an import returns the mapping to the caller
                        if (uiState.editingWorkflowId != null || uiState.isCreateMode) viewModel.confirmMappingAndSave(context)
                        else viewModel.confirmMapping()
                    }
                } else null,
                canFinishMapping = uiState.canConfirmMapping
            )
        }

        // Node browser bottom sheet
        if (uiState.showNodeBrowser) {
            val scope = rememberCoroutineScope()

            NodeBrowserBottomSheet(
                nodeTypesByCategory = viewModel.getNodeTypesByCategory(),
                sheetState = nodeBrowserSheetState,
                onNodeTypeSelected = { nodeType ->
                    scope.launch {
                        nodeBrowserSheetState.hide()
                    }.invokeOnCompletion {
                        // Use manual placement if enabled, otherwise standard auto-layout
                        if (uiState.isManualPlacementMode) {
                            viewModel.addNodeManual(nodeType)
                        } else {
                            viewModel.addNode(nodeType)
                        }
                    }
                },
                onDismiss = {
                    viewModel.hideNodeBrowser()
                }
            )
        }

        // Filtered node browser for long-press connection mode
        if (uiState.showCompatibleNodeBrowser) {
            val compatibleBrowserSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            val sourceSlot = uiState.longPressSourceSlot

            NodeBrowserBottomSheet(
                nodeTypesByCategory = viewModel.getNodeTypesByCategory(),
                sheetState = compatibleBrowserSheetState,
                onNodeTypeSelected = { nodeType ->
                    scope.launch {
                        compatibleBrowserSheetState.hide()
                    }.invokeOnCompletion {
                        // Call appropriate method based on direction
                        if (sourceSlot?.isOutput == true) {
                            viewModel.selectNodeForConnection(nodeType)
                        } else {
                            viewModel.selectNodeForReverseConnection(nodeType)
                        }
                    }
                },
                onDismiss = {
                    viewModel.cancelLongPressConnection()
                },
                // Filter based on direction: output->input or input->output
                filterToOutputType = if (sourceSlot?.isOutput == true) sourceSlot.slotType else null,
                filterToInputType = if (sourceSlot?.isOutput == false) sourceSlot.slotType else null
            )
        }

        // Input selection dialog (when multiple compatible inputs exist)
        if (uiState.showInputSelectionDialog) {
            val nodeTypeName = uiState.inputSelectionNodeType?.displayName
                ?: uiState.inputSelectionNodeType?.classType ?: ""

            InputSelectionDialog(
                nodeTypeName = nodeTypeName,
                compatibleInputs = uiState.inputSelectionCompatibleInputs,
                onInputSelected = { input ->
                    viewModel.selectInputForConnection(input)
                },
                onDismiss = {
                    viewModel.cancelLongPressConnection()
                }
            )
        }

        // Output selection dialog (when multiple compatible outputs exist - reverse connection)
        if (uiState.showOutputSelectionDialog) {
            val nodeTypeName = uiState.outputSelectionNodeType?.displayName
                ?: uiState.outputSelectionNodeType?.classType ?: ""

            OutputSelectionDialog(
                nodeTypeName = nodeTypeName,
                compatibleOutputs = uiState.outputSelectionCompatibleOutputs,
                onOutputSelected = { output ->
                    viewModel.selectOutputForConnection(output)
                },
                onDismiss = {
                    viewModel.cancelLongPressConnection()
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

        // Disable manual placement confirmation dialog
        if (uiState.showDisableManualPlacementConfirmation) {
            AlertDialog(
                onDismissRequest = { viewModel.cancelDisableManualPlacement() },
                title = { Text(stringResource(R.string.title_disable_manual_placement)) },
                text = { Text(stringResource(R.string.msg_disable_manual_placement)) },
                confirmButton = {
                    Button(onClick = { viewModel.confirmDisableManualPlacement() }) {
                        Text(stringResource(R.string.button_disable))
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { viewModel.cancelDisableManualPlacement() }) {
                        Text(stringResource(R.string.button_cancel))
                    }
                }
            )
        }

        // Node rename dialog
        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = {
                    showRenameDialog = false
                    renamingNodeId = null
                },
                title = { Text(stringResource(R.string.title_node_editor_rename)) },
                text = {
                    OutlinedTextField(
                        value = renameDialogText,
                        onValueChange = { renameDialogText = it },
                        label = { Text(stringResource(R.string.label_node_editor_rename)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val nodeId = renamingNodeId
                            if (nodeId != null && renameDialogText.isNotBlank()) {
                                viewModel.renameNode(nodeId, renameDialogText.trim())
                                showRenameDialog = false
                                renamingNodeId = null
                            }
                        },
                        enabled = renameDialogText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.button_save))
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showRenameDialog = false
                            renamingNodeId = null
                        }
                    ) {
                        Text(stringResource(R.string.button_cancel))
                    }
                }
            )
        }

        // Group rename dialog
        if (showGroupRenameDialog) {
            AlertDialog(
                onDismissRequest = {
                    showGroupRenameDialog = false
                    renamingGroupId = null
                },
                title = { Text(stringResource(R.string.title_workflow_editor_rename_group)) },
                text = {
                    OutlinedTextField(
                        value = groupRenameDialogText,
                        onValueChange = { groupRenameDialogText = it },
                        label = { Text(stringResource(R.string.label_workflow_editor_rename_group)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val groupId = renamingGroupId
                            if (groupId != null && groupRenameDialogText.isNotBlank()) {
                                viewModel.renameGroup(groupId, groupRenameDialogText.trim())
                                showGroupRenameDialog = false
                                renamingGroupId = null
                            }
                        },
                        enabled = groupRenameDialogText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.button_save))
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showGroupRenameDialog = false
                            renamingGroupId = null
                        }
                    ) {
                        Text(stringResource(R.string.button_cancel))
                    }
                }
            )
        }

        // Note edit dialog (combined title + content)
        if (showEditNoteDialog) {
            AlertDialog(
                onDismissRequest = {
                    showEditNoteDialog = false
                    editingNoteId = null
                },
                title = { Text(stringResource(R.string.workflow_editor_edit_note_content)) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = editNoteDialogTitle,
                            onValueChange = { editNoteDialogTitle = it },
                            label = { Text(stringResource(R.string.label_workflow_editor_rename_note)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editNoteDialogContent,
                            onValueChange = { editNoteDialogContent = it },
                            label = { Text(stringResource(R.string.label_workflow_editor_note_content)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp),
                            maxLines = 15
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val noteId = editingNoteId
                            if (noteId != null && editNoteDialogTitle.isNotBlank()) {
                                viewModel.renameNote(noteId, editNoteDialogTitle.trim())
                                viewModel.updateNoteContent(noteId, editNoteDialogContent)
                                showEditNoteDialog = false
                                editingNoteId = null
                            }
                        },
                        enabled = editNoteDialogTitle.isNotBlank()
                    ) {
                        Text(stringResource(R.string.button_save))
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showEditNoteDialog = false
                            editingNoteId = null
                        }
                    ) {
                        Text(stringResource(R.string.button_cancel))
                    }
                }
            )
        }

        // Save new workflow dialog
        if (uiState.showSaveDialog) {
            WorkflowDetailsDialog(
                titleRes = R.string.title_save_workflow,
                confirmLabelRes = R.string.button_save,
                selectedType = uiState.saveDialogSelectedType,
                onTypeSelected = { viewModel.onSaveDialogTypeSelected(it) },
                name = uiState.saveDialogName,
                onNameChange = { viewModel.onSaveDialogNameChange(it) },
                nameError = uiState.saveDialogNameError,
                description = uiState.saveDialogDescription,
                onDescriptionChange = { viewModel.onSaveDialogDescriptionChange(it) },
                isValidating = uiState.isSaveValidating,
                onConfirm = {
                    viewModel.proceedWithSave(context)
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
                type = uiState.saveDialogSelectedType,
                onDismiss = { viewModel.dismissMissingFieldsDialog() }
            )
        }
    }
}

@Composable
private fun FieldMappingPanel(
    mappingState: WorkflowMappingState,
    selectedFieldKey: String?,
    onFieldSelected: (String?) -> Unit,
    onClearFieldMapping: (String) -> Unit,
    onClearAllMappings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requiredFields = mappingState.fieldMappings.filter { it.isRequiredField }
    val optionalFields = mappingState.fieldMappings.filter { !it.isRequiredField }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row with title and clear all button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_field_mapping),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onClearAllMappings) {
                    Text(
                        text = stringResource(R.string.button_clear_all_mapping),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            NoOverscrollContainer {
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    // Necessary section
                    if (requiredFields.isNotEmpty()) {
                        item(key = "section_necessary") {
                            Text(
                                text = stringResource(R.string.section_necessary),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(
                            items = requiredFields,
                            key = { "req_${it.field.fieldKey}_${it.selectedCandidateIndex}" }
                        ) { fieldMapping ->
                            FieldMappingRow(
                                fieldMapping = fieldMapping,
                                isSelected = fieldMapping.field.fieldKey == selectedFieldKey,
                                onSelect = {
                                    val newKey = if (fieldMapping.field.fieldKey == selectedFieldKey) null else fieldMapping.field.fieldKey
                                    onFieldSelected(newKey)
                                },
                                onClearMapping = { onClearFieldMapping(fieldMapping.field.fieldKey) }
                            )
                        }
                    }

                    // Optional section
                    if (optionalFields.isNotEmpty()) {
                        item(key = "section_optional") {
                            Text(
                                text = stringResource(R.string.section_optional),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            items = optionalFields,
                            key = { "opt_${it.field.fieldKey}_${it.selectedCandidateIndex}" }
                        ) { fieldMapping ->
                            FieldMappingRow(
                                fieldMapping = fieldMapping,
                                isSelected = fieldMapping.field.fieldKey == selectedFieldKey,
                                onSelect = {
                                    val newKey = if (fieldMapping.field.fieldKey == selectedFieldKey) null else fieldMapping.field.fieldKey
                                    onFieldSelected(newKey)
                                },
                                onClearMapping = { onClearFieldMapping(fieldMapping.field.fieldKey) }
                            )
                        }
                    }
                }
            }

            if (mappingState.fieldMappings.any { it.hasMultipleCandidates }) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.hint_tap_node_to_change),
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
    onSelect: () -> Unit,
    onClearMapping: () -> Unit
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
                    // Mapped: show node info
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
                fieldMapping.candidates.isEmpty() -> {
                    // No candidates available
                    Text(
                        text = stringResource(R.string.msg_no_matching_nodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fieldMapping.isRequiredField) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                fieldMapping.isRequiredField -> {
                    // Required field not mapped - "Select a node" with error color
                    Text(
                        text = stringResource(R.string.msg_needs_remapping),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    // Optional field not mapped - "Select a node" with muted color
                    Text(
                        text = stringResource(R.string.msg_needs_remapping),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status icon - clickable to clear mapping
        IconButton(
            onClick = onClearMapping,
            modifier = Modifier.size(28.dp)
        ) {
            when {
                fieldMapping.isMapped -> {
                    // State 1: Mapped (both required and optional) - green checkmark
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.content_description_clear),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                fieldMapping.isRequiredField -> {
                    // State 2: Unmapped required - warning triangle with error color
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> {
                    // State 3: Unmapped optional - outlined circle, no color highlight
                    Icon(
                        Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
        title = { Text(stringResource(R.string.title_workflow_editor_discard)) },
        text = { Text(stringResource(R.string.msg_workflow_editor_discard)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.button_discard))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Dialog for selecting which input to connect to when multiple compatible inputs exist.
 * Used in long-press connection flow when the target node has multiple valid inputs.
 */
@Composable
private fun InputSelectionDialog(
    nodeTypeName: String,
    compatibleInputs: List<InputDefinition>,
    onInputSelected: (InputDefinition) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedInput by remember { mutableStateOf<InputDefinition?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.title_workflow_editor_select_input))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.msg_workflow_editor_select_input, nodeTypeName),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                compatibleInputs.forEach { input ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedInput == input,
                                onClick = { selectedInput = input }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedInput == input,
                            onClick = { selectedInput = input }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = input.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = input.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedInput?.let(onInputSelected) },
                enabled = selectedInput != null
            ) {
                Text(stringResource(R.string.button_connect))
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

/**
 * Dialog for selecting which output to connect from when multiple compatible outputs exist.
 * Used in reverse connection flow when the source node has multiple valid outputs.
 */
@Composable
private fun OutputSelectionDialog(
    nodeTypeName: String,
    compatibleOutputs: List<OutputSlot>,
    onOutputSelected: (OutputSlot) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedOutput by remember { mutableStateOf<OutputSlot?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.title_workflow_editor_select_output))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.msg_workflow_editor_select_output, nodeTypeName),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                compatibleOutputs.forEach { output ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedOutput == output,
                                onClick = { selectedOutput = output }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedOutput == output,
                            onClick = { selectedOutput = output }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = output.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = output.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedOutput?.let(onOutputSelected) },
                enabled = selectedOutput != null
            ) {
                Text(stringResource(R.string.button_connect))
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}
