package sh.hnet.comfychair.workflow

/**
 * Computes layout positions for workflow graph nodes using a layered approach
 */
class WorkflowLayoutEngine {

    companion object {
        const val NODE_WIDTH = 420f
        const val NODE_MIN_HEIGHT = 160f
        const val NODE_HEADER_HEIGHT = 64f
        const val NODE_PADDING = 48f
        const val HORIZONTAL_SPACING = 240f
        const val VERTICAL_SPACING = 80f
        const val INPUT_ROW_HEIGHT = 40f

        // Serpentine layout constants
        const val MAX_NODES_PER_COLUMN = 8
        const val MIN_COLUMNS = 2
        const val ROW_SPACING = 120f
    }

    /**
     * Compute positions for all nodes in the graph
     */
    fun layoutGraph(graph: WorkflowGraph): WorkflowGraph {
        if (graph.nodes.isEmpty()) return graph

        // Step 1: Build dependency map (node -> nodes it depends on)
        val dependencies = buildDependencyMap(graph)

        // Step 2: Assign layers using longest path method
        // Orphan nodes (no connections) are placed at the end
        val layers = assignLayers(graph.nodes, dependencies, graph.edges)

        // Step 3: Order nodes within each layer
        val orderedLayers = orderNodesInLayers(layers, graph.edges)

        // Step 4: Calculate final positions
        return positionNodes(graph, orderedLayers)
    }

    /**
     * Calculate the bounds of the laid out graph
     */
    fun calculateBounds(graph: WorkflowGraph): GraphBounds {
        if (graph.nodes.isEmpty()) return GraphBounds()

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        graph.nodes.forEach { node ->
            minX = minOf(minX, node.x)
            minY = minOf(minY, node.y)
            maxX = maxOf(maxX, node.x + node.width)
            maxY = maxOf(maxY, node.y + node.height)
        }

        return GraphBounds(
            minX = minX - NODE_PADDING,
            minY = minY - NODE_PADDING,
            maxX = maxX + NODE_PADDING,
            maxY = maxY + NODE_PADDING
        )
    }

    /**
     * Build a map of node ID to the set of node IDs it depends on (inputs from)
     */
    private fun buildDependencyMap(graph: WorkflowGraph): Map<String, Set<String>> {
        val dependencies = mutableMapOf<String, MutableSet<String>>()

        // Initialize all nodes with empty dependency sets
        graph.nodes.forEach { node ->
            dependencies[node.id] = mutableSetOf()
        }

        // Add dependencies from edges
        graph.edges.forEach { edge ->
            dependencies[edge.targetNodeId]?.add(edge.sourceNodeId)
        }

        return dependencies
    }

    /**
     * Assign nodes to layers based on their dependencies.
     * Connected nodes are layered by dependency depth.
     * Orphan nodes (no incoming AND no outgoing edges) are placed in a final layer at the end.
     */
    private fun assignLayers(
        nodes: List<WorkflowNode>,
        dependencies: Map<String, Set<String>>,
        edges: List<WorkflowEdge>
    ): Map<Int, MutableList<WorkflowNode>> {
        val layerAssignment = mutableMapOf<String, Int>()
        val nodeMap = nodes.associateBy { it.id }

        // Build set of nodes that have outgoing edges
        val nodesWithOutgoing = edges.map { it.sourceNodeId }.toSet()

        // Identify orphan nodes (no incoming AND no outgoing connections)
        val orphanNodes = mutableSetOf<String>()
        nodes.forEach { node ->
            val hasIncoming = dependencies[node.id]?.isNotEmpty() == true
            val hasOutgoing = node.id in nodesWithOutgoing
            if (!hasIncoming && !hasOutgoing) {
                orphanNodes.add(node.id)
            }
        }

        // Compute layer for each non-orphan node using longest path
        fun computeLayer(nodeId: String, visited: MutableSet<String>): Int {
            // Skip orphans - they'll be handled separately
            if (nodeId in orphanNodes) return -1

            // Return cached value if already computed
            if (nodeId in layerAssignment) return layerAssignment[nodeId]!!

            // Prevent infinite loops in case of cycles
            if (nodeId in visited) return 0

            visited.add(nodeId)

            val deps = dependencies[nodeId] ?: emptySet()
            // Filter out orphan dependencies
            val validDeps = deps.filter { it !in orphanNodes }
            val maxDepLayer = if (validDeps.isEmpty()) {
                -1
            } else {
                validDeps.maxOfOrNull { depId ->
                    if (nodeMap.containsKey(depId)) {
                        computeLayer(depId, visited)
                    } else {
                        -1 // Unknown dependency
                    }
                } ?: -1
            }

            val layer = maxDepLayer + 1
            layerAssignment[nodeId] = layer
            return layer
        }

        // Compute layers for non-orphan nodes
        nodes.filter { it.id !in orphanNodes }.forEach { node ->
            computeLayer(node.id, mutableSetOf())
        }

        // Group non-orphan nodes by layer
        val layers = mutableMapOf<Int, MutableList<WorkflowNode>>()
        nodes.filter { it.id !in orphanNodes }.forEach { node ->
            val layer = layerAssignment[node.id] ?: 0
            layers.getOrPut(layer) { mutableListOf() }.add(node)
        }

        // Add orphan nodes to a final layer (after all connected nodes)
        if (orphanNodes.isNotEmpty()) {
            val maxLayer = layers.keys.maxOrNull() ?: -1
            val orphanLayer = maxLayer + 1
            layers[orphanLayer] = nodes.filter { it.id in orphanNodes }.toMutableList()
        }

        return layers
    }

    /**
     * Order nodes within each layer to minimize edge crossings
     * Uses a simple barycenter heuristic
     */
    private fun orderNodesInLayers(
        layers: Map<Int, MutableList<WorkflowNode>>,
        edges: List<WorkflowEdge>
    ): List<List<WorkflowNode>> {
        val sortedLayerKeys = layers.keys.sorted()
        val result = mutableListOf<List<WorkflowNode>>()

        // Build reverse lookup for previous layer positions
        val nodePositions = mutableMapOf<String, Int>()

        for (layerIndex in sortedLayerKeys) {
            val layerNodes = layers[layerIndex] ?: continue

            if (result.isEmpty()) {
                // First layer: sort by category for nice grouping
                val sorted = layerNodes.sortedWith(
                    compareBy({ it.category.ordinal }, { it.classType })
                )
                sorted.forEachIndexed { index, node ->
                    nodePositions[node.id] = index
                }
                result.add(sorted)
            } else {
                // Subsequent layers: use barycenter of connected nodes in previous layer
                val barycenters = layerNodes.map { node ->
                    val connectedPositions = edges
                        .filter { it.targetNodeId == node.id }
                        .mapNotNull { edge -> nodePositions[edge.sourceNodeId] }

                    val barycenter = if (connectedPositions.isNotEmpty()) {
                        connectedPositions.average()
                    } else {
                        Double.MAX_VALUE // Put disconnected nodes at the end
                    }
                    node to barycenter
                }

                val sorted = barycenters.sortedBy { it.second }.map { it.first }
                sorted.forEachIndexed { index, node ->
                    nodePositions[node.id] = index
                }
                result.add(sorted)
            }
        }

        return result
    }

    /**
     * Calculate the number of columns based on total node count.
     * Ensures no more than MAX_NODES_PER_COLUMN nodes per column for readability.
     */
    private fun calculateColumnsForNodeCount(nodeCount: Int): Int {
        val calculated = kotlin.math.ceil(nodeCount / MAX_NODES_PER_COLUMN.toFloat()).toInt()
        return maxOf(MIN_COLUMNS, calculated)
    }

    /**
     * Calculate final x, y positions for all nodes using serpentine layout.
     * Layers are arranged in rows with dynamic column count based on node count.
     */
    private fun positionNodes(
        graph: WorkflowGraph,
        layers: List<List<WorkflowNode>>
    ): WorkflowGraph {
        val updatedNodes = graph.nodes.toMutableList()
        val nodeIndexMap = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()

        // Calculate columns based on total nodes for optimal readability
        val columnsPerRow = calculateColumnsForNodeCount(graph.nodes.size)

        // Group layers into rows
        val rows = layers.chunked(columnsPerRow)

        // First pass: calculate the height of each row (max height of all layers in that row)
        val rowHeights = rows.map { rowLayers ->
            rowLayers.maxOfOrNull { layer ->
                var layerHeight = 0f
                for (node in layer) {
                    layerHeight += calculateNodeHeight(node) + VERTICAL_SPACING
                }
                // Remove trailing spacing
                if (layer.isNotEmpty()) layerHeight -= VERTICAL_SPACING
                layerHeight
            } ?: 0f
        }

        // Second pass: position nodes in serpentine pattern
        var currentRowY = NODE_PADDING

        rows.forEachIndexed { rowIndex, rowLayers ->
            rowLayers.forEachIndexed { columnIndex, layer ->
                val columnX = NODE_PADDING + columnIndex * (NODE_WIDTH + HORIZONTAL_SPACING)
                var currentY = currentRowY

                for (node in layer) {
                    val nodeIndex = nodeIndexMap[node.id] ?: continue
                    val height = calculateNodeHeight(node)

                    updatedNodes[nodeIndex] = node.copy(
                        x = columnX,
                        y = currentY,
                        width = NODE_WIDTH,
                        height = height
                    )

                    currentY += height + VERTICAL_SPACING
                }
            }

            // Move to next row
            currentRowY += rowHeights[rowIndex] + ROW_SPACING
        }

        return graph.copy(nodes = updatedNodes)
    }

    /**
     * Calculate the height of a node based on its inputs and outputs.
     * Layout: Header -> Literal inputs -> Connection area (inputs left, outputs right)
     */
    private fun calculateNodeHeight(node: WorkflowNode): Float {
        // Count literal inputs (editable key:value pairs)
        val literalInputCount = node.inputs.count { (_, value) ->
            value is InputValue.Literal
        }

        // Count connection inputs (Connection or UnconnectedSlot)
        val connectionInputCount = node.inputs.count { (_, value) ->
            value is InputValue.Connection || value is InputValue.UnconnectedSlot
        }

        // Output count
        val outputCount = node.outputs.size

        // Connection area height = max of connection inputs and outputs
        val connectionAreaHeight = maxOf(connectionInputCount, outputCount) * INPUT_ROW_HEIGHT

        // Total content height
        val contentHeight = (literalInputCount * INPUT_ROW_HEIGHT) + connectionAreaHeight

        return maxOf(NODE_MIN_HEIGHT, NODE_HEADER_HEIGHT + contentHeight + 16f)
    }
}
