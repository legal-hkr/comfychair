package sh.hnet.comfychair.workflow

/**
 * Steps that turn a parsed workflow graph into what the graph canvas draws, shared by the workflow editor and the
 * graph preview on a workflow's detail page, so both look the same. Without node definitions from the server (the
 * registry not populated yet) the graph is drawn without output slots and with uncoloured connections.
 */
object WorkflowGraphPreparation {

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

    /** A workflow's JSON parsed, given outputs, laid out and with typed connections: ready to draw. */
    fun prepare(jsonContent: String, registry: NodeTypeRegistry, name: String = "", description: String = ""): WorkflowGraph {
        var graph = WorkflowParser(logDetails = false).parse(jsonContent, name, description)
        graph = populateNodeOutputs(graph, registry)
        graph = WorkflowLayoutEngine().layoutGraph(graph)
        return resolveEdgeTypes(graph, registry)
    }
}
