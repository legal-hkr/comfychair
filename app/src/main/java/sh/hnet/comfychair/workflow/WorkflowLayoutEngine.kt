package sh.hnet.comfychair.workflow

/**
 * Computes layout positions for workflow graph nodes using a layered approach
 *
 * @param targetAspect Width / height the whole layout should come close to (e.g. a wide preview card); null for the
 *   editor's layout, which wraps layers into rows for a portrait screen
 */
class WorkflowLayoutEngine(private val targetAspect: Float? = null) {

    companion object {
        const val NODE_WIDTH = 480f  // 6 grid cells (6 × 80)
        const val NODE_MIN_HEIGHT = 160f
        const val NODE_HEADER_HEIGHT = 64f
        const val NODE_PADDING = 48f
        const val HORIZONTAL_SPACING = 240f
        const val VERTICAL_SPACING = 80f
        const val INPUT_ROW_HEIGHT = 40f
        // Above the first row and below the last (the node height adds both)
        const val NODE_BODY_PADDING = 8f

        /** Vertical centre of a node's row (inputs in order, then outputs beside the connection inputs), from its top. */
        fun rowCenterY(rowIndex: Int): Float =
            NODE_HEADER_HEIGHT + NODE_BODY_PADDING + rowIndex * INPUT_ROW_HEIGHT + INPUT_ROW_HEIGHT / 2

        /** An input socket's centre from the node's top (inputs are sorted: literals first, then connections). */
        fun inputSlotY(node: WorkflowNode, inputName: String): Float {
            val index = node.inputs.keys.indexOf(inputName)
            return if (index >= 0) rowCenterY(index) else node.height / 2
        }

        /** An output socket's centre from the node's top: outputs start beside the first connection input. */
        fun outputSlotY(node: WorkflowNode, outputIndex: Int): Float =
            rowCenterY(node.inputs.count { (_, value) -> value is InputValue.Literal } + outputIndex)

        // A subgraph instance's foot, holding its Open button (spec §19.1: a 36 dp button 6 dp inside a 4 dp bezel)
        const val SUBGRAPH_FOOTER_HEIGHT = 96f
        // Inside a subgraph: the Inputs and Outputs strips' width, and the gap from the content to their centre lines
        const val PORT_STRIP_WIDTH = 48f
        const val PORT_STRIP_GAP = 160f

        /** A port strip's height for [sockets] sockets: its cap (a node's header), then a row per socket. */
        fun portStripHeight(sockets: Int): Float =
            NODE_HEADER_HEIGHT + 2 * NODE_BODY_PADDING + maxOf(sockets, 1) * INPUT_ROW_HEIGHT

        // Serpentine layout constants
        const val MAX_NODES_PER_COLUMN = 8
        const val MIN_COLUMNS = 2
        const val ROW_SPACING = 120f

        // Grid constants for manual node placement
        const val GRID_CELL_SIZE = 80f  // Square grid cells for manual placement

        /**
         * Snap a position to the nearest grid cell.
         * Returns a pair of (snappedX, snappedY).
         */
        fun snapToGrid(x: Float, y: Float): Pair<Float, Float> {
            val snappedX = (kotlin.math.round(x / GRID_CELL_SIZE) * GRID_CELL_SIZE).coerceAtLeast(NODE_PADDING)
            val snappedY = (kotlin.math.round(y / GRID_CELL_SIZE) * GRID_CELL_SIZE).coerceAtLeast(NODE_PADDING)
            return Pair(snappedX, snappedY)
        }

        /**
         * Snap a height value up to the nearest grid cell.
         */
        fun snapHeightToGrid(height: Float): Float {
            return (kotlin.math.ceil(height / GRID_CELL_SIZE) * GRID_CELL_SIZE).toFloat()
        }

        // Group constants
        const val GROUP_PADDING_TOP = 48f  // Room for the label chip above the nodes
        const val GROUP_PADDING_SIDE = 16f    // Left and right padding (50% of top)
        const val GROUP_PADDING_BOTTOM = 16f  // Bottom padding (50% of top)
        const val GROUP_HEADER_HEIGHT = 24f   // Smaller than node header

        // Note constants
        const val NOTE_LINE_HEIGHT = 24f
        const val NOTE_BODY_PADDING = 32f

        // Virtual note node prefix for layout integration
        const val VIRTUAL_NOTE_PREFIX = "virtual_note_"

        /**
         * Clear manual position flags from all nodes and notes in a mutable graph.
         * Called when disabling manual placement mode to prepare for auto-layout.
         */
        fun clearManualPositions(graph: MutableWorkflowGraph) {
            // Clear hasManualPosition for all nodes
            for (i in graph.nodes.indices) {
                graph.nodes[i] = graph.nodes[i].copy(hasManualPosition = false)
            }
            // Clear hasManualPosition for all notes
            for (i in graph.notes.indices) {
                graph.notes[i] = graph.notes[i].copy(hasManualPosition = false)
            }
        }
    }

    /**
     * Compute positions for all nodes and notes in the graph.
     * Notes are converted to virtual nodes and laid out using the same algorithm as real nodes.
     * Uses group-aware layout when groups are present.
     *
     * Layout mode is determined by the workflow's isManualPlacementMode flag:
     * - Auto mode (false): Full re-layout of all nodes using the layout algorithm
     * - Manual mode (true): Preserve existing positions, only position new elements
     */
    fun layoutGraph(graph: WorkflowGraph): WorkflowGraph {
        // Inside a subgraph: its Inputs and Outputs strips aren't laid out, they're placed around the result
        val ports = graph.nodes.filter(SubgraphLevels::isPortNode)
        if (ports.isNotEmpty()) {
            val portIds = ports.map { it.id }.toSet()
            val content = layoutGraph(graph.copy(
                nodes = graph.nodes.filter { it.id !in portIds },
                edges = graph.edges.filter { it.sourceNodeId !in portIds && it.targetNodeId !in portIds }
            ))
            return content.copy(nodes = placePorts(content, ports) + content.nodes, edges = graph.edges)
        }
        if (graph.nodes.isEmpty() && graph.notes.isEmpty()) return graph

        // Use the workflow's explicit mode setting
        if (graph.isManualPlacementMode) {
            return layoutManualMode(graph)
        }

        // Standard auto-layout mode
        return layoutAutoMode(graph)
    }

    /**
     * Manual placement mode: preserve existing positions, only layout new nodes and notes.
     * After layout, all nodes and notes are marked as having manual positions.
     */
    private fun layoutManualMode(graph: WorkflowGraph): WorkflowGraph {
        // Separate nodes with and without manual positions
        val nodesWithPosition = graph.nodes.filter { it.hasManualPosition }
        val nodesWithoutPosition = graph.nodes.filter { !it.hasManualPosition }

        // Separate notes with and without manual positions
        val notesWithPosition = graph.notes.filter { it.hasManualPosition }
        val notesWithoutPosition = graph.notes.filter { !it.hasManualPosition }

        if (nodesWithoutPosition.isEmpty() && notesWithoutPosition.isEmpty()) {
            // All elements already have positions - just calculate heights if needed
            val updatedNodes = graph.nodes.map { node ->
                if (node.height <= 0) {
                    node.copy(height = calculateNodeHeight(node), width = NODE_WIDTH)
                } else {
                    node
                }
            }
            return graph.copy(nodes = updatedNodes)
        }

        // Find max extent of all positioned elements (both nodes and notes)
        val maxNodeX = nodesWithPosition.maxOfOrNull { it.x + it.width } ?: NODE_PADDING
        val maxNoteX = notesWithPosition.maxOfOrNull { it.x + it.width } ?: NODE_PADDING
        val maxX = maxOf(maxNodeX, maxNoteX)

        val maxNodeY = nodesWithPosition.maxOfOrNull { it.y } ?: NODE_PADDING
        val maxNoteY = notesWithPosition.maxOfOrNull { it.y } ?: NODE_PADDING
        var currentY = maxOf(maxNodeY, maxNoteY)

        // Place new nodes starting from currentY, snapped to grid
        val newlyPositionedNodes = nodesWithoutPosition.map { node ->
            val height = calculateNodeHeight(node)
            val (snappedX, snappedY) = snapToGrid(maxX + HORIZONTAL_SPACING, currentY)
            currentY = snappedY + height + VERTICAL_SPACING
            node.copy(
                x = snappedX,
                y = snappedY,
                width = NODE_WIDTH,
                height = height,
                hasManualPosition = true
            )
        }

        // Place new notes after nodes, snapped to grid
        val newlyPositionedNotes = notesWithoutPosition.map { note ->
            val height = if (note.height > 0) note.height else calculateNoteHeight(note)
            val (snappedX, snappedY) = snapToGrid(maxX + HORIZONTAL_SPACING, currentY)
            currentY = snappedY + height + VERTICAL_SPACING
            note.copy(
                x = snappedX,
                y = snappedY,
                height = height,
                hasManualPosition = true
            )
        }

        return graph.copy(
            nodes = nodesWithPosition + newlyPositionedNodes,
            notes = notesWithPosition + newlyPositionedNotes
        )
    }

    /**
     * Standard auto-layout mode: run full layout algorithm on all nodes.
     * After layout, nodes and notes are marked as having manual positions.
     */
    private fun layoutAutoMode(graph: WorkflowGraph): WorkflowGraph {
        // Create virtual nodes for notes (notes have no edges → treated as orphans in final layer)
        val virtualNoteNodes = graph.notes.map { note ->
            createVirtualNodeForNote(note)
        }
        val virtualNoteNodeIds = virtualNoteNodes.map { it.id }.toSet()

        // Combine real nodes with virtual note nodes
        val combinedNodes = graph.nodes + virtualNoteNodes
        val graphWithVirtuals = graph.copy(nodes = combinedNodes)

        // Run layout (virtual notes as orphans go to final layer with other unconnected nodes)
        val layoutedWithVirtuals = if (combinedNodes.isEmpty()) {
            graphWithVirtuals
        } else if (graph.groups.isEmpty()) {
            layoutWithoutGroups(graphWithVirtuals)
        } else {
            layoutWithGroups(graphWithVirtuals)
        }

        // Extract positions from virtual nodes back to notes
        val (realNodes, positionedNotes) = separateVirtualNotes(
            layoutedWithVirtuals.nodes,
            virtualNoteNodeIds,
            graph.notes
        )

        // Mark all nodes as having manual positions after auto-layout
        val nodesWithManualFlag = realNodes.map { node ->
            node.copy(hasManualPosition = true)
        }

        // Mark all notes as having manual positions after auto-layout
        val notesWithManualFlag = positionedNotes.map { note ->
            note.copy(hasManualPosition = true)
        }

        return layoutedWithVirtuals.copy(
            nodes = nodesWithManualFlag,
            notes = notesWithManualFlag
        )
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
     * Create a virtual node representing a note for layout purposes.
     * Notes have no edges, so they will be treated as orphans and placed in the final layer.
     */
    private fun createVirtualNodeForNote(note: WorkflowNote): WorkflowNode {
        // Use measured height if available, otherwise fallback to estimate
        val height = if (note.height > 0) note.height else calculateNoteHeight(note)
        return WorkflowNode(
            id = "$VIRTUAL_NOTE_PREFIX${note.id}",
            classType = "ComfyChairNote",
            title = note.title,
            category = NodeCategory.OTHER,
            inputs = emptyMap(),
            outputs = emptyList(),
            templateInputKeys = emptySet(),
            width = note.width,
            height = height
        )
    }

    /**
     * Translate a group member ID to the corresponding node ID.
     * Notes use "note:X" format in groups but "virtual_note_X" as node IDs.
     */
    private fun memberIdToNodeId(memberId: String): String {
        return if (WorkflowNote.isNoteMemberId(memberId)) {
            val noteId = WorkflowNote.memberIdToNoteId(memberId)
            "$VIRTUAL_NOTE_PREFIX$noteId"
        } else {
            memberId
        }
    }

    /**
     * Separate virtual note nodes from real nodes and extract note positions.
     * Returns a pair of (real nodes, positioned notes).
     */
    private fun separateVirtualNotes(
        allNodes: List<WorkflowNode>,
        virtualNoteNodeIds: Set<String>,
        originalNotes: List<WorkflowNote>
    ): Pair<List<WorkflowNode>, List<WorkflowNote>> {
        val noteMap = originalNotes.associateBy { it.id }

        val realNodes = allNodes.filter { it.id !in virtualNoteNodeIds }
        val positionedNotes = allNodes
            .filter { it.id in virtualNoteNodeIds }
            .mapNotNull { virtualNode ->
                val noteId = virtualNode.id.removePrefix(VIRTUAL_NOTE_PREFIX).toIntOrNull()
                    ?: return@mapNotNull null
                noteMap[noteId]?.copy(
                    x = virtualNode.x,
                    y = virtualNode.y,
                    height = virtualNode.height
                )
            }

        return Pair(realNodes, positionedNotes)
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
     * Groups nested in others: each group's parent, the smallest group holding all its [members] (of two with the same
     * members, the earlier holds the later), or -1. Groups that only partly overlap aren't nested.
     */
    private fun groupParents(members: List<Set<String>>): IntArray = IntArray(members.size) { i ->
        if (members[i].isEmpty()) return@IntArray -1
        members.indices
            .filter { j -> j != i && members[j].containsAll(members[i]) && (members[j].size > members[i].size || j < i) }
            .minWithOrNull(compareBy<Int>({ members[it].size }, { -it })) ?: -1
    }

    private fun groupDepths(parents: IntArray): IntArray {
        val depths = IntArray(parents.size) { -1 }
        fun depth(i: Int): Int {
            if (depths[i] < 0) depths[i] = if (parents[i] < 0) 0 else depth(parents[i]) + 1
            return depths[i]
        }
        parents.indices.forEach(::depth)
        return depths
    }

    /**
     * Group-aware layout: treats groups as single units during layout. Nested groups are blocks in their parent's
     * block: each group lays out the members no group inside it holds and its child groups' blocks (each with room for
     * its frame, so frames nest without touching), deepest first. A node in groups that only partly overlap belongs
     * to the deepest (then smallest) of them.
     * Notes are already converted to virtual nodes (virtual_note_X) by layoutGraph(),
     * so we just need to translate member IDs (note:X -> virtual_note_X) when looking up.
     */
    private fun layoutWithGroups(graph: WorkflowGraph): WorkflowGraph {
        val nodeMap = graph.nodes.associateBy { it.id }
        val groups = graph.groups
        val members = groups.map { group -> group.memberNodeIds.map(::memberIdToNodeId).filter { it in nodeMap }.toSet() }
        val parents = groupParents(members)
        val depths = groupDepths(parents)
        fun virtualId(index: Int) = "virtual_group_$index"

        // Each grouped node's own group
        val owner = mutableMapOf<String, Int>()
        val byDepth = groups.indices.sortedWith(compareBy<Int>({ -depths[it] }, { members[it].size }, { -it }))
        for (i in byDepth) for (nodeId in members[i]) owner.putIfAbsent(nodeId, i)
        val groupedNodeIds = owner.keys

        // A node as seen from group [index]'s block: itself, the child group holding it, or null outside the group
        fun itemIn(nodeId: String, index: Int): String? {
            var group = owner[nodeId] ?: return null
            if (group == index) return nodeId
            while (parents[group] >= 0) {
                if (parents[group] == index) return virtualId(group)
                group = parents[group]
            }
            return null
        }
        fun topItem(nodeId: String): String {
            var group = owner[nodeId] ?: return nodeId
            while (parents[group] >= 0) group = parents[group]
            return virtualId(group)
        }

        // Step 1: Compute internal layout for each group, children before their parents
        val groupInternalLayouts = mutableMapOf<Int, GroupInternalLayout>()
        for (i in groups.indices.sortedByDescending { depths[it] }) {
            val ownNodes = members[i].filter { owner[it] == i }.mapNotNull { nodeMap[it] }
            val childBlocks = groups.indices.filter { parents[it] == i }.mapNotNull { child ->
                groupInternalLayouts[child]?.let { createVirtualNodeForGroup(virtualId(child), groups[child], it, nested = true) }
            }
            val items = ownNodes + childBlocks
            // Group needs at least 1 member
            if (items.isEmpty()) continue
            val edges = graph.edges.map { edge ->
                edge.copy(
                    sourceNodeId = itemIn(edge.sourceNodeId, i) ?: edge.sourceNodeId,
                    targetNodeId = itemIn(edge.targetNodeId, i) ?: edge.targetNodeId
                )
            }
            groupInternalLayouts[i] = computeGroupInternalLayout(i, items, edges)
        }

        // Step 2: Create virtual nodes for the outermost groups
        val virtualGroupNodes = groups.indices.filter { parents[it] < 0 }.mapNotNull { i ->
            groupInternalLayouts[i]?.let { createVirtualNodeForGroup(virtualId(i), groups[i], it, nested = false) }
        }

        // Step 3: Build combined node list and collapsed edges
        val ungroupedNodes = graph.nodes.filter { it.id !in groupedNodeIds }
        val combinedNodes = ungroupedNodes + virtualGroupNodes
        val collapsedEdges = graph.edges.mapNotNull { edge ->
            val source = topItem(edge.sourceNodeId)
            val target = topItem(edge.targetNodeId)
            // Skip internal edges (both endpoints in same group)
            if (source == target && source.startsWith("virtual_group_")) null
            else edge.copy(sourceNodeId = source, targetNodeId = target)
        }.distinctBy { "${it.sourceNodeId}->${it.targetNodeId}" }

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

        // Step 5: Expand virtual nodes back to member positions, nested blocks inside their parents'
        val finalNodes = mutableListOf<WorkflowNode>()
        fun expand(index: Int, originX: Float, originY: Float) {
            val layout = groupInternalLayouts[index] ?: return
            for ((itemId, relPos) in layout.memberPositions) {
                if (itemId.startsWith("virtual_group_")) {
                    // A nested block holds its frame's side margins
                    expand(itemId.removePrefix("virtual_group_").toInt(), originX + relPos.x + GROUP_PADDING_SIDE, originY + relPos.y)
                } else {
                    val originalNode = nodeMap[itemId] ?: continue
                    finalNodes.add(originalNode.copy(
                        x = originX + relPos.x,
                        y = originY + relPos.y,
                        width = relPos.width,
                        height = relPos.height
                    ))
                }
            }
        }
        for (node in positioned.nodes) {
            if (node.id.startsWith("virtual_group_")) expand(node.id.removePrefix("virtual_group_").toInt(), node.x, node.y)
            else finalNodes.add(node)
        }

        return graph.copy(nodes = finalNodes)
    }

    /**
     * Compute internal layout for members within a group.
     * Positions are relative to group origin (0,0).
     * Uses grid layout with preference for horizontal arrangement.
     * Optimizes for external connections: inputs on left, outputs on right.
     * Unconnected members (including virtual note nodes) are placed last.
     */
    private fun computeGroupInternalLayout(
        groupId: Int,
        memberNodes: List<WorkflowNode>,
        allEdges: List<WorkflowEdge>
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

        // Sort members: external inputs first, then middle, then external outputs, then notes last
        val sortedMembers = memberNodes.sortedWith { a, b ->
            val aIsNote = a.id.startsWith(VIRTUAL_NOTE_PREFIX)
            val bIsNote = b.id.startsWith(VIRTUAL_NOTE_PREFIX)

            // Notes always go last (rightmost/bottom position in grid)
            if (aIsNote && !bIsNote) return@sortedWith 1
            if (bIsNote && !aIsNote) return@sortedWith -1

            val aHasInput = a.id in nodesWithExternalInputs
            val aHasOutput = a.id in nodesWithExternalOutputs
            val bHasInput = b.id in nodesWithExternalInputs
            val bHasOutput = b.id in nodesWithExternalOutputs

            when {
                // Members with only inputs go first (leftmost columns)
                aHasInput && !aHasOutput && !(bHasInput && !bHasOutput) -> -1
                bHasInput && !bHasOutput && !(aHasInput && !aHasOutput) -> 1
                // Members with only outputs go last (rightmost columns)
                aHasOutput && !aHasInput && !(bHasOutput && !bHasInput) -> 1
                bHasOutput && !bHasInput && !(aHasOutput && !aHasInput) -> -1
                else -> 0
            }
        }

        // Create member info list with dimensions (use defaults if not yet laid out)
        data class MemberInfo(val id: String, val width: Float, val height: Float)
        val allMembers = sortedMembers.map { node ->
            val width = if (node.width > 0) node.width else NODE_WIDTH
            val height = if (node.height > 0) node.height else calculateNodeHeight(node)
            MemberInfo(node.id, width, height)
        }

        // Calculate grid dimensions: prefer vertical (more rows than columns) for portrait screens, horizontal for a
        // wide target
        val totalCount = allMembers.size
        val side = kotlin.math.ceil(kotlin.math.sqrt(totalCount.toDouble())).toInt().coerceAtLeast(1)
        val wide = (targetAspect ?: 0f) > 1f
        val rows = if (wide) kotlin.math.ceil(totalCount.toDouble() / side).toInt().coerceAtLeast(1) else side
        val columns = kotlin.math.ceil(totalCount.toDouble() / rows).toInt().coerceAtLeast(1)

        // Calculate row heights (max height of members in each row)
        val rowHeights = (0 until rows).map { row ->
            val startIdx = row * columns
            val endIdx = minOf(startIdx + columns, totalCount)
            (startIdx until endIdx).maxOfOrNull { allMembers[it].height } ?: NODE_MIN_HEIGHT
        }

        // Position all members in grid pattern. The group's frame (its label above the members, its margin below) is
        // part of the block the layout places, so the usual spacing separates frames as it separates nodes: members
        // start below the label. Sideways they start at the block's edge, in line with ungrouped nodes in the same
        // column; the frame's side margins fit in the horizontal spacing.
        val frameTop = GROUP_PADDING_TOP + GROUP_HEADER_HEIGHT
        val memberPositions = mutableMapOf<String, RelativePosition>()

        // Each column as wide as its widest member (a wide note takes two columns' width)
        val columnWidths = (0 until columns).map { col ->
            allMembers.filterIndexed { index, _ -> index % columns == col }.maxOfOrNull { it.width } ?: NODE_WIDTH
        }
        val columnLefts = columnWidths.runningFold(0f) { acc, width -> acc + width + HORIZONTAL_SPACING }

        allMembers.forEachIndexed { index, member ->
            val col = index % columns
            val row = index / columns

            val x = columnLefts[col]
            val y = frameTop + (0 until row).sumOf { rowHeights[it].toDouble() + VERTICAL_SPACING }.toFloat()

            memberPositions[member.id] = RelativePosition(
                x = x,
                y = y,
                width = member.width,
                height = member.height
            )
        }

        // Calculate total dimensions
        val totalWidth = columnWidths.sum() + (columns - 1) * HORIZONTAL_SPACING
        val totalHeight = frameTop + rowHeights.sum() + (rows - 1) * VERTICAL_SPACING + GROUP_PADDING_BOTTOM

        return GroupInternalLayout(
            groupId = groupId,
            memberPositions = memberPositions,
            width = totalWidth,
            height = totalHeight
        )
    }

    /**
     * Create a virtual node representing a group for layout purposes. A [nested] group's block is wider by its frame's
     * side margins, which the usual spacing holds between blocks at the top level but which must fit inside a parent.
     */
    private fun createVirtualNodeForGroup(
        id: String,
        group: WorkflowGroup,
        layout: GroupInternalLayout,
        nested: Boolean
    ): WorkflowNode {
        return WorkflowNode(
            id = id,
            classType = "ComfyChairGroup",
            title = group.title,
            category = NodeCategory.OTHER,
            inputs = emptyMap(),
            outputs = emptyList(),
            templateInputKeys = emptySet(),
            width = layout.width + if (nested) 2 * GROUP_PADDING_SIDE else 0f,
            height = layout.height
        )
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
     * A subgraph's Inputs and Outputs strips (spec §19.2.4) around a level's laid out [content]: their centre lines
     * [PORT_STRIP_GAP] left of the leftmost and right of the rightmost member, vertically centred on the content
     * (around the origin when it's empty). Each is half a strip wide, so its sockets (outputs at the right edge of the
     * Inputs node, inputs at the left edge of the Outputs node) sit on the strip's centre line.
     */
    private fun placePorts(content: WorkflowGraph, ports: List<WorkflowNode>): List<WorkflowNode> {
        val boxes = content.nodes.map { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) } +
            content.notes.map { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height.coerceAtLeast(NODE_MIN_HEIGHT)) } +
            calculateRenderedGroups(content.groups, content.nodes, content.notes).map { floatArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) }
        val minX = boxes.minOfOrNull { it[0] } ?: 0f
        val maxX = boxes.maxOfOrNull { it[2] } ?: 0f
        val centerY = if (boxes.isEmpty()) 0f else ((boxes.minOf { it[1] }) + (boxes.maxOf { it[3] })) / 2
        return ports.map { port ->
            val sockets = if (SubgraphLevels.isInputsNode(port)) port.outputs.size else port.inputs.size
            val height = portStripHeight(sockets)
            val centerLine = if (SubgraphLevels.isInputsNode(port)) minX - PORT_STRIP_GAP else maxX + PORT_STRIP_GAP
            port.copy(
                x = if (SubgraphLevels.isInputsNode(port)) centerLine - PORT_STRIP_WIDTH / 2 else centerLine,
                y = centerY - height / 2,
                width = PORT_STRIP_WIDTH / 2,
                height = height,
                hasManualPosition = false
            )
        }
    }

    /**
     * Calculate the bounds of the laid out graph (nodes and notes)
     */
    fun calculateBounds(graph: WorkflowGraph): GraphBounds {
        if (graph.nodes.isEmpty() && graph.notes.isEmpty()) return GraphBounds()

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        // Include nodes in bounds
        graph.nodes.forEach { node ->
            minX = minOf(minX, node.x)
            minY = minOf(minY, node.y)
            maxX = maxOf(maxX, node.x + node.width)
            maxY = maxOf(maxY, node.y + node.height)
        }

        // Include notes in bounds
        graph.notes.forEach { note ->
            minX = minOf(minX, note.x)
            minY = minOf(minY, note.y)
            maxX = maxOf(maxX, note.x + note.width)
            maxY = maxOf(maxY, note.y + note.height)
        }

        // Include groups, which reach beyond their members (the label chip above them)
        calculateRenderedGroups(graph.groups, graph.nodes, graph.notes).forEach { group ->
            minX = minOf(minX, group.x)
            minY = minOf(minY, group.y)
            maxX = maxOf(maxX, group.x + group.width)
            maxY = maxOf(maxY, group.y + group.height)
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
     * How many layers to put side by side in a row so the whole layout's width / height comes closest to [aspect].
     */
    private fun columnsForAspect(layers: List<List<WorkflowNode>>, aspect: Float): Int {
        if (layers.isEmpty()) return 1
        fun layerWidth(layer: List<WorkflowNode>) = layer.maxOfOrNull { if (it.width > 0) it.width else NODE_WIDTH } ?: NODE_WIDTH
        fun layerHeight(layer: List<WorkflowNode>): Float =
            layer.sumOf { (if (it.height > 0) it.height else calculateNodeHeight(it)).toDouble() }.toFloat() +
                (layer.size - 1).coerceAtLeast(0) * VERTICAL_SPACING
        return (1..layers.size).minByOrNull { columns ->
            val rows = layers.chunked(columns)
            val width = rows.maxOf { row -> row.sumOf { layerWidth(it).toDouble() }.toFloat() + (row.size - 1) * HORIZONTAL_SPACING }
            val height = rows.sumOf { row -> row.maxOf { layerHeight(it) }.toDouble() }.toFloat() + (rows.size - 1) * ROW_SPACING
            kotlin.math.abs(kotlin.math.ln((width / height) / aspect))
        } ?: 1
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

        // Calculate columns based on total nodes for optimal readability, or to match the target proportions
        val columnsPerRow = targetAspect?.let { columnsForAspect(layers, it) }
            ?: calculateColumnsForNodeCount(graph.nodes.size)

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
     * Height is rounded up to the nearest grid cell for clean grid alignment.
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

        // A subgraph instance has its Open button at the foot (spec §19.1)
        val footer = if (node.isSubgraphInstance) SUBGRAPH_FOOTER_HEIGHT else 0f
        val rawHeight = maxOf(NODE_MIN_HEIGHT, NODE_HEADER_HEIGHT + contentHeight + 2 * NODE_BODY_PADDING + footer)
        // Snap to grid (round up to nearest GRID_CELL_SIZE)
        return (kotlin.math.ceil(rawHeight / GRID_CELL_SIZE) * GRID_CELL_SIZE).toFloat()
    }

    /**
     * Calculate the height of a note based on its content.
     * This is a fallback estimate - actual height is measured by TextMeasurer in drawNote().
     * Height is rounded up to the nearest grid cell for clean grid alignment.
     */
    fun calculateNoteHeight(note: WorkflowNote): Float {
        val lines = note.content.lines().size.coerceAtLeast(1)
        val rawHeight = lines * NOTE_LINE_HEIGHT + NODE_HEADER_HEIGHT + NOTE_BODY_PADDING
        // Snap to grid (round up to nearest GRID_CELL_SIZE)
        return (kotlin.math.ceil(rawHeight / GRID_CELL_SIZE) * GRID_CELL_SIZE).toFloat()
    }

    /**
     * Calculate rendered groups with computed bounds from member node and note positions.
     *
     * @param groups The workflow groups (with membership data only)
     * @param nodes All nodes in the graph (must have positions assigned)
     * @param notes All notes in the graph (must have positions assigned)
     * @return List of RenderedGroup with computed bounds for rendering
     */
    fun calculateRenderedGroups(
        groups: List<WorkflowGroup>,
        nodes: List<WorkflowNode>,
        notes: List<WorkflowNote> = emptyList()
    ): List<RenderedGroup> {
        if (groups.isEmpty()) return emptyList()

        val nodeMap = nodes.associateBy { it.id }
        val noteMap = notes.associateBy { WorkflowNote.noteIdToMemberId(it.id) }
        fun boundsOf(memberId: String): MemberBoundsData? =
            if (WorkflowNote.isNoteMemberId(memberId)) noteMap[memberId]?.let { MemberBoundsData(it.x, it.y, it.width, it.height) }
            else nodeMap[memberId]?.let { MemberBoundsData(it.x, it.y, it.width, it.height) }

        // Members that exist; a group nested in another is framed inside its parent's frame, the parent's label above
        // the child's, so a stack of groups shows as one
        val members = groups.map { group -> group.memberNodeIds.filter { boundsOf(it) != null }.toSet() }
        val parents = groupParents(members)
        val depths = groupDepths(parents)
        val frames = arrayOfNulls<RenderedGroup>(groups.size)
        for (i in groups.indices.sortedByDescending { depths[it] }) {
            val children = groups.indices.filter { parents[it] == i }
            val inChildren = children.flatMapTo(HashSet()) { members[it] }
            // Boxes the frame wraps: its own members and its children's frames
            val boxes = members[i].filter { it !in inChildren }.mapNotNull(::boundsOf) +
                children.mapNotNull { frames[it] }.map { MemberBoundsData(it.x, it.y, it.width, it.height) }
            // Skip groups with no valid members
            if (boxes.isEmpty()) continue
            val minX = boxes.minOf { it.x } - GROUP_PADDING_SIDE
            val minY = boxes.minOf { it.y } - GROUP_PADDING_TOP - GROUP_HEADER_HEIGHT
            val maxX = boxes.maxOf { it.x + it.width } + GROUP_PADDING_SIDE
            val maxY = boxes.maxOf { it.y + it.height } + GROUP_PADDING_BOTTOM
            frames[i] = RenderedGroup(group = groups[i], x = minX, y = minY, width = maxX - minX, height = maxY - minY)
        }
        // Parents first, drawn under their children
        return groups.indices.sortedBy { depths[it] }.mapNotNull { frames[it] }
    }

    /**
     * Simple bounds data for member (node or note).
     */
    private data class MemberBoundsData(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
}
