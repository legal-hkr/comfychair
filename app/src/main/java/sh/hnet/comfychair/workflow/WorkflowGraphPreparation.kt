package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.util.ConnectionValidator

/**
 * Steps that turn a parsed workflow graph into what the graph canvas draws, shared by the workflow editor and the
 * graph preview on a workflow's detail page, so both look the same. Without node definitions from the server (the
 * registry not populated yet) the graph is drawn without output slots and with uncoloured connections.
 */
object WorkflowGraphPreparation {

    private data class LocalTypesKey(
        val subgraphs: String,
        val promoted: Map<String, List<String>>,
        val ports: Set<String>,
        val version: Int
    )

    // The last graph's subgraph types: the editor looks types up many times between changes of the graph
    @Volatile
    private var localTypesCache: Pair<LocalTypesKey, Map<String, NodeTypeDefinition>>? = null

    /**
     * [registry] with the types of a graph's subgraphs ([subgraphs], as stored in the graph) added, as their instances
     * among [nodes] show them, and inside a subgraph its port nodes' types; [registry] itself for a graph without
     * subgraphs.
     */
    fun registryFor(subgraphs: String?, nodes: List<WorkflowNode>, registry: NodeTypeRegistry): NodeTypeRegistry {
        if (subgraphs == null) return registry
        val promoted = nodes.groupBy { it.classType }
            .mapValues { (_, instances) -> instances.flatMap { node -> node.inputs.keys.filter(Subgraphs::isPromotedKey) } }
        val ports = nodes.mapNotNullTo(HashSet()) { node -> node.classType.takeIf { SubgraphLevels.isPortType(it) } }
        val key = LocalTypesKey(subgraphs, promoted, ports, registry.version)
        val types = localTypesCache?.takeIf { it.first == key }?.second
            ?: runCatching {
                val definitions = org.json.JSONObject(subgraphs)
                Subgraphs.nodeTypes(definitions, promoted, registry::getNodeDefinition) + SubgraphLevels.portTypes(definitions, ports)
            }
                .getOrDefault(emptyMap())
                .also { localTypesCache = key to it }
        return registry.withLocalTypes(types)
    }

    /** [registryFor] a whole graph. */
    fun registryFor(graph: WorkflowGraph, registry: NodeTypeRegistry): NodeTypeRegistry =
        registryFor(graph.subgraphs, graph.nodes, registry)

    /** Add each node's output types from its definition. */
    fun populateNodeOutputs(graph: WorkflowGraph, registry: NodeTypeRegistry): WorkflowGraph {
        // The workflow's own types (subgraphs, a level's port nodes) are known without the server's
        val populated = registry.isPopulated()
        return graph.copy(nodes = graph.nodes.map { node ->
            val definition = registry.getNodeDefinition(node.classType)
            node.copy(outputs = definition?.outputs ?: if (populated) emptyList() else node.outputs)
        })
    }

    /**
     * The sockets of each node's definition the node doesn't list yet, as free sockets: a workflow saved in ComfyUI's
     * API format lists only the inputs it fills (a switch without its two inputs couldn't be linked). Only sockets –
     * values a file leaves out stay out, as adding them would change what runs – and not the growing groups of slots,
     * which have their own slots by name. Free sockets aren't saved, so nothing changes in the workflow. For drawing
     * and editing only, not for the field analysis.
     */
    fun addMissingSockets(graph: WorkflowGraph, registry: NodeTypeRegistry): WorkflowGraph =
        graph.copy(nodes = graph.nodes.map { node ->
            val definition = registry.getNodeDefinition(node.classType) ?: return@map node
            val missing = definition.inputs.filter { input ->
                input.name !in node.inputs && !input.socketless && input.type !in GROWING_TYPES &&
                    (ConnectionValidator.isConnectionType(input.type) || input.forceInput)
            }
            if (missing.isEmpty()) node
            else node.copy(inputs = sortInputsForLayout(node.inputs + missing.associate { it.name to InputValue.UnconnectedSlot(it.type) }))
        })

    // Groups of slots that grow as they're linked ("name.slot"), drawn by their slots
    private val GROWING_TYPES = setOf("COMFY_AUTOGROW_V3", "COMFY_DYNAMICGROUP_V3")

    /** Give each connection the type of the output it leaves, for its colour. */
    fun resolveEdgeTypes(graph: WorkflowGraph, registry: NodeTypeRegistry): WorkflowGraph {
        if (!registry.isPopulated()) return graph
        return graph.copy(edges = graph.edges.map { edge ->
            val sourceNode = graph.nodes.find { it.id == edge.sourceNodeId }
            edge.copy(slotType = sourceNode?.let { registry.getOutputType(it.classType, edge.sourceOutputIndex) })
        })
    }

    /** The definitions of the node types the graph uses. */
    fun nodeDefinitions(graph: WorkflowGraph, registry: NodeTypeRegistry): Map<String, NodeTypeDefinition> {
        val definitions = mutableMapOf<String, NodeTypeDefinition>()
        graph.nodes.forEach { node ->
            if (node.classType !in definitions) {
                registry.getNodeDefinition(node.classType)?.let { definitions[node.classType] = it }
            }
        }
        return definitions
    }

    /**
     * Where the members of one level of a workflow stand in the editor's layout (the top level, or a subgraph's
     * definition: a JSON object with `nodes`, `groups` and `notes`): (x, y, width, height) by node ID and by "note:N".
     */
    fun levelPositions(level: org.json.JSONObject, registry: NodeTypeRegistry): Map<String, FloatArray> {
        val graph = prepare(level.toString(), registry)
        val engine = WorkflowLayoutEngine(null)
        return graph.nodes.associate { it.id to floatArrayOf(it.x, it.y, it.width, it.height) } +
            graph.notes.associate { note ->
                WorkflowNote.noteIdToMemberId(note.id) to
                    floatArrayOf(note.x, note.y, note.width, note.height.takeIf { it > 0f } ?: engine.calculateNoteHeight(note))
            }
    }

    /**
     * A workflow's JSON parsed, given outputs, laid out and with typed connections: ready to draw.
     *
     * @param targetAspect Width / height to lay the graph out for (a preview card); null for the editor's layout
     */
    fun prepare(jsonContent: String, registry: NodeTypeRegistry, targetAspect: Float? = null): WorkflowGraph {
        var graph = WorkflowParser(logDetails = false).parse(jsonContent, "", "")
        val types = registryFor(graph, registry)
        graph = addMissingSockets(populateNodeOutputs(graph, types), types)
        graph = WorkflowLayoutEngine(targetAspect).layoutGraph(graph)
        return resolveEdgeTypes(graph, types)
    }
}
