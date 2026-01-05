package sh.hnet.comfychair.util

import android.content.Context
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.TemplateKeyRegistry
import sh.hnet.comfychair.workflow.WorkflowGraph
import sh.hnet.comfychair.workflow.WorkflowMappingState
import sh.hnet.comfychair.workflow.WorkflowNode

/**
 * Analyzes workflow graphs to detect field mapping candidates.
 *
 * Provides pure functions for creating field mappings, finding candidates,
 * and tracing connections in workflow graphs.
 */
object FieldMappingAnalyzer {

    /**
     * Create field mapping state by analyzing graph nodes.
     *
     * @param context Context for string resources
     * @param graph The workflow graph to analyze
     * @param type The workflow type
     * @param nodeTypeRegistry Registry for looking up node definitions
     * @return Complete mapping state for the workflow
     */
    fun createFieldMappingState(
        context: Context,
        graph: WorkflowGraph,
        type: WorkflowType,
        nodeTypeRegistry: NodeTypeRegistry
    ): WorkflowMappingState {
        // Get strictly required keys for this workflow type
        val strictlyRequiredKeys = TemplateKeyRegistry.getRequiredKeysForType(type)

        // Get optional keys, adjusted for workflow structure (DualCLIPLoader, BasicGuider)
        val optionalKeys = getOptionalKeysFromGraph(type, graph)

        // Pre-compute prompt field mappings using graph tracing
        val promptMappings = createPromptFieldMappings(graph, nodeTypeRegistry)

        val fieldMappings = mutableListOf<FieldMappingState>()

        // Process strictly required keys (isRequired = true)
        for (fieldKey in strictlyRequiredKeys) {
            val candidates = findCandidatesForField(fieldKey, graph, promptMappings, nodeTypeRegistry)
            val requiredField = FieldDisplayRegistry.createRequiredField(context, fieldKey, isRequired = true)

            fieldMappings.add(
                FieldMappingState(
                    field = requiredField,
                    candidates = candidates,
                    // Auto-select first candidate if any exist
                    // User can change selection in mapping UI if needed
                    selectedCandidateIndex = if (candidates.isNotEmpty()) 0 else -1
                )
            )
        }

        // Process optional keys (isRequired = false)
        for (fieldKey in optionalKeys) {
            val candidates = findCandidatesForField(fieldKey, graph, promptMappings, nodeTypeRegistry)
            val requiredField = FieldDisplayRegistry.createRequiredField(context, fieldKey, isRequired = false)

            fieldMappings.add(
                FieldMappingState(
                    field = requiredField,
                    candidates = candidates,
                    // Auto-select first candidate if any exist
                    // User can change selection in mapping UI if needed
                    selectedCandidateIndex = if (candidates.isNotEmpty()) 0 else -1
                )
            )
        }

        return WorkflowMappingState(
            workflowType = type,
            fieldMappings = fieldMappings
        )
    }

    /**
     * Get optional keys for a workflow type, adjusted for graph structure.
     * Handles BasicGuider (no CFG/negative).
     *
     * @param type The workflow type
     * @param graph The workflow graph
     * @return Set of optional field keys
     */
    fun getOptionalKeysFromGraph(type: WorkflowType, graph: WorkflowGraph): Set<String> {
        val baseKeys = TemplateKeyRegistry.getOptionalKeysForType(type).toMutableSet()

        if (type == WorkflowType.TTI) {
            // Check for BasicGuider (no CFG, no negative prompt)
            val hasBasicGuider = graph.nodes.any { it.classType == "BasicGuider" }
            if (hasBasicGuider) {
                baseKeys.remove("cfg")
                baseKeys.remove("negative_text")
            }
        }

        return baseKeys
    }

    /**
     * Find candidates for a field in the graph.
     * Uses two detection strategies:
     * 1. Input-key-based: Look for nodes with matching input key names (fast path)
     * 2. Output-type-based: For "image" field, look for nodes with IMAGE output + ENUM inputs (fallback)
     *
     * @param fieldKey The field key to find candidates for
     * @param graph The workflow graph
     * @param promptMappings Pre-computed prompt field mappings
     * @param nodeTypeRegistry Registry for looking up node definitions
     * @return List of candidates matching the field
     */
    fun findCandidatesForField(
        fieldKey: String,
        graph: WorkflowGraph,
        promptMappings: Map<String, List<FieldCandidate>>,
        nodeTypeRegistry: NodeTypeRegistry
    ): List<FieldCandidate> {
        // Handle positive_text and negative_text with graph tracing
        if (fieldKey == "positive_text" || fieldKey == "negative_text") {
            return promptMappings[fieldKey] ?: emptyList()
        }

        // Strategy 1: Find nodes that have matching input key names
        val directCandidates = mutableListOf<FieldCandidate>()

        for (node in graph.nodes) {
            node.inputs.forEach { (inputName, inputValue) ->
                if (TemplateKeyRegistry.doesInputKeyMatchField(fieldKey, inputName)) {
                    val currentValue = when (inputValue) {
                        is InputValue.Literal -> inputValue.value
                        else -> null
                    }

                    if (TemplateKeyRegistry.doesValueMatchPlaceholder(fieldKey, currentValue)) {
                        directCandidates.add(
                            FieldCandidate(
                                nodeId = node.id,
                                nodeName = node.title,
                                classType = node.classType,
                                inputKey = inputName,
                                currentValue = currentValue
                            )
                        )
                    }
                }
            }
        }

        // Strategy 2 (fallback): For "image" field, find nodes with IMAGE output + ENUM inputs
        // This works regardless of input key language (Chinese, etc.)
        if (directCandidates.isEmpty() && fieldKey == "image") {
            for (node in graph.nodes) {
                if ("IMAGE" in node.outputs) {
                    val definition = nodeTypeRegistry.getNodeDefinition(node.classType)
                    definition?.inputs
                        ?.filter { it.type == "ENUM" && node.inputs.containsKey(it.name) }
                        ?.forEach { inputDef ->
                            val inputValue = node.inputs[inputDef.name]
                            val currentValue = when (inputValue) {
                                is InputValue.Literal -> inputValue.value
                                else -> null
                            }
                            directCandidates.add(
                                FieldCandidate(
                                    nodeId = node.id,
                                    nodeName = node.title,
                                    classType = node.classType,
                                    inputKey = inputDef.name,
                                    currentValue = currentValue
                                )
                            )
                        }
                }
            }
        }

        return directCandidates
    }

    /**
     * Create field mappings for positive_text and negative_text using graph tracing.
     * Uses two detection strategies:
     * 1. Input-key-based: Look for nodes with "text" or "prompt" inputs (fast path)
     * 2. Output-type-based: Look for nodes with CONDITIONING output + STRING inputs (fallback for custom nodes)
     *
     * @param graph The workflow graph
     * @param nodeTypeRegistry Registry for looking up node definitions
     * @return Map of field keys to their candidates
     */
    fun createPromptFieldMappings(
        graph: WorkflowGraph,
        nodeTypeRegistry: NodeTypeRegistry
    ): Map<String, List<FieldCandidate>> {
        val positiveTextCandidates = mutableListOf<FieldCandidate>()
        val negativeTextCandidates = mutableListOf<FieldCandidate>()

        // Text input keys to look for (common English names)
        val textInputKeys = listOf("text", "prompt")

        // Strategy 1: Find nodes with known text/prompt input keys
        data class TextEncoderNode(val node: WorkflowNode, val inputKey: String)
        val textEncoderNodes = graph.nodes.mapNotNull { node ->
            val matchingInputKey = textInputKeys.firstOrNull { node.inputs.containsKey(it) }
            if (matchingInputKey != null) TextEncoderNode(node, matchingInputKey) else null
        }

        // Strategy 2 (fallback): Find nodes with CONDITIONING output + STRING inputs
        // This works regardless of input key language (Chinese, etc.)
        val outputBasedNodes = if (textEncoderNodes.isEmpty()) {
            graph.nodes.flatMap { node ->
                if ("CONDITIONING" in node.outputs) {
                    val definition = nodeTypeRegistry.getNodeDefinition(node.classType)
                    definition?.inputs
                        ?.filter { it.type == "STRING" && node.inputs.containsKey(it.name) }
                        ?.map { inputDef -> TextEncoderNode(node, inputDef.name) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
        } else {
            emptyList()
        }

        // Combine both strategies
        val allTextEncoderNodes = textEncoderNodes + outputBasedNodes

        // For each text encoder, trace its output to find if it connects to positive or negative
        for ((node, inputKey) in allTextEncoderNodes) {
            val textInput = node.inputs[inputKey]
            val currentValue = when (textInput) {
                is InputValue.Literal -> textInput.value
                else -> null
            }

            val candidate = FieldCandidate(
                nodeId = node.id,
                nodeName = node.title,
                classType = node.classType,
                inputKey = inputKey,
                currentValue = currentValue
            )

            // Determine if this node connects to positive or negative input
            val connectionType = traceConditioningConnection(graph, node.id)

            when (connectionType) {
                "positive" -> positiveTextCandidates.add(0, candidate) // Add traced match first
                "negative" -> negativeTextCandidates.add(0, candidate) // Add traced match first
                else -> {
                    // Unknown connection - use title-based classification
                    val titleLower = node.title.lowercase()
                    when {
                        titleLower.contains("positive") -> positiveTextCandidates.add(candidate)
                        titleLower.contains("negative") -> negativeTextCandidates.add(candidate)
                        else -> {
                            // Add to both as fallback candidates
                            positiveTextCandidates.add(candidate)
                            negativeTextCandidates.add(candidate)
                        }
                    }
                }
            }
        }

        return mapOf(
            "positive_text" to positiveTextCandidates,
            "negative_text" to negativeTextCandidates
        )
    }

    /**
     * Trace a conditioning node's output to find what type of connection it has.
     * Uses WorkflowGraph.edges to trace connections.
     *
     * This is classType-agnostic: it simply checks if the target input name
     * indicates positive or negative conditioning, regardless of the target node type.
     *
     * @param graph The workflow graph
     * @param conditioningNodeId ID of the conditioning node to trace from
     * @return "positive", "negative", or null if no connection found
     */
    fun traceConditioningConnection(graph: WorkflowGraph, conditioningNodeId: String): String? {
        // Find edges originating from this conditioning node
        val outgoingEdges = graph.edges.filter { it.sourceNodeId == conditioningNodeId }

        for (edge in outgoingEdges) {
            // Check target input name - works for any node type (KSampler, custom samplers, etc.)
            when (edge.targetInputName.lowercase()) {
                "positive" -> return "positive"
                "negative" -> return "negative"
                "conditioning" -> return "positive"  // Single conditioning input (like BasicGuider)
            }
        }

        return null
    }
}
