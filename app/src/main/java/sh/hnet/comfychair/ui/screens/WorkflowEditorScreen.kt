@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LinkedValue
import sh.hnet.comfychair.ui.components.NodeBrowserBottomSheet
import sh.hnet.comfychair.ui.components.NodeValuesSheetContent
import sh.hnet.comfychair.ui.components.WorkflowGraphCanvas
import sh.hnet.comfychair.ui.components.dialogs.ChoiceDialog
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.ui.components.dialogs.DialogChoice
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FormDialog
import sh.hnet.comfychair.ui.components.graphCanvasColor
import sh.hnet.comfychair.ui.components.rememberGraphDetailLevel
import sh.hnet.comfychair.ui.components.settings.groupShapes
import sh.hnet.comfychair.ui.components.shared.boldNamesResource
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
import sh.hnet.comfychair.util.ConnectionValidator
import sh.hnet.comfychair.viewmodel.WorkflowEditorViewModel
import sh.hnet.comfychair.workflow.ConnectionDirection
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.GraphDetailLevel
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.WorkflowGraph
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
    // A linked input socket's menu (Disconnect, Change source), and the app's field names for the linking question
    var socketMenu by remember { mutableStateOf<SlotPosition?>(null) }
    val fieldNameOf: (String) -> String = remember(context) { { key -> FieldDisplayRegistry.getDisplayName(context, key) } }
    // Values that differ from the workflow's (edits that only fill an empty value with the node's default don't count)
    val changedValues = remember(uiState.graph, uiState.nodeDefinitions, uiState.nodeAttributeEdits) {
        uiState.graph?.let { changedValueCount(it, uiState.nodeDefinitions, uiState.nodeAttributeEdits) } ?: 0
    }
    val detailLevel = rememberGraphDetailLevel(uiState.scale)
    val status = when {
        connection != null -> EditorStatus.Connecting(
            connection.sourceSlot.slotType ?: "",
            fromOutput = connection.direction == ConnectionDirection.OUTPUT_TO_INPUT,
            inputName = connection.sourceSlot.slotName.takeIf { connection.direction == ConnectionDirection.INPUT_TO_OUTPUT },
            noTargets = connection.validTargetSlots.isEmpty(),
            // Zoomed out, sockets can't be tapped: the connection waits until zoomed in
            zoomInToConnect = detailLevel != GraphDetailLevel.FULL
        )
        mode == EditorMode.MAPPING -> EditorStatus.Mapping(import = uiState.isImportMapping)
        mode == EditorMode.EDIT -> EditorStatus.Editing(
            uiState.hasUnsavedChanges,
            uiState.isManualPlacementMode,
            // Sockets can be tapped at full detail only
            zoomInToConnect = detailLevel != GraphDetailLevel.FULL
        )
        mode == EditorMode.GRAPH -> EditorStatus.Graph
        else -> EditorStatus.NodeValues(changedValues)
    }

    // Accessibility: the graph can't be explored node by node, so a list of its nodes (spec §10.5); in Edit structure
    // the selected node's sockets (each opening its menu, as a tap does) and a pending connection's targets (spec §17.7)
    var showNodeList by remember { mutableStateOf(false) }
    val nodesListLabel = stringResource(R.string.label_nodes_list)
    var socketListNodeId by remember { mutableStateOf<String?>(null) }
    var showTargetList by remember { mutableStateOf(false) }
    val selectedNode = uiState.selectedNodeIds.singleOrNull()?.let { id -> uiState.graph?.nodes?.find { it.id == id } }
    val socketsLabel = selectedNode?.let { stringResource(R.string.label_node_sockets, it.title) }
    val targetsLabel = stringResource(R.string.label_connection_targets)

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
    // Taken over only when Back has something to do first or might not leave (unsaved changes ask); otherwise it's the
    // system's, with its predictive animation back to the previous screen (goBack would only close the editor too)
    val backHasWork = connection != null || uiState.isEditingNode || uiState.isFieldMappingMode ||
        ((uiState.isCreateMode || uiState.isEditMode) &&
            (uiState.hasUnsavedChanges || (uiState.isCreateMode && uiState.graph?.nodes?.isNotEmpty() == true)))
    BackHandler(enabled = backHasWork, onBack = goBack)

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
    // The free canvas area (spec §12): below the top row (8 dp above it, 48 dp, 8 dp below) and above the toolbar
    // (the canvas ends 16 dp above the bottom; the toolbar, 64 dp, 16 dp above the navigation bar, and 8 dp gap),
    // with 16 dp at the sides; the layout is made for its proportions
    val windowSize = LocalWindowInfo.current.containerSize
    val statusBarHeight = WindowInsets.statusBars.getTop(density)
    val navigationBarHeight = WindowInsets.navigationBars.getBottom(density)
    LaunchedEffect(windowSize, statusBarHeight, navigationBarHeight) {
        with(density) {
            val top = 64.dp.toPx()
            val bottom = navigationBarHeight + 72.dp.toPx()
            val side = 16.dp.toPx()
            val canvasHeight = windowSize.height - statusBarHeight - 16.dp.toPx()
            viewModel.setViewport(top, bottom, side, (windowSize.width - 2 * side) / (canvasHeight - top - bottom))
        }
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
                        .navigationBarsPadding(),
                    linkedValues = linkedValuesOf(node, uiState.graph, uiState.nodeDefinitions[node.classType]),
                    // A linked value's row opens its source node, as tapping that node does
                    onOpenNode = { id -> uiState.graph?.nodes?.find { it.id == id }?.let(viewModel::onNodeTappedForEditing) }
                )
            }
        }
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(graphCanvasColor())
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
                        // A socket's menu, anchored at the socket
                        socketMenu?.let { slot ->
                            SocketMenu(
                                slot = slot,
                                graph = uiState.graph,
                                anchor = IntOffset(
                                    (slot.center.x * displayScale + displayOffset.x).roundToInt(),
                                    (slot.center.y * displayScale + displayOffset.y).roundToInt()
                                ),
                                onDismiss = { socketMenu = null },
                                onConnect = {
                                    if (slot.isOutput) viewModel.enterConnectionMode(slot) else viewModel.enterReverseConnectionMode(slot)
                                },
                                onAddNode = { viewModel.requestAddNodeFor(slot, fieldNameOf) },
                                onDisconnect = {
                                    if (slot.isOutput) viewModel.disconnectOutput(slot.nodeId, slot.outputIndex)
                                    else viewModel.deleteConnection(slot.nodeId, slot.slotName)
                                }
                            )
                        }
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
                                    // In INPUT_TO_OUTPUT mode: complete connection to this output (a value the app
                                    // fills asks first)
                                    connectionState?.direction == ConnectionDirection.INPUT_TO_OUTPUT ->
                                        viewModel.requestConnectToTarget(outputSlot, fieldNameOf)
                                    // Tapping the same output slot exits connection mode
                                    connectionState?.sourceSlot?.nodeId == outputSlot.nodeId &&
                                            connectionState.sourceSlot.outputIndex == outputSlot.outputIndex ->
                                        viewModel.exitConnectionMode()
                                    // Otherwise the output's menu: connect, add a node, disconnect all
                                    else -> socketMenu = outputSlot
                                }
                            },
                            onInputSlotTapped = { inputSlot ->
                                val connectionState = uiState.connectionModeState
                                when {
                                    // In OUTPUT_TO_INPUT mode: complete connection to this input (a value the app fills
                                    // asks first)
                                    connectionState?.direction == ConnectionDirection.OUTPUT_TO_INPUT ->
                                        viewModel.requestConnectToTarget(inputSlot, fieldNameOf)
                                    // Tapping the same input slot exits reverse connection mode
                                    connectionState?.sourceSlot?.nodeId == inputSlot.nodeId &&
                                            connectionState.sourceSlot.slotName == inputSlot.slotName ->
                                        viewModel.exitConnectionMode()
                                    // Otherwise the input's menu (a socket, linked or free, or a value's): choose or
                                    // change the source, add a node, disconnect (spec §17.4, extended by the owner)
                                    else -> socketMenu = inputSlot
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
                            // Pressing a socket grows a ring (the feedback of a press); held, it opens the same menu as a tap
                            onOutputSlotLongPressed = { slot -> socketMenu = slot },
                            onInputSlotLongPressed = { slot -> socketMenu = slot },
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
                            // Overview: a tapped block zooms in to it, animated like the zoom buttons
                            onZoomTo = { scale, offset ->
                                shouldAnimateZoomChange.value = true
                                viewModel.onTransform(scale, offset)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    if (mode == EditorMode.EDIT || mode == EditorMode.NODE_VALUES) {
                                        customActions = buildList {
                                            add(CustomAccessibilityAction(nodesListLabel) { showNodeList = true; true })
                                            if (mode == EditorMode.EDIT && connection == null && selectedNode != null && socketsLabel != null) {
                                                add(CustomAccessibilityAction(socketsLabel) { socketListNodeId = selectedNode.id; true })
                                            }
                                            if (connection != null) {
                                                add(CustomAccessibilityAction(targetsLabel) { showTargetList = true; true })
                                            }
                                        }
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
        socketListNodeId?.let { nodeId ->
            val node = uiState.graph?.nodes?.find { it.id == nodeId }
            if (node == null) {
                socketListNodeId = null
            } else {
                SlotListSheet(
                    title = stringResource(R.string.label_node_sockets, node.title),
                    slots = socketsOf(node, uiState.nodeDefinitions).map { slot ->
                        slot to stringResource(if (slot.isOutput) R.string.label_socket_output else R.string.label_socket_input)
                    },
                    onPick = { slot ->
                        socketListNodeId = null
                        socketMenu = slot
                    },
                    onDismiss = { socketListNodeId = null }
                )
            }
        }
        if (showTargetList && connection != null) {
            SlotListSheet(
                title = targetsLabel,
                slots = connection.validTargetSlots.map { slot ->
                    slot to uiState.graph?.nodes?.find { it.id == slot.nodeId }?.title.orEmpty()
                },
                onPick = { slot ->
                    showTargetList = false
                    viewModel.requestConnectToTarget(slot, fieldNameOf)
                },
                onDismiss = { showTargetList = false }
            )
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
        // Linking a value the app fills: what it means, before it's done (spec §17.2.3)
        uiState.pendingMappedLink?.let { pending ->
            ConfirmDialog(
                title = stringResource(R.string.title_editor_link_mapped, pending.inputName),
                text = if (pending.addNode) {
                    boldNamesResource(R.string.msg_editor_link_mapped_new_node, pending.inputName, pending.fieldName)
                } else {
                    boldNamesResource(R.string.msg_editor_link_mapped, pending.inputName, pending.fieldName, pending.sourceTitle)
                },
                confirmLabel = stringResource(R.string.button_link),
                icon = Icons.Rounded.Link,
                onConfirm = { viewModel.confirmMappedLink(pending) },
                onDismiss = viewModel::dismissMappedLink
            )
        }

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
                SegmentedListItem(
                    onClick = { onPick(node) },
                    shapes = groupShapes(index, nodes.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    supportingContent = if (node.title != node.classType) ({ Text(node.classType) }) else null
                ) {
                    Text(node.title)
                }
            }
        }
    }
}

/** Sockets as a list (accessibility): a node's, or a pending connection's targets; [slots] pairs each with its line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotListSheet(title: String, slots: List<Pair<SlotPosition, String>>, onPick: (SlotPosition) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            itemsIndexed(slots, key = { _, (slot, _) -> "${slot.nodeId}/${slot.isOutput}/${slot.slotName}/${slot.outputIndex}" }) { index, (slot, line) ->
                SegmentedListItem(
                    onClick = { onPick(slot) },
                    shapes = groupShapes(index, slots.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    supportingContent = { Text(line) }
                ) {
                    Text(slot.slotName)
                }
            }
        }
    }
}

/**
 * A node's sockets in Edit structure, where a tap would find them: connection inputs, value inputs that can take a link,
 * then the outputs.
 */
private fun socketsOf(node: WorkflowNode, definitions: Map<String, NodeTypeDefinition>): List<SlotPosition> {
    val inputs = node.inputs.mapNotNull { (name, value) ->
        val type = when (value) {
            is InputValue.UnconnectedSlot -> value.slotType
            is InputValue.Connection -> definitions[node.classType]?.inputs?.find { it.name == name }?.type
            is InputValue.Literal -> definitions[node.classType]?.inputs?.find { it.name == name }?.type?.uppercase()
                ?.takeIf { it in ConnectionValidator.LINKABLE_VALUE_TYPES } ?: return@mapNotNull null
        }
        SlotPosition(node.id, name, isOutput = false, outputIndex = 0, center = Offset(node.x, node.y + WorkflowLayoutEngine.inputSlotY(node, name)), slotType = type)
    }
    val outputs = node.outputs.mapIndexed { index, output ->
        SlotPosition(
            node.id, output.name, isOutput = true, outputIndex = index,
            center = Offset(node.x + node.width, node.y + WorkflowLayoutEngine.outputSlotY(node, index)), slotType = output.type
        )
    }
    return inputs + outputs
}

/**
 * A socket's menu (Edit structure), the same for inputs and outputs, in the app's menu style: Connect (compatible
 * sockets at the other end highlighted; an input's new link replaces the one it had, an output gets one more), Add a
 * node, and Disconnect apart. The label names the socket and the sockets it's linked to.
 */
@Composable
private fun SocketMenu(
    slot: SlotPosition,
    graph: WorkflowGraph?,
    anchor: IntOffset,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onAddNode: () -> Unit,
    onDisconnect: () -> Unit
) {
    val node = graph?.nodes?.find { it.id == slot.nodeId }
    val link = node?.inputs?.get(slot.slotName) as? InputValue.Connection
    val connected = if (slot.isOutput) {
        graph?.edges?.any { it.sourceNodeId == slot.nodeId && it.sourceOutputIndex == slot.outputIndex } == true
    } else link != null
    // "model – from MODEL in Load Diffusion Model", "LATENT – to latent_image in KSampler": the sockets at the other
    // end, each with its node; names bold, escaped before the markup
    val resources = LocalResources.current
    fun inNode(socket: String, nodeTitle: String) =
        resources.getString(R.string.label_editor_socket_in, TextUtils.htmlEncode(socket), TextUtils.htmlEncode(nodeTitle))
    val otherEnds = if (slot.isOutput) {
        graph?.edges.orEmpty().filter { it.sourceNodeId == slot.nodeId && it.sourceOutputIndex == slot.outputIndex }
            .map { edge -> inNode(edge.targetInputName, graph?.nodes?.find { it.id == edge.targetNodeId }?.title.orEmpty()) }
    } else {
        listOfNotNull(link?.let { l ->
            val source = graph?.nodes?.find { it.id == l.sourceNodeId }
            inNode(source?.outputs?.getOrNull(l.outputIndex)?.name.orEmpty(), source?.title.orEmpty())
        })
    }
    val label = AnnotatedString.fromHtml(
        when {
            otherEnds.isEmpty() -> resources.getString(R.string.label_editor_socket_menu_free, TextUtils.htmlEncode(slot.slotName))
            slot.isOutput -> resources.getString(R.string.label_editor_output_menu_header, TextUtils.htmlEncode(slot.slotName), otherEnds.joinToString(", "))
            else -> resources.getString(R.string.label_editor_socket_menu_header, TextUtils.htmlEncode(slot.slotName), otherEnds.first())
        }
    )
    data class Item(val labelRes: Int, val icon: ImageVector, val action: () -> Unit)
    val items = buildList {
        // Connect, as its opposite Disconnect: the label says which socket and where it's linked
        add(Item(R.string.menu_editor_connect, Icons.Rounded.Cable, onConnect))
        add(Item(R.string.menu_editor_add_node, Icons.Rounded.AddCircleOutline, onAddNode))
    }
    val disconnect = if (connected) listOf(Item(R.string.menu_editor_disconnect, Icons.Rounded.LinkOff, onDisconnect)) else emptyList()
    // Disconnect in a group of its own, apart from the actions that add a link (as Delete in the server menu)
    val groups = listOf(items, disconnect).filter { it.isNotEmpty() }
    Box(Modifier.offset { anchor }) {
        DropdownMenuPopup(expanded = true, onDismissRequest = onDismiss) {
            groups.forEachIndexed { groupIndex, group ->
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                    if (groupIndex == 0) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                        )
                    }
                    group.forEachIndexed { index, item ->
                        DropdownMenuItem(
                            text = { Text(stringResource(item.labelRes)) },
                            leadingIcon = { Icon(item.icon, contentDescription = null) },
                            shape = MenuDefaults.itemShape(index, group.size).shape,
                            onClick = {
                                onDismiss()
                                item.action()
                            }
                        )
                    }
                }
                if (groupIndex < groups.size - 1) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            }
        }
    }
}

/** The node's value inputs (in ComfyUI: widgets) linked to other nodes, with where each value comes from. */
private fun linkedValuesOf(node: WorkflowNode, graph: WorkflowGraph?, definition: NodeTypeDefinition?): List<LinkedValue> =
    node.inputs.mapNotNull { (name, value) ->
        val link = value as? InputValue.Connection ?: return@mapNotNull null
        val input = definition?.inputs?.find { it.name == name } ?: return@mapNotNull null
        val isValue = !input.forceInput && (input.type.uppercase() in ConnectionValidator.LINKABLE_VALUE_TYPES || input.options != null)
        if (!isValue) return@mapNotNull null
        val source = graph?.nodes?.find { it.id == link.sourceNodeId }
        LinkedValue(name, link.sourceNodeId, source?.title.orEmpty(), source?.outputs?.getOrNull(link.outputIndex)?.name.orEmpty())
    }
