package sh.hnet.comfychair.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.ExtensionOff
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.SubgraphLevels
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.hnet.comfychair.util.ConnectionValidator
import sh.hnet.comfychair.workflow.ConnectionDirection
import sh.hnet.comfychair.workflow.ConnectionModeState
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.GraphColors
import sh.hnet.comfychair.workflow.GraphDetailLevel
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.RenderedGroup
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.Subgraphs
import sh.hnet.comfychair.workflow.WorkflowEdge
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.WorkflowNote
import sh.hnet.comfychair.workflow.changedInputNames
import sh.hnet.comfychair.workflow.getEffectiveDefault
import sh.hnet.comfychair.workflow.routing.EdgeRouter
import sh.hnet.comfychair.workflow.routing.RouterProvider

/**
 * Highlight state for nodes in mapping mode
 */
enum class NodeHighlightState {
    NONE,
    CANDIDATE,
    SELECTED
}

/**
 * The graph's colours (spec §12.2): theme roles for everything neutral, ComfyUI's hues ([GraphColors]) only for title
 * tints and slot types.
 */
private data class CanvasColors(
    val isDark: Boolean,
    val palette: GraphColors.Palette,
    val canvas: Color,
    val nodeBody: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val neutralTint: Color,
    val pill: Color,
    val linkedPill: Color,
    val onLinkedPill: Color,
    val primary: Color,
    val outline: Color,
    val outlineVariant: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val groupArea: Color,
    val groupChip: Color,
    val note: Color,
    val onNote: Color,
    val shadow: Color,
    // A subgraph instance's frame and its Open button (spec §19.1); a level's Inputs and Outputs strips (§19.2.4)
    val instanceFrame: Color,
    val onInstanceFrame: Color,
    val openButton: Color,
    val onOpenButton: Color,
    val stripGlass: Color
) {
    /** Sockets, wires and port labels of a slot type; neutral when the type has no colour. */
    fun slot(type: String?): Color = palette.slot(type) ?: onSurfaceVariant
}

/**
 * The graph canvas's background, also for what surrounds the canvas, so there's no seam. Deeper than the spec's
 * (surface, surfaceContainerLow), so the editor's top row and toolbar (surfaceContainer and up) stand out from it:
 * the dimmest surface in the light theme, the lowest container in the dark one.
 */
@Composable
fun graphCanvasColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.surface.luminance() < 0.5f) scheme.surfaceContainerLowest else scheme.surfaceDim
}

/** Node geometry in graph units: twice the spec's dp, as the layout's rows (40) are twice the spec's (20). */
private object NodeStyle {
    const val CORNER = 32f
    const val TITLE_PADDING = 24f
    const val TITLE_TEXT = 25f
    // Notes a size above nodes (a wide note is read whole): title, text and its line height
    const val NOTE_TITLE_TEXT = 28f
    const val NOTE_TEXT = 22f
    const val NOTE_LINE = 27f
    const val ROW_PADDING = 20f
    const val LABEL_TEXT = 22f
    const val PILL_HEIGHT = 32f
    const val PILL_PADDING = 14f
    const val PILL_TEXT = 20f
    const val PILL_ICON = 20f
    const val PILL_ICON_GAP = 6f
    const val SOCKET_RADIUS = 10f
    const val SOCKET_RING = 4f
    const val SOCKET_HALO = 4f
    const val SELECTION_WIDTH = 6f
    const val SELECTION_GAP = 4f
    const val CHANGED_DOT_RADIUS = 6f
    const val BADGE = 28f
    // A bypassed node's badge chip (spec §20.1: 22 dp high, a 16 dp icon, 6 dp before it, 4 dp after, 9 dp at the end)
    const val CHIP_HEIGHT = 44f
    const val CHIP_ICON = 32f
    const val CHIP_PADDING_START = 12f
    const val CHIP_GAP = 8f
    const val CHIP_PADDING_END = 18f
    const val CHIP_TEXT = 22f
    const val GROUP_CORNER = 40f
    // A group's area is frosted glass: its colour at this opacity over the wires passing under it, blurred by this
    // radius in graph units
    const val GLASS_ALPHA = 0.7f
    const val GROUP_CHIP_INSET = 16f
    const val GROUP_CHIP_HEIGHT = 40f
    const val GROUP_CHIP_PADDING = 16f
    // A subgraph instance (spec §19.1): the frame's corners, its 4 dp bezel, the inner body's corners, the Open button
    const val INSTANCE_CORNER = 28f
    const val INSTANCE_BEZEL = 8f
    const val INSTANCE_INNER_CORNER = 20f
    const val OPEN_BUTTON_HEIGHT = 72f
    const val OPEN_BUTTON_INSET = 12f
    const val OPEN_BUTTON_PADDING = 24f
    const val ICON = 32f
    const val ICON_GAP = 12f
    // A level's port strips (spec §19.2.4): labels this far outside the strip
    const val STRIP_LABEL_GAP = 12f
    // Inside a subgraph: the mark of a value its instance shows (13 dp)
    const val SHOWN_MARK = 26f
}

/** Text paints, made once per canvas (drawing happens on the main thread only). */
private class GraphPaints {
    private val semiBold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.TITLE_TEXT; typeface = semiBold }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.LABEL_TEXT }
    val labelStrong = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.LABEL_TEXT; typeface = semiBold }
    val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.PILL_TEXT }
    val pillStrong = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.PILL_TEXT; typeface = semiBold }
    val groupLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = WorkflowLayoutEngine.GROUP_HEADER_HEIGHT; typeface = semiBold }
    val noteTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.NOTE_TITLE_TEXT; typeface = semiBold }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    // A bypassed node's badge (labelSmall, weight 600)
    val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NodeStyle.CHIP_TEXT; typeface = semiBold }
    // Overview text: its size set when drawn (fixed on screen)
    val blockTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = semiBold }
}

/** Icons drawn on nodes: the state badges at the title's end and the link in pills the generation screen fills. */
private class GraphIcons(
    val bypassed: VectorPainter,
    val muted: VectorPainter,
    val missing: VectorPainter,
    val link: VectorPainter,
    // A subgraph instance (its title, its block), its Open button, a level's Inputs and Outputs strips
    val subgraph: VectorPainter,
    val open: VectorPainter,
    val inputs: VectorPainter,
    val outputs: VectorPainter,
    // Inside a subgraph: a value its instance shows (spec §19.3)
    val shown: VectorPainter
)

// Blur radius of the wires under a group's frosted area, in graph units
private const val GLASS_BLUR = 10f
// The blurred wires are drawn at this fraction of the screen's resolution
private const val GLASS_LAYER_SCALE = 2f

/**
 * The soft shadow of elevation 1 (light theme: white nodes, sockets, notes and group labels on the light canvas), in
 * graph units; [level] below 1 for what lies lower (a group's area, under its nodes).
 */
private fun Paint.setElevationShadow(colors: CanvasColors, alpha: Float, scale: Float = 1f, level: Float = 1f) {
    setShadowLayer(4f * scale * level, 0f, 2f * scale * level, colors.shadow.copy(alpha = 0.3f * alpha * level).toArgb())
}

/** Baseline that centres a single line of [paint]'s text on [centerY]. */
private fun Paint.baselineFor(centerY: Float): Float {
    val metrics = fontMetrics
    return centerY - (metrics.ascent + metrics.descent) / 2
}

/**
 * Holds animatable position and alpha for a node.
 * nodeData stores the current node for rendering during fade-out.
 * When a node is removed, nodeData becomes the snapshot used during fade-out animation.
 */
private data class AnimatedNodeState(
    val x: Animatable<Float, AnimationVector1D>,
    val y: Animatable<Float, AnimationVector1D>,
    val alpha: Animatable<Float, AnimationVector1D>,
    val nodeData: WorkflowNode? = null,  // Current node data for rendering
    val isFadingOut: Boolean = false     // True when node is removed and fading out
)

/**
 * Holds animatable position and alpha for a note.
 * noteData stores the current note for rendering during fade-out.
 */
private data class AnimatedNoteState(
    val x: Animatable<Float, AnimationVector1D>,
    val y: Animatable<Float, AnimationVector1D>,
    val alpha: Animatable<Float, AnimationVector1D>,
    val noteData: WorkflowNote? = null,
    val isFadingOut: Boolean = false
)

/**
 * Holds animatable bounds and alpha for a group.
 * groupData stores the current group for rendering during fade-out.
 */
private data class AnimatedGroupState(
    val x: Animatable<Float, AnimationVector1D>,
    val y: Animatable<Float, AnimationVector1D>,
    val width: Animatable<Float, AnimationVector1D>,
    val height: Animatable<Float, AnimationVector1D>,
    val alpha: Animatable<Float, AnimationVector1D>,
    val groupData: RenderedGroup? = null,
    val isFadingOut: Boolean = false
)

/**
 * Unique key for an edge
 */
private data class EdgeKey(
    val sourceNodeId: String,
    val sourceOutputIndex: Int,
    val targetNodeId: String,
    val targetInputName: String
)

/**
 * Holds animatable alpha for an edge.
 * edgeData stores the current edge for rendering during fade-out.
 */
private data class AnimatedEdgeState(
    val alpha: Animatable<Float, AnimationVector1D>,
    val edgeData: WorkflowEdge? = null,
    val isFadingOut: Boolean = false
)

/**
 * Canvas component for rendering workflow graphs
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkflowGraphCanvas(
    graph: WorkflowGraph,
    scale: Float,
    offset: Offset,
    onTransform: (scale: Float, offset: Offset) -> Unit,
    modifier: Modifier = Modifier,
    isFieldMappingMode: Boolean = false,
    highlightedNodeIds: Set<String> = emptySet(),
    mappingState: WorkflowMappingState? = null,
    selectedFieldKey: String? = null,
    editingNodeId: String? = null,
    nodeAttributeEdits: Map<String, Map<String, Any>> = emptyMap(),
    editableInputNames: Set<String> = emptySet(),
    enableManualTransform: Boolean = true,
    isEditMode: Boolean = false,
    selectedNodeIds: Set<String> = emptySet(),
    connectionModeState: ConnectionModeState? = null,
    /** Map fields: values that can be picked for a custom field (spec §18.3), each tappable on its whole row */
    valuePickTargets: List<SlotPosition>? = null,
    onValuePicked: ((SlotPosition) -> Unit)? = null,
    renderedGroups: List<RenderedGroup> = emptyList(),
    notes: List<WorkflowNote> = emptyList(),
    selectedNoteIds: Set<Int> = emptySet(),
    nodeDefinitions: Map<String, NodeTypeDefinition> = emptyMap(),
    /** Shown names of fields by key, e.g. custom fields ("field_<id>") by the names the user gave them */
    fieldNames: Map<String, String> = emptyMap(),
    onNodeTapped: ((String) -> Unit)? = null,
    onTapOutsideNodes: (() -> Unit)? = null,
    onOutputSlotTapped: ((SlotPosition) -> Unit)? = null,
    onInputSlotTapped: ((SlotPosition) -> Unit)? = null,
    onNoteTapped: ((Int) -> Unit)? = null,
    onNoteHeightsChanged: (() -> Unit)? = null,
    longPressSourceSlot: SlotPosition? = null,
    onOutputSlotLongPressed: ((SlotPosition) -> Unit)? = null,
    onInputSlotLongPressed: ((SlotPosition) -> Unit)? = null,
    // Manual node placement
    showGrid: Boolean = false,
    isManualPlacementEnabled: Boolean = false,
    draggingNodeId: String? = null,
    dragOffset: Offset = Offset.Zero,
    onNodeDragStart: ((String) -> Unit)? = null,
    onNodeDrag: ((Offset) -> Unit)? = null,
    onNodeDragEnd: (() -> Unit)? = null,
    onNodeDragCancel: (() -> Unit)? = null,
    justDroppedNodeId: String? = null,
    onJustDroppedNodeRendered: (() -> Unit)? = null,
    // Manual note placement
    draggingNoteId: Int? = null,
    onNoteDragStart: ((Int) -> Unit)? = null,
    onNoteDragEnd: (() -> Unit)? = null,
    justDroppedNoteId: Int? = null,
    onJustDroppedNoteRendered: (() -> Unit)? = null,
    // Nodes, notes, groups and connections move and fade in on changes; off, they're drawn in place at once (a preview)
    animateChanges: Boolean = true,
    // Overview level: a tapped block asks to be zoomed to (scale and offset centring it); null: no zooming (a preview)
    onZoomTo: ((scale: Float, offset: Offset) -> Unit)? = null,
    // Always the overview, whatever the zoom (previews: small workflows look like the large ones)
    forceOverview: Boolean = false,
    // A subgraph instance's Open button was tapped (spec §19.1); null: no Open buttons (a preview)
    onOpenSubgraph: ((String) -> Unit)? = null,
    // What's there when the canvas first shows is drawn in place at once (a level entered or left: its own motion)
    appearInPlace: Boolean = false,
    // Inside a subgraph: the values its instance shows (node, input), marked on their rows (spec §19.3)
    shownOnInstance: Set<Pair<String, String>> = emptySet(),
    // Inside a bypassed subgraph instance: every node looks switched off, without badges (spec §20, owner's choice)
    levelBypassed: Boolean = false
) {
    // Use rememberUpdatedState to always have access to current values in the gesture handler
    val currentScaleState = rememberUpdatedState(scale)
    val currentOffsetState = rememberUpdatedState(offset)
    val currentGraphState = rememberUpdatedState(graph)
    val currentConnectionModeState = rememberUpdatedState(connectionModeState)
    val currentPickTargets = rememberUpdatedState(valuePickTargets)
    val currentOnValuePicked = rememberUpdatedState(onValuePicked)
    val currentIsEditMode = rememberUpdatedState(isEditMode)
    val currentRenderedGroups = rememberUpdatedState(renderedGroups)
    val currentNotes = rememberUpdatedState(notes)
    val currentNodeDefinitions = rememberUpdatedState(nodeDefinitions)
    val currentOnNodeDragStart = rememberUpdatedState(onNodeDragStart)
    val currentOnNodeDrag = rememberUpdatedState(onNodeDrag)
    val currentOnNodeDragEnd = rememberUpdatedState(onNodeDragEnd)
    val currentOnNodeDragCancel = rememberUpdatedState(onNodeDragCancel)
    val currentIsManualPlacementEnabled = rememberUpdatedState(isManualPlacementEnabled)
    val currentDraggingNodeId = rememberUpdatedState(draggingNodeId)
    val currentOnNoteDragStart = rememberUpdatedState(onNoteDragStart)
    val currentOnNoteDragEnd = rememberUpdatedState(onNoteDragEnd)
    val currentDraggingNoteId = rememberUpdatedState(draggingNoteId)

    // Calculate selected node IDs from mapping state - only for the currently selected field
    val mappingSelectedNodeIds = remember(mappingState, selectedFieldKey) {
        if (selectedFieldKey == null) {
            // No field selected: show no SELECTED states, only CANDIDATE
            emptySet()
        } else {
            // Only the node mapped to the selected field gets SELECTED state
            mappingState?.fieldMappings
                ?.find { it.field.fieldKey == selectedFieldKey }
                ?.selectedCandidate
                ?.nodeId
                ?.let { setOf(it) }
                ?: emptySet()
        }
    }

    // Create display name resolver using Context for localized field names
    val context = LocalContext.current
    // Custom fields by their own names (spec §18), built-in fields by the app's
    val currentFieldNames = rememberUpdatedState(fieldNames)
    val displayNameResolver: (String) -> String = remember(context) {
        { fieldKey -> currentFieldNames.value[fieldKey] ?: FieldDisplayRegistry.getDisplayName(context, fieldKey) }
    }

    // TextMeasurer for markdown rendering in notes
    val textMeasurer = rememberTextMeasurer()
    // Notes' content laid out once, not every frame
    val noteLayouts = remember { NoteLayouts() }
    val density = LocalDensity.current

    // The wire shape chosen in Settings
    val edgeRouter = remember { RouterProvider.getActiveRouter(context) }
    // Wires' shapes kept between frames (see WirePaths)
    val wirePaths = remember(edgeRouter) { WirePaths() }

    // Track if note heights changed during drawing (triggers relayout)
    var noteHeightsChanged by remember { mutableStateOf(false) }

    // Trigger relayout callback when note heights change
    LaunchedEffect(noteHeightsChanged) {
        if (noteHeightsChanged) {
            onNoteHeightsChanged?.invoke()
            noteHeightsChanged = false
        }
    }

    // Theme roles, and ComfyUI's hues in the theme's tones; dark or light by the theme actually shown
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    val canvasColor = graphCanvasColor()
    val colors = remember(scheme, isDark) {
        CanvasColors(
            isDark = isDark,
            palette = GraphColors.palette(isDark),
            canvas = canvasColor,
            nodeBody = if (isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant,
            neutralTint = scheme.surfaceContainerHighest,
            pill = scheme.surfaceContainerHighest,
            linkedPill = scheme.primaryContainer,
            onLinkedPill = scheme.onPrimaryContainer,
            primary = scheme.primary,
            outline = scheme.outline,
            outlineVariant = scheme.outlineVariant,
            errorContainer = scheme.errorContainer,
            onErrorContainer = scheme.onErrorContainer,
            groupArea = scheme.surfaceContainer,
            groupChip = scheme.surfaceContainerHighest,
            note = scheme.tertiaryContainer,
            onNote = scheme.onTertiaryContainer,
            // Compose's own elevation shadow colour (the theme has no shadow role)
            shadow = DefaultShadowColor,
            instanceFrame = scheme.primaryContainer,
            onInstanceFrame = scheme.onPrimaryContainer,
            openButton = scheme.secondaryContainer,
            onOpenButton = scheme.onSecondaryContainer,
            // The lowest container would vanish on the dark canvas
            stripGlass = if (isDark) scheme.surfaceContainerHighest else scheme.surfaceContainerLowest
        )
    }
    val paints = remember { GraphPaints() }

    // Subgraph instances (spec §19.1): their subgraph's name where their title differs, the nodes inside, and the
    // labels of the values they show from inside
    val resources = LocalResources.current
    val openLabel = stringResource(R.string.sg_open)
    val inputsLabel = stringResource(R.string.sg_inputs)
    val outputsLabel = stringResource(R.string.sg_outputs)
    val addLabel = stringResource(R.string.sg_add_socket)
    val definitionsJson = remember(graph.subgraphs) {
        graph.subgraphs?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    }
    val instanceTexts: Map<String, InstanceText> = remember(definitionsJson, graph.nodes, resources, openLabel) {
        val definitions = definitionsJson ?: return@remember emptyMap()
        graph.nodes.filter { it.isSubgraphInstance && definitions.has(it.classType) }.associate { node ->
            val name = SubgraphLevels.nameOf(definitions, node.classType)
            val count = SubgraphLevels.nodeCount(definitions, node.classType)
            node.id to InstanceText(
                open = openLabel,
                name = name.takeIf { it != node.title },
                count = resources.getQuantityString(R.plurals.sg_nodes_count, count, count),
                labels = SubgraphLevels.promotedLabels(definitions, node.classType, node.inputs.keys)
            )
        }
    }
    val currentInstanceTexts = rememberUpdatedState(instanceTexts)
    val currentOnOpenSubgraph = rememberUpdatedState(onOpenSubgraph)

    // Sockets: a touch target of about 48 dp on screen while editing (spec §10.4), at least 30 graph units, at most 60
    // so a tap near a node's edge still reaches the node; the nearest socket wins
    val slotHitRadius = 30f
    val touchSlotRadiusPx = with(density) { 24.dp.toPx() }
    // State badges at the end of the title bar, and the link icon of pills the generation screen fills
    val icons = GraphIcons(
        bypassed = rememberVectorPainter(Icons.Rounded.DoNotDisturbOn),
        muted = rememberVectorPainter(Icons.Rounded.Block),
        missing = rememberVectorPainter(Icons.Rounded.ExtensionOff),
        link = rememberVectorPainter(Icons.Rounded.Link),
        subgraph = rememberVectorPainter(Icons.Rounded.Layers),
        open = rememberVectorPainter(Icons.Rounded.ChevronRight),
        inputs = rememberVectorPainter(Icons.AutoMirrored.Rounded.Login),
        outputs = rememberVectorPainter(Icons.AutoMirrored.Rounded.Logout),
        shown = rememberVectorPainter(Icons.Rounded.Visibility)
    )
    fun socketRadius(scale: Float) = (touchSlotRadiusPx / scale).coerceIn(slotHitRadius, 60f)

    // Bypassed nodes look switched off (spec §20): how far each one is, 0 on to 1 off, faded when Bypass is toggled;
    // what's bypassed when the canvas first shows is off at once
    val bypassedLabel = stringResource(R.string.node_bypassed_badge)
    val bypassedIds = graph.nodes.filter { it.isBypassed }.mapTo(HashSet()) { it.id }
    val offStates = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val offSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(bypassedIds) {
        (bypassedIds + offStates.keys).forEach { id ->
            val target = if (id in bypassedIds) 1f else 0f
            val state = offStates[id]
            if (state == null) offStates[id] = Animatable(target)
            else if (state.targetValue != target) launch { state.animateTo(target, offSpec) }
        }
    }
    fun offOf(node: WorkflowNode): Float =
        if (levelBypassed) 1f else offStates[node.id]?.value ?: if (node.isBypassed) 1f else 0f

    // Animation state for nodes (position + alpha)
    val animatedNodeStates = remember { mutableStateMapOf<String, AnimatedNodeState>() }

    // Animation state for notes (position + alpha)
    val animatedNoteStates = remember { mutableStateMapOf<Int, AnimatedNoteState>() }

    // Animation state for groups (bounds + alpha)
    val animatedGroupStates = remember { mutableStateMapOf<Int, AnimatedGroupState>() }

    // Animation state for edges (alpha only)
    val animatedEdgeStates = remember { mutableStateMapOf<EdgeKey, AnimatedEdgeState>() }

    // Animation spec for smooth transitions (none when changes aren't animated)
    val animationSpec: FiniteAnimationSpec<Float> = if (animateChanges) {
        tween(durationMillis = 350, easing = FastOutSlowInEasing)
    } else snap()

    val inConnectionMode = connectionModeState != null

    // Focus (spec §12.5): the selected nodes (Edit structure), the node whose values are open, or the node a pending
    // connection starts from. Their wires are emphasised, all others recede; changes crossfade.
    val focusedNodeIds: Set<String> = buildSet {
        if (isEditMode) addAll(selectedNodeIds)
        editingNodeId?.let(::add)
        connectionModeState?.sourceSlot?.nodeId?.let(::add)
    }
    var shownFocus by remember { mutableStateOf(focusedNodeIds) }
    var previousFocus by remember { mutableStateOf(focusedNodeIds) }
    val focusProgress = remember { Animatable(1f) }
    val focusSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(focusedNodeIds) {
        if (focusedNodeIds == shownFocus) return@LaunchedEffect
        previousFocus = shownFocus
        shownFocus = focusedNodeIds
        focusProgress.snapTo(0f)
        focusProgress.animateTo(1f, focusSpec)
    }

    // Level of detail (spec §12.6): from the zoom, with hysteresis. Crossing the threshold starts the change at
    // once and runs it through: title bars spread over their nodes (and back), with standard spatial motion
    val zoomLevel = rememberGraphDetailLevel(scale)
    val level = if (forceOverview) GraphDetailLevel.OVERVIEW else zoomLevel
    val overviewProgress = remember { Animatable(if (level == GraphDetailLevel.OVERVIEW) 1f else 0f) }
    val spreadSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(level) {
        val target = if (level == GraphDetailLevel.OVERVIEW) 1f else 0f
        if (animateChanges) overviewProgress.animateTo(target, spreadSpec) else overviewProgress.snapTo(target)
    }
    val currentLevel = rememberUpdatedState(level)
    val currentOnZoomTo = rememberUpdatedState(onZoomTo)
    // Overview text: one size on screen for every block (smaller only when the zoom leaves no room for it)
    val overviewTitlePx = with(density) { 8.5.sp.toPx() }

    // Long-press feedback: a ring growing around the slot until the press counts
    var longPressSlot by remember { mutableStateOf<SlotPosition?>(null) }
    val longPressProgress = remember { Animatable(0f) }

    LaunchedEffect(longPressSlot) {
        if (longPressSlot != null) {
            // Animate glow expansion during long-press (matches 500ms threshold)
            longPressProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(500, easing = FastOutSlowInEasing)
            )
        } else {
            // Snap back animation when cancelled
            longPressProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(150, easing = FastOutSlowInEasing)
            )
        }
    }

    // Node/Note drag scale animation (lift/drop effect)
    val dragScale = remember { Animatable(1f) }
    LaunchedEffect(draggingNodeId, draggingNoteId) {
        if (draggingNodeId != null || draggingNoteId != null) {
            // Lift animation - scale up
            dragScale.animateTo(
                targetValue = 1.08f,
                animationSpec = tween(150, easing = FastOutSlowInEasing)
            )
        } else {
            // Drop animation - scale back to normal
            dragScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(100, easing = FastOutSlowInEasing)
            )
        }
    }

    // Fade-outs of removed nodes, notes, groups and wires outlive the effects that start them
    val fadeScope = rememberCoroutineScope()

    // Animate nodes when graph changes (position + fade in/out)
    LaunchedEffect(graph.nodes, justDroppedNodeId) {
        val currentNodeIds = graph.nodes.map { it.id }.toSet()
        val inPlace = appearInPlace && animatedNodeStates.isEmpty()

        // Handle removed nodes - fade out then remove
        animatedNodeStates.keys.toList().forEach { nodeId ->
            if (nodeId !in currentNodeIds) {
                val state = animatedNodeStates[nodeId] ?: return@forEach
                if (!state.isFadingOut && state.nodeData != null) {
                    val fading = state.copy(isFadingOut = true)
                    animatedNodeStates[nodeId] = fading
                    // Not in this effect's scope: the graph changing again (a relayout) would cancel it half-way and
                    // leave the removed one drawn for good; and one that came back meanwhile keeps its new state
                    fadeScope.launch {
                        state.alpha.animateTo(0f, animationSpec)
                        if (animatedNodeStates[nodeId] === fading) animatedNodeStates.remove(nodeId)
                    }
                }
            }
        }

        // Update or create animated state for current nodes
        graph.nodes.forEach { node ->
            val existing = animatedNodeStates[node.id]
            if (existing != null && !existing.isFadingOut) {
                // Existing node - check if just dropped (skip animation) or animate position
                if (existing.x.value != node.x || existing.y.value != node.y) {
                    if (node.id == justDroppedNodeId) {
                        // Just dropped - snap immediately without animation
                        existing.x.snapTo(node.x)
                        existing.y.snapTo(node.y)
                        onJustDroppedNodeRendered?.invoke()
                    } else {
                        // Normal animation
                        launch { existing.x.animateTo(node.x, animationSpec) }
                        launch { existing.y.animateTo(node.y, animationSpec) }
                    }
                }
                // Update node data for rendering
                animatedNodeStates[node.id] = existing.copy(nodeData = node)
                if (existing.alpha.value != 1f) {
                    launch { existing.alpha.animateTo(1f, animationSpec) }
                }
            } else if (inPlace) {
                animatedNodeStates[node.id] = AnimatedNodeState(Animatable(node.x), Animatable(node.y), Animatable(1f), node)
            } else {
                // New node - start at origin with alpha 0, fade in
                val newState = AnimatedNodeState(
                    x = Animatable(0f),
                    y = Animatable(0f),
                    alpha = Animatable(0f),
                    nodeData = node
                )
                animatedNodeStates[node.id] = newState
                launch { newState.x.animateTo(node.x, animationSpec) }
                launch { newState.y.animateTo(node.y, animationSpec) }
                launch { newState.alpha.animateTo(1f, animationSpec) }
            }
        }
    }

    // Animate notes when notes change (position + fade in/out)
    LaunchedEffect(notes, justDroppedNoteId) {
        val currentNoteIds = notes.map { it.id }.toSet()
        val inPlace = appearInPlace && animatedNoteStates.isEmpty()

        // Handle removed notes - fade out then remove
        animatedNoteStates.keys.toList().forEach { noteId ->
            if (noteId !in currentNoteIds) {
                val state = animatedNoteStates[noteId] ?: return@forEach
                if (!state.isFadingOut && state.noteData != null) {
                    val fading = state.copy(isFadingOut = true)
                    animatedNoteStates[noteId] = fading
                    // Not in this effect's scope: the graph changing again (a relayout) would cancel it half-way and
                    // leave the removed one drawn for good; and one that came back meanwhile keeps its new state
                    fadeScope.launch {
                        state.alpha.animateTo(0f, animationSpec)
                        if (animatedNoteStates[noteId] === fading) animatedNoteStates.remove(noteId)
                    }
                }
            }
        }

        // Update or create animated state for current notes
        notes.forEach { note ->
            val existing = animatedNoteStates[note.id]
            if (existing != null && !existing.isFadingOut) {
                // Existing note - check if just dropped (skip animation) or animate position
                if (existing.x.value != note.x || existing.y.value != note.y) {
                    if (note.id == justDroppedNoteId) {
                        // Just dropped - snap immediately without animation
                        existing.x.snapTo(note.x)
                        existing.y.snapTo(note.y)
                        onJustDroppedNoteRendered?.invoke()
                    } else {
                        // Normal animation
                        launch { existing.x.animateTo(note.x, animationSpec) }
                        launch { existing.y.animateTo(note.y, animationSpec) }
                    }
                }
                animatedNoteStates[note.id] = existing.copy(noteData = note)
                if (existing.alpha.value != 1f) {
                    launch { existing.alpha.animateTo(1f, animationSpec) }
                }
            } else if (inPlace) {
                animatedNoteStates[note.id] = AnimatedNoteState(Animatable(note.x), Animatable(note.y), Animatable(1f), note)
            } else {
                val newState = AnimatedNoteState(
                    x = Animatable(0f),
                    y = Animatable(0f),
                    alpha = Animatable(0f),
                    noteData = note
                )
                animatedNoteStates[note.id] = newState
                launch { newState.x.animateTo(note.x, animationSpec) }
                launch { newState.y.animateTo(note.y, animationSpec) }
                launch { newState.alpha.animateTo(1f, animationSpec) }
            }
        }
    }

    // Animate groups when groups change (bounds + fade in/out)
    LaunchedEffect(renderedGroups) {
        val currentGroupIds = renderedGroups.map { it.id }.toSet()
        val inPlace = appearInPlace && animatedGroupStates.isEmpty()

        // Handle removed groups - fade out then remove
        animatedGroupStates.keys.toList().forEach { groupId ->
            if (groupId !in currentGroupIds) {
                val state = animatedGroupStates[groupId] ?: return@forEach
                if (!state.isFadingOut && state.groupData != null) {
                    val fading = state.copy(isFadingOut = true)
                    animatedGroupStates[groupId] = fading
                    // Not in this effect's scope: the graph changing again (a relayout) would cancel it half-way and
                    // leave the removed one drawn for good; and one that came back meanwhile keeps its new state
                    fadeScope.launch {
                        state.alpha.animateTo(0f, animationSpec)
                        if (animatedGroupStates[groupId] === fading) animatedGroupStates.remove(groupId)
                    }
                }
            }
        }

        // Update or create animated state for current groups
        renderedGroups.forEach { group ->
            val existing = animatedGroupStates[group.id]
            if (existing != null && !existing.isFadingOut) {
                if (existing.x.value != group.x || existing.y.value != group.y ||
                    existing.width.value != group.width || existing.height.value != group.height) {
                    launch { existing.x.animateTo(group.x, animationSpec) }
                    launch { existing.y.animateTo(group.y, animationSpec) }
                    launch { existing.width.animateTo(group.width, animationSpec) }
                    launch { existing.height.animateTo(group.height, animationSpec) }
                }
                animatedGroupStates[group.id] = existing.copy(groupData = group)
                if (existing.alpha.value != 1f) {
                    launch { existing.alpha.animateTo(1f, animationSpec) }
                }
            } else if (inPlace) {
                animatedGroupStates[group.id] = AnimatedGroupState(
                    Animatable(group.x), Animatable(group.y), Animatable(group.width), Animatable(group.height), Animatable(1f), group
                )
            } else {
                // New group - start at origin with actual size and alpha 0
                val newState = AnimatedGroupState(
                    x = Animatable(0f),
                    y = Animatable(0f),
                    width = Animatable(group.width),
                    height = Animatable(group.height),
                    alpha = Animatable(0f),
                    groupData = group
                )
                animatedGroupStates[group.id] = newState
                launch { newState.x.animateTo(group.x, animationSpec) }
                launch { newState.y.animateTo(group.y, animationSpec) }
                launch { newState.alpha.animateTo(1f, animationSpec) }
            }
        }
    }

    // Animate edges when edges change (fade in/out only)
    LaunchedEffect(graph.edges) {
        val currentEdgeKeys = graph.edges.map { edge ->
            EdgeKey(edge.sourceNodeId, edge.sourceOutputIndex, edge.targetNodeId, edge.targetInputName)
        }.toSet()
        val inPlace = appearInPlace && animatedEdgeStates.isEmpty()

        // Handle removed edges - fade out then remove
        animatedEdgeStates.keys.toList().forEach { edgeKey ->
            if (edgeKey !in currentEdgeKeys) {
                val state = animatedEdgeStates[edgeKey] ?: return@forEach
                if (!state.isFadingOut && state.edgeData != null) {
                    val fading = state.copy(isFadingOut = true)
                    animatedEdgeStates[edgeKey] = fading
                    // Not in this effect's scope: the graph changing again (a relayout) would cancel it half-way and
                    // leave the removed one drawn for good; and one that came back meanwhile keeps its new state
                    fadeScope.launch {
                        state.alpha.animateTo(0f, animationSpec)
                        if (animatedEdgeStates[edgeKey] === fading) animatedEdgeStates.remove(edgeKey)
                    }
                }
            }
        }

        // Update or create animated state for current edges
        graph.edges.forEach { edge ->
            val key = EdgeKey(edge.sourceNodeId, edge.sourceOutputIndex, edge.targetNodeId, edge.targetInputName)
            val existing = animatedEdgeStates[key]
            if (existing != null && !existing.isFadingOut) {
                animatedEdgeStates[key] = existing.copy(edgeData = edge)
                if (existing.alpha.value != 1f) {
                    launch { existing.alpha.animateTo(1f, animationSpec) }
                }
            } else if (inPlace) {
                animatedEdgeStates[key] = AnimatedEdgeState(alpha = Animatable(1f), edgeData = edge)
            } else {
                val newState = AnimatedEdgeState(alpha = Animatable(0f), edgeData = edge)
                animatedEdgeStates[key] = newState
                launch { newState.alpha.animateTo(1f, animationSpec) }
            }
        }
    }

    // Build animated nodes for drawing (includes fading-out nodes)
    // Note: Elements without animation state are skipped - they'll appear next frame after LaunchedEffect initializes
    data class NodeWithAlpha(val node: WorkflowNode, val alpha: Float)
    val currentNodeIds = graph.nodes.map { it.id }.toSet()
    val animatedNodes: List<NodeWithAlpha> = buildList {
        // Current nodes (not fading out) - only render if animation state exists
        graph.nodes.forEach { node ->
            val state = animatedNodeStates[node.id]
            if (state != null && !state.isFadingOut) {
                val nodeData = state.nodeData ?: node
                // For just-dropped nodes, use actual position to avoid flicker before LaunchedEffect snaps
                // For dragging nodes, include dragOffset so edges follow the node in real-time
                val (renderX, renderY) = when {
                    node.id == justDroppedNodeId -> Pair(node.x, node.y)
                    node.id == draggingNodeId -> Pair(state.x.value + dragOffset.x, state.y.value + dragOffset.y)
                    else -> Pair(state.x.value, state.y.value)
                }
                add(NodeWithAlpha(nodeData.copy(x = renderX, y = renderY), state.alpha.value))
            }
            // Skip nodes without animation state - wait for LaunchedEffect to initialize
        }
        // Removed nodes (fading out or about to fade out) - render any node not in current graph
        animatedNodeStates.forEach { (nodeId, state) ->
            if (nodeId !in currentNodeIds && state.nodeData != null) {
                add(NodeWithAlpha(state.nodeData.copy(x = state.x.value, y = state.y.value), state.alpha.value))
            }
        }
    }

    // Build animated notes for drawing (includes fading-out notes)
    data class NoteWithAlpha(val note: WorkflowNote, val alpha: Float)
    val currentNoteIds = notes.map { it.id }.toSet()
    val animatedNotes: List<NoteWithAlpha> = buildList {
        notes.forEach { note ->
            val state = animatedNoteStates[note.id]
            if (state != null && !state.isFadingOut) {
                val noteData = state.noteData ?: note
                // For just-dropped notes, use actual position to avoid flicker before LaunchedEffect snaps
                // For dragging notes, include dragOffset so the note follows the finger in real-time
                val (renderX, renderY) = when {
                    note.id == justDroppedNoteId -> Pair(note.x, note.y)
                    note.id == draggingNoteId -> Pair(state.x.value + dragOffset.x, state.y.value + dragOffset.y)
                    else -> Pair(state.x.value, state.y.value)
                }
                add(NoteWithAlpha(noteData.copy(x = renderX, y = renderY), state.alpha.value))
            }
            // Skip notes without animation state
        }
        // Removed notes - render any note not in current list
        animatedNoteStates.forEach { (noteId, state) ->
            if (noteId !in currentNoteIds && state.noteData != null) {
                add(NoteWithAlpha(state.noteData.copy(x = state.x.value, y = state.y.value), state.alpha.value))
            }
        }
    }

    // Build animated groups for drawing (includes fading-out groups)
    data class GroupWithAlpha(val group: RenderedGroup, val alpha: Float)
    val currentGroupIds = renderedGroups.map { it.id }.toSet()
    val animatedGroups: List<GroupWithAlpha> = buildList {
        renderedGroups.forEach { group ->
            val state = animatedGroupStates[group.id]
            if (state != null && !state.isFadingOut) {
                add(GroupWithAlpha(
                    RenderedGroup(group.group, state.x.value, state.y.value, state.width.value, state.height.value),
                    state.alpha.value
                ))
            }
            // Skip groups without animation state
        }
        // Removed groups - render any group not in current list
        animatedGroupStates.forEach { (groupId, state) ->
            if (groupId !in currentGroupIds && state.groupData != null) {
                add(GroupWithAlpha(
                    state.groupData.copy(x = state.x.value, y = state.y.value, width = state.width.value, height = state.height.value),
                    state.alpha.value
                ))
            }
        }
    }

    // Build animated edges for drawing (includes fading-out edges)
    data class EdgeWithAlpha(val edge: WorkflowEdge, val alpha: Float)
    val currentEdgeKeys = graph.edges.map { edge ->
        EdgeKey(edge.sourceNodeId, edge.sourceOutputIndex, edge.targetNodeId, edge.targetInputName)
    }.toSet()
    val animatedEdges: List<EdgeWithAlpha> = buildList {
        graph.edges.forEach { edge ->
            val key = EdgeKey(edge.sourceNodeId, edge.sourceOutputIndex, edge.targetNodeId, edge.targetInputName)
            val state = animatedEdgeStates[key]
            if (state != null && !state.isFadingOut) {
                val edgeData = state.edgeData ?: edge
                add(EdgeWithAlpha(edgeData, state.alpha.value))
            }
            // Skip edges without animation state
        }
        // Removed edges - render any edge not in current graph
        animatedEdgeStates.forEach { (edgeKey, state) ->
            if (edgeKey !in currentEdgeKeys && state.edgeData != null) {
                add(EdgeWithAlpha(state.edgeData, state.alpha.value))
            }
        }
    }

    // Wires passing through groups, blurred under their frosted areas
    val glassLayer = rememberGraphicsLayer()

    Canvas(
        modifier = modifier
            // Node/Note drag gesture handler for manual placement (custom implementation that consumes events)
            .pointerInput(isEditMode, isManualPlacementEnabled) {
                if (!isEditMode || !currentIsManualPlacementEnabled.value) {
                    return@pointerInput
                }

                val longPressTimeout = 500L
                val movementTolerance = 20f  // Screen pixels

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()

                    // Transform to graph coordinates
                    val graphX = (down.position.x - currentOffsetState.value.x) / currentScaleState.value
                    val graphY = (down.position.y - currentOffsetState.value.y) / currentScaleState.value

                    // Check if touch is on a node (use findLast for z-order - higher IDs on top)
                    // Nodes have priority over notes since they render on top
                    val hitNode = currentGraphState.value.nodes.findLast { node ->
                        graphX >= node.x && graphX <= node.x + node.width &&
                        graphY >= node.y && graphY <= node.y + node.height
                    }

                    // If not on a node, check if touch is on a note
                    val hitNote = if (hitNode == null) {
                        currentNotes.value.findLast { note ->
                            graphX >= note.x && graphX <= note.x + note.width &&
                            graphY >= note.y && graphY <= note.y + note.height
                        }
                    } else null

                    if (hitNode == null && hitNote == null) {
                        // Not on a node or note, let other gestures handle it
                        return@awaitEachGesture
                    }

                    // On a node or note - consume events to prevent pan/zoom interference
                    down.consume()

                    var currentPointer = down
                    var longPressTriggered = false
                    var isDragging = false
                    var localDraggingNodeId: String? = null
                    var localDraggingNoteId: Int? = null

                    // Wait for long press or cancel
                    while (!longPressTriggered) {
                        val event = withTimeoutOrNull(50L) {
                            awaitPointerEvent(PointerEventPass.Main)
                        }

                        // Check if long press timeout reached
                        if (System.currentTimeMillis() - downTime >= longPressTimeout) {
                            val currentEvent = currentEvent
                            val stillPressed = currentEvent.changes.any { it.id == currentPointer.id && it.pressed }
                            if (stillPressed) {
                                // Long press succeeded - start drag
                                longPressTriggered = true
                                isDragging = true
                                if (hitNode != null) {
                                    localDraggingNodeId = hitNode.id
                                    sh.hnet.comfychair.util.DebugLogger.d("NodeDrag", "Long press succeeded, starting drag for node ${hitNode.id}")
                                    currentOnNodeDragStart.value?.invoke(hitNode.id)
                                } else if (hitNote != null) {
                                    localDraggingNoteId = hitNote.id
                                    sh.hnet.comfychair.util.DebugLogger.d("NoteDrag", "Long press succeeded, starting drag for note ${hitNote.id}")
                                    currentOnNoteDragStart.value?.invoke(hitNote.id)
                                }
                            }
                            break
                        }

                        if (event != null) {
                            val change = event.changes.firstOrNull { it.id == currentPointer.id }
                            if (change == null || !change.pressed) {
                                // Released before long press - cancel
                                sh.hnet.comfychair.util.DebugLogger.d("NodeDrag", "Released before long press")
                                break
                            }

                            // Check movement tolerance
                            val moved = (change.position - down.position).getDistance() > movementTolerance
                            if (moved) {
                                // Moved too much - cancel
                                sh.hnet.comfychair.util.DebugLogger.d("NodeDrag", "Moved too much before long press")
                                break
                            }

                            // Consume to prevent pan/zoom
                            change.consume()
                            currentPointer = change
                        }
                    }

                    // Handle drag if long press was triggered
                    if (isDragging && (localDraggingNodeId != null || localDraggingNoteId != null)) {
                        var previousPosition = currentPointer.position

                        // Enter drag loop
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == currentPointer.id }

                            if (change == null || !change.pressed) {
                                // Released - end drag
                                if (localDraggingNodeId != null) {
                                    sh.hnet.comfychair.util.DebugLogger.d("NodeDrag", "Drag ended for node $localDraggingNodeId")
                                    currentOnNodeDragEnd.value?.invoke()
                                } else if (localDraggingNoteId != null) {
                                    sh.hnet.comfychair.util.DebugLogger.d("NoteDrag", "Drag ended for note $localDraggingNoteId")
                                    currentOnNoteDragEnd.value?.invoke()
                                }
                                break
                            }

                            // Calculate drag delta in screen space, then convert to graph space
                            val dragAmount = change.position - previousPosition
                            if (dragAmount != Offset.Zero) {
                                val deltaGraph = Offset(
                                    dragAmount.x / currentScaleState.value,
                                    dragAmount.y / currentScaleState.value
                                )
                                // Both node and note drag use the same onNodeDrag callback
                                // (they share the same dragOffset in the UI state)
                                currentOnNodeDrag.value?.invoke(deltaGraph)
                            }

                            previousPosition = change.position
                            change.consume()
                        }
                    }
                }
            }
            // Long-press gesture handler for output slots
            .pointerInput(isEditMode, onOutputSlotLongPressed) {
                if (!isEditMode || onOutputSlotLongPressed == null) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val longPressTimeout = 500L
                    val pollInterval = 50L  // Check every 50ms even without events

                    // Transform to graph coordinates
                    val graphX = (down.position.x - currentOffsetState.value.x) / currentScaleState.value
                    val graphY = (down.position.y - currentOffsetState.value.y) / currentScaleState.value

                    // Sockets can be pressed at full detail only
                    if (currentLevel.value != GraphDetailLevel.FULL) return@awaitEachGesture

                    // Check if down is on an output slot
                    val hitSlot = findOutputSlotAt(
                        graphX = graphX,
                        graphY = graphY,
                        nodes = currentGraphState.value.nodes,
                        slotHitRadius = socketRadius(currentScaleState.value)
                    )
                    if (hitSlot == null) {
                        return@awaitEachGesture
                    }

                    // Start visual feedback
                    longPressSlot = hitSlot

                    var pointer = down
                    var longPressTriggered = false

                    while (true) {
                        // Use timeout to avoid blocking forever when finger is still
                        val event = withTimeoutOrNull(pollInterval) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }

                        // Check timeout first (works even if no event received)
                        if (System.currentTimeMillis() - downTime > longPressTimeout) {
                            // Verify finger is still down before triggering
                            val currentEvent = currentEvent
                            val stillPressed = currentEvent.changes.any { it.id == pointer.id && it.pressed }
                            if (stillPressed) {
                                onOutputSlotLongPressed(hitSlot)
                                longPressTriggered = true
                            }
                            longPressSlot = null
                            // Wait for finger release
                            waitForUpOrCancellation()
                            break
                        }

                        // Process event if one was received
                        if (event != null) {
                            val change = event.changes.firstOrNull { it.id == pointer.id }

                            if (change == null || !change.pressed) {
                                // Released before threshold - cancel
                                longPressSlot = null
                                break
                            }

                            // Check movement tolerance (20f screen pixels)
                            val moved = (change.position - down.position).getDistance() > 20f
                            if (moved) {
                                longPressSlot = null
                                break
                            }

                            pointer = change
                        }
                    }
                }
            }
            // Long-press gesture handler for input slots (reverse connection)
            .pointerInput(isEditMode, onInputSlotLongPressed) {
                if (!isEditMode || onInputSlotLongPressed == null) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val longPressTimeout = 500L
                    val pollInterval = 50L  // Check every 50ms even without events

                    // Transform to graph coordinates
                    val graphX = (down.position.x - currentOffsetState.value.x) / currentScaleState.value
                    val graphY = (down.position.y - currentOffsetState.value.y) / currentScaleState.value

                    // Check if down is on an input slot
                    // Sockets can be pressed at full detail only
                    if (currentLevel.value != GraphDetailLevel.FULL) return@awaitEachGesture

                    val hitSlot = findInputSlotAt(
                        graphX = graphX,
                        graphY = graphY,
                        nodes = currentGraphState.value.nodes,
                        slotHitRadius = socketRadius(currentScaleState.value),
                        nodeDefinitions = currentNodeDefinitions.value,
                        valueSockets = currentConnectionModeState.value == null
                    )
                    if (hitSlot == null) {
                        return@awaitEachGesture
                    }

                    // Start visual feedback
                    longPressSlot = hitSlot

                    var pointer = down
                    var longPressTriggered = false

                    while (true) {
                        // Use timeout to avoid blocking forever when finger is still
                        val event = withTimeoutOrNull(pollInterval) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }

                        // Check timeout first (works even if no event received)
                        if (System.currentTimeMillis() - downTime > longPressTimeout) {
                            // Verify finger is still down before triggering
                            val currentEvent = currentEvent
                            val stillPressed = currentEvent.changes.any { it.id == pointer.id && it.pressed }
                            if (stillPressed) {
                                onInputSlotLongPressed(hitSlot)
                                longPressTriggered = true
                            }
                            longPressSlot = null
                            // Wait for finger release
                            waitForUpOrCancellation()
                            break
                        }

                        // Process event if one was received
                        if (event != null) {
                            val change = event.changes.firstOrNull { it.id == pointer.id }

                            if (change == null || !change.pressed) {
                                // Released before threshold - cancel
                                longPressSlot = null
                                break
                            }

                            // Check movement tolerance (20f screen pixels)
                            val moved = (change.position - down.position).getDistance() > 20f
                            if (moved) {
                                longPressSlot = null
                                break
                            }

                            pointer = change
                        }
                    }
                }
            }
            .pointerInput(onNodeTapped, onTapOutsideNodes, onOutputSlotTapped, onInputSlotTapped, onNoteTapped) {
                if (onNodeTapped != null || onTapOutsideNodes != null || onOutputSlotTapped != null || onInputSlotTapped != null || onNoteTapped != null) {
                    detectTapGestures { tapOffset ->
                        // Transform tap position to graph coordinates
                        val graphX = (tapOffset.x - currentOffsetState.value.x) / currentScaleState.value
                        val graphY = (tapOffset.y - currentOffsetState.value.y) / currentScaleState.value
                        val graph = currentGraphState.value
                        val connectionState = currentConnectionModeState.value
                        val inConnectionMode = connectionState != null
                        val inEditMode = currentIsEditMode.value
                        val radius = socketRadius(currentScaleState.value)

                        // Overview: a tapped block is zoomed to (full detail, centred); in Edit structure also selected
                        val zoomTo = currentOnZoomTo.value
                        if (currentLevel.value == GraphDetailLevel.OVERVIEW && zoomTo != null) {
                            val hitNode = graph.nodes.findLast { graphX in it.x..(it.x + it.width) && graphY in it.y..(it.y + it.height) }
                            val hitNote = currentNotes.value.findLast { graphX in it.x..(it.x + it.width) && graphY in it.y..(it.y + it.height) }
                            val (centerX, centerY) = when {
                                hitNode != null -> hitNode.x + hitNode.width / 2 to hitNode.y + hitNode.height / 2
                                hitNote != null -> hitNote.x + hitNote.width / 2 to hitNote.y + hitNote.height / 2
                                else -> null to null
                            }
                            if (centerX != null && centerY != null) {
                                // 100 % of the spec: one dp per spec unit, two graph units
                                val targetScale = (density.density / 2f).coerceIn(0.2f, 3f)
                                zoomTo(
                                    targetScale,
                                    Offset(size.width / 2f - centerX * targetScale, size.height / 2f - centerY * targetScale)
                                )
                                if (inEditMode && hitNode != null) onNodeTapped?.invoke(hitNode.id)
                                return@detectTapGestures
                            }
                        }
                        // Sockets can be tapped at full detail only; zoomed out, a tap selects the node
                        val socketsActive = currentLevel.value == GraphDetailLevel.FULL

                        // Picking a value for a custom field: a target's socket or anywhere on its row; other taps do
                        // nothing (they'd assign built-in fields)
                        currentPickTargets.value?.let { targets ->
                            if (socketsActive) {
                                targets.firstOrNull { slot ->
                                    val node = graph.nodes.find { it.id == slot.nodeId }
                                    node != null && graphX in (node.x - radius)..(node.x + node.width) &&
                                        abs(graphY - (node.y + WorkflowLayoutEngine.inputSlotY(node, slot.slotName))) <= WorkflowLayoutEngine.INPUT_ROW_HEIGHT / 2
                                }?.let { currentOnValuePicked.value?.invoke(it) }
                            }
                            return@detectTapGestures
                        }

                        // In connection mode, check for taps on valid target slots (the nearest one)
                        if (connectionState != null && socketsActive) {
                            fun distance(slot: SlotPosition) = (graphX - slot.center.x).let { it * it } + (graphY - slot.center.y).let { it * it }
                            val tappedTargetSlot = connectionState.validTargetSlots
                                .filter { distance(it) <= radius * radius }
                                .minByOrNull { distance(it) }
                                // A value row that can take the link counts anywhere on its row (a bigger target)
                                ?: connectionState.validTargetSlots.firstOrNull { slot ->
                                    val node = graph.nodes.find { it.id == slot.nodeId }
                                    node != null && node.inputs[slot.slotName] is InputValue.Literal &&
                                        graphX in node.x..(node.x + node.width) &&
                                        abs(graphY - slot.center.y) <= WorkflowLayoutEngine.INPUT_ROW_HEIGHT / 2
                                }
                            if (tappedTargetSlot != null) {
                                // Call appropriate callback based on direction
                                when (connectionState.direction) {
                                    ConnectionDirection.OUTPUT_TO_INPUT -> onInputSlotTapped?.invoke(tappedTargetSlot)
                                    ConnectionDirection.INPUT_TO_OUTPUT -> onOutputSlotTapped?.invoke(tappedTargetSlot)
                                }
                                return@detectTapGestures
                            }
                        }

                        // A subgraph instance's Open button, in every mode (not while a connection waits)
                        val open = currentOnOpenSubgraph.value
                        if (socketsActive && !inConnectionMode && open != null) {
                            graph.nodes.lastOrNull { node ->
                                node.id in currentInstanceTexts.value && openButtonBounds(node).contains(Offset(graphX, graphY))
                            }?.let {
                                open(it.id)
                                return@detectTapGestures
                            }
                        }

                        // In edit mode (not connection mode), check for taps on output slots to start connection
                        if (socketsActive && inEditMode && !inConnectionMode && onOutputSlotTapped != null) {
                            findOutputSlotAt(graphX, graphY, graph.nodes, radius)?.let {
                                onOutputSlotTapped(it)
                                return@detectTapGestures
                            }
                        }

                        // In edit mode (not connection mode), check for taps on input slots to start reverse connection
                        if (socketsActive && inEditMode && !inConnectionMode && onInputSlotTapped != null) {
                            val tappedInputSlot = findInputSlotAt(
                                graphX = graphX,
                                graphY = graphY,
                                nodes = graph.nodes,
                                slotHitRadius = radius,
                                nodeDefinitions = currentNodeDefinitions.value,
                                valueSockets = true
                            )
                            if (tappedInputSlot != null) {
                                onInputSlotTapped(tappedInputSlot)
                                return@detectTapGestures
                            }
                        }

                        // Check for taps on notes (select/deselect, same as nodes)
                        for (note in currentNotes.value) {
                            if (graphX >= note.x && graphX <= note.x + note.width &&
                                graphY >= note.y && graphY <= note.y + note.height
                            ) {
                                // Tap anywhere on note (except edit icon) -> select/deselect
                                onNoteTapped?.invoke(note.id)
                                return@detectTapGestures
                            }
                        }

                        // Find tapped node
                        val tappedNode = graph.nodes.find { node ->
                            graphX >= node.x && graphX <= node.x + node.width &&
                                    graphY >= node.y && graphY <= node.y + node.height
                        }

                        if (tappedNode != null) {
                            onNodeTapped?.invoke(tappedNode.id)
                        } else {
                            onTapOutsideNodes?.invoke()
                        }
                    }
                }
            }
            .pointerInput(enableManualTransform) {
                if (enableManualTransform) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()

                            // Skip pan/zoom processing if a node drag is in progress
                            if (currentDraggingNodeId.value != null) {
                                // Just consume and wait for release
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()

                            if (zoom != 1f || pan != Offset.Zero) {
                                val oldScale = currentScaleState.value
                                val newScale = (oldScale * zoom).coerceIn(0.2f, 3f)
                                val oldOffset = currentOffsetState.value

                                // When zooming, adjust offset to keep the pinch centroid stationary
                                // The point under the centroid in graph coords should stay under the centroid
                                val zoomChange = newScale / oldScale
                                val newOffset = Offset(
                                    x = centroid.x - (centroid.x - oldOffset.x) * zoomChange + pan.x,
                                    y = centroid.y - (centroid.y - oldOffset.y) * zoomChange + pan.y
                                )
                                onTransform(newScale, newOffset)
                            }

                            // Consume changes to prevent scrolling
                            event.changes.forEach { change ->
                                if (change.positionChanged()) {
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
    ) {
        // Compute node IDs that are valid connection targets in connection mode
        val connectionCandidateNodeIds = connectionModeState?.validTargetSlots?.map { it.nodeId }?.toSet() ?: emptySet()

        // The canvas itself (in a preview too, so sockets' halos match what's around them)
        drawRect(colors.canvas)

        // Level of detail: graph units per dp on screen (sizes fixed on screen), and the geometry of both levels
        // while a switch crossfades
        val unitsPerDp = density.density / scale
        val specZoom = GraphDetailLevel.specZoom(scale, density.density)
        // Overview: the title size shared by all blocks, at most what a line of the lowest block takes
        val lowestBlock = minOf(
            animatedNodes.minOfOrNull { it.node.height } ?: Float.MAX_VALUE,
            animatedNotes.minOfOrNull { it.note.height } ?: Float.MAX_VALUE
        )
        val overviewTitleSize = minOf(overviewTitlePx / scale, lowestBlock * 0.5f)
        val minSelectionWidth = 2f * unitsPerDp
        // How far the title bars have spread over their nodes (0 full detail, 1 overview); a spring may overshoot
        val spread = overviewProgress.value.coerceIn(0f, 1f)

        // Build node positions map for edge drawing
        val nodePositionsMap = animatedNodes.associate { it.node.id to it.node }

        // What lies on screen, in graph units, with room for outlines, badges and shadows: only that is drawn (a
        // large graph zoomed in shows a few of its nodes)
        val cullMargin = 120f + 48f * unitsPerDp
        val visibleLeft = -offset.x / scale - cullMargin
        val visibleTop = -offset.y / scale - cullMargin
        val visibleRight = (size.width - offset.x) / scale + cullMargin
        val visibleBottom = (size.height - offset.y) / scale + cullMargin
        fun onScreen(x: Float, y: Float, width: Float, height: Float) =
            x < visibleRight && x + width > visibleLeft && y < visibleBottom && y + height > visibleTop
        // A wire lies within its two nodes' box, widened by how far its curve can swing sideways
        fun wireOnScreen(edge: WorkflowEdge): Boolean {
            val source = nodePositionsMap[edge.sourceNodeId] ?: return false
            val target = nodePositionsMap[edge.targetNodeId] ?: return false
            val left = minOf(source.x, target.x)
            val right = maxOf(source.x + source.width, target.x + target.width)
            val top = minOf(source.y, target.y)
            val bottom = maxOf(source.y + source.height, target.y + target.height)
            val swing = (right - left + bottom - top) * 0.4f + 100f
            return onScreen(left - swing, top, right - left + 2 * swing, bottom - top)
        }

        // Wires: those of focused nodes over the others, which recede
        val progress = focusProgress.value
        val (focusedEdges, otherEdges) = animatedEdges.partition { (edge, _) ->
            edge.sourceNodeId in shownFocus || edge.targetNodeId in shownFocus
        }
        val wiresInOrder = (otherEdges + focusedEdges).filter { (edge, alpha) -> alpha > 0.01f && wireOnScreen(edge) }
        fun DrawScope.drawWireUnclipped(edge: WorkflowEdge, alpha: Float, before: WireEmphasis, after: WireEmphasis) {
            drawEdge(
                edge = edge,
                nodesById = nodePositionsMap,
                colors = colors,
                router = edgeRouter,
                alpha = alpha,
                recede = lerp(before.recede, after.recede, progress),
                // Scaling with the zoom, but at least 1.5 dp on screen (focused 2.25 dp)
                width = lerp(before.width, after.width, progress).let { maxOf(it, it * 0.375f * unitsPerDp) },
                draggingNodeId = draggingNodeId,
                dragScale = dragScale.value,
                wirePaths = wirePaths
            )
        }
        fun DrawScope.drawWire(edge: WorkflowEdge, alpha: Float) {
            val before = wireEmphasis(edge, previousFocus)
            val after = wireEmphasis(edge, shownFocus)
            // A wire leaves its socket with a curve that swings back past it, hidden under a node's body; a port strip
            // is narrow glass, so its wires don't swing back ([drawEdge]) and are drawn whole: a wire bending past the
            // strip on its way (a target close beside it, far above or below) stays visible
            drawWireUnclipped(edge, alpha, before, after)
        }

        // Each wire's part under frosted glass: the groups (and a level's port strips, of the same glass) it passes
        // through without reaching one of their members
        val portStrips = animatedNodes.filter { (node, alpha) -> alpha > 0.01f && SubgraphLevels.isPortNode(node) }
        val groupAreas = animatedGroups.filter { (group, alpha) ->
            alpha > 0.01f && onScreen(group.x, group.y, group.width, group.height)
        }.map { (group, _) ->
            group.group.memberNodeIds to Path().apply {
                addRoundRect(
                    RoundRect(
                        group.x, group.y, group.x + group.width, group.y + group.height,
                        CornerRadius(NodeStyle.GROUP_CORNER)
                    )
                )
            }
        } + portStrips.map { (node, _) ->
            setOf(node.id) to Path().apply {
                val bounds = portStripBounds(node)
                addRoundRect(RoundRect(bounds, CornerRadius(bounds.width / 2)))
            }
        }
        // Each wire's glass areas, by index. Clips are by single areas, each a rounded rectangle, which the renderer
        // clips on the GPU; a shape made of several (or what lies outside one) would be a mask drawn on the CPU
        val underGlass: Map<WorkflowEdge, List<Int>> = wiresInOrder.mapNotNull { (edge, _) ->
            val passed = groupAreas.indices.filter { i ->
                val members = groupAreas[i].first
                edge.sourceNodeId !in members && edge.targetNodeId !in members
            }
            if (passed.isEmpty()) null else edge to passed
        }.toMap()
        val anyUnderGlass = underGlass.isNotEmpty()
        // The wires in two layers, the focused nodes' over the others
        val wireLayers = wiresInOrder.partition { (edge, _) ->
            edge.sourceNodeId !in shownFocus && edge.targetNodeId !in shownFocus
        }.toList()
        // What lies outside the areas [areas] (from [from] on), one area at a time
        fun DrawScope.outsideAreas(areas: List<Int>, from: Int = 0, block: DrawScope.() -> Unit) {
            if (from == areas.size) block()
            else clipPath(groupAreas[areas[from]].second, ClipOp.Difference) { outsideAreas(areas, from + 1, block) }
        }

        // Those parts, drawn on screen into a layer that blurs them (a frosted look, scaling with the zoom): each area
        // with the wires passing under it. At half the screen's resolution (blurred, nothing tells it apart), so a
        // quarter of the pixels to blur
        if (anyUnderGlass) {
            val layerSize = IntSize(
                (size.width / GLASS_LAYER_SCALE).toInt().coerceAtLeast(1),
                (size.height / GLASS_LAYER_SCALE).toInt().coerceAtLeast(1)
            )
            glassLayer.record(this, layoutDirection, layerSize) {
                withTransform({
                    scale(1f / GLASS_LAYER_SCALE, 1f / GLASS_LAYER_SCALE, Offset.Zero)
                    translate(offset.x, offset.y)
                    scale(scale, scale, Offset.Zero)
                }) {
                    groupAreas.forEachIndexed { area, (_, shape) ->
                        clipPath(shape) {
                            wireLayers.forEach { layer ->
                                layer.forEach { (edge, alpha) -> if (underGlass[edge]?.contains(area) == true) drawWire(edge, alpha) }
                            }
                        }
                    }
                }
            }
            val blur = (GLASS_BLUR * scale / GLASS_LAYER_SCALE).coerceAtLeast(0.5f)
            glassLayer.renderEffect = BlurEffect(blur, blur, TileMode.Decal)
        }

        // Apply transformation
        withTransform({
            translate(offset.x, offset.y)
            scale(scale, scale, Offset.Zero)
        }) {
            // Draw grid for manual node placement (behind everything)
            if (showGrid) {
                val gridColor = colors.outlineVariant
                val gridSize = WorkflowLayoutEngine.GRID_CELL_SIZE

                // Calculate visible range in graph coordinates
                val visibleMinX = -offset.x / scale - gridSize
                val visibleMinY = -offset.y / scale - gridSize
                val visibleMaxX = (size.width - offset.x) / scale + gridSize
                val visibleMaxY = (size.height - offset.y) / scale + gridSize
                // A hairline on screen at any zoom
                val lineWidth = 1f / scale

                // Draw vertical lines
                var x = (visibleMinX / gridSize).toInt() * gridSize
                while (x <= visibleMaxX) {
                    drawLine(
                        color = gridColor,
                        start = Offset(x, visibleMinY),
                        end = Offset(x, visibleMaxY),
                        strokeWidth = lineWidth
                    )
                    x += gridSize
                }

                // Draw horizontal lines
                var y = (visibleMinY / gridSize).toInt() * gridSize
                while (y <= visibleMaxY) {
                    drawLine(
                        color = gridColor,
                        start = Offset(visibleMinX, y),
                        end = Offset(visibleMaxX, y),
                        strokeWidth = lineWidth
                    )
                    y += gridSize
                }
            } else if (isEditMode && specZoom >= 0.4f) {
                // Dot grid while editing (spec §12.7): 24 dp pitch at 100 %, scaling with the zoom; dots 1 dp
                val pitch = 48f
                val minX = kotlin.math.floor(-offset.x / scale / pitch) * pitch
                val minY = kotlin.math.floor(-offset.y / scale / pitch) * pitch
                val maxX = (size.width - offset.x) / scale
                val maxY = (size.height - offset.y) / scale
                val dots = ArrayList<Offset>()
                var dotX = minX
                while (dotX <= maxX) {
                    var dotY = minY
                    while (dotY <= maxY) {
                        dots.add(Offset(dotX, dotY))
                        dotY += pitch
                    }
                    dotX += pitch
                }
                drawPoints(dots, PointMode.Points, colors.outlineVariant, strokeWidth = unitsPerDp, cap = StrokeCap.Round)
            }

            // The wires that only pass through a group, blurred, under the group's frosted area
            withTransform({
                scale(1f / scale, 1f / scale, Offset.Zero)
                translate(-offset.x, -offset.y)
                // Recorded at a lower resolution: drawn back at the screen's
                scale(GLASS_LAYER_SCALE, GLASS_LAYER_SCALE, Offset.Zero)
            }) {
                if (anyUnderGlass) drawLayer(glassLayer)
            }

            // Groups' areas: frosted glass over the wires passing under them; their labels go above the wires
            animatedGroups.forEach { (group, alpha) ->
                if (alpha > 0.01f && onScreen(group.x, group.y, group.width, group.height)) {
                    drawGroupArea(group = group, colors = colors, paints = paints, alpha = alpha)
                }
            }

            // A level's Inputs and Outputs strips, of the same glass; their own wires go over them
            portStrips.forEach { (node, alpha) ->
                val inputs = SubgraphLevels.isInputsNode(node)
                val types = if (inputs) node.outputs.map { it.type } else node.inputs.keys.map { key ->
                    (node.inputs[key] as? InputValue.UnconnectedSlot)?.slotType ?: nodeDefinitions[node.classType]?.inputs?.find { it.name == key }?.type
                }
                drawPortStrip(
                    node = node,
                    word = if (inputs) inputsLabel else outputsLabel,
                    addWord = addLabel,
                    socketTypes = types,
                    socketNames = if (inputs) node.outputs.map { it.name } else node.inputs.keys.toList(),
                    colors = colors, paints = paints, icons = icons, alpha = alpha,
                    spread = spread
                )
            }

            // The wires, sharp outside the groups they pass under (their own groups' wires stay above the glass)
            wireLayers.forEach { layer ->
                layer.forEach { (edge, alpha) -> if (edge !in underGlass) drawWire(edge, alpha) }
                // Wires under the same areas together, outside them
                layer.filter { (edge, _) -> edge in underGlass }.groupBy { (edge, _) -> underGlass.getValue(edge) }
                    .forEach { (areas, wires) -> outsideAreas(areas) { wires.forEach { (edge, alpha) -> drawWire(edge, alpha) } } }
            }

            // A port strip's icon, and toward the overview its block growing over its wires' ends, as a node's title
            // bar spreads over its sockets
            portStrips.forEach { (node, alpha) -> drawPortStripBlock(node, colors, paints, icons, alpha, spread) }

            // Wire colour of each connected input (its wire's type), and the outputs that have a wire
            val inputWireColors: Map<String, Map<String, Color>> = buildMap<String, MutableMap<String, Color>> {
                graph.edges.forEach { edge ->
                    getOrPut(edge.targetNodeId) { mutableMapOf() }[edge.targetInputName] = colors.slot(edge.slotType)
                }
            }
            val connectedOutputs: Set<Pair<String, Int>> = graph.edges.mapTo(HashSet()) { it.sourceNodeId to it.sourceOutputIndex }

            // Groups' labels over the wires, so a wire never runs across one
            animatedGroups.forEach { (group, alpha) ->
                if (alpha > 0.01f && onScreen(group.x, group.y, group.width, group.height)) {
                    drawGroupLabel(group = group, colors = colors, paints = paints, alpha = alpha, chipAlpha = 1f - spread)
                }
            }

            // Draw nodes with alpha - sort by ID for consistent z-order (higher IDs on top)
            val sortedAnimatedNodes = animatedNodes.sortedBy { it.node.id }
            sortedAnimatedNodes.forEach { (node, alpha) ->
                // A level's port strips are drawn with the groups; nodes off screen aren't drawn
                if (alpha > 0.01f && !SubgraphLevels.isPortNode(node) && onScreen(node.x, node.y, node.width, node.height)) {
                    val highlightState = when {
                        // Mapping mode selection
                        isFieldMappingMode && node.id in mappingSelectedNodeIds -> NodeHighlightState.SELECTED
                        isFieldMappingMode && node.id in highlightedNodeIds -> NodeHighlightState.CANDIDATE
                        // Connection mode - the node it starts from (findable when zoomed out), and valid target nodes
                        inConnectionMode && node.id == connectionModeState?.sourceSlot?.nodeId -> NodeHighlightState.SELECTED
                        inConnectionMode && node.id in connectionCandidateNodeIds -> NodeHighlightState.CANDIDATE
                        // Edit mode selection
                        isEditMode && node.id in selectedNodeIds -> NodeHighlightState.SELECTED
                        // Attribute editing mode
                        editingNodeId == node.id -> NodeHighlightState.SELECTED
                        else -> NodeHighlightState.NONE
                    }

                    // Get edits for this node
                    val nodeEdits = nodeAttributeEdits[node.id] ?: emptyMap()
                    val nodeDefinition = nodeDefinitions[node.classType]
                    // Node values mode only (not while editing the structure or mapping)
                    val showChangedDot = !isEditMode && !isFieldMappingMode && nodeEdits.isNotEmpty() &&
                        changedInputNames(node, nodeDefinition, nodeEdits).isNotEmpty()
                    // The server lacks this node type (only known once the server's definitions are there)
                    val isMissing = nodeDefinitions.isNotEmpty() && nodeDefinition == null

                    // Editable inputs are only highlighted when this node is being edited
                    val highlightEditableInputs = editingNodeId == node.id

                    // The node being dragged is drawn lifted (its position already includes dragOffset)
                    val nodeScale = if (draggingNodeId == node.id) dragScale.value else 1f
                    withTransform({
                        scale(nodeScale, nodeScale, Offset(node.x + node.width / 2, node.y + node.height / 2))
                    }) {
                        // Full detail until the title bar has covered the node; then only the block
                        if (spread < 1f) {
                            drawNode(
                                node = node,
                                colors = colors,
                                paints = paints,
                                icons = icons,
                                alpha = alpha,
                                highlightState = highlightState,
                                isMissing = isMissing,
                                nodeEdits = nodeEdits,
                                nodeDefinition = nodeDefinition,
                                editableInputNames = if (highlightEditableInputs) editableInputNames else emptySet(),
                                displayNameResolver = displayNameResolver,
                                inputWireColors = inputWireColors[node.id] ?: emptyMap(),
                                mappedFields = graph.mappedFields,
                                showChangedDot = showChangedDot,
                                instance = instanceTexts[node.id],
                                shownInputs = shownOnInstance.filter { it.first == node.id }.mapTo(HashSet()) { it.second },
                                off = offOf(node),
                                // The badge only for a node bypassed itself, not for one inside a bypassed instance
                                bypassedLabel = bypassedLabel.takeIf { node.isBypassed }
                            )
                        }
                        if (spread > 0f) {
                            val (tint, titleColor) = titleBarColors(node, isMissing, colors)
                            val off = offOf(node)
                            drawTitleSpread(
                                left = node.x, top = node.y, width = node.width, height = node.height,
                                title = node.title, tint = tint, titleColor = if (off > 0f) lerp(titleColor, colors.outline, off) else titleColor,
                                spread = spread, overviewTitleSize = overviewTitleSize,
                                colors = colors, paints = paints, alpha = alpha,
                                icon = if (node.isSubgraphInstance) icons.subgraph else null,
                                // A bypassed block: the icon at its end, no word (spec §20.2)
                                trailingIcon = if (node.isBypassed) icons.bypassed else null
                            )
                            if (spread >= 1f && highlightState != NodeHighlightState.NONE) {
                                drawBlockSelection(
                                    node.x, node.y, node.width, node.height, colors, alpha, minSelectionWidth,
                                    candidate = highlightState == NodeHighlightState.CANDIDATE
                                )
                            }
                        }
                    }
                }
            }

            // Draw notes with alpha
            var anyHeightChanged = false
            val originalNotesById = notes.associateBy { it.id }
            animatedNotes.forEach { (note, alpha) ->
                if (alpha > 0.01f) {
                    val originalNote = originalNotesById[note.id]
                    val heightBefore = originalNote?.height ?: 0f
                    val isSelected = note.id in selectedNoteIds
                    // Off screen only measured (the layout needs its height; laid out once, so it's cheap)
                    val shown = onScreen(note.x, note.y, note.width, maxOf(note.height, 1f))

                    // The note being dragged is drawn lifted
                    val noteScale = if (draggingNoteId == note.id) dragScale.value else 1f
                    withTransform({
                        scale(noteScale, noteScale, Offset(note.x + note.width / 2, note.y + note.height / 2))
                    }) {
                        // Full detail (which also measures the note's height) until its title bar has covered it
                        if (spread < 1f || !shown) {
                            drawNote(
                                note = note,
                                colors = colors,
                                paints = paints,
                                alpha = alpha,
                                isSelected = isSelected,
                                textMeasurer = textMeasurer,
                                density = density,
                                noteLayouts = noteLayouts,
                                drawIt = shown
                            )
                        }
                        if (spread > 0f && shown) {
                            drawTitleSpread(
                                left = note.x, top = note.y, width = note.width, height = note.height,
                                title = note.title, tint = colors.note, titleColor = colors.onNote,
                                spread = spread, overviewTitleSize = overviewTitleSize,
                                colors = colors, paints = paints, alpha = alpha
                            )
                            if (spread >= 1f && isSelected) {
                                drawBlockSelection(note.x, note.y, note.width, note.height, colors, alpha, minSelectionWidth, candidate = false)
                            }
                        }
                    }

                    // Copy measured height back to original note for layout
                    if (originalNote != null && note.height != heightBefore) {
                        originalNote.height = note.height
                        anyHeightChanged = true
                    }
                }
            }
            if (anyHeightChanged) {
                noteHeightsChanged = true
            }

            // Sockets on the nodes' edges, in every mode: filled when connected, a ring when free; those the spreading
            // title bar has covered are gone, so none in overview
            // (a level's port strips too: in the overview they're plain blocks)
            if (spread < 1f) animatedNodes.forEach { (node, nodeAlpha) ->
                if (nodeAlpha <= 0.01f || !onScreen(node.x, node.y, node.width, node.height)) return@forEach
                val scaleMultiplier = if (node.id == draggingNodeId) dragScale.value else 1f
                val coveredTo = WorkflowLayoutEngine.NODE_HEADER_HEIGHT + (node.height - WorkflowLayoutEngine.NODE_HEADER_HEIGHT) * spread
                val off = offOf(node)

                node.outputs.forEachIndexed { outputIndex, output ->
                    if (WorkflowLayoutEngine.outputSlotY(node, outputIndex) < coveredTo) return@forEachIndexed
                    val position = scaleSocketPosition(
                        node, node.x + node.width, node.y + WorkflowLayoutEngine.outputSlotY(node, outputIndex),
                        draggingNodeId, dragScale.value
                    )
                    // The slot a connection starts from (tapped, or long-pressed with the node browser open)
                    val isSource = (inConnectionMode &&
                        connectionModeState?.direction == ConnectionDirection.OUTPUT_TO_INPUT &&
                        connectionModeState.sourceSlot.nodeId == node.id &&
                        connectionModeState.sourceSlot.outputIndex == outputIndex) ||
                        (longPressSourceSlot?.nodeId == node.id && longPressSourceSlot.isOutput &&
                            longPressSourceSlot.outputIndex == outputIndex)
                    val add = SubgraphLevels.isPortNode(node) && SubgraphLevels.isAddSocket(output.name)
                    drawSocket(
                        center = position,
                        color = if (isSource) colors.primary else (if (add) colors.onSurfaceVariant else colors.slot(output.type)).switchedOff(off, colors),
                        connected = isSource || (node.id to outputIndex) in connectedOutputs,
                        colors = colors,
                        paints = paints,
                        alpha = nodeAlpha,
                        scale = scaleMultiplier,
                        dashed = add
                    )
                }

                node.inputs.forEach { (inputName, inputValue) ->
                    val isPickTarget = level == GraphDetailLevel.FULL &&
                        valuePickTargets?.any { slot -> slot.nodeId == node.id && slot.slotName == inputName } == true
                    // In Edit structure every value row that can take a link has a socket, as in ComfyUI (the owner's
                    // choice over the spec's selected nodes only); while a connection waits, only its targets and source
                    val valueSocketType = if (isPickTarget) {
                        valueRowType(node, inputName, nodeDefinitions)
                    } else if (inputValue is InputValue.Literal && isEditMode && level == GraphDetailLevel.FULL) {
                        val isTarget = connectionModeState?.validTargetSlots?.any { slot -> slot.nodeId == node.id && slot.slotName == inputName } == true
                        val isValueSource = connectionModeState?.sourceSlot?.let { slot -> !slot.isOutput && slot.nodeId == node.id && slot.slotName == inputName } == true
                        when {
                            isTarget || isValueSource -> linkableValueType(node, inputName, nodeDefinitions)
                            // Without a connection waiting, every value has its socket: lists too, for their field menu
                            connectionModeState == null -> valueRowType(node, inputName, nodeDefinitions)
                            else -> null
                        }
                    } else null
                    if (inputValue !is InputValue.Connection && inputValue !is InputValue.UnconnectedSlot && valueSocketType == null) return@forEach
                    if (WorkflowLayoutEngine.inputSlotY(node, inputName) < coveredTo) return@forEach
                    val position = scaleSocketPosition(
                        node, node.x, node.y + WorkflowLayoutEngine.inputSlotY(node, inputName),
                        draggingNodeId, dragScale.value
                    )
                    val isSource = (inConnectionMode &&
                        connectionModeState?.direction == ConnectionDirection.INPUT_TO_OUTPUT &&
                        connectionModeState.sourceSlot.nodeId == node.id &&
                        connectionModeState.sourceSlot.slotName == inputName) ||
                        (longPressSourceSlot?.nodeId == node.id && !longPressSourceSlot.isOutput &&
                            longPressSourceSlot.slotName == inputName)
                    val typeColor = when {
                        valueSocketType != null -> colors.slot(valueSocketType)
                        inputValue is InputValue.UnconnectedSlot -> colors.slot(inputValue.slotType)
                        else -> inputWireColors[node.id]?.get(inputName) ?: colors.onSurfaceVariant
                    }
                    val add = SubgraphLevels.isPortNode(node) && SubgraphLevels.isAddSocket(inputName)
                    drawSocket(
                        center = position,
                        color = if (isSource) colors.primary else (if (add) colors.onSurfaceVariant else typeColor).switchedOff(off, colors),
                        connected = isSource || inputValue is InputValue.Connection,
                        colors = colors,
                        paints = paints,
                        alpha = nodeAlpha,
                        scale = scaleMultiplier,
                        dashed = add
                    )
                }
            }

            // Values that can be picked for a custom field: the same ring as a connection's targets (spec §18.3)
            if (level == GraphDetailLevel.FULL) {
                val ringRadius = NodeStyle.SOCKET_RADIUS + NodeStyle.SOCKET_HALO + NodeStyle.SELECTION_WIDTH / 2
                valuePickTargets?.forEach { slot ->
                    val node = graph.nodes.find { it.id == slot.nodeId } ?: return@forEach
                    drawCircle(
                        color = colors.primary,
                        radius = ringRadius,
                        center = Offset(node.x, node.y + WorkflowLayoutEngine.inputSlotY(node, slot.slotName)),
                        style = Stroke(NodeStyle.SELECTION_WIDTH)
                    )
                }
            }

            // Connection feedback while editing (sockets can be tapped at full detail only)
            if (isEditMode && level == GraphDetailLevel.FULL) {
                // Compatible slots of a pending connection: a ring in primary around the socket
                val ringRadius = NodeStyle.SOCKET_RADIUS + NodeStyle.SOCKET_HALO + NodeStyle.SELECTION_WIDTH / 2
                connectionModeState?.validTargetSlots?.forEach { slot ->
                    drawCircle(
                        color = colors.primary,
                        radius = ringRadius,
                        center = slot.center,
                        style = Stroke(NodeStyle.SELECTION_WIDTH)
                    )
                }

                // Long-press on a slot: the ring grows until the press counts
                longPressSlot?.let { slot ->
                    val pressProgress = longPressProgress.value
                    if (pressProgress > 0f) {
                        drawCircle(
                            color = colors.primary,
                            radius = ringRadius + pressProgress * 24f,
                            center = slot.center,
                            style = Stroke(NodeStyle.SELECTION_WIDTH)
                        )
                    }
                }
            }
        }
    }
}

/** The level of detail at [scale], with hysteresis (see [GraphDetailLevel.next]); the editor's status uses it too. */
@Composable
fun rememberGraphDetailLevel(scale: Float): GraphDetailLevel {
    val density = LocalDensity.current.density
    // The previous level, read and replaced as the scale changes: not state, the scale drives recomposition
    val previous = remember { arrayOfNulls<GraphDetailLevel>(1) }
    return GraphDetailLevel.next(GraphDetailLevel.specZoom(scale, density), previous[0]).also { previous[0] = it }
}

/** A node's title bar colours: its tint (neutral when muted or bypassed, error when the server lacks it) and text. */
private fun titleBarColors(node: WorkflowNode, isMissing: Boolean, colors: CanvasColors): Pair<Color, Color> = when {
    isMissing -> colors.errorContainer to colors.onErrorContainer
    // A subgraph instance keeps its frame's colour whatever its state (the badge tells it)
    node.isSubgraphInstance -> colors.instanceFrame to colors.onInstanceFrame
    node.isMuted || node.isBypassed -> colors.neutralTint to colors.onSurface
    else -> (colors.palette.titleTintOf(node) ?: colors.neutralTint) to colors.onSurface
}

/**
 * The title bar of a node or note spreading over all of it toward the overview (spec §12.6), and back. Its title
 * (the one the user gave it) follows: from the top to the middle, from its own size to the overview's, on two lines
 * once the bar has room for them. [spread] 0 is full detail (nothing drawn here), 1 the overview block, which then
 * carries the light theme's shadow instead of the node.
 */
private fun DrawScope.drawTitleSpread(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    title: String,
    tint: Color,
    titleColor: Color,
    spread: Float,
    overviewTitleSize: Float,
    colors: CanvasColors,
    paints: GraphPaints,
    alpha: Float,
    // Before the title, at its size (a subgraph instance's block)
    icon: VectorPainter? = null,
    // At the block's end, at the title's size, in onSurfaceVariant (a bypassed node's block, spec §20.2)
    trailingIcon: VectorPainter? = null
) {
    if (alpha <= 0.01f) return
    val header = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
    val corner = NodeStyle.CORNER
    val barHeight = header + (height - header) * spread
    if (spread >= 1f && !colors.isDark) {
        paints.shadow.color = tint.copy(alpha = alpha).toArgb()
        paints.shadow.setElevationShadow(colors, alpha)
        drawContext.canvas.nativeCanvas.drawRoundRect(left, top, left + width, top + height, corner, corner, paints.shadow)
    } else {
        // The bar's bottom corners round off as it reaches the node's rounded bottom
        val bottomCorner = CornerRadius((corner - (height - barHeight)).coerceAtLeast(0f))
        drawPath(
            path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left, top = top, right = left + width, bottom = top + barHeight,
                        topLeftCornerRadius = CornerRadius(corner), topRightCornerRadius = CornerRadius(corner),
                        bottomRightCornerRadius = bottomCorner, bottomLeftCornerRadius = bottomCorner
                    )
                )
            },
            color = tint.copy(alpha = alpha)
        )
    }

    val padding = NodeStyle.TITLE_PADDING
    val paint = paints.blockTitle.apply {
        textSize = NodeStyle.TITLE_TEXT + (overviewTitleSize - NodeStyle.TITLE_TEXT) * spread
        color = titleColor.copy(alpha = alpha).toArgb()
    }
    val lineHeight = paint.fontSpacing
    val iconSize = if (icon != null) paint.textSize else 0f
    val iconSpace = if (icon != null) iconSize + paint.textSize * 0.4f else 0f
    val trailingSize = paint.textSize * 1.2f
    val trailingSpace = if (trailingIcon != null) trailingSize + paint.textSize * 0.4f else 0f
    val textWidth = width - 2 * padding - iconSpace - trailingSpace
    val lines = if (barHeight - 2 * padding >= 2 * lineHeight) {
        wrapTwoLines(title, textWidth, paint)
    } else {
        listOf(ellipsizeEnd(title, textWidth, paint))
    }
    val firstCenter = top + barHeight / 2 - (lines.size - 1) * lineHeight / 2
    if (icon != null) {
        translate(left + padding, firstCenter - iconSize / 2) {
            with(icon) { draw(Size(iconSize, iconSize), alpha = alpha, colorFilter = ColorFilter.tint(titleColor)) }
        }
    }
    lines.forEachIndexed { index, line ->
        drawContext.canvas.nativeCanvas.drawText(line, left + padding + iconSpace, paint.baselineFor(firstCenter + index * lineHeight), paint)
    }
    if (trailingIcon != null) {
        translate(left + width - padding - trailingSize, top + barHeight / 2 - trailingSize / 2) {
            with(trailingIcon) { draw(Size(trailingSize, trailingSize), alpha = alpha, colorFilter = ColorFilter.tint(colors.onSurfaceVariant)) }
        }
    }
}

/**
 * A colour of a switched-off node's content (spec §20, without transparency as the owner chose): text toward
 * `outline`, closer to the body, [off] of the way; sockets and marks toward `outlineVariant`.
 */
private fun Color.switchedOff(off: Float, colors: CanvasColors, text: Boolean = false): Color =
    if (off <= 0f) this else lerp(this, if (text) colors.outline else colors.outlineVariant, off)

/** Selection of an overview block: as at full detail, at least 2 dp on screen; candidates dashed. */
private fun DrawScope.drawBlockSelection(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    colors: CanvasColors,
    alpha: Float,
    minWidth: Float,
    candidate: Boolean
) {
    val strokeWidth = maxOf(NodeStyle.SELECTION_WIDTH, minWidth)
    val outset = NodeStyle.SELECTION_GAP + strokeWidth / 2
    drawRoundRect(
        color = colors.primary.copy(alpha = alpha),
        topLeft = Offset(left - outset, top - outset),
        size = Size(width + 2 * outset, height + 2 * outset),
        cornerRadius = CornerRadius(NodeStyle.CORNER + outset),
        style = Stroke(
            width = strokeWidth,
            pathEffect = if (candidate) PathEffect.dashPathEffect(floatArrayOf(3 * strokeWidth, 2 * strokeWidth)) else null
        )
    )
}

/** [text] on at most two lines of [maxWidth], broken between words, the second cut with "…" when it doesn't fit. */
private fun wrapTwoLines(text: String, maxWidth: Float, paint: Paint): List<String> {
    if (paint.measureText(text) <= maxWidth) return listOf(text)
    val words = text.split(' ').filter { it.isNotEmpty() }
    var first = ""
    var used = 0
    for (word in words) {
        val candidate = if (first.isEmpty()) word else "$first $word"
        if (paint.measureText(candidate) > maxWidth) break
        first = candidate
        used++
    }
    // A first word too long for the line: cut it there
    if (used == 0) return listOf(ellipsizeEnd(text, maxWidth, paint))
    val rest = words.drop(used).joinToString(" ")
    return listOf(first, ellipsizeEnd(rest, maxWidth, paint)).filter { it.isNotEmpty() }
}

/**
 * A node (spec §12.3): rounded, no outline, a title bar in its tint, rows of input labels (in their slot colour),
 * widget values as pills (literal ones neutral, ones the generation screen fills tonal with a link icon) and output
 * labels. Sockets are drawn separately, over everything ([drawSocket]).
 */
private fun DrawScope.drawNode(
    node: WorkflowNode,
    colors: CanvasColors,
    paints: GraphPaints,
    icons: GraphIcons,
    alpha: Float = 1f,
    highlightState: NodeHighlightState = NodeHighlightState.NONE,
    isMissing: Boolean = false,
    nodeEdits: Map<String, Any> = emptyMap(),
    nodeDefinition: NodeTypeDefinition? = null,
    editableInputNames: Set<String> = emptySet(),
    displayNameResolver: (String) -> String = { it },
    inputWireColors: Map<String, Color> = emptyMap(),
    mappedFields: Map<Pair<String, String>, String> = emptyMap(),
    // Node values: a dot after the title when a value differs from the workflow's
    showChangedDot: Boolean = false,
    // A subgraph instance: drawn as a frame around its body, with its Open button (spec §19.1)
    instance: InstanceText? = null,
    // Inside a subgraph: values its instance shows, marked after their names (spec §19.3)
    shownInputs: Set<String> = emptySet(),
    // How switched off its content looks (spec §20): 0 on, 1 bypassed; [bypassedLabel] its badge's word
    off: Float = 0f,
    bypassedLabel: String? = null
) {
    fun Color.faded() = copy(alpha = this.alpha * alpha)
    fun Color.offText() = switchedOff(off, colors, text = true)
    fun Color.offMark() = switchedOff(off, colors)

    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
    val left = node.x
    val top = node.y
    val right = node.x + node.width
    val bottom = node.y + node.height

    // A missing type, a muted or a bypassed node: a neutral (or error) title bar and a badge; the body stays as it is.
    // A subgraph instance keeps its frame in every state (the badge tells it)
    val (titleBar, titleText, badge) = when {
        instance != null && node.isMuted -> Triple(colors.instanceFrame, colors.onInstanceFrame, icons.muted)
        instance != null && node.isBypassed -> Triple(colors.instanceFrame, colors.onInstanceFrame, null)
        instance != null -> Triple(colors.instanceFrame, colors.onInstanceFrame, null)
        isMissing -> Triple(colors.errorContainer, colors.onErrorContainer, icons.missing)
        node.isMuted -> Triple(colors.neutralTint, colors.onSurface, icons.muted)
        node.isBypassed -> Triple(colors.neutralTint, colors.onSurface, null)
        else -> Triple(colors.palette.titleTintOf(node) ?: colors.neutralTint, colors.onSurface, null)
    }
    val badgeColor = when {
        instance != null -> colors.onInstanceFrame
        isMissing -> colors.onErrorContainer
        else -> colors.onSurfaceVariant
    }

    if (instance != null) {
        // A rounded rectangle in a rounded rectangle: the frame (its top the title row, a thin bezel around the rest)
        // and inside it the body
        val corner = NodeStyle.INSTANCE_CORNER
        if (colors.isDark) {
            drawRoundRect(color = colors.instanceFrame.faded(), topLeft = Offset(left, top), size = Size(node.width, node.height), cornerRadius = CornerRadius(corner))
        } else {
            paints.shadow.color = colors.instanceFrame.faded().toArgb()
            paints.shadow.setElevationShadow(colors, alpha)
            drawContext.canvas.nativeCanvas.drawRoundRect(left, top, right, bottom, corner, corner, paints.shadow)
        }
        val bezel = NodeStyle.INSTANCE_BEZEL
        drawRoundRect(
            color = colors.nodeBody.faded(),
            topLeft = Offset(left + bezel, top + headerHeight),
            size = Size(node.width - 2 * bezel, node.height - headerHeight - bezel),
            cornerRadius = CornerRadius(NodeStyle.INSTANCE_INNER_CORNER)
        )
    } else {
        // Body; in the light theme with a soft shadow (elevation 1), as white nodes need it on the light canvas
        if (colors.isDark) {
            drawRoundRect(
                color = colors.nodeBody.faded(),
                topLeft = Offset(left, top),
                size = Size(node.width, node.height),
                cornerRadius = CornerRadius(NodeStyle.CORNER)
            )
        } else {
            paints.shadow.color = colors.nodeBody.faded().toArgb()
            paints.shadow.setElevationShadow(colors, alpha)
            drawContext.canvas.nativeCanvas.drawRoundRect(
                left, top, right, bottom, NodeStyle.CORNER, NodeStyle.CORNER, paints.shadow
            )
        }

        // Title bar: the top of the node's shape
        drawPath(
            path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left, top = top, right = right, bottom = top + headerHeight,
                        topLeftCornerRadius = CornerRadius(NodeStyle.CORNER),
                        topRightCornerRadius = CornerRadius(NodeStyle.CORNER),
                        bottomRightCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius.Zero
                    )
                )
            },
            color = titleBar.faded()
        )
    }

    // Title (a subgraph instance's after its icon), leaving room for the changed dot and the badge
    val titleCenterY = top + headerHeight / 2
    val iconSpace = if (instance != null) NodeStyle.ICON + NodeStyle.ICON_GAP else 0f
    if (instance != null) {
        translate(left + NodeStyle.TITLE_PADDING, titleCenterY - NodeStyle.ICON / 2) {
            with(icons.subgraph) { draw(Size(NodeStyle.ICON, NodeStyle.ICON), alpha = alpha, colorFilter = ColorFilter.tint(titleText)) }
        }
    }
    val dotSpace = if (showChangedDot) 12f + 2 * NodeStyle.CHANGED_DOT_RADIUS else 0f
    // A bypassed node's badge: a chip in the body's colour, at full strength (spec §20.1)
    val chipPaint = paints.badge.apply { color = colors.onSurfaceVariant.faded().toArgb() }
    val chipWidth = bypassedLabel?.let { NodeStyle.CHIP_PADDING_START + NodeStyle.CHIP_ICON + NodeStyle.CHIP_GAP + chipPaint.measureText(it) + NodeStyle.CHIP_PADDING_END }
    val badgeSpace = (if (badge != null) NodeStyle.BADGE + 12f else 0f) + (chipWidth?.let { it + 12f } ?: 0f)
    val titlePaint = paints.title.apply { color = titleText.offText().faded().toArgb() }
    val title = ellipsizeEnd(node.title, node.width - 2 * NodeStyle.TITLE_PADDING - iconSpace - dotSpace - badgeSpace, titlePaint)
    val nativeCanvas = drawContext.canvas.nativeCanvas
    nativeCanvas.drawText(title, left + NodeStyle.TITLE_PADDING + iconSpace, titlePaint.baselineFor(titleCenterY), titlePaint)
    if (showChangedDot) {
        drawCircle(
            color = colors.primary.offMark().faded(),
            radius = NodeStyle.CHANGED_DOT_RADIUS,
            center = Offset(
                left + NodeStyle.TITLE_PADDING + iconSpace + titlePaint.measureText(title) + 12f + NodeStyle.CHANGED_DOT_RADIUS,
                titleCenterY
            )
        )
    }
    if (badge != null) {
        translate(right - NodeStyle.TITLE_PADDING - NodeStyle.BADGE, titleCenterY - NodeStyle.BADGE / 2) {
            with(badge) {
                draw(Size(NodeStyle.BADGE, NodeStyle.BADGE), alpha = alpha, colorFilter = ColorFilter.tint(badgeColor))
            }
        }
    }
    if (bypassedLabel != null && chipWidth != null) {
        val chipLeft = right - NodeStyle.TITLE_PADDING - chipWidth
        drawRoundRect(
            color = colors.nodeBody.faded(),
            topLeft = Offset(chipLeft, titleCenterY - NodeStyle.CHIP_HEIGHT / 2),
            size = Size(chipWidth, NodeStyle.CHIP_HEIGHT),
            cornerRadius = CornerRadius(NodeStyle.CHIP_HEIGHT / 2)
        )
        translate(chipLeft + NodeStyle.CHIP_PADDING_START, titleCenterY - NodeStyle.CHIP_ICON / 2) {
            with(icons.bypassed) {
                draw(Size(NodeStyle.CHIP_ICON, NodeStyle.CHIP_ICON), alpha = alpha, colorFilter = ColorFilter.tint(colors.onSurfaceVariant))
            }
        }
        nativeCanvas.drawText(
            bypassedLabel,
            chipLeft + NodeStyle.CHIP_PADDING_START + NodeStyle.CHIP_ICON + NodeStyle.CHIP_GAP,
            chipPaint.baselineFor(titleCenterY),
            chipPaint
        )
    }

    // Input rows: connection inputs as labels in their slot colour, widgets as a name and a value pill
    val rowLeft = left + NodeStyle.ROW_PADDING
    val rowRight = right - NodeStyle.ROW_PADDING
    node.inputs.entries.forEachIndexed { rowIndex, (name, value) ->
        val centerY = top + WorkflowLayoutEngine.rowCenterY(rowIndex)
        val editable = name in editableInputNames
        val labelPaint = if (editable) paints.labelStrong else paints.label

        if (value !is InputValue.Literal) {
            val slotColor = when (value) {
                is InputValue.UnconnectedSlot -> colors.slot(value.slotType)
                else -> inputWireColors[name] ?: colors.onSurfaceVariant
            }
            labelPaint.color = (if (editable) colors.primary else slotColor).offText().faded().toArgb()
            // Half the width: an output's label may share the row
            val label = ellipsizeEnd(instance?.labels?.get(name) ?: Subgraphs.displayName(name), node.width / 2 - NodeStyle.ROW_PADDING, labelPaint)
            nativeCanvas.drawText(label, rowLeft, labelPaint.baselineFor(centerY), labelPaint)
            return@forEachIndexed
        }

        // The value shown: an edit, else the workflow's own (an empty one is the definition's default)
        val inputDefinition = nodeDefinition?.inputs?.find { it.name == name }
        val originalValue = value.value.takeUnless { it == "" } ?: inputDefinition?.getEffectiveDefault()
        val editedValue = nodeEdits[name]
        val currentValue = editedValue ?: originalValue
        val isEdited = editedValue != null && !valuesEqual(editedValue, originalValue, inputDefinition?.type)
        val linkedField = linkedFieldName(currentValue, node.id, name, mappedFields, displayNameResolver)

        // The pill: at most 60 % of the node, so the name keeps room
        val pillText = linkedField ?: currentValue?.let { formatPillValue(it) }
        var pillLeft = rowRight
        if (pillText != null) {
            val pillPaint = if (isEdited && linkedField == null) paints.pillStrong else paints.pill
            val iconSpace = if (linkedField != null) NodeStyle.PILL_ICON + NodeStyle.PILL_ICON_GAP else 0f
            val maxText = node.width * 0.6f - NodeStyle.ROW_PADDING - 2 * NodeStyle.PILL_PADDING - iconSpace
            val text = ellipsizeMiddle(pillText, maxText, pillPaint)
            val pillWidth = pillPaint.measureText(text) + 2 * NodeStyle.PILL_PADDING + iconSpace
            pillLeft = rowRight - pillWidth
            val pillTop = centerY - NodeStyle.PILL_HEIGHT / 2
            val (container, content) = when {
                linkedField != null -> lerp(colors.linkedPill, colors.pill, off) to colors.onLinkedPill.offText()
                isEdited -> colors.pill to colors.primary.offText()
                else -> colors.pill to colors.onSurface.offText()
            }
            drawRoundRect(
                color = container.faded(),
                topLeft = Offset(pillLeft, pillTop),
                size = Size(pillWidth, NodeStyle.PILL_HEIGHT),
                cornerRadius = CornerRadius(NodeStyle.PILL_HEIGHT / 2)
            )
            var textLeft = pillLeft + NodeStyle.PILL_PADDING
            if (linkedField != null) {
                translate(textLeft, centerY - NodeStyle.PILL_ICON / 2) {
                    with(icons.link) {
                        draw(Size(NodeStyle.PILL_ICON, NodeStyle.PILL_ICON), alpha = alpha, colorFilter = ColorFilter.tint(content))
                    }
                }
                textLeft += iconSpace
            }
            pillPaint.color = content.faded().toArgb()
            nativeCanvas.drawText(text, textLeft, pillPaint.baselineFor(centerY), pillPaint)
        }

        labelPaint.color = (if (editable) colors.primary else colors.onSurfaceVariant).offText().faded().toArgb()
        // A value shown from inside a subgraph by its label (spec §19.1), else by its own name without its path
        val shown = name in shownInputs
        val markSpace = if (shown) NodeStyle.SHOWN_MARK + NodeStyle.PILL_ICON_GAP else 0f
        val label = ellipsizeEnd(instance?.labels?.get(name) ?: Subgraphs.displayName(name), pillLeft - rowLeft - 16f - markSpace, labelPaint)
        nativeCanvas.drawText(label, rowLeft, labelPaint.baselineFor(centerY), labelPaint)
        if (shown) {
            translate(rowLeft + labelPaint.measureText(label) + NodeStyle.PILL_ICON_GAP, centerY - NodeStyle.SHOWN_MARK / 2) {
                with(icons.shown) {
                    draw(Size(NodeStyle.SHOWN_MARK, NodeStyle.SHOWN_MARK), alpha = alpha, colorFilter = ColorFilter.tint(colors.onSurfaceVariant.offMark()))
                }
            }
        }
    }

    // Output rows: labels at the end, in their slot colour, beside the connection inputs
    node.outputs.forEachIndexed { outputIndex, output ->
        val centerY = top + WorkflowLayoutEngine.outputSlotY(node, outputIndex)
        val paint = paints.label.apply { color = colors.slot(output.type).offText().faded().toArgb() }
        val label = ellipsizeEnd(output.name, node.width / 2 - NodeStyle.ROW_PADDING, paint)
        nativeCanvas.drawText(label, rowRight - paint.measureText(label), paint.baselineFor(centerY), paint)
    }

    if (instance != null) drawOpenButton(node, instance, colors, paints, icons, alpha)

    // Selection: an outline in primary just outside the shape; candidates (mapping, connecting) dashed
    if (highlightState != NodeHighlightState.NONE) {
        val outset = NodeStyle.SELECTION_GAP + NodeStyle.SELECTION_WIDTH / 2
        drawRoundRect(
            color = colors.primary.faded(),
            topLeft = Offset(left - outset, top - outset),
            size = Size(node.width + 2 * outset, node.height + 2 * outset),
            cornerRadius = CornerRadius(NodeStyle.CORNER + outset),
            style = Stroke(
                width = NodeStyle.SELECTION_WIDTH,
                pathEffect = if (highlightState == NodeHighlightState.CANDIDATE) {
                    PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                } else null
            )
        )
    }
}

/**
 * A socket centred on a node's edge: filled in its slot colour when connected, a ring around the node's body colour
 * when free, with a halo in the canvas colour that separates it from wires (in the light theme lifted by a shadow).
 */
private fun DrawScope.drawSocket(
    center: Offset,
    color: Color,
    connected: Boolean,
    colors: CanvasColors,
    paints: GraphPaints,
    alpha: Float,
    scale: Float = 1f,
    // A strip's add socket: a dashed ring (spec §19.2.4)
    dashed: Boolean = false
) {
    val radius = NodeStyle.SOCKET_RADIUS * scale
    val haloRadius = radius + NodeStyle.SOCKET_HALO * scale
    if (colors.isDark) {
        drawCircle(color = colors.canvas.copy(alpha = alpha), radius = haloRadius, center = center)
    } else {
        // Lifted like the nodes, so a socket stands out on a white node and on the light canvas alike
        paints.shadow.color = colors.canvas.copy(alpha = alpha).toArgb()
        paints.shadow.setElevationShadow(colors, alpha, scale)
        drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y, haloRadius, paints.shadow)
    }
    if (connected) {
        drawCircle(color = color.copy(alpha = alpha), radius = radius, center = center)
    } else {
        val ring = NodeStyle.SOCKET_RING * scale
        drawCircle(color = colors.nodeBody.copy(alpha = alpha), radius = radius, center = center)
        val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.6f * ring, 1.6f * ring)) else null
        drawCircle(color = color.copy(alpha = alpha), radius = radius - ring / 2, center = center, style = Stroke(ring, pathEffect = dash))
    }
}

/** How a wire is drawn for a focus: how far it recedes into `outlineVariant` (0–1) and its width in graph units. */
private class WireEmphasis(val recede: Float, val width: Float)

private val WIRE_PLAIN = WireEmphasis(recede = 0f, width = 4f)
private val WIRE_FOCUSED = WireEmphasis(recede = 0f, width = 6f)
private val WIRE_RECEDED = WireEmphasis(recede = 1f, width = 4f)

/** Without focus every wire in its slot colour; with focus, the focused nodes' wires wider and the rest receding. */
private fun wireEmphasis(edge: WorkflowEdge, focus: Set<String>): WireEmphasis = when {
    focus.isEmpty() -> WIRE_PLAIN
    edge.sourceNodeId in focus || edge.targetNodeId in focus -> WIRE_FOCUSED
    else -> WIRE_RECEDED
}

/**
 * A wire from an output socket to an input socket, shaped by the router chosen in Settings, in the slot colour of its
 * output type (receding toward `outlineVariant` while other nodes are focused), round caps and a dot at each end
 * under the sockets.
 */
private fun DrawScope.drawEdge(
    edge: WorkflowEdge,
    nodesById: Map<String, WorkflowNode>,
    colors: CanvasColors,
    router: EdgeRouter,
    alpha: Float = 1f,
    recede: Float = 0f,
    width: Float = 4f,
    draggingNodeId: String? = null,
    dragScale: Float = 1f,
    wirePaths: WirePaths? = null
) {
    val sourceNode = nodesById[edge.sourceNodeId] ?: return
    val targetNode = nodesById[edge.targetNodeId] ?: return

    // Socket positions, following a node lifted while dragged
    val start = scaleSocketPosition(
        sourceNode, sourceNode.x + sourceNode.width,
        sourceNode.y + WorkflowLayoutEngine.outputSlotY(sourceNode, edge.sourceOutputIndex), draggingNodeId, dragScale
    )
    val end = scaleSocketPosition(
        targetNode, targetNode.x, targetNode.y + WorkflowLayoutEngine.inputSlotY(targetNode, edge.targetInputName),
        draggingNodeId, dragScale
    )
    // A port strip is narrow glass: its wires don't swing back behind its centre line, where their sockets are
    val backAtStart = !SubgraphLevels.isInputsNode(sourceNode)
    val backAtEnd = !SubgraphLevels.isOutputsNode(targetNode)
    val path = wirePaths?.get(router, start, end, backAtStart, backAtEnd)
        ?: router.computeRoute(start, end, backAtStart, backAtEnd).path

    val color = lerp(colors.slot(edge.slotType), colors.outlineVariant, recede).copy(alpha = alpha)
    drawPath(path = path, color = color, style = Stroke(width = width, cap = StrokeCap.Round))
    drawCircle(color = color, radius = width, center = start)
    drawCircle(color = color, radius = width, center = end)
}

/**
 * Wires' shapes kept between frames: the same path object for the same ends. The renderer prepares a thick smooth
 * curve for the GPU once per path object, so a new one every frame (while panning and zooming nothing moves in graph
 * units) is prepared again and again. Shapes of ends that changed (a node moved) are dropped once there are many.
 */
internal class WirePaths {
    private data class Key(val start: Offset, val end: Offset, val backAtStart: Boolean, val backAtEnd: Boolean)
    private val paths = HashMap<Key, Path>()

    fun get(router: EdgeRouter, start: Offset, end: Offset, backAtStart: Boolean, backAtEnd: Boolean): Path {
        if (paths.size > MAX_KEPT) paths.clear()
        return paths.getOrPut(Key(start, end, backAtStart, backAtEnd)) {
            router.computeRoute(start, end, backAtStart, backAtEnd).path
        }
    }

    private companion object {
        const val MAX_KEPT = 4000
    }
}

/**
 * A group (spec §12.4): a filled rounded area beneath its nodes, its name in a chip at the top start.
 */
/**
 * A group's area: the lowest level above the canvas, frosted glass over the wires passing under it (blurred
 * beneath by the canvas), in the light theme with half a node's shadow.
 */
private fun DrawScope.drawGroupArea(group: RenderedGroup, colors: CanvasColors, paints: GraphPaints, alpha: Float = 1f) {
    val glass = colors.groupArea.copy(alpha = NodeStyle.GLASS_ALPHA * alpha)
    if (colors.isDark) {
        drawRoundRect(
            color = glass,
            topLeft = Offset(group.x, group.y),
            size = Size(group.width, group.height),
            cornerRadius = CornerRadius(NodeStyle.GROUP_CORNER)
        )
    } else {
        paints.shadow.color = glass.toArgb()
        paints.shadow.setElevationShadow(colors, alpha, level = 0.5f)
        drawContext.canvas.nativeCanvas.drawRoundRect(
            group.x, group.y, group.x + group.width, group.y + group.height,
            NodeStyle.GROUP_CORNER, NodeStyle.GROUP_CORNER, paints.shadow
        )
    }
}

/** A group's label chip, at a node's level above the wires (in the light theme with a node's shadow). */
private fun DrawScope.drawGroupLabel(
    group: RenderedGroup,
    colors: CanvasColors,
    paints: GraphPaints,
    alpha: Float = 1f,
    // The label chip fades out toward the overview, which shows the area only
    chipAlpha: Float = 1f
) {
    if (chipAlpha <= 0f) return
    val chipHeight = NodeStyle.GROUP_CHIP_HEIGHT
    val paint = paints.groupLabel.apply { color = colors.onSurface.copy(alpha = alpha * chipAlpha).toArgb() }
    val inset = NodeStyle.GROUP_CHIP_INSET
    val padding = NodeStyle.GROUP_CHIP_PADDING
    val label = ellipsizeEnd(group.title, group.width - 2 * inset - 2 * padding, paint)
    if (label.isEmpty()) return
    val chipLeft = group.x + inset
    val chipTop = group.y + inset
    val chipWidth = paint.measureText(label) + 2 * padding
    if (colors.isDark) {
        drawRoundRect(
            color = colors.groupChip.copy(alpha = alpha * chipAlpha),
            topLeft = Offset(chipLeft, chipTop),
            size = Size(chipWidth, chipHeight),
            cornerRadius = CornerRadius(chipHeight / 2)
        )
    } else {
        paints.shadow.color = colors.groupChip.copy(alpha = alpha * chipAlpha).toArgb()
        paints.shadow.setElevationShadow(colors, alpha * chipAlpha)
        drawContext.canvas.nativeCanvas.drawRoundRect(
            chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight, chipHeight / 2, chipHeight / 2, paints.shadow
        )
    }
    drawContext.canvas.nativeCanvas.drawText(label, chipLeft + padding, paint.baselineFor(chipTop + chipHeight / 2), paint)
}

/**
 * Scale a socket position around the node's center when the node is being dragged.
 * Used to ensure socket indicators and edge endpoints scale with the node body.
 */
private fun scaleSocketPosition(
    node: WorkflowNode,
    socketX: Float,
    socketY: Float,
    draggingNodeId: String?,
    dragScale: Float
): Offset {
    if (node.id != draggingNodeId || dragScale == 1f) {
        return Offset(socketX, socketY)
    }
    val cx = node.x + node.width / 2
    val cy = node.y + node.height / 2
    return Offset(
        cx + dragScale * (socketX - cx),
        cy + dragScale * (socketY - cy)
    )
}

/**
 * Draw a markdown note.
 * Notes look like nodes but with distinct amber/gold colors.
 * Uses TextMeasurer for markdown-formatted content with word wrapping.
 * Measures actual height and updates note.height for layout consistency.
 */
private fun DrawScope.drawNote(
    note: WorkflowNote,
    colors: CanvasColors,
    paints: GraphPaints,
    alpha: Float = 1f,
    isSelected: Boolean = false,
    textMeasurer: TextMeasurer? = null,
    density: androidx.compose.ui.unit.Density? = null,
    noteLayouts: NoteLayouts? = null,
    // False: only measure the note (off screen), so the layout knows its height
    drawIt: Boolean = true
) {
    // Helper to apply alpha to a color
    fun Color.withAlpha() = this.copy(alpha = this.alpha * alpha)

    // A tertiary container, like a sticky note
    val noteColor = colors.note.withAlpha()
    val textColor = colors.onNote.withAlpha()

    val cornerRadius = NodeStyle.CORNER
    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
    val contentPadding = 16f

    // Content laid out (once per content, width and colours) and the note's actual height from it
    var effectiveHeight = note.height
    var content: NoteLayout? = null
    val contentColors = NoteColors(
        text = colors.onNote,
        // Two tones between the note and its text: code's background, a table's rules
        codeBackground = lerp(colors.note, colors.onNote, 0.12f),
        rule = lerp(colors.note, colors.onNote, 0.35f)
    )

    if (textMeasurer != null && density != null && noteLayouts != null) {
        // A size above node text, the line height to match
        content = noteLayouts.layout(
            content = note.content,
            width = note.width - contentPadding * 2,
            colors = contentColors,
            fontSize = with(density) { NodeStyle.NOTE_TEXT.toSp() },
            lineHeight = with(density) { NodeStyle.NOTE_LINE.toSp() },
            textMeasurer = textMeasurer,
            density = density
        )
        effectiveHeight = headerHeight + contentPadding * 2 + content.height

        // Update note model for layout consistency
        if (note.height != effectiveHeight) {
            note.height = effectiveHeight
        }
    }

    if (!drawIt) return

    // Single-colour sticky note background; at a node's level (in the light theme with a node's shadow)
    if (colors.isDark) {
        drawRoundRect(
            color = noteColor,
            topLeft = Offset(note.x, note.y),
            size = Size(note.width, effectiveHeight),
            cornerRadius = CornerRadius(cornerRadius)
        )
    } else {
        paints.shadow.color = noteColor.toArgb()
        paints.shadow.setElevationShadow(colors, alpha)
        drawContext.canvas.nativeCanvas.drawRoundRect(
            note.x, note.y, note.x + note.width, note.y + effectiveHeight, cornerRadius, cornerRadius, paints.shadow
        )
    }

    // Selection: an outline in primary just outside the shape, as on nodes
    if (isSelected) {
        val outset = NodeStyle.SELECTION_GAP + NodeStyle.SELECTION_WIDTH / 2
        drawRoundRect(
            color = colors.primary.withAlpha(),
            topLeft = Offset(note.x - outset, note.y - outset),
            size = Size(note.width + 2 * outset, effectiveHeight + 2 * outset),
            cornerRadius = CornerRadius(cornerRadius + outset),
            style = Stroke(width = NodeStyle.SELECTION_WIDTH)
        )
    }

    // Draw title using native canvas
    val textColorArgb = textColor.toArgb()
    drawContext.canvas.nativeCanvas.apply {
        val titlePaint = paints.noteTitle.apply { color = textColorArgb }
        val title = ellipsizeEnd(note.title, note.width - 2 * NodeStyle.TITLE_PADDING, titlePaint)
        drawText(title, note.x + NodeStyle.TITLE_PADDING, titlePaint.baselineFor(note.y + headerHeight / 2), titlePaint)

    }

    // Draw markdown content with alpha
    if (content != null) {
        drawNoteContent(content, note.x + contentPadding, note.y + headerHeight + contentPadding, contentColors, alpha)
    } else {
        // Fallback: Draw content using native canvas (no markdown formatting)
        drawContext.canvas.nativeCanvas.apply {
            val contentPaint = android.graphics.Paint().apply {
                color = textColorArgb
                textSize = 20f
                isAntiAlias = true
            }

            val lineHeight = WorkflowLayoutEngine.NOTE_LINE_HEIGHT
            val lines = note.content.lines()

            var contentY = note.y + headerHeight + 28f
            for (line in lines) {
                val truncatedLine = ellipsizeEnd(line, note.width - 32f, contentPaint)
                drawText(truncatedLine, note.x + 16f, contentY, contentPaint)
                contentY += lineHeight
            }
        }
    }
}

/** [text] cut at the end with "…" to fit [maxWidth]; empty when not even that fits. */
private fun ellipsizeEnd(text: String, maxWidth: Float, paint: Paint): String {
    val limit = fittingChars(maxWidth, paint)
    if (text.length <= limit && paint.measureText(text) <= maxWidth) return text
    // The longest start that fits with "…", found by halving (values can be thousands of characters long, e.g. a
    // system prompt, and this runs every frame)
    val room = maxWidth - paint.measureText("…")
    var low = 0
    var high = minOf(text.length, limit)
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (paint.measureText(text, 0, mid) <= room) low = mid else high = mid - 1
    }
    return if (low == 0) "" else text.substring(0, low).trimEnd() + "…"
}

/** [text] cut in the middle with "…" to fit [maxWidth] (values: both their start and their end tell them apart). */
private fun ellipsizeMiddle(text: String, maxWidth: Float, paint: Paint): String {
    val limit = fittingChars(maxWidth, paint)
    if (text.length <= limit && paint.measureText(text) <= maxWidth) return text
    fun cut(keep: Int) = text.substring(0, (keep + 1) / 2).trimEnd() + "…" + text.substring(text.length - keep / 2).trimStart()
    // The most characters kept that fit, found by halving (see ellipsizeEnd)
    var low = 0
    var high = minOf(text.length - 1, limit)
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (paint.measureText(cut(mid)) <= maxWidth) low = mid else high = mid - 1
    }
    return if (low <= 1) "…" else cut(low)
}

/** At most this many characters fit in [maxWidth]: none is narrower than a fifth of the text size. */
private fun fittingChars(maxWidth: Float, paint: Paint): Int =
    (maxWidth / (paint.textSize * 0.2f)).toInt().coerceAtLeast(1)

/**
 * The app field's label when the generation screen fills this input: a {{placeholder}} value, or an input the
 * original workflow maps (even when a saved edit replaced the placeholder with a literal).
 */
private fun linkedFieldName(
    value: Any?,
    nodeId: String,
    inputName: String,
    mappedFields: Map<Pair<String, String>, String>,
    displayNameResolver: (String) -> String
): String? {
    val placeholder = (value as? String)?.let { Regex("""\{\{(\w+)\}\}""").matchEntire(it)?.groupValues?.get(1) }
        ?: mappedFields[Pair(nodeId, inputName)]
    return placeholder?.let(displayNameResolver)
}

/** A literal value in a pill, on one line. */
private fun formatPillValue(value: Any): String = when (value) {
    is String -> value.replace(Regex("""\s+"""), " ").trim()
    else -> value.toString()
}

/**
 * Compare two values for equality, handling numeric type mismatches.
 * For example, 8.0 (Double) and 8 (Int) should be considered equal.
 */
private fun valuesEqual(a: Any?, b: Any?, type: String?): Boolean {
    if (a == b) return true
    if (a == null || b == null) return false

    return when (type) {
        "INT" -> {
            val aInt = when (a) {
                is Number -> a.toInt()
                is String -> a.toIntOrNull()
                else -> null
            }
            val bInt = when (b) {
                is Number -> b.toInt()
                is String -> b.toIntOrNull()
                else -> null
            }
            aInt != null && bInt != null && aInt == bInt
        }
        "FLOAT" -> {
            val aFloat = when (a) {
                is Number -> a.toDouble()
                is String -> a.toDoubleOrNull()
                else -> null
            }
            val bFloat = when (b) {
                is Number -> b.toDouble()
                is String -> b.toDoubleOrNull()
                else -> null
            }
            aFloat != null && bFloat != null && aFloat == bFloat
        }
        else -> a == b
    }
}

/**
 * Find an output slot at the given graph coordinates.
 * Returns the SlotPosition if found within the hit radius, null otherwise.
 */
private fun findOutputSlotAt(
    graphX: Float,
    graphY: Float,
    nodes: List<WorkflowNode>,
    slotHitRadius: Float
): SlotPosition? {
    var best: SlotPosition? = null
    var bestDistance = slotHitRadius * slotHitRadius
    for (node in nodes) {
        // Output slots are on the right side of the node
        val outputX = node.x + node.width

        // Check each output at its correct Y position
        node.outputs.forEachIndexed { outputIndex, output ->
            val outputY = node.y + WorkflowLayoutEngine.outputSlotY(node, outputIndex)

            val dx = graphX - outputX
            val dy = graphY - outputY
            if (dx * dx + dy * dy <= bestDistance) {
                bestDistance = dx * dx + dy * dy
                best = SlotPosition(
                    nodeId = node.id,
                    slotName = output.name,
                    isOutput = true,
                    outputIndex = outputIndex,
                    center = Offset(outputX, outputY),
                    slotType = output.type
                )
            }
        }
    }
    return best
}

/**
 * Find an input slot at the given graph coordinates: connection-type inputs (Connection or UnconnectedSlot), and with
 * [valueSockets] the value rows that can take a link.
 * Returns the SlotPosition if found within the hit radius, null otherwise.
 */
private fun findInputSlotAt(
    graphX: Float,
    graphY: Float,
    nodes: List<WorkflowNode>,
    slotHitRadius: Float,
    nodeDefinitions: Map<String, NodeTypeDefinition>,
    valueSockets: Boolean = false
): SlotPosition? {
    var best: SlotPosition? = null
    var bestDistance = slotHitRadius * slotHitRadius
    for (node in nodes) {
        // Input slots are on the left side of the node
        val inputX = node.x
        var inputIndex = 0

        for ((inputName, inputValue) in node.inputs) {
            // Connection-type inputs, and (with [valueSockets]) the value rows that can take a link
            val valueType = if (inputValue is InputValue.Literal && valueSockets) {
                valueRowType(node, inputName, nodeDefinitions)
            } else null
            val isConnectionInput = inputValue is InputValue.Connection ||
                inputValue is InputValue.UnconnectedSlot || valueType != null

            if (isConnectionInput) {
                val inputY = node.y + WorkflowLayoutEngine.rowCenterY(inputIndex)

                val dx = graphX - inputX
                val dy = graphY - inputY
                if (dx * dx + dy * dy <= bestDistance) {
                    bestDistance = dx * dx + dy * dy
                    // Resolve slot type - for connected inputs, look up from node definition
                    val slotType = when (inputValue) {
                        is InputValue.Literal -> valueType
                        is InputValue.UnconnectedSlot -> inputValue.slotType
                        is InputValue.Connection -> {
                            nodeDefinitions[node.classType]?.inputs
                                ?.find { it.name == inputName }?.type
                        }
                        else -> null
                    }
                    best = SlotPosition(
                        nodeId = node.id,
                        slotName = inputName,
                        isOutput = false,
                        outputIndex = 0,
                        center = Offset(inputX, inputY),
                        slotType = slotType
                    )
                }
            }
            inputIndex++
        }
    }
    return best
}

/** The type of a value row that can take a link (INT, FLOAT, STRING, BOOLEAN), or null. */
private fun linkableValueType(node: WorkflowNode, inputName: String, nodeDefinitions: Map<String, NodeTypeDefinition>): String? =
    nodeDefinitions[node.classType]?.inputs?.find { it.name == inputName }?.type?.uppercase()
        ?.takeIf { it in ConnectionValidator.LINKABLE_VALUE_TYPES && !Subgraphs.isPromotedKey(inputName) }

/**
 * The type of a value row that has a socket in Edit structure: one that can take a link, or a list (its socket opens
 * the field menu only). Null for anything else.
 */
private fun valueRowType(node: WorkflowNode, inputName: String, nodeDefinitions: Map<String, NodeTypeDefinition>): String? {
    linkableValueType(node, inputName, nodeDefinitions)?.let { return it }
    val definition = nodeDefinitions[node.classType]?.inputs?.find { it.name == inputName } ?: return null
    return if (definition.options != null && !definition.forceInput) definition.type else null
}

/** What a subgraph instance shows (spec §19.1): its Open button's words, and the labels of the values it shows. */
private class InstanceText(
    val open: String,
    /** The subgraph's name, when the instance's title differs from it */
    val name: String?,
    /** "12 nodes" */
    val count: String,
    val labels: Map<String, String>
)

/** Where a subgraph instance's Open button is: full width at the foot of its body. */
private fun openButtonBounds(node: WorkflowNode): androidx.compose.ui.geometry.Rect {
    val inset = NodeStyle.INSTANCE_BEZEL + NodeStyle.OPEN_BUTTON_INSET
    val bottom = node.y + node.height - inset
    return androidx.compose.ui.geometry.Rect(node.x + inset, bottom - NodeStyle.OPEN_BUTTON_HEIGHT, node.x + node.width - inset, bottom)
}

/**
 * A subgraph instance's Open button: a filled tonal button with "Open (12 nodes)" ("Open Name (12 nodes)", the
 * subgraph's name after a space, when the instance's title differs) and a chevron, the count in a lighter colour.
 */
private fun DrawScope.drawOpenButton(
    node: WorkflowNode,
    instance: InstanceText,
    colors: CanvasColors,
    paints: GraphPaints,
    icons: GraphIcons,
    alpha: Float
) {
    fun Color.faded() = copy(alpha = this.alpha * alpha)
    val bounds = openButtonBounds(node)
    drawRoundRect(
        color = colors.openButton.faded(),
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(NodeStyle.OPEN_BUTTON_HEIGHT / 2)
    )
    val strong = paints.labelStrong.apply { color = colors.onOpenButton.faded().toArgb() }
    val plain = paints.label.apply { color = colors.onSurfaceVariant.faded().toArgb() }
    val count = " (${instance.count})"
    val available = bounds.width - 2 * NodeStyle.OPEN_BUTTON_PADDING - NodeStyle.ICON - NodeStyle.ICON_GAP
    val fixed = strong.measureText(instance.open) + plain.measureText(count) +
        (if (instance.name != null) strong.measureText(" ") else 0f)
    val name = instance.name?.let { ellipsizeEnd(it, (available - fixed).coerceAtLeast(0f), strong) }?.takeIf { it.isNotEmpty() }
    val parts = buildList {
        add(instance.open to strong)
        if (name != null) add(" $name" to strong)
        add(count to plain)
    }
    val textWidth = parts.sumOf { (text, paint) -> paint.measureText(text).toDouble() }.toFloat()
    val contentWidth = minOf(textWidth, available) + NodeStyle.ICON_GAP + NodeStyle.ICON
    var x = bounds.center.x - contentWidth / 2
    val centerY = bounds.center.y
    val nativeCanvas = drawContext.canvas.nativeCanvas
    parts.forEach { (text, paint) ->
        val shown = ellipsizeEnd(text, (bounds.right - NodeStyle.OPEN_BUTTON_PADDING - NodeStyle.ICON - NodeStyle.ICON_GAP - x).coerceAtLeast(0f), paint)
        nativeCanvas.drawText(shown, x, paint.baselineFor(centerY), paint)
        x += paint.measureText(shown)
    }
    translate(x + NodeStyle.ICON_GAP, centerY - NodeStyle.ICON / 2) {
        with(icons.open) { draw(Size(NodeStyle.ICON, NodeStyle.ICON), alpha = alpha, colorFilter = ColorFilter.tint(colors.onOpenButton)) }
    }
}

/**
 * Over a port strip's wires: its icon in the cap, and toward the overview a plain block of its colour growing from the
 * cap down the strip as nodes' title bars spread, covering sockets and wires' ends; in the overview the whole strip,
 * nothing on it.
 */
private fun DrawScope.drawPortStripBlock(
    node: WorkflowNode,
    colors: CanvasColors,
    paints: GraphPaints,
    icons: GraphIcons,
    alpha: Float,
    spread: Float
) {
    val bounds = portStripBounds(node)
    if (spread > 0f) {
        val corner = bounds.width / 2
        val bottom = bounds.top + portStripCoveredTo(node, spread)
        // Fading in over the cap's glass at the start, so the change isn't a jump
        val color = colors.stripGlass.copy(alpha = alpha * (spread * 4f).coerceAtMost(1f))
        if (colors.isDark) {
            drawRoundRect(color = color, topLeft = bounds.topLeft, size = Size(bounds.width, bottom - bounds.top), cornerRadius = CornerRadius(corner))
        } else {
            paints.shadow.color = color.toArgb()
            paints.shadow.setElevationShadow(colors, color.alpha)
            drawContext.canvas.nativeCanvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bottom, corner, corner, paints.shadow)
        }
    }
    if (spread >= 1f) return
    val iconSize = NodeStyle.ICON
    val capCenter = node.y + WorkflowLayoutEngine.NODE_HEADER_HEIGHT / 2
    translate(bounds.center.x - iconSize / 2, capCenter - iconSize / 2) {
        with(if (SubgraphLevels.isInputsNode(node)) icons.inputs else icons.outputs) {
            draw(Size(iconSize, iconSize), alpha = alpha * (1f - spread), colorFilter = ColorFilter.tint(colors.onSurfaceVariant))
        }
    }
}

/** A level's Inputs or Outputs strip (spec §19.2.4) as drawn: centred on its sockets' line. */
private fun portStripBounds(node: WorkflowNode): androidx.compose.ui.geometry.Rect {
    val centerLine = if (SubgraphLevels.isInputsNode(node)) node.x + node.width else node.x
    val half = WorkflowLayoutEngine.PORT_STRIP_WIDTH / 2
    return androidx.compose.ui.geometry.Rect(centerLine - half, node.y, centerLine + half, node.y + node.height)
}

/**
 * A level's Inputs or Outputs strip: a capsule of the groups' frosted glass with the sockets set into it (drawn with
 * the other sockets), its icon in the cap with the word beside it outside, and the sockets' labels outside, on the side
 * away from the content. Toward the overview ([spread] 1, as nodes' title bars spread) its icon and labels go: the
 * block drawn over the wires ([drawPortStripBlock]) takes its place (the owner's choice over spec §19.2.4).
 */
private fun DrawScope.drawPortStrip(
    node: WorkflowNode,
    word: String,
    addWord: String,
    socketTypes: List<String?>,
    socketNames: List<String>,
    colors: CanvasColors,
    paints: GraphPaints,
    icons: GraphIcons,
    alpha: Float,
    spread: Float
) {
    val bounds = portStripBounds(node)
    val glass = colors.stripGlass.copy(alpha = NodeStyle.GLASS_ALPHA * alpha)
    val corner = bounds.width / 2
    if (colors.isDark) {
        drawRoundRect(color = glass, topLeft = bounds.topLeft, size = bounds.size, cornerRadius = CornerRadius(corner))
    } else {
        paints.shadow.color = glass.toArgb()
        paints.shadow.setElevationShadow(colors, alpha)
        drawContext.canvas.nativeCanvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, corner, corner, paints.shadow)
    }
    if (spread >= 1f) return
    val inputs = SubgraphLevels.isInputsNode(node)
    val capCenter = node.y + WorkflowLayoutEngine.NODE_HEADER_HEIGHT / 2
    // Labels fade as the block grows down the strip, each gone once the block has covered its socket
    val coveredTo = portStripCoveredTo(node, spread)
    val labelAlpha = alpha * (1f - spread)
    val nativeCanvas = drawContext.canvas.nativeCanvas
    // Outside the strip, away from the content: right-aligned left of the Inputs strip, left of the Outputs strip's right
    fun drawOutside(text: String, centerY: Float, paint: Paint) {
        val x = if (inputs) bounds.left - NodeStyle.STRIP_LABEL_GAP - paint.measureText(text) else bounds.right + NodeStyle.STRIP_LABEL_GAP
        nativeCanvas.drawText(text, x, paint.baselineFor(centerY), paint)
    }
    drawOutside(word, capCenter, paints.labelStrong.apply { color = colors.onSurfaceVariant.copy(alpha = labelAlpha).toArgb() })
    socketNames.forEachIndexed { index, name ->
        if (spread > 0f && WorkflowLayoutEngine.rowCenterY(index) < coveredTo) return@forEachIndexed
        val add = SubgraphLevels.isAddSocket(name)
        // The add socket's word in italics, neutral
        val paint = paints.label.apply {
            color = (if (add) colors.onSurfaceVariant else colors.slot(socketTypes.getOrNull(index))).copy(alpha = labelAlpha).toArgb()
            textSkewX = if (add) -0.25f else 0f
        }
        drawOutside(if (add) addWord else name, node.y + WorkflowLayoutEngine.rowCenterY(index), paint)
        paint.textSkewX = 0f
    }
}

/** How far down a port strip its overview block reaches (from the strip's top), as a node's title bar spreads. */
private fun portStripCoveredTo(node: WorkflowNode, spread: Float): Float =
    WorkflowLayoutEngine.NODE_HEADER_HEIGHT + (node.height - WorkflowLayoutEngine.NODE_HEADER_HEIGHT) * spread
