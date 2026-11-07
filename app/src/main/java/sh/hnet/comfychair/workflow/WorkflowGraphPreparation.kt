package sh.hnet.comfychair.workflow

/**
 * Steps that turn a parsed workflow graph into what the graph canvas draws, shared by the workflow editor and the
 * graph preview on a workflow's detail page, so both look the same. Without node definitions from the server (the
 * registry not populated yet) the graph is drawn without output slots and with uncoloured connections.
 */
object WorkflowGraphPreparation {

    private data class LocalTypesKey(val subgraphs: String, val promoted: Map<String, List<String>>, val version: Int)

    // The last graph's subgraph types: the editor looks types up many times between changes of the graph
    @Volatile
    private var localTypesCache: Pair<LocalTypesKey, Map<String, NodeTypeDefinition>>? = null

    /**
     * [registry] with the types of a graph's subgraphs ([subgraphs], as stored in the graph) added, as their instances
     * among [nodes] show them; [registry] itself for a graph without subgraphs.
     */
    fun registryFor(subgraphs: String?, nodes: List<WorkflowNode>, registry: NodeTypeRegistry): NodeTypeRegistry {
        if (subgraphs == null) return registry
        val promoted = nodes.groupBy { it.classType }
            .mapValues { (_, instances) -> instances.flatMap { node -> node.inputs.keys.filter(Subgraphs::isPromotedKey) } }
        val key = LocalTypesKey(subgraphs, promoted, registry.version)
        val types = localTypesCache?.takeIf { it.first == key }?.second
            ?: runCatching { Subgraphs.nodeTypes(org.json.JSONObject(subgraphs), promoted, registry::getNodeDefinition) }
                .getOrDefault(emptyMap())
                .also { localTypesCache = key to it }
        return registry.withLocalTypes(types)
    }

    /** [registryFor] a whole graph. */
    fun registryFor(graph: WorkflowGraph, registry: NodeTypeRegistry): NodeTypeRegistry =
        registryFor(graph.subgraphs, graph.nodes, registry)

    /** Add each node's output types from its definition. */
    fun populateNodeOutputs(graph: WorkflowGraph, registry: NodeTypeRegistry): WorkflowGraph {
        if (!registry.isPopulated()) return graph
        return graph.copy(nodes = graph.nodes.map { node ->
            node.copy(outputs = registry.getNodeDefinition(node.classType)?.outputs ?: emptyList())
        })
    }

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
        graph = populateNodeOutputs(graph, types)
        graph = WorkflowLayoutEngine(targetAspect).layoutGraph(graph)
        return resolveEdgeTypes(graph, types)
    }
}
