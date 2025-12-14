package sh.hnet.comfychair.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import sh.hnet.comfychair.workflow.InputValue
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
    val highlightBorder: Color,
    val candidateBorder: Color,
    val selectedBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val edgeColor: Color
)

/**
 * Canvas component for rendering workflow graphs
 */
@Composable
fun WorkflowGraphCanvas(
    graph: WorkflowGraph,
    scale: Float,
    offset: Offset,
    showTemplateHighlight: Boolean,
    onTransform: (scale: Float, offset: Offset) -> Unit,
    modifier: Modifier = Modifier,
    isFieldMappingMode: Boolean = false,
    highlightedNodeIds: Set<String> = emptySet(),
    mappingState: WorkflowMappingState? = null,
    onNodeTapped: ((String) -> Unit)? = null
) {
    // Use rememberUpdatedState to always have access to current values in the gesture handler
    val currentScaleState = rememberUpdatedState(scale)
    val currentOffsetState = rememberUpdatedState(offset)
    val currentGraphState = rememberUpdatedState(graph)

    // Calculate selected node IDs from mapping state
    val selectedNodeIds = remember(mappingState) {
        mappingState?.fieldMappings
            ?.mapNotNull { it.selectedCandidate?.nodeId }
            ?.toSet()
            ?: emptySet()
    }

    // Extract theme colors
    val colors = CanvasColors(
        nodeBackground = MaterialTheme.colorScheme.surfaceContainer,
        nodeHeaderBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        nodeBorder = MaterialTheme.colorScheme.outline,
        highlightBorder = MaterialTheme.colorScheme.tertiary,
        candidateBorder = MaterialTheme.colorScheme.secondary,
        selectedBorder = MaterialTheme.colorScheme.primary,
        textPrimary = MaterialTheme.colorScheme.onSurface,
        textSecondary = MaterialTheme.colorScheme.onSurfaceVariant,
        edgeColor = MaterialTheme.colorScheme.outlineVariant
    )

    Canvas(
        modifier = modifier
            .pointerInput(isFieldMappingMode, onNodeTapped) {
                if (isFieldMappingMode && onNodeTapped != null) {
                    detectTapGestures { tapOffset ->
                        // Transform tap position to graph coordinates
                        val graphX = (tapOffset.x - currentOffsetState.value.x) / currentScaleState.value
                        val graphY = (tapOffset.y - currentOffsetState.value.y) / currentScaleState.value

                        // Find tapped node
                        val tappedNode = currentGraphState.value.nodes.find { node ->
                            graphX >= node.x && graphX <= node.x + node.width &&
                                    graphY >= node.y && graphY <= node.y + node.height
                        }

                        if (tappedNode != null) {
                            onNodeTapped(tappedNode.id)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
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
    ) {
        // Apply transformation
        withTransform({
            translate(offset.x, offset.y)
            scale(scale, scale, Offset.Zero)
        }) {
            // Draw edges first (behind nodes)
            graph.edges.forEach { edge ->
                drawEdge(edge, graph.nodes, colors)
            }

            // Draw nodes
            graph.nodes.forEach { node ->
                val highlightState = when {
                    isFieldMappingMode && node.id in selectedNodeIds -> NodeHighlightState.SELECTED
                    isFieldMappingMode && node.id in highlightedNodeIds -> NodeHighlightState.CANDIDATE
                    else -> NodeHighlightState.NONE
                }
                drawNode(
                    node = node,
                    showTemplateHighlight = showTemplateHighlight && !isFieldMappingMode,
                    colors = colors,
                    mappingHighlightState = highlightState
                )
            }
        }
    }
}

/**
 * Draw a single node
 */
private fun DrawScope.drawNode(
    node: WorkflowNode,
    showTemplateHighlight: Boolean,
    colors: CanvasColors,
    mappingHighlightState: NodeHighlightState = NodeHighlightState.NONE
) {
    val isTemplateHighlighted = showTemplateHighlight && node.hasTemplateVariables

    // Determine border color and width based on highlight state
    val (borderColor, borderWidth) = when (mappingHighlightState) {
        NodeHighlightState.SELECTED -> colors.selectedBorder to 8f
        NodeHighlightState.CANDIDATE -> colors.candidateBorder to 6f
        NodeHighlightState.NONE -> if (isTemplateHighlighted) {
            colors.highlightBorder to 6f
        } else {
            colors.nodeBorder to 3f
        }
    }

    val headerHeight = WorkflowLayoutEngine.NODE_HEADER_HEIGHT

    // Node background (body)
    drawRoundRect(
        color = colors.nodeBackground,
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, node.height),
        cornerRadius = CornerRadius(16f)
    )

    // Header background (darker) - draw as rect that gets clipped by the rounded node
    drawRect(
        color = colors.nodeHeaderBackground,
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, headerHeight)
    )
    // Redraw top corners rounded
    drawRoundRect(
        color = colors.nodeHeaderBackground,
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, 32f),
        cornerRadius = CornerRadius(16f)
    )

    // Node border
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(node.x, node.y),
        size = Size(node.width, node.height),
        cornerRadius = CornerRadius(16f),
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
    val highlightArgb = colors.highlightBorder.toArgb()

    drawContext.canvas.nativeCanvas.apply {
        // Title text
        val titlePaint = Paint().apply {
            color = textPrimaryArgb
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val title = truncateText(node.title, node.width - 32f, titlePaint)
        drawText(title, node.x + 16f, node.y + 40f, titlePaint)

        // Input labels
        val inputPaint = Paint().apply {
            color = textSecondaryArgb
            textSize = 22f
            isAntiAlias = true
        }

        val highlightPaint = Paint().apply {
            color = highlightArgb
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        var inputY = node.y + headerHeight + 32f
        node.inputs.forEach { (name, value) ->
            val valueStr = when (value) {
                is InputValue.Literal -> formatInputValue(value.value)
                is InputValue.Connection -> null
            }

            // Check if this input key is a known template key
            val isTemplateKey = name in node.templateInputKeys

            if (isTemplateKey && isTemplateHighlighted) {
                // Draw highlighted: entire row in highlight color
                val displayText = if (valueStr != null) "$name: $valueStr" else name
                val truncated = truncateText(displayText, node.width - 32f, highlightPaint)
                drawText(truncated, node.x + 16f, inputY, highlightPaint)
            } else {
                // Draw normal
                val displayText = if (valueStr != null) "$name: $valueStr" else name
                val truncated = truncateText(displayText, node.width - 32f, inputPaint)
                drawText(truncated, node.x + 16f, inputY, inputPaint)
            }
            inputY += WorkflowLayoutEngine.INPUT_ROW_HEIGHT
        }
    }
}

/**
 * Draw an edge (connection) between two nodes
 */
private fun DrawScope.drawEdge(edge: WorkflowEdge, nodes: List<WorkflowNode>, colors: CanvasColors) {
    val sourceNode = nodes.find { it.id == edge.sourceNodeId } ?: return
    val targetNode = nodes.find { it.id == edge.targetNodeId } ?: return

    // Calculate connection points
    val startX = sourceNode.x + sourceNode.width
    val startY = sourceNode.y + sourceNode.height / 2

    val endX = targetNode.x
    val endY = targetNode.y + calculateInputY(targetNode, edge.targetInputName)

    // Draw bezier curve
    val path = Path().apply {
        moveTo(startX, startY)
        val controlOffset = (endX - startX) / 2
        cubicTo(
            startX + controlOffset, startY,
            endX - controlOffset, endY,
            endX, endY
        )
    }

    // Draw the path
    drawPath(
        path = path,
        color = colors.edgeColor,
        style = Stroke(width = 4f)
    )

    // Draw small circle at connection points
    drawCircle(
        color = colors.edgeColor,
        radius = 8f,
        center = Offset(startX, startY)
    )
    drawCircle(
        color = colors.edgeColor,
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
 * Format an input value for display
 */
private fun formatInputValue(value: Any): String {
    return when (value) {
        is String -> {
            if (value.length > 20) {
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
