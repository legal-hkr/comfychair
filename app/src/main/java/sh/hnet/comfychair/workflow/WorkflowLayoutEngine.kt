package sh.hnet.comfychair.workflow

/**
 * Computes layout positions for workflow graph nodes using a layered approach
 */
class WorkflowLayoutEngine {

    companion object {
        const val NODE_WIDTH = 360f
        const val NODE_MIN_HEIGHT = 160f
        const val NODE_HEADER_HEIGHT = 64f
        const val NODE_PADDING = 48f
        const val HORIZONTAL_SPACING = 240f
        const val VERTICAL_SPACING = 80f
        const val INPUT_ROW_HEIGHT = 40f
    }

    /**
     * Compute positions for all nodes in the graph
     */
    fun layoutGraph(graph: WorkflowGraph): WorkflowGraph {
        if (graph.nodes.isEmpty()) return graph

        // Step 1: Build dependency map (node -> nodes it depends on)
        val dependencies = buildDependencyMap(graph)

        // Step 2: Assign layers using longest path method
        val layers = assignLayers(graph.nodes, dependencies)

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
     * Assign nodes to layers based on their dependencies
     * Nodes with no dependencies go to layer 0, etc.
     */
    private fun assignLayers(
        nodes: List<WorkflowNode>,
        dependencies: Map<String, Set<String>>
    ): Map<Int, MutableList<WorkflowNode>> {
        val layerAssignment = mutableMapOf<String, Int>()
        val nodeMap = nodes.associateBy { it.id }

        // Compute layer for each node using longest path
        fun computeLayer(nodeId: String, visited: MutableSet<String>): Int {
            // Return cached value if already computed
            if (nodeId in layerAssignment) return layerAssignment[nodeId]!!

            // Prevent infinite loops in case of cycles
            if (nodeId in visited) return 0

            visited.add(nodeId)

            val deps = dependencies[nodeId] ?: emptySet()
            val maxDepLayer = if (deps.isEmpty()) {
                -1
            } else {
                deps.maxOfOrNull { depId ->
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

        // Compute layers for all nodes
        nodes.forEach { node ->
            computeLayer(node.id, mutableSetOf())
        }

        // Group nodes by layer
        val layers = mutableMapOf<Int, MutableList<WorkflowNode>>()
        nodes.forEach { node ->
            val layer = layerAssignment[node.id] ?: 0
            layers.getOrPut(layer) { mutableListOf() }.add(node)
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
     * Calculate final x, y positions for all nodes
     */
    private fun positionNodes(
        graph: WorkflowGraph,
        layers: List<List<WorkflowNode>>
    ): WorkflowGraph {
        val updatedNodes = graph.nodes.toMutableList()
        val nodeIndexMap = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()

        var currentX = NODE_PADDING

        for (layer in layers) {
            var currentY = NODE_PADDING
            var maxWidth = 0f

            for (node in layer) {
                val nodeIndex = nodeIndexMap[node.id] ?: continue
                val height = calculateNodeHeight(node)

                updatedNodes[nodeIndex] = node.copy(
                    x = currentX,
                    y = currentY,
                    width = NODE_WIDTH,
                    height = height
                )

                currentY += height + VERTICAL_SPACING
                maxWidth = maxOf(maxWidth, NODE_WIDTH)
            }

            currentX += maxWidth + HORIZONTAL_SPACING
        }

        return graph.copy(nodes = updatedNodes)
    }

    /**
     * Calculate the height of a node based on its inputs
     */
    private fun calculateNodeHeight(node: WorkflowNode): Float {
        val inputCount = node.inputs.size
        val contentHeight = inputCount * INPUT_ROW_HEIGHT
        return maxOf(NODE_MIN_HEIGHT, NODE_HEADER_HEIGHT + contentHeight + 16f)
    }
}
