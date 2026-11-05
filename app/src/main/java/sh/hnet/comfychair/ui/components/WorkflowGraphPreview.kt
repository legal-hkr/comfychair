package sh.hnet.comfychair.ui.components

import sh.hnet.comfychair.workflow.NodeTypeDefinition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.workflow.GraphBounds
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowGraphPreparation
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine

/** A graph ready to draw, with what the canvas needs alongside it. */
private class PreparedGraph(
    val graph: WorkflowGraph,
    val bounds: GraphBounds,
    val nodeDefinitions: Map<String, NodeTypeDefinition>
) {
    val renderedGroups = WorkflowLayoutEngine().calculateRenderedGroups(graph.groups, graph.nodes, graph.notes)
}

/**
 * The whole workflow graph as the workflow editor draws it (node headers, colours, connections), fitted into the
 * element and centred, without gestures. A tap anywhere calls [onClick].
 *
 * @param description What a tap does, for TalkBack
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkflowGraphPreview(
    jsonContent: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val registry = ConnectionManager.nodeTypeRegistry
    // Parsing and layout off the main thread; a large graph takes a moment
    val prepared by produceState<PreparedGraph?>(null, jsonContent) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val graph = WorkflowGraphPreparation.prepare(jsonContent, registry)
                PreparedGraph(
                    graph,
                    WorkflowLayoutEngine().calculateBounds(graph),
                    WorkflowGraphPreparation.nodeDefinitions(graph, registry)
                )
            }.getOrNull()
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val padding = with(LocalDensity.current) { 12.dp.toPx() }

    Box(modifier.onSizeChanged { size = it }) {
        val current = prepared
        if (current == null) {
            LoadingIndicator(Modifier.align(Alignment.Center))
        } else if (size != IntSize.Zero && current.bounds.width > 0 && current.bounds.height > 0) {
            // The whole graph, as large as fits, centred
            val bounds = current.bounds
            val availableWidth = size.width - 2 * padding
            val availableHeight = size.height - 2 * padding
            val scale = minOf(availableWidth / bounds.width, availableHeight / bounds.height, 1f)
            val offset = Offset(
                x = (size.width - bounds.width * scale) / 2f - bounds.minX * scale,
                y = (size.height - bounds.height * scale) / 2f - bounds.minY * scale
            )
            WorkflowGraphCanvas(
                graph = current.graph,
                scale = scale,
                offset = offset,
                onTransform = { _, _ -> },
                enableManualTransform = false,
                renderedGroups = current.renderedGroups,
                notes = current.graph.notes,
                nodeDefinitions = current.nodeDefinitions,
                modifier = Modifier.fillMaxSize()
            )
        }
        // On top, so the canvas's own touch handling doesn't take the tap
        Spacer(
            Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
        )
    }
}
