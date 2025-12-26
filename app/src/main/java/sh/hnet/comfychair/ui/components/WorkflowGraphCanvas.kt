package sh.hnet.comfychair.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeCategory
import sh.hnet.comfychair.workflow.SlotColors
import sh.hnet.comfychair.workflow.ConnectionModeState
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.WorkflowEdge
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode

/**
 * Highlight state for nodes in mapping mode
 */
enum class NodeHighlightState {
    NONE,
    CANDIDATE,
    SELECTED
}

/**
 * Data class to hold theme colors for canvas drawing
 */
private data class CanvasColors(
    val nodeBackground: Color,
    val nodeHeaderBackground: Color,
    val nodeBorder: Color,
    val templateTextHighlight: Color,
    val candidateBorder: Color,
    val selectedBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val edgeColor: Color,
    val slotColors: Map<String, Color>,
    val categoryColors: Map<NodeCategory, SlotColors.NodeColorPair>,
    val positivePromptColors: SlotColors.NodeColorPair,
    val negativePromptColors: SlotColors.NodeColorPair,
    val isDarkTheme: Boolean
)

/**
 * Holds animatable position values for a node
 */
private data class AnimatedNodePosition(
    val x: Animatable<Float, AnimationVector1D>,
    val y: Animatable<Float, AnimationVector1D>
)

/**
 * Canvas component for rendering workflow graphs
 */
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
    onNodeTapped: ((String) -> Unit)? = null,
    onTapOutsideNodes: (() -> Unit)? = null,
    onOutputSlotTapped: ((SlotPosition) -> Unit)? = null,
    onInputSlotTapped: ((SlotPosition) -> Unit)? = null,
    onRenameNodeTapped: ((String) -> Unit)? = null
) {
    // Use rememberUpdatedState to always have access to current values in the gesture handler
    val currentScaleState = rememberUpdatedState(scale)
    val currentOffsetState = rememberUpdatedState(offset)
    val currentGraphState = rememberUpdatedState(graph)
    val currentConnectionModeState = rememberUpdatedState(connectionModeState)
    val currentIsEditMode = rememberUpdatedState(isEditMode)

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

    // Get UI field prefix for template display names
    val uiFieldPrefix = stringResource(R.string.node_editor_ui_field_prefix)

    // Create display name resolver using Context for localized field names
    val context = LocalContext.current
    val displayNameResolver: (String) -> String = remember(context) {
        { fieldKey -> FieldDisplayRegistry.getDisplayName(context, fieldKey) }
    }

    // Load edit icon drawable
    val editIconDrawable = remember(context) {
        ContextCompat.getDrawable(context, R.drawable.edit_24px)
    }

    // Extract theme colors
    val isDarkTheme = isSystemInDarkTheme()
    val colors = CanvasColors(
        nodeBackground = MaterialTheme.colorScheme.surfaceContainer,
        nodeHeaderBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        nodeBorder = MaterialTheme.colorScheme.outline,
        templateTextHighlight = MaterialTheme.colorScheme.secondary,
        candidateBorder = MaterialTheme.colorScheme.secondary,
        selectedBorder = MaterialTheme.colorScheme.primary,
        textPrimary = MaterialTheme.colorScheme.onSurface,
        textSecondary = MaterialTheme.colorScheme.onSurfaceVariant,
        edgeColor = MaterialTheme.colorScheme.outlineVariant,
        slotColors = SlotColors.getSlotColorMap(isDarkTheme),
        categoryColors = SlotColors.getCategoryColorMap(isDarkTheme),
        positivePromptColors = SlotColors.getPositivePromptColors(isDarkTheme),
        negativePromptColors = SlotColors.getNegativePromptColors(isDarkTheme),
        isDarkTheme = isDarkTheme
    )

    // Constants for slot detection
    val slotHitRadius = 30f  // Larger hit area for easier tapping

    // Animation state for node positions
    val animatedPositions = remember { mutableStateMapOf<String, AnimatedNodePosition>() }

    // Animation spec for smooth transitions
    val animationSpec = tween<Float>(
        durationMillis = 350,
        easing = FastOutSlowInEasing
    )

    // Animate node positions when graph changes
    LaunchedEffect(graph.nodes) {
        val currentNodeIds = graph.nodes.map { it.id }.toSet()

        // Remove animated positions for deleted nodes
        val deletedIds = animatedPositions.keys - currentNodeIds
        deletedIds.forEach { animatedPositions.remove(it) }

        // Update or create animated positions for each node
        graph.nodes.forEach { node ->
            val existingPosition = animatedPositions[node.id]
            if (existingPosition != null) {
                // Animate to new position if it changed
                if (existingPosition.x.targetValue != node.x || existingPosition.y.targetValue != node.y) {
                    launch {
                        existingPosition.x.animateTo(node.x, animationSpec)
                    }
                    launch {
                        existingPosition.y.animateTo(node.y, animationSpec)
                    }
                }
            } else {
                // New node - start at target position (no animation for new nodes)
                animatedPositions[node.id] = AnimatedNodePosition(
                    x = Animatable(node.x),
                    y = Animatable(node.y)
                )
            }
        }
    }

    // Build animated nodes for drawing - reads Animatable.value directly to trigger recomposition
    // Note: This is computed every recomposition to pick up animation value changes
    val animatedNodes = graph.nodes.map { node ->
        val animPos = animatedPositions[node.id]
        if (animPos != null) {
            node.copy(x = animPos.x.value, y = animPos.y.value)
        } else {
            node
        }
    }

    // Edit icon hit area constants
    val editIconSize = 32f
    val editIconPadding = 12f

    Canvas(
        modifier = modifier
            .pointerInput(onNodeTapped, onTapOutsideNodes, onOutputSlotTapped, onInputSlotTapped, onRenameNodeTapped) {
                if (onNodeTapped != null || onTapOutsideNodes != null || onOutputSlotTapped != null || onInputSlotTapped != null || onRenameNodeTapped != null) {
                    detectTapGestures { tapOffset ->
                        // Transform tap position to graph coordinates
                        val graphX = (tapOffset.x - currentOffsetState.value.x) / currentScaleState.value
                        val graphY = (tapOffset.y - currentOffsetState.value.y) / currentScaleState.value
                        val graph = currentGraphState.value
                        val inConnectionMode = currentConnectionModeState.value != null
                        val inEditMode = currentIsEditMode.value

                        // In connection mode, check for taps on valid input slots first
                        if (inConnectionMode && onInputSlotTapped != null) {
                            val connectionState = currentConnectionModeState.value!!
                            val tappedInputSlot = connectionState.validInputSlots.find { slot ->
                                val dx = graphX - slot.center.x
                                val dy = graphY - slot.center.y
                                (dx * dx + dy * dy) <= slotHitRadius * slotHitRadius
                            }
                            if (tappedInputSlot != null) {
                                onInputSlotTapped(tappedInputSlot)
                                return@detectTapGestures
                            }
                        }

                        // In edit mode, check for taps on output slots
                        if (inEditMode && onOutputSlotTapped != null) {
                            for (node in graph.nodes) {
                                // Output slots are on the right side of the node
                                val outputX = node.x + node.width

                                // Check each output at its correct Y position
                                node.outputs.forEachIndexed { outputIndex, outputType ->
                                    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
                                    val literalInputCount = node.inputs.count { (_, v) -> v is InputValue.Literal }
                                    val outputY = node.y + headerHeight + 20f +
                                        (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) +
                                        (outputIndex * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)

                                    val dx = graphX - outputX
                                    val dy = graphY - outputY
                                    if (dx * dx + dy * dy <= slotHitRadius * slotHitRadius) {
                                        val slotPosition = SlotPosition(
                                            nodeId = node.id,
                                            slotName = outputType,
                                            isOutput = true,
                                            outputIndex = outputIndex,
                                            center = Offset(outputX, outputY),
                                            slotType = outputType
                                        )
                                        onOutputSlotTapped(slotPosition)
                                        return@detectTapGestures
                                    }
                                }
                            }
                        }

                        // In edit mode, check for taps on the edit icon in node headers
                        if (inEditMode && onRenameNodeTapped != null) {
                            val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
                            for (node in graph.nodes) {
                                // Edit icon is in the top-right corner of the header
                                val iconRight = node.x + node.width - 8f
                                val iconLeft = iconRight - 32f
                                val iconTop = node.y + 8f
                                val iconBottom = node.y + headerHeight - 8f

                                if (graphX >= iconLeft && graphX <= iconRight &&
                                    graphY >= iconTop && graphY <= iconBottom) {
                                    onRenameNodeTapped(node.id)
                                    return@detectTapGestures
                                }
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
        // Apply transformation
        withTransform({
            translate(offset.x, offset.y)
            scale(scale, scale, Offset.Zero)
        }) {
            // Draw edges first (behind nodes) - use animatedNodes for positions
            graph.edges.forEach { edge ->
                drawEdge(edge, animatedNodes, colors)
            }

            // Build a map of node ID -> (input name -> wire color) for connected inputs
            val nodeInputColors: Map<String, Map<String, Int>> = buildMap {
                graph.edges.forEach { edge ->
                    val wireColor = edge.slotType?.let { colors.slotColors[it.uppercase()] }
                        ?: colors.edgeColor
                    val nodeColors = getOrPut(edge.targetNodeId) { mutableMapOf() }
                    (nodeColors as MutableMap)[edge.targetInputName] = wireColor.toArgb()
                }
            }

            // Draw nodes at animated positions
            animatedNodes.forEach { node ->
                val highlightState = when {
                    // Mapping mode selection
                    isFieldMappingMode && node.id in mappingSelectedNodeIds -> NodeHighlightState.SELECTED
                    isFieldMappingMode && node.id in highlightedNodeIds -> NodeHighlightState.CANDIDATE
                    // Edit mode selection
                    isEditMode && node.id in selectedNodeIds -> NodeHighlightState.SELECTED
                    // Attribute editing mode
                    editingNodeId == node.id -> NodeHighlightState.SELECTED
                    else -> NodeHighlightState.NONE
                }

                // Determine color pair - special handling for positive/negative prompts
                val titleLower = node.title.lowercase()
                val colorPair = when {
                    titleLower.contains("positive") -> colors.positivePromptColors
                    titleLower.contains("negative") -> colors.negativePromptColors
                    else -> colors.categoryColors[node.category]
                }

                // Get edits for this node
                val nodeEdits = nodeAttributeEdits[node.id] ?: emptyMap()

                // Editable inputs are only highlighted when this node is being edited
                val highlightEditableInputs = editingNodeId == node.id

                drawNode(
                    node = node,
                    colors = colors,
                    highlightState = highlightState,
                    colorPair = colorPair,
                    nodeEdits = nodeEdits,
                    editableInputNames = if (highlightEditableInputs) editableInputNames else emptySet(),
                    uiFieldPrefix = uiFieldPrefix,
                    displayNameResolver = displayNameResolver,
                    inputWireColors = nodeInputColors[node.id] ?: emptyMap(),
                    showEditIcon = isEditMode,
                    editIconDrawable = editIconDrawable
                )
            }

            // Draw slot circles for connection editing
            if (isEditMode) {
                val inConnectionMode = connectionModeState != null

                // Draw output slots on all nodes (one per output) - use animatedNodes
                animatedNodes.forEach { node ->
                    val outputX = node.x + node.width

                    node.outputs.forEachIndexed { outputIndex, outputType ->
                        val outputY = node.y + calculateOutputY(node, outputIndex)

                        // Check if this is the source slot in connection mode
                        val isSourceSlot = inConnectionMode &&
                            connectionModeState?.sourceOutputSlot?.nodeId == node.id &&
                            connectionModeState?.sourceOutputSlot?.outputIndex == outputIndex

                        // Get slot color based on output type
                        val typeColor = colors.slotColors[outputType.uppercase()] ?: colors.edgeColor
                        val slotColor = if (isSourceSlot) {
                            colors.selectedBorder
                        } else {
                            typeColor
                        }

                        drawCircle(
                            color = slotColor,
                            radius = if (isSourceSlot) 14f else 10f,
                            center = Offset(outputX, outputY)
                        )

                        // Draw inner circle for contrast
                        if (!isSourceSlot) {
                            drawCircle(
                                color = colors.nodeBackground,
                                radius = 6f,
                                center = Offset(outputX, outputY)
                            )
                        }
                    }
                }

                // Draw input slots for connection-type inputs on all nodes - use animatedNodes
                animatedNodes.forEach { node ->
                    var inputIndex = 0
                    node.inputs.forEach { (inputName, inputValue) ->
                        val isConnectionInput = inputValue is InputValue.Connection || inputValue is InputValue.UnconnectedSlot

                        if (isConnectionInput) {
                            val inputX = node.x
                            val inputY = node.y + WorkflowLayoutEngine.NODE_HEADER_HEIGHT + 20f +
                                (inputIndex * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)

                            // Get slot type for color
                            val slotType = when (inputValue) {
                                is InputValue.UnconnectedSlot -> inputValue.slotType
                                else -> null
                            }
                            val typeColor = slotType?.let { colors.slotColors[it.uppercase()] } ?: colors.edgeColor

                            drawCircle(
                                color = typeColor,
                                radius = 10f,
                                center = Offset(inputX, inputY)
                            )
                            drawCircle(
                                color = colors.nodeBackground,
                                radius = 6f,
                                center = Offset(inputX, inputY)
                            )
                        }
                        inputIndex++
                    }
                }

                // Draw highlighted valid input slots when in connection mode
                if (inConnectionMode) {
                    connectionModeState?.validInputSlots?.forEach { slot ->
                        // Draw highlighted input slot
                        drawCircle(
                            color = colors.candidateBorder,
                            radius = 12f,
                            center = slot.center
                        )
                        drawCircle(
                            color = colors.nodeBackground,
                            radius = 6f,
                            center = slot.center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draw a single node
 */
private fun DrawScope.drawNode(
    node: WorkflowNode,
    colors: CanvasColors,
    highlightState: NodeHighlightState = NodeHighlightState.NONE,
    colorPair: SlotColors.NodeColorPair? = null,
    nodeEdits: Map<String, Any> = emptyMap(),
    editableInputNames: Set<String> = emptySet(),
    uiFieldPrefix: String = "UI: %1\$s",
    displayNameResolver: (String) -> String = { it },
    inputWireColors: Map<String, Int> = emptyMap(),
    showEditIcon: Boolean = false,
    editIconDrawable: Drawable? = null
) {
    // Determine border color and width based on highlight state
    val (borderColor, borderWidth) = when (highlightState) {
        NodeHighlightState.SELECTED -> colors.selectedBorder to 8f
        NodeHighlightState.CANDIDATE -> colors.candidateBorder to 6f
        NodeHighlightState.NONE -> colors.nodeBorder to 3f
    }

    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
    val cornerRadius = 16f

    // Use color pair for header and body if available, otherwise use defaults
    val headerColor = colorPair?.header ?: colors.nodeHeaderBackground
    val bodyColor = colorPair?.body ?: colors.nodeBackground

    // Create clip path for the entire node shape
    val nodeClipPath = Path().apply {
        addRoundRect(
            RoundRect(
                left = node.x,
                top = node.y,
                right = node.x + node.width,
                bottom = node.y + node.height,
                cornerRadius = CornerRadius(cornerRadius)
            )
        )
    }

    // Node background (body) - use category body color
    drawRoundRect(
        color = bodyColor,
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, node.height),
        cornerRadius = CornerRadius(cornerRadius)
    )

    // Header background - clipped to node shape so corners are rounded
    clipPath(nodeClipPath) {
        drawRect(
            color = headerColor,
            topLeft = Offset(node.x, node.y),
            size = Size(node.width, headerHeight)
        )
    }

    // Node border
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, node.height),
        cornerRadius = CornerRadius(cornerRadius),
        style = Stroke(width = borderWidth)
    )

    // Header separator line
    drawLine(
        color = colors.nodeBorder.copy(alpha = 0.5f),
        start = Offset(node.x, node.y + headerHeight),
        end = Offset(node.x + node.width, node.y + headerHeight),
        strokeWidth = 2f
    )

    // Draw text using native canvas
    val textPrimaryArgb = colors.textPrimary.toArgb()
    val textSecondaryArgb = colors.textSecondary.toArgb()
    val templateHighlightArgb = colors.templateTextHighlight.toArgb()
    val headerColorArgb = headerColor.toArgb()

    drawContext.canvas.nativeCanvas.apply {
        // Title text - leave space for edit icon if shown
        val titleMaxWidth = if (showEditIcon) node.width - 56f else node.width - 32f
        val titlePaint = Paint().apply {
            color = textPrimaryArgb
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val title = truncateText(node.title, titleMaxWidth, titlePaint)
        drawText(title, node.x + 16f, node.y + 40f, titlePaint)

        // Draw edit icon in top-right corner of header when in edit mode
        if (showEditIcon && editIconDrawable != null) {
            val iconSize = 40
            val iconLeft = (node.x + node.width - 48f).toInt()
            val iconTop = (node.y + 12f).toInt()

            // Clone the drawable to avoid mutating the cached version
            val iconCopy = editIconDrawable.mutate().constantState?.newDrawable()?.mutate()
            if (iconCopy != null) {
                // Tint with the text secondary color
                DrawableCompat.setTint(iconCopy, textSecondaryArgb)
                iconCopy.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                iconCopy.draw(this)
            }
        }

        // Input labels
        val inputPaint = Paint().apply {
            color = textSecondaryArgb
            textSize = 22f
            isAntiAlias = true
        }

        // Paint for editable inputs (when side sheet is open)
        val editablePaint = Paint().apply {
            color = colors.selectedBorder.toArgb()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        // Paint for value text inside boxes
        val valueTextPaint = Paint().apply {
            color = textPrimaryArgb
            textSize = 20f
            isAntiAlias = true
        }

        // Paint for template value text (bold, no box)
        val templateValuePaint = Paint().apply {
            color = templateHighlightArgb
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        // Paint for value box background (uses header color)
        val valueBoxPaint = Paint().apply {
            color = headerColorArgb
            isAntiAlias = true
        }

        // Red color for edited values
        val editedColorArgb = android.graphics.Color.RED

        var inputY = node.y + headerHeight + 32f
        node.inputs.forEach { (name, value) ->
            // Check if this is a connection-type input (Connection or UnconnectedSlot)
            val isConnectionInput = value is InputValue.Connection || value is InputValue.UnconnectedSlot

            // Get the original value (only for Literal inputs)
            val originalValue = when (value) {
                is InputValue.Literal -> value.value
                is InputValue.Connection -> null
                is InputValue.UnconnectedSlot -> null
            }

            // Get the current value - use edit if available, otherwise original
            val editedValue = nodeEdits[name]
            val currentValue = editedValue ?: originalValue

            // Check if this value has been edited AND is different from original
            val isEdited = editedValue != null && editedValue != originalValue

            // Only show value string for literal inputs (not connection-type)
            val valueStr = if (!isConnectionInput) {
                currentValue?.let { formatInputValue(it, uiFieldPrefix, displayNameResolver) }
            } else {
                null
            }

            // Check if value contains actual {{...}} template pattern
            val hasTemplatePattern = currentValue?.toString()?.let { str ->
                str.contains("{{") && str.contains("}}")
            } ?: false

            // Determine which paint to use for the key name
            val wireColor = inputWireColors[name]
            // For UnconnectedSlot, get color from slot type
            val slotTypeColor = if (value is InputValue.UnconnectedSlot) {
                colors.slotColors[value.slotType.uppercase()]?.toArgb()
            } else null

            val keyPaint = when {
                // Editable inputs highlighted when side sheet is open
                name in editableInputNames -> editablePaint
                // Connected inputs: bold with blended wire color
                wireColor != null -> {
                    // Blend wire color with standard text color (50/50)
                    val blendedColor = blendColors(wireColor, textSecondaryArgb, 0.5f)
                    Paint().apply {
                        color = blendedColor
                        textSize = 22f
                        typeface = Typeface.DEFAULT_BOLD
                        isAntiAlias = true
                    }
                }
                // Unconnected slot: bold with slot type color
                slotTypeColor != null -> {
                    val blendedColor = blendColors(slotTypeColor, textSecondaryArgb, 0.5f)
                    Paint().apply {
                        color = blendedColor
                        textSize = 22f
                        typeface = Typeface.DEFAULT_BOLD
                        isAntiAlias = true
                    }
                }
                // Normal
                else -> inputPaint
            }

            // Draw key name on the left
            drawText(name, node.x + 16f, inputY, keyPaint)

            // Draw value on the right if present (only for literal inputs)
            if (currentValue is Boolean) {
                // Draw a toggle switch for boolean values
                val toggleWidth = 44f
                val toggleHeight = 24f
                val toggleRight = node.x + node.width - 12f
                val toggleLeft = toggleRight - toggleWidth
                val toggleTop = inputY - 18f
                val toggleBottom = toggleTop + toggleHeight
                val toggleRadius = toggleHeight / 2

                // Draw track (on: selectedBorder/primary, off: nodeBorder/outline)
                val trackPaint = Paint().apply {
                    color = if (currentValue) colors.selectedBorder.toArgb() else colors.nodeBorder.toArgb()
                    isAntiAlias = true
                }
                val trackRect = android.graphics.RectF(toggleLeft, toggleTop, toggleRight, toggleBottom)
                drawRoundRect(trackRect, toggleRadius, toggleRadius, trackPaint)

                // Draw thumb
                // Light mode: always white; Dark mode: black when on, white when off
                val thumbRadius = (toggleHeight - 8f) / 2
                val thumbPadding = 4f
                val thumbCenterY = toggleTop + toggleHeight / 2
                val thumbCenterX = if (currentValue) {
                    toggleRight - thumbPadding - thumbRadius
                } else {
                    toggleLeft + thumbPadding + thumbRadius
                }

                val thumbColor = if (colors.isDarkTheme && currentValue) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
                val thumbPaint = Paint().apply {
                    color = thumbColor
                    isAntiAlias = true
                }
                drawCircle(thumbCenterX, thumbCenterY, thumbRadius, thumbPaint)
            } else if (valueStr != null) {
                val boxPaddingH = 8f
                val boxPaddingV = 6f
                val textHeight = 20f // Approximate text height based on font size
                val boxHeight = textHeight + boxPaddingV * 2
                val boxRight = node.x + node.width - 12f
                val boxWidth = node.width * 0.5f
                val boxLeft = boxRight - boxWidth
                val maxValueWidth = boxWidth - boxPaddingH * 2

                // Choose paint based on whether this is a template or edited value
                val valuePaint = when {
                    hasTemplatePattern -> templateValuePaint
                    isEdited -> Paint().apply {
                        color = blendColors(textPrimaryArgb, editedColorArgb, 0.5f)
                        textSize = 20f
                        typeface = Typeface.DEFAULT_BOLD
                        isAntiAlias = true
                    }
                    else -> valueTextPaint
                }

                // Truncate value to fit in available space
                val truncatedValue = truncateText(valueStr, maxValueWidth, valuePaint)

                // Center the box vertically on the text baseline
                val boxTop = inputY - textHeight - boxPaddingV + 4f
                val boxBottom = boxTop + boxHeight

                // Draw rounded rectangle background only for non-template values
                if (!hasTemplatePattern) {
                    val boxRect = android.graphics.RectF(boxLeft, boxTop, boxRight, boxBottom)
                    drawRoundRect(boxRect, 6f, 6f, valueBoxPaint)
                }

                // Draw value text left-aligned with padding
                val textX = boxLeft + boxPaddingH
                val textY = inputY
                drawText(truncatedValue, textX, textY, valuePaint)
            }

            inputY += WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        }

        // Calculate where outputs start (after literal inputs, aligned with connection inputs)
        val literalInputCount = node.inputs.count { (_, value) -> value is InputValue.Literal }
        var outputY = node.y + headerHeight + 32f + (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)

        // Draw outputs on the right side
        node.outputs.forEachIndexed { _, outputType ->
            // Get color for output type
            val outputColorInt = colors.slotColors[outputType.uppercase()]?.toArgb()

            // Create paint for output name (right-aligned, colored)
            val outputPaint = if (outputColorInt != null) {
                val blendedColor = blendColors(outputColorInt, textSecondaryArgb, 0.5f)
                Paint().apply {
                    color = blendedColor
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                    textAlign = Paint.Align.RIGHT
                }
            } else {
                Paint().apply {
                    color = textSecondaryArgb
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                    textAlign = Paint.Align.RIGHT
                }
            }

            // Draw output name (right-aligned, with padding from right edge)
            val outputTextX = node.x + node.width - 16f
            drawText(outputType, outputTextX, outputY, outputPaint)

            outputY += WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        }
    }
}

/**
 * Draw an edge (connection) between two nodes.
 * Uses bezier curves that depart/arrive horizontally for a natural look.
 */
private fun DrawScope.drawEdge(edge: WorkflowEdge, nodes: List<WorkflowNode>, colors: CanvasColors) {
    val sourceNode = nodes.find { it.id == edge.sourceNodeId } ?: return
    val targetNode = nodes.find { it.id == edge.targetNodeId } ?: return

    // Connection points: always exit right side of source, enter left side of target
    val startX = sourceNode.x + sourceNode.width
    val startY = sourceNode.y + calculateOutputY(sourceNode, edge.sourceOutputIndex)
    val endX = targetNode.x
    val endY = targetNode.y + calculateInputY(targetNode, edge.targetInputName)

    // Resolve color from slot type, fallback to default
    val edgeColor = edge.slotType?.let { slotType ->
        colors.slotColors[slotType.uppercase()]
    } ?: colors.edgeColor

    // Minimum control point offset to ensure wire is visible leaving/entering nodes
    val minOffset = 20f

    val path = Path().apply {
        moveTo(startX, startY)

        if (startX < endX) {
            // Normal case: source is to the left of target
            // Control point offset based on horizontal distance, with minimum
            val xDist = endX - startX
            val offset = maxOf(xDist * 0.4f, minOffset)
            cubicTo(
                startX + offset, startY,  // First control: right of start, same Y (horizontal departure)
                endX - offset, endY,      // Second control: left of end, same Y (horizontal arrival)
                endX, endY
            )
        } else {
            // Complex case: source is to the right of or same column as target
            // Need to loop around - go right, then curve back left
            val loopOffset = maxOf(80f, minOffset)
            val verticalDist = kotlin.math.abs(endY - startY)
            val midY = (startY + endY) / 2

            if (verticalDist < 50f) {
                // Nodes are at similar height - need bigger loop
                val farRight = maxOf(startX, endX) + loopOffset
                cubicTo(
                    farRight, startY,
                    farRight, endY,
                    endX, endY
                )
            } else {
                // Nodes have vertical separation - route through middle
                cubicTo(
                    startX + loopOffset, startY,
                    startX + loopOffset, midY,
                    (startX + endX) / 2, midY
                )
                cubicTo(
                    endX - loopOffset, midY,
                    endX - loopOffset, endY,
                    endX, endY
                )
            }
        }
    }

    // Draw the path
    drawPath(
        path = path,
        color = edgeColor,
        style = Stroke(width = 4f)
    )

    // Draw small circle at connection points
    drawCircle(
        color = edgeColor,
        radius = 8f,
        center = Offset(startX, startY)
    )
    drawCircle(
        color = edgeColor,
        radius = 8f,
        center = Offset(endX, endY)
    )
}

/**
 * Calculate the Y position for a specific input on a node
 */
private fun calculateInputY(node: WorkflowNode, inputName: String): Float {
    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
    val inputIndex = node.inputs.keys.indexOf(inputName)
    return if (inputIndex >= 0) {
        headerHeight + 20f + (inputIndex * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)
    } else {
        node.height / 2
    }
}

/**
 * Calculate the Y offset for an output slot based on its index.
 * Outputs are positioned after literal inputs.
 */
private fun calculateOutputY(node: WorkflowNode, outputIndex: Int): Float {
    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT
    // Count literal inputs to know where outputs start
    val literalInputCount = node.inputs.count { (_, value) -> value is InputValue.Literal }
    // Outputs start after literal inputs
    return headerHeight + 20f + (literalInputCount * WorkflowLayoutEngine.INPUT_ROW_HEIGHT) +
        (outputIndex * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)
}


/**
 * Truncate text to fit within a given width
 */
private fun truncateText(text: String, maxWidth: Float, paint: Paint): String {
    if (paint.measureText(text) <= maxWidth) return text

    var truncated = text
    while (truncated.isNotEmpty() && paint.measureText("$truncated...") > maxWidth) {
        truncated = truncated.dropLast(1)
    }
    return if (truncated.isEmpty()) "..." else "$truncated..."
}

/**
 * Format an input value for display.
 * Template placeholders like {{clip_name}} are converted to UI display names.
 */
private fun formatInputValue(
    value: Any,
    uiFieldPrefix: String,
    displayNameResolver: (String) -> String
): String {
    return when (value) {
        is String -> {
            // Check for template pattern {{placeholder_name}}
            val templateRegex = Regex("""\{\{(\w+)\}\}""")
            val match = templateRegex.matchEntire(value)
            if (match != null) {
                // Extract placeholder name and get display name using resolver
                val placeholderName = match.groupValues[1]
                val displayName = displayNameResolver(placeholderName)
                uiFieldPrefix.format(displayName)
            } else if (value.length > 20) {
                "\"${value.take(17)}...\""
            } else {
                "\"$value\""
            }
        }
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString().take(20)
    }
}

/**
 * Blend two ARGB colors together.
 * @param color1 First color (ARGB int)
 * @param color2 Second color (ARGB int)
 * @param ratio Blend ratio (0.0 = all color1, 1.0 = all color2)
 * @return Blended color as ARGB int
 */
private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
    val inverseRatio = 1f - ratio
    val a = (android.graphics.Color.alpha(color1) * inverseRatio + android.graphics.Color.alpha(color2) * ratio).toInt()
    val r = (android.graphics.Color.red(color1) * inverseRatio + android.graphics.Color.red(color2) * ratio).toInt()
    val g = (android.graphics.Color.green(color1) * inverseRatio + android.graphics.Color.green(color2) * ratio).toInt()
    val b = (android.graphics.Color.blue(color1) * inverseRatio + android.graphics.Color.blue(color2) * ratio).toInt()
    return android.graphics.Color.argb(a, r, g, b)
}
