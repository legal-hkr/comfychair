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

        // Group constants
        const val GROUP_PADDING = 32f
        const val GROUP_HEADER_HEIGHT = 24f  // Smaller than node header
    }

    /**
     * Compute positions for all nodes in the graph.
     * Uses group-aware layout when groups are present.
     */
    fun layoutGraph(graph: WorkflowGraph): WorkflowGraph {
        if (graph.nodes.isEmpty()) return graph

        // Fast path: no groups, use standard layout
        if (graph.groups.isEmpty()) {
            return layoutWithoutGroups(graph)
        }

        // Group-aware layout
        return layoutWithGroups(graph)
    }

    /**
     * Standard layout algorithm for graphs without groups.
     */
    private fun layoutWithoutGroups(graph: WorkflowGraph): WorkflowGraph {
        val dependencies = buildDependencyMap(graph)
        val layers = assignLayers(graph.nodes, dependencies, graph.edges)
        val orderedLayers = orderNodesInLayers(layers, graph.edges)
        return positionNodes(graph, orderedLayers)
    }

    /**
     * Internal layout result for a group's members.
     * Positions are relative to the group's origin (0,0).
     */
    private data class GroupInternalLayout(
        val groupId: Int,
        val memberPositions: Map<String, RelativePosition>,
        val width: Float,
        val height: Float
    )

    private data class RelativePosition(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * Group-aware layout: treats groups as single units during layout.
     */
    private fun layoutWithGroups(graph: WorkflowGraph): WorkflowGraph {
        val nodeMap = graph.nodes.associateBy { it.id }
        val groupedNodeIds = graph.groups.flatMap { it.memberNodeIds }.toSet()

        // Step 1: Compute internal layout for each group
        val groupInternalLayouts = mutableMapOf<Int, GroupInternalLayout>()
        for (group in graph.groups) {
            val memberNodes = group.memberNodeIds.mapNotNull { nodeMap[it] }
            if (memberNodes.size >= 2) {
                groupInternalLayouts[group.id] = computeGroupInternalLayout(
                    group.id, memberNodes, graph.edges, groupedNodeIds
                )
            }
        }

        // Step 2: Create virtual nodes for groups
        val virtualGroupNodes = graph.groups.mapNotNull { group ->
            val layout = groupInternalLayouts[group.id] ?: return@mapNotNull null
            createVirtualNodeForGroup(group, layout)
        }
        val virtualNodeIds = virtualGroupNodes.map { it.id }.toSet()

        // Step 3: Build combined node list and collapsed edges
        val ungroupedNodes = graph.nodes.filter { it.id !in groupedNodeIds }
        val combinedNodes = ungroupedNodes + virtualGroupNodes
        val collapsedEdges = collapseEdgesForGroups(graph.edges, graph.groups, groupedNodeIds)

        // Step 4: Run standard layout on combined nodes
        val dependencies = buildDependencyMapFromEdges(combinedNodes, collapsedEdges)
        val layers = assignLayers(combinedNodes, dependencies, collapsedEdges)
        val orderedLayers = orderNodesInLayers(layers, collapsedEdges)
        val positioned = positionNodes(
            WorkflowGraph(
                name = graph.name,
                description = graph.description,
                nodes = combinedNodes,
                edges = collapsedEdges,
                groups = emptyList(),
                templateVariables = graph.templateVariables
            ),
            orderedLayers
        )

        // Step 5: Expand virtual nodes back to member positions
        val finalNodes = expandVirtualNodesToMembers(
            positioned.nodes, virtualNodeIds, groupInternalLayouts, graph.nodes
        )

        return graph.copy(nodes = finalNodes)
    }

    /**
     * Compute internal layout for nodes within a group.
     * Positions are relative to group origin (0,0).
     * Uses grid layout with preference for horizontal arrangement.
     * Optimizes for external connections: inputs on left, outputs on right.
     */
    private fun computeGroupInternalLayout(
        groupId: Int,
        memberNodes: List<WorkflowNode>,
        allEdges: List<WorkflowEdge>,
        groupedNodeIds: Set<String>
    ): GroupInternalLayout {
        val memberIds = memberNodes.map { it.id }.toSet()

        // Categorize nodes by external connection type
        val nodesWithExternalInputs = mutableSetOf<String>()
        val nodesWithExternalOutputs = mutableSetOf<String>()

        for (edge in allEdges) {
            val sourceInGroup = edge.sourceNodeId in memberIds
            val targetInGroup = edge.targetNodeId in memberIds

            if (sourceInGroup && !targetInGroup) {
                // Edge going OUT of group
                nodesWithExternalOutputs.add(edge.sourceNodeId)
            }
            if (!sourceInGroup && targetInGroup) {
                // Edge coming INTO group
                nodesWithExternalInputs.add(edge.targetNodeId)
            }
        }

        // Sort members: external inputs first (leftmost), then middle, then external outputs (rightmost)
        val sortedMembers = memberNodes.sortedWith { a, b ->
            val aHasInput = a.id in nodesWithExternalInputs
            val aHasOutput = a.id in nodesWithExternalOutputs
            val bHasInput = b.id in nodesWithExternalInputs
            val bHasOutput = b.id in nodesWithExternalOutputs

            when {
                // Nodes with only inputs go first (leftmost columns)
                aHasInput && !aHasOutput && !(bHasInput && !bHasOutput) -> -1
                bHasInput && !bHasOutput && !(aHasInput && !aHasOutput) -> 1
                // Nodes with only outputs go last (rightmost columns)
                aHasOutput && !aHasInput && !(bHasOutput && !bHasInput) -> 1
                bHasOutput && !bHasInput && !(aHasOutput && !aHasInput) -> -1
                else -> 0
            }
        }

        // Calculate grid dimensions: prefer vertical (more rows than columns) for portrait screens
        val nodeCount = sortedMembers.size
        val rows = kotlin.math.ceil(kotlin.math.sqrt(nodeCount.toDouble())).toInt().coerceAtLeast(1)
        val columns = kotlin.math.ceil(nodeCount.toDouble() / rows).toInt().coerceAtLeast(1)

        // Calculate height for each node
        val nodeHeights = sortedMembers.map { calculateNodeHeight(it) }

        // Calculate row heights (max height of nodes in each row)
        val rowHeights = (0 until rows).map { row ->
            val startIdx = row * columns
            val endIdx = minOf(startIdx + columns, nodeCount)
            (startIdx until endIdx).maxOfOrNull { nodeHeights[it] } ?: NODE_MIN_HEIGHT
        }

        // Position nodes in grid pattern
        // Nodes start at (0,0) relative to virtual node position - this keeps them
        // aligned with ungrouped nodes. The group background will extend outward.
        val memberPositions = mutableMapOf<String, RelativePosition>()

        sortedMembers.forEachIndexed { index, node ->
            val col = index % columns
            val row = index / columns

            val x = col * (NODE_WIDTH + HORIZONTAL_SPACING)
            val y = (0 until row).sumOf { rowHeights[it].toDouble() + VERTICAL_SPACING }.toFloat()
            val height = nodeHeights[index]

            memberPositions[node.id] = RelativePosition(
                x = x,
                y = y,
                width = NODE_WIDTH,
                height = height
            )
        }

        // Calculate total dimensions
        val totalWidth = columns * NODE_WIDTH + (columns - 1) * HORIZONTAL_SPACING
        val totalHeight = rowHeights.sum() + (rows - 1) * VERTICAL_SPACING

        return GroupInternalLayout(
            groupId = groupId,
            memberPositions = memberPositions,
            width = totalWidth,
            height = totalHeight
        )
    }

    /**
     * Create a virtual node representing a group for layout purposes.
     */
    private fun createVirtualNodeForGroup(
        group: WorkflowGroup,
        layout: GroupInternalLayout
    ): WorkflowNode {
        return WorkflowNode(
            id = "virtual_group_${group.id}",
            classType = "ComfyChairGroup",
            title = group.title,
            category = NodeCategory.OTHER,
            inputs = emptyMap(),
            outputs = emptyList(),
            templateInputKeys = emptySet(),
            width = layout.width,
            height = layout.height
        )
    }

    /**
     * Collapse edges so that edges to/from group members become edges to/from the virtual group node.
     */
    private fun collapseEdgesForGroups(
        edges: List<WorkflowEdge>,
        groups: List<WorkflowGroup>,
        groupedNodeIds: Set<String>
    ): List<WorkflowEdge> {
        // Build map: nodeId -> virtual group node ID
        val nodeToVirtualGroup = mutableMapOf<String, String>()
        for (group in groups) {
            val virtualId = "virtual_group_${group.id}"
            for (nodeId in group.memberNodeIds) {
                nodeToVirtualGroup[nodeId] = virtualId
            }
        }

        return edges.mapNotNull { edge ->
            val newSourceId = nodeToVirtualGroup[edge.sourceNodeId] ?: edge.sourceNodeId
            val newTargetId = nodeToVirtualGroup[edge.targetNodeId] ?: edge.targetNodeId

            // Skip internal edges (both endpoints in same group)
            if (newSourceId == newTargetId && newSourceId.startsWith("virtual_group_")) {
                return@mapNotNull null
            }

            edge.copy(
                sourceNodeId = newSourceId,
                targetNodeId = newTargetId
            )
        }.distinctBy { "${it.sourceNodeId}->${it.targetNodeId}" }
    }

    /**
     * Build dependency map from edges (for combined nodes).
     */
    private fun buildDependencyMapFromEdges(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>
    ): Map<String, Set<String>> {
        val dependencies = mutableMapOf<String, MutableSet<String>>()

        nodes.forEach { node ->
            dependencies[node.id] = mutableSetOf()
        }

        edges.forEach { edge ->
            dependencies[edge.targetNodeId]?.add(edge.sourceNodeId)
        }

        return dependencies
    }

    /**
     * Expand virtual group nodes back to their member nodes with proper positions.
     */
    private fun expandVirtualNodesToMembers(
        positionedNodes: List<WorkflowNode>,
        virtualNodeIds: Set<String>,
        groupInternalLayouts: Map<Int, GroupInternalLayout>,
        originalNodes: List<WorkflowNode>
    ): List<WorkflowNode> {
        val result = mutableListOf<WorkflowNode>()
        val originalNodeMap = originalNodes.associateBy { it.id }

        for (node in positionedNodes) {
            if (node.id in virtualNodeIds) {
                // This is a virtual group node - expand to members
                val groupId = node.id.removePrefix("virtual_group_").toIntOrNull() ?: continue
                val layout = groupInternalLayouts[groupId] ?: continue

                for ((memberId, relPos) in layout.memberPositions) {
                    val originalNode = originalNodeMap[memberId] ?: continue
                    result.add(originalNode.copy(
                        x = node.x + relPos.x,
                        y = node.y + relPos.y,
                        width = relPos.width,
                        height = relPos.height
                    ))
                }
            } else {
                // Regular node - keep as is
                result.add(node)
            }
        }

        return result
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

        // First pass: calculate dimensions for each row
        // - Row height = max of layer heights in that row
        // - Column widths = max node width in each layer/column
        val rowHeights = rows.map { rowLayers ->
            rowLayers.maxOfOrNull { layer ->
                var layerHeight = 0f
                for (node in layer) {
                    // Use pre-set height for virtual nodes, calculate for regular nodes
                    val nodeHeight = if (node.height > 0) node.height else calculateNodeHeight(node)
                    layerHeight += nodeHeight + VERTICAL_SPACING
                }
                // Remove trailing spacing
                if (layer.isNotEmpty()) layerHeight -= VERTICAL_SPACING
                layerHeight
            } ?: 0f
        }

        // Calculate column widths for each row (max width of nodes in each layer)
        val rowColumnWidths = rows.map { rowLayers ->
            rowLayers.map { layer ->
                layer.maxOfOrNull { node ->
                    if (node.width > 0) node.width else NODE_WIDTH
                } ?: NODE_WIDTH
            }
        }

        // Second pass: position nodes in serpentine pattern
        var currentRowY = NODE_PADDING

        rows.forEachIndexed { rowIndex, rowLayers ->
            val columnWidths = rowColumnWidths[rowIndex]
            var currentX = NODE_PADDING

            rowLayers.forEachIndexed { columnIndex, layer ->
                var currentY = currentRowY

                for (node in layer) {
                    val nodeIndex = nodeIndexMap[node.id] ?: continue
                    // Use pre-set dimensions for virtual nodes, calculate for regular nodes
                    val height = if (node.height > 0) node.height else calculateNodeHeight(node)
                    val width = if (node.width > 0) node.width else NODE_WIDTH

                    updatedNodes[nodeIndex] = node.copy(
                        x = currentX,
                        y = currentY,
                        width = width,
                        height = height
                    )

                    currentY += height + VERTICAL_SPACING
                }

                // Move to next column using actual column width
                currentX += columnWidths[columnIndex] + HORIZONTAL_SPACING
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

    /**
     * Calculate rendered groups with computed bounds from member node positions.
     *
     * @param groups The workflow groups (with membership data only)
     * @param nodes All nodes in the graph (must have positions assigned)
     * @return List of RenderedGroup with computed bounds for rendering
     */
    fun calculateRenderedGroups(
        groups: List<WorkflowGroup>,
        nodes: List<WorkflowNode>
    ): List<RenderedGroup> {
        if (groups.isEmpty()) return emptyList()

        val nodeMap = nodes.associateBy { it.id }

        return groups.mapNotNull { group ->
            val memberNodes = group.memberNodeIds.mapNotNull { nodeMap[it] }

            // Skip groups with no valid members (nodes may have been deleted)
            if (memberNodes.isEmpty()) return@mapNotNull null

            // Calculate bounds to encompass all member nodes
            val minX = memberNodes.minOf { it.x } - GROUP_PADDING
            val minY = memberNodes.minOf { it.y } - GROUP_PADDING - GROUP_HEADER_HEIGHT
            val maxX = memberNodes.maxOf { it.x + it.width } + GROUP_PADDING
            val maxY = memberNodes.maxOf { it.y + it.height } + GROUP_PADDING

            RenderedGroup(
                group = group,
                x = minX,
                y = minY,
                width = maxX - minX,
                height = maxY - minY
            )
        }
    }
}
