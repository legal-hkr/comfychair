@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import sh.hnet.comfychair.ui.components.shared.segmentedItemShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.NodeBrowserBottomSheet
import sh.hnet.comfychair.ui.components.NodeValuesSheetContent
import sh.hnet.comfychair.ui.components.WorkflowGraphCanvas
import sh.hnet.comfychair.ui.components.dialogs.ChoiceDialog
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.ui.components.dialogs.DialogChoice
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FormDialog
import sh.hnet.comfychair.ui.components.workflow.AllFieldsSheet
import sh.hnet.comfychair.ui.components.workflow.EditorEditActions
import sh.hnet.comfychair.ui.components.workflow.EditorMode
import sh.hnet.comfychair.ui.components.workflow.EditorStatus
import sh.hnet.comfychair.ui.components.workflow.EditorToolbar
import sh.hnet.comfychair.ui.components.workflow.EditorToolbarActions
import sh.hnet.comfychair.ui.components.workflow.EditorTopRow
import sh.hnet.comfychair.ui.components.workflow.FieldMappingCard
import sh.hnet.comfychair.ui.components.workflow.MissingFieldsDialog
import sh.hnet.comfychair.ui.components.workflow.MissingNodesDialog
import sh.hnet.comfychair.ui.components.workflow.WorkflowDetailsDialog
import sh.hnet.comfychair.viewmodel.WorkflowEditorViewModel
import sh.hnet.comfychair.workflow.ConnectionDirection
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.changedValueCount

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
    // Values that differ from the workflow's (edits that only fill an empty value with the node's default don't count)
    val changedValues = remember(uiState.graph, uiState.nodeDefinitions, uiState.nodeAttributeEdits) {
        uiState.graph?.let { changedValueCount(it, uiState.nodeDefinitions, uiState.nodeAttributeEdits) } ?: 0
    }
    val status = when {
        connection != null -> EditorStatus.Connecting(
            connection.sourceSlot.slotType ?: "",
            fromOutput = connection.direction == ConnectionDirection.OUTPUT_TO_INPUT
        )
        mode == EditorMode.MAPPING -> EditorStatus.Mapping(import = uiState.isImportMapping)
        mode == EditorMode.EDIT -> EditorStatus.Editing(uiState.hasUnsavedChanges, uiState.isManualPlacementMode)
        mode == EditorMode.GRAPH -> EditorStatus.Graph
        else -> EditorStatus.NodeValues(changedValues)
    }

    // Accessibility: the graph can't be explored node by node, so a list of its nodes (spec §10.5)
    var showNodeList by remember { mutableStateOf(false) }
    val nodesListLabel = stringResource(R.string.label_nodes_list)

    // Map fields: the card starts at the first unmapped field
    var showAllFields by remember { mutableStateOf(false) }
    var confirmCancelImport by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isFieldMappingMode) {
        if (uiState.isFieldMappingMode && uiState.selectedFieldKey == null) viewModel.selectNextUnmappedField()
    }

    // Back by mode (spec §10.2): a waiting connection or the values of a node first; structure editing asks before
    // dropping changes. The system Back and the Back button do the same.
    val goBack = {
        when {
            connection != null -> viewModel.exitConnectionMode()
            uiState.isEditingNode -> viewModel.dismissNodeEditor()
            // Importing: asks; saving from the editor: back to editing, nothing lost
            uiState.isFieldMappingMode -> if (uiState.isImportMapping) confirmCancelImport = true else viewModel.returnFromSaveMapping()
            uiState.isCreateMode -> viewModel.handleCreateModeClose()
            uiState.isEditMode -> viewModel.handleEditExistingModeClose()
            else -> onClose()
        }
    }
    BackHandler(onBack = goBack)

    // Node values: a standard (non-modal) bottom sheet, so the graph stays pannable; swiping it down closes the node
    val valuesSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = valuesSheetState)
    LaunchedEffect(uiState.isEditingNode) {
        if (uiState.isEditingNode) valuesSheetState.expand() else valuesSheetState.hide()
    }
    val editingNode by rememberUpdatedState(uiState.isEditingNode)
    LaunchedEffect(valuesSheetState) {
        snapshotFlow { valuesSheetState.currentValue }.drop(1).collect { value ->
            if (value != SheetValue.Expanded && editingNode) viewModel.dismissNodeEditor()
        }
    }
    // The node stays in the sheet while it slides away
    val lastSheetNode = remember { mutableStateOf<WorkflowNode?>(null) }
    SideEffect { uiState.selectedNodeForEditing?.let { lastSheetNode.value = it } }
    val sheetNode = uiState.selectedNodeForEditing ?: lastSheetNode.value
    // At most a little over half the screen, so the node stays visible above it
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val sheetMaxHeight = with(density) { (windowHeight * 0.55f).toDp() }
    LaunchedEffect(windowHeight) {
        with(density) { viewModel.setValuesViewport(64.dp.toPx(), windowHeight * 0.55f) }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp,
        sheetContent = {
            sheetNode?.let { node ->
                NodeValuesSheetContent(
                    node = node,
                    nodeDefinition = uiState.nodeDefinitions[node.classType],
                    currentEdits = uiState.nodeAttributeEdits[node.id] ?: emptyMap(),
                    onEditChange = { inputName, value -> viewModel.updateNodeAttribute(node.id, inputName, value) },
                    onResetToDefault = { inputName -> viewModel.resetNodeAttribute(node.id, inputName) },
                    onResetNode = { viewModel.resetNode(node.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMaxHeight)
                        .navigationBarsPadding()
                )
            }
        }
    ) {
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
                    if (!isAnimatingTransition.value) {
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
                val useAnimatedValues = isStartingTransition ||
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
                            enableManualTransform = uiState.draggingNodeId == null && uiState.draggingNoteId == null,
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
                            notes = uiState.graph?.notes ?: emptyList(),
                            selectedNoteIds = uiState.selectedNoteIds,
                            onNoteTapped = { noteId ->
                                viewModel.toggleNoteSelection(noteId)
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
                                .semantics {
                                    if (mode == EditorMode.EDIT || mode == EditorMode.NODE_VALUES) {
                                        customActions = listOf(CustomAccessibilityAction(nodesListLabel) {
                                            showNodeList = true
                                            true
                                        })
                                    }
                                }
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
                }
            }
        }


        // Map fields: the current field on a card above the toolbar
        if (uiState.isFieldMappingMode && uiState.mappingState != null) {
            FieldMappingCard(
                fields = viewModel.orderedMappingFields(),
                currentKey = uiState.selectedFieldKey,
                onSelectField = { viewModel.selectFieldForMapping(it) },
                onAllFields = { showAllFields = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
            )
            if (showAllFields) {
                AllFieldsSheet(
                    fields = viewModel.allMappingFields(),
                    onSelectField = {
                        viewModel.selectFieldForMapping(it)
                        showAllFields = false
                    },
                    onClearField = { viewModel.clearFieldMapping(it) },
                    onClearAll = { viewModel.clearAllMappings() },
                    onDismiss = { showAllFields = false }
                )
            }
        }
        if (showNodeList) {
            NodeListSheet(
                nodes = uiState.graph?.nodes ?: emptyList(),
                onPick = { node ->
                    showNodeList = false
                    if (mode == EditorMode.EDIT) {
                        viewModel.clearSelection()
                        viewModel.toggleNodeSelection(node.id)
                    } else {
                        viewModel.onNodeTappedForEditing(node)
                    }
                },
                onDismiss = { showNodeList = false }
            )
        }
        if (confirmCancelImport) {
            ConfirmDialog(
                title = stringResource(R.string.title_cancel_import),
                text = stringResource(R.string.msg_cancel_import),
                confirmLabel = stringResource(R.string.button_cancel_import),
                dismissLabel = stringResource(R.string.button_keep_mapping),
                destructive = true,
                onConfirm = {
                    confirmCancelImport = false
                    viewModel.cancelMapping()
                },
                onDismiss = { confirmCancelImport = false }
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
                    onRenameNode = uiState.selectedNodeIds.singleOrNull()?.takeIf { uiState.selectedNoteIds.isEmpty() }?.let { nodeId ->
                        {
                            uiState.graph?.nodes?.find { it.id == nodeId }?.let { node ->
                                renamingNodeId = nodeId
                                renameDialogText = node.title
                                showRenameDialog = true
                            }
                        }
                    },
                    onEditNote = uiState.selectedNoteIds.singleOrNull()?.takeIf { uiState.selectedNodeIds.isEmpty() }?.let { noteId ->
                        {
                            viewModel.getNote(noteId)?.let { note ->
                                editingNoteId = noteId
                                editNoteDialogTitle = note.title
                                editNoteDialogContent = note.content
                                showEditNoteDialog = true
                            }
                        }
                    },
                    // The group holding the whole selection
                    onRenameGroup = uiState.graph?.groups?.find { group ->
                        uiState.selectedNodeIds.isNotEmpty() && group.memberNodeIds.containsAll(uiState.selectedNodeIds)
                    }?.let { group ->
                        {
                            renamingGroupId = group.id
                            groupRenameDialogText = group.title
                            showGroupRenameDialog = true
                        }
                    },
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

        // Several compatible inputs or outputs: which one (a tap connects)
        if (uiState.showInputSelectionDialog) {
            val nodeTypeName = uiState.inputSelectionNodeType?.displayName ?: uiState.inputSelectionNodeType?.classType ?: ""
            ChoiceDialog(
                title = stringResource(R.string.title_workflow_editor_select_input),
                text = stringResource(R.string.msg_workflow_editor_select_input, nodeTypeName),
                choices = uiState.inputSelectionCompatibleInputs.map { DialogChoice(it, it.name, it.type) },
                onChoose = { viewModel.selectInputForConnection(it) },
                onDismiss = { viewModel.cancelLongPressConnection() }
            )
        }
        if (uiState.showOutputSelectionDialog) {
            val nodeTypeName = uiState.outputSelectionNodeType?.displayName ?: uiState.outputSelectionNodeType?.classType ?: ""
            ChoiceDialog(
                title = stringResource(R.string.title_workflow_editor_select_output),
                text = stringResource(R.string.msg_workflow_editor_select_output, nodeTypeName),
                choices = uiState.outputSelectionCompatibleOutputs.map { DialogChoice(it, it.name, it.type) },
                onChoose = { viewModel.selectOutputForConnection(it) },
                onDismiss = { viewModel.cancelLongPressConnection() }
            )
        }


        if (uiState.showDiscardConfirmation) {
            ConfirmDialog(
                title = stringResource(R.string.title_discard_changes),
                text = stringResource(R.string.msg_workflow_editor_discard),
                confirmLabel = stringResource(R.string.button_discard),
                dismissLabel = stringResource(R.string.button_keep_editing),
                icon = Icons.Rounded.Warning,
                destructive = true,
                onConfirm = { viewModel.confirmDiscard() },
                onDismiss = { viewModel.dismissDiscardConfirmation() }
            )
        }

        // Turning manual placement off drops the nodes' own positions
        if (uiState.showDisableManualPlacementConfirmation) {
            ConfirmDialog(
                title = stringResource(R.string.title_disable_manual_placement),
                text = stringResource(R.string.msg_disable_manual_placement),
                confirmLabel = stringResource(R.string.button_turn_off),
                icon = Icons.Rounded.GridView,
                destructive = true,
                onConfirm = { viewModel.confirmDisableManualPlacement() },
                onDismiss = { viewModel.cancelDisableManualPlacement() }
            )
        }

        // Rename node: an empty name goes back to the node's class name
        if (showRenameDialog) {
            val node = uiState.graph?.nodes?.find { it.id == renamingNodeId }
            val close = {
                showRenameDialog = false
                renamingNodeId = null
            }
            val rename = {
                val nodeId = renamingNodeId
                if (nodeId != null) viewModel.renameNode(nodeId, renameDialogText.trim().ifEmpty { node?.classType ?: "" })
                close()
            }
            FormDialog(
                title = stringResource(R.string.title_node_editor_rename),
                confirmLabel = stringResource(R.string.button_rename),
                onConfirm = rename,
                onDismiss = close
            ) {
                DialogTextField(
                    value = renameDialogText,
                    onValueChange = { renameDialogText = it },
                    label = stringResource(R.string.label_node_editor_rename),
                    supporting = node?.let { stringResource(R.string.hint_node_name_empty, it.classType) },
                    onDone = rename
                )
            }
        }

        if (showGroupRenameDialog) {
            val close = {
                showGroupRenameDialog = false
                renamingGroupId = null
            }
            val rename = {
                val groupId = renamingGroupId
                if (groupId != null && groupRenameDialogText.isNotBlank()) {
                    viewModel.renameGroup(groupId, groupRenameDialogText.trim())
                    close()
                }
            }
            FormDialog(
                title = stringResource(R.string.title_workflow_editor_rename_group),
                confirmLabel = stringResource(R.string.button_rename),
                confirmEnabled = groupRenameDialogText.isNotBlank(),
                onConfirm = rename,
                onDismiss = close
            ) {
                DialogTextField(
                    value = groupRenameDialogText,
                    onValueChange = { groupRenameDialogText = it },
                    label = stringResource(R.string.label_workflow_editor_rename_group),
                    onDone = rename
                )
            }
        }

        // Note: its title and its text (Markdown)
        if (showEditNoteDialog) {
            val close = {
                showEditNoteDialog = false
                editingNoteId = null
            }
            FormDialog(
                title = stringResource(R.string.workflow_editor_edit_note_content),
                confirmLabel = stringResource(R.string.button_save),
                confirmEnabled = editNoteDialogTitle.isNotBlank(),
                onConfirm = {
                    val noteId = editingNoteId
                    if (noteId != null && editNoteDialogTitle.isNotBlank()) {
                        viewModel.renameNote(noteId, editNoteDialogTitle.trim())
                        viewModel.updateNoteContent(noteId, editNoteDialogContent)
                        close()
                    }
                },
                onDismiss = close
            ) {
                DialogTextField(
                    value = editNoteDialogTitle,
                    onValueChange = { editNoteDialogTitle = it },
                    label = stringResource(R.string.label_workflow_editor_rename_note)
                )
                DialogTextField(
                    value = editNoteDialogContent,
                    onValueChange = { editNoteDialogContent = it },
                    label = stringResource(R.string.label_workflow_editor_note_content),
                    multiLine = true
                )
            }
        }

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
}

/** The graph's nodes as a list (accessibility): picking one selects it or opens its values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeListSheet(nodes: List<WorkflowNode>, onPick: (WorkflowNode) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.label_nodes_list),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                Surface(
                    onClick = { onPick(node) },
                    shape = segmentedItemShape(index, nodes.size),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text(node.title) },
                        supportingContent = if (node.title != node.classType) ({ Text(node.classType) }) else null,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    )
                }
            }
        }
    }
}
