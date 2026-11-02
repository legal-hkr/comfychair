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
                    selectedCandidateIndex = autoSelectIndex(fieldKey, candidates)
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
                    selectedCandidateIndex = autoSelectIndex(fieldKey, candidates)
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
                if (node.outputs.any { it.type == "IMAGE" }) {
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
                if (node.outputs.any { it.type == "CONDITIONING" }) {
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

        // Primitive String nodes (node ID -> text input key), e.g. one "String (Multiline)" node
        // feeding the same prompt to several text encoders
        val nodesById = graph.nodes.associateBy { it.id }
        val primitiveTextNodes = graph.nodes.mapNotNull { node ->
            val inputMap = node.inputs.mapValues { (_, value) -> (value as? InputValue.Literal)?.value ?: value }
            TemplateKeyRegistry.primitiveTextKey(node.classType, inputMap) { it is InputValue.Connection }
                ?.let { node.id to it }
        }.toMap()

        fun candidateFor(node: WorkflowNode, inputKey: String) = FieldCandidate(
            nodeId = node.id,
            nodeName = node.title,
            classType = node.classType,
            inputKey = inputKey,
            currentValue = (node.inputs[inputKey] as? InputValue.Literal)?.value
        )

        fun MutableList<FieldCandidate>.addUnique(candidate: FieldCandidate, first: Boolean = false) {
            if (any { it.nodeId == candidate.nodeId && it.inputKey == candidate.inputKey }) return
            if (first) add(0, candidate) else add(candidate)
        }

        // For each text encoder, trace its output to find if it connects to positive or negative
        for ((node, inputKey) in allTextEncoderNodes) {
            // Text linked from a primitive String node: offer that node, so the prompt reaches
            // every encoder it feeds instead of replacing this encoder's link
            val sourceNode = (node.inputs[inputKey] as? InputValue.Connection)?.let { nodesById[it.sourceNodeId] }
            val sourceKey = sourceNode?.let { primitiveTextNodes[it.id] }
            val candidate = if (sourceNode != null && sourceKey != null) {
                candidateFor(sourceNode, sourceKey)
            } else {
                candidateFor(node, inputKey)
            }

            // Determine if this node connects to positive or negative input
            val connectionType = traceConditioningConnection(graph, node.id)

            when (connectionType) {
                "positive" -> positiveTextCandidates.addUnique(candidate, first = true) // Traced match first
                "negative" -> negativeTextCandidates.addUnique(candidate, first = true) // Traced match first
                else -> {
                    // Unknown connection - use title-based classification
                    val titleLower = candidate.nodeName.lowercase()
                    when {
                        titleLower.contains("positive") -> positiveTextCandidates.addUnique(candidate)
                        titleLower.contains("negative") -> negativeTextCandidates.addUnique(candidate)
                        else -> {
                            // Add to both as fallback candidates
                            positiveTextCandidates.addUnique(candidate)
                            negativeTextCandidates.addUnique(candidate)
                        }
                    }
                }
            }
        }

        // Primitive String nodes not feeding a text encoder (e.g. prompts of API nodes without
        // CLIP encoding) as fallback candidates
        for ((nodeId, inputKey) in primitiveTextNodes) {
            val node = nodesById[nodeId] ?: continue
            val alreadyOffered = (positiveTextCandidates + negativeTextCandidates).any { it.nodeId == nodeId }
            if (alreadyOffered) continue
            val candidate = candidateFor(node, inputKey)
            val titleLower = node.title.lowercase()
            if (!titleLower.contains("negative")) positiveTextCandidates.addUnique(candidate)
            if (!titleLower.contains("positive")) negativeTextCandidates.addUnique(candidate)
        }

        return mapOf(
            "positive_text" to positiveTextCandidates,
            "negative_text" to negativeTextCandidates
        )
    }

    /**
     * Choose the auto-selected candidate index for a field.
     *
     * Prefers the candidate whose current value already matches the expected placeholder
     * (set by a previous save), so re-editing a workflow keeps the correct node selected
     * rather than defaulting to whichever node happens to be first in iteration order.
     * Falls back to index 0 if no placeholder match is found.
     */
    private fun autoSelectIndex(fieldKey: String, candidates: List<FieldCandidate>): Int {
        if (candidates.isEmpty()) return -1
        val placeholderPattern = "{{${TemplateKeyRegistry.getPlaceholderForKey(fieldKey)}}}"
        val preferred = candidates.indexOfFirst { it.currentValue?.toString() == placeholderPattern }
        return if (preferred >= 0) preferred else 0
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
