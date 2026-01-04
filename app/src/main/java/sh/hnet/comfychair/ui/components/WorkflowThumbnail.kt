package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.workflow.GraphBounds
import sh.hnet.comfychair.workflow.SlotColors
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine
import sh.hnet.comfychair.workflow.WorkflowNode
import sh.hnet.comfychair.workflow.WorkflowParser

/**
 * Simplified node data for thumbnail rendering
 */
private data class ThumbnailNode(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Color
)

/**
 * Simplified group data for thumbnail rendering
 */
private data class ThumbnailGroup(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Color
)

/**
 * Cached thumbnail data
 */
private data class ThumbnailData(
    val nodes: List<ThumbnailNode>,
    val groups: List<ThumbnailGroup>,
    val bounds: GraphBounds
)

/**
 * Displays a mini preview of a workflow graph as a thumbnail.
 * Shows simplified colored rectangles representing nodes.
 *
 * @param jsonContent The workflow JSON content to visualize
 * @param modifier Modifier for the composable
 * @param size The size of the thumbnail (default 48.dp)
 */
@Composable
fun WorkflowThumbnail(
    jsonContent: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow

    // Parse and layout the graph, cache based on content hash and theme
    val thumbnailData = remember(jsonContent.hashCode(), isDarkTheme) {
        computeThumbnailData(jsonContent, isDarkTheme)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        if (thumbnailData != null && thumbnailData.nodes.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val canvasWidth = this.size.width
                val canvasHeight = this.size.height
                val bounds = thumbnailData.bounds

                // Calculate scale to fit graph in canvas with padding
                val padding = 4.dp.toPx()
                val availableWidth = canvasWidth - (padding * 2)
                val availableHeight = canvasHeight - (padding * 2)

                val scaleX = if (bounds.width > 0) availableWidth / bounds.width else 1f
                val scaleY = if (bounds.height > 0) availableHeight / bounds.height else 1f
                val scale = minOf(scaleX, scaleY)

                // Calculate offset to center the graph
                val scaledWidth = bounds.width * scale
                val scaledHeight = bounds.height * scale
                val offsetX = padding + (availableWidth - scaledWidth) / 2
                val offsetY = padding + (availableHeight - scaledHeight) / 2

                // Draw groups first (behind nodes)
                thumbnailData.groups.forEach { group ->
                    val groupX = offsetX + (group.x - bounds.minX) * scale
                    val groupY = offsetY + (group.y - bounds.minY) * scale
                    val groupWidth = group.width * scale
                    val groupHeight = group.height * scale

                    drawRoundRect(
                        color = group.color.copy(alpha = 0.2f),
                        topLeft = Offset(groupX, groupY),
                        size = Size(groupWidth, groupHeight),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }

                // Draw each node as a small rounded rectangle
                thumbnailData.nodes.forEach { node ->
                    val nodeX = offsetX + (node.x - bounds.minX) * scale
                    val nodeY = offsetY + (node.y - bounds.minY) * scale
                    val nodeWidth = node.width * scale
                    val nodeHeight = node.height * scale

                    // Minimum size to ensure visibility
                    val minSize = 2.dp.toPx()
                    val drawWidth = maxOf(nodeWidth, minSize)
                    val drawHeight = maxOf(nodeHeight, minSize)

                    drawRoundRect(
                        color = node.color,
                        topLeft = Offset(nodeX, nodeY),
                        size = Size(drawWidth, drawHeight),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Parse workflow JSON and compute thumbnail data
 */
private fun computeThumbnailData(jsonContent: String, isDarkTheme: Boolean): ThumbnailData? {
    return try {
        val parser = WorkflowParser()
        val layoutEngine = WorkflowLayoutEngine()

        // Parse the workflow
        val graph = parser.parse(jsonContent, "", "")

        if (graph.nodes.isEmpty()) {
            return null
        }

        // Layout the nodes
        val layoutedGraph = layoutEngine.layoutGraph(graph)

        // Calculate bounds
        val bounds = layoutEngine.calculateBounds(layoutedGraph)

        // Convert to simplified thumbnail nodes with colors
        val thumbnailNodes = layoutedGraph.nodes.map { node ->
            val colorPair = SlotColors.getCategoryColor(node.category, isDarkTheme)
            ThumbnailNode(
                x = node.x,
                y = node.y,
                width = node.width,
                height = node.height,
                color = colorPair.body
            )
        }

        // Calculate rendered groups with computed bounds
        val renderedGroups = layoutEngine.calculateRenderedGroups(
            layoutedGraph.groups,
            layoutedGraph.nodes
        )

        // Convert groups to simplified thumbnail groups
        val thumbnailGroups = renderedGroups.map { group ->
            val groupColor = try {
                Color(android.graphics.Color.parseColor(group.color))
            } catch (e: Exception) {
                Color(android.graphics.Color.parseColor("#3f789e"))
            }
            ThumbnailGroup(
                x = group.x,
                y = group.y,
                width = group.width,
                height = group.height,
                color = groupColor
            )
        }

        ThumbnailData(nodes = thumbnailNodes, groups = thumbnailGroups, bounds = bounds)
    } catch (e: Exception) {
        // Return null on any parsing error - thumbnail will show empty
        null
    }
}
