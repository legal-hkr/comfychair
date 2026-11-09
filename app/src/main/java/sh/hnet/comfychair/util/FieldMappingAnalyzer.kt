package sh.hnet.comfychair.util

import android.content.Context
import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.workflow.FieldCandidate
import sh.hnet.comfychair.workflow.FieldDisplayRegistry
import sh.hnet.comfychair.workflow.FieldMappingState
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.SubgraphLevels
import sh.hnet.comfychair.workflow.Subgraphs
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

    /** A field's candidates and the one chosen at first (-1 none), before display names (no Context needed). */
    data class FieldAnalysis(
        val fieldKey: String,
        val isRequired: Boolean,
        val candidates: List<FieldCandidate>,
        val selectedIndex: Int
    )

    /**
     * Every field of [type], required ones first, with its candidates on [graph] (the nodes that run: a workflow with
     * subgraphs flattened) and the one chosen at first. The single analysis for importing and for saving after
     * editing.
     */
    fun analyze(graph: WorkflowGraph, type: WorkflowType, nodeTypeRegistry: NodeTypeRegistry): List<FieldAnalysis> {
        val requiredKeys = TemplateKeyRegistry.getRequiredKeysForType(type)
        // Optional keys, adjusted for workflow structure (BasicGuider)
        val optionalKeys = getOptionalKeysFromGraph(type, graph)
        // Prompts traced through the graph to the positive or negative input they feed
        val promptMappings = createPromptFieldMappings(graph, nodeTypeRegistry)
        return requiredKeys.map { it to true }.plus(optionalKeys.map { it to false }).map { (fieldKey, required) ->
            val candidates = findCandidatesForField(fieldKey, graph, promptMappings, nodeTypeRegistry)
            FieldAnalysis(fieldKey, required, candidates, autoSelectIndex(fieldKey, candidates))
        }
    }

    /**
     * Create field mapping state by analyzing graph nodes ([analyze]), with the fields' display names.
     */
    fun createFieldMappingState(
        context: Context,
        graph: WorkflowGraph,
        type: WorkflowType,
        nodeTypeRegistry: NodeTypeRegistry
    ): WorkflowMappingState = WorkflowMappingState(
        workflowType = type,
        fieldMappings = analyze(graph, type, nodeTypeRegistry).map { analysis ->
            FieldMappingState(
                field = FieldDisplayRegistry.createRequiredField(context, analysis.fieldKey, isRequired = analysis.isRequired),
                candidates = analysis.candidates,
                selectedCandidateIndex = analysis.selectedIndex
            )
        }
    )

    /**
     * A mapping found on the flat view of a workflow with subgraphs (where the nodes that run are), with its candidates
     * where the editor shows them: a value inside a subgraph on its instance, as the subgraph's input feeding it or as
     * a promoted value, which [storedRoot] (the workflow as stored) gets promoted when the instance doesn't show it yet.
     * The selection stays on the same value.
     */
    fun toExposedView(state: WorkflowMappingState, storedRoot: JSONObject): WorkflowMappingState {
        if (Subgraphs.definitionsOf(storedRoot) == null) return state
        val nodes = storedRoot.optJSONObject("nodes") ?: storedRoot
        return state.copy(fieldMappings = state.fieldMappings.map { mapping ->
            val selected = mapping.selectedCandidate
            val moved = mapping.candidates.map { candidate ->
                val (nodeId, key) = Subgraphs.exposedAddress(storedRoot, candidate.nodeId, candidate.inputKey)
                if (nodeId == candidate.nodeId) return@map candidate to candidate
                Subgraphs.promote(storedRoot, nodeId, key)
                candidate to candidate.copy(nodeId = nodeId, nodeName = pathTitle(storedRoot, candidate), inputKey = key)
            }
            // A subgraph's input feeding two values inside is one candidate
            val candidates = moved.map { it.second }.distinctBy { it.nodeId to it.inputKey }
            val selectedNow = moved.find { it.first == selected }?.second
            mapping.copy(
                candidates = candidates,
                selectedCandidateIndex = selectedNow?.let { now ->
                    candidates.indexOfFirst { it.nodeId == now.nodeId && it.inputKey == now.inputKey }
                } ?: -1
            )
        })
    }

    /**
     * A value inside a subgraph named by its node and, in parentheses, the subgraph it's in (the innermost instance's
     * title): "ImageScale (Upscale)". The whole path is in the editor's pill once inside.
     */
    private fun pathTitle(storedRoot: JSONObject, candidate: FieldCandidate): String {
        val definitions = Subgraphs.definitionsOf(storedRoot) ?: return candidate.nodeName
        val nodes = storedRoot.optJSONObject("nodes") ?: storedRoot
        val parts = candidate.nodeId.split(":")
        fun titleOf(node: JSONObject?): String? {
            node ?: return null
            return node.optJSONObject("_meta")?.optString("title")?.takeIf { it.isNotEmpty() }
                ?: SubgraphLevels.nameOf(definitions, node.optString("class_type"))
        }
        val titles = mutableListOf<String>()
        var node = nodes.optJSONObject(parts.first())
        titleOf(node)?.let(titles::add)
        for (part in parts.drop(1).dropLast(1)) {
            node = definitions.optJSONObject(node?.optString("class_type").orEmpty())?.optJSONObject("nodes")?.optJSONObject(part)
            titleOf(node)?.let(titles::add)
        }
        titles.add(candidate.nodeName)
        return if (titles.size > 1) "${titles.last()} (${titles[titles.size - 2]})" else titles.last()
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
     * 2. For "image" only, when none: inputs with an image upload button on nodes with an IMAGE output
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
        val nodesById = graph.nodes.associateBy { it.id }

        for (node in graph.nodes) {
            node.inputs.forEach { (inputName, inputValue) ->
                // A free socket holds no value: no field's
                if (inputValue !is InputValue.UnconnectedSlot && TemplateKeyRegistry.doesInputKeyMatchField(fieldKey, inputName)) {
                    // A value linked from another node isn't the field's: mapping it would replace the link (e.g. a
                    // width from a resolution node). A primitive node holding it is offered instead.
                    val (target, targetKey) = when (inputValue) {
                        is InputValue.Connection -> primitiveSource(nodesById[inputValue.sourceNodeId])
                            ?: listSource(nodesById[inputValue.sourceNodeId], inputValue.outputIndex, nodeTypeRegistry)
                            ?: return@forEach
                        else -> node to inputName
                    }
                    val currentValue = (target.inputs[targetKey] as? InputValue.Literal)?.value

                    if (TemplateKeyRegistry.doesValueMatchPlaceholder(fieldKey, currentValue) &&
                        directCandidates.none { it.nodeId == target.id && it.inputKey == targetKey }
                    ) {
                        directCandidates.add(
                            FieldCandidate(
                                nodeId = target.id,
                                nodeName = target.title,
                                classType = target.classType,
                                inputKey = targetKey,
                                currentValue = currentValue
                            )
                        )
                    }
                }
            }
        }

        // Strategy 2 (fallback): For "image" field, the inputs with ComfyUI's image upload button on nodes with an
        // IMAGE output, whatever their name (a custom loader's, in Chinese…); not any choice list (an API node's
        // aspect ratio)
        if (directCandidates.isEmpty() && fieldKey == "image") {
            for (node in graph.nodes) {
                if (node.outputs.any { it.type == "IMAGE" }) {
                    val definition = nodeTypeRegistry.getNodeDefinition(node.classType)
                    definition?.inputs
                        ?.filter { it.uploadKind == "image" && node.inputs.containsKey(it.name) }
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

        // Strategy 1: text encoders, nodes with a text or prompt input that make conditioning (by their outputs, or
        // by name where the outputs aren't known); their prompt is traced to the input it feeds
        data class TextEncoderNode(val node: WorkflowNode, val inputKey: String)
        val textEncoderNodes = graph.nodes.mapNotNull { node ->
            val matchingInputKey = textInputKeys.firstOrNull { node.inputs.containsKey(it) } ?: return@mapNotNull null
            if (isTextEncoder(node)) TextEncoderNode(node, matchingInputKey) else null
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

        // The text an input gets: its own value, or when it's linked, the nearest sources up its text links (a
        // primitive text node, a node's own text or prompt), nearest first: one "String (Multiline)" feeding several
        // encoders, a user prompt passing a prompt enhancer's switch (whose system prompt lies further up). Mapping a
        // linked input would replace the link
        fun textSources(node: WorkflowNode, inputKey: String): List<FieldCandidate> {
            val value = node.inputs[inputKey]
            if (value !is InputValue.Connection) return listOf(candidateFor(node, inputKey))
            val sources = mutableListOf<FieldCandidate>()
            val visited = mutableSetOf(node.id)
            var frontier = listOf(value)
            repeat(MAX_TRACE_DEPTH) {
                val next = mutableListOf<InputValue.Connection>()
                for (link in frontier) {
                    val source = nodesById[link.sourceNodeId] ?: continue
                    if (!visited.add(source.id)) continue
                    val outputType = source.outputs.getOrNull(link.outputIndex)?.type
                    // A node passing on any type (a switch: "*", COMFY_MATCHTYPE_V3) passes text too
                    val anyType = outputType == "*" || outputType?.startsWith("COMFY_MATCHTYPE") == true
                    if (outputType != null && outputType != "STRING" && !anyType) continue
                    primitiveTextNodes[source.id]?.let { sources.add(candidateFor(source, it)); continue }
                    // Its own text: a text or prompt input, or a switch's text choice (on_false holding the prompt
                    // when an enhancer is off); never a choice list or an instruction for an enhancer
                    val definition = nodeTypeRegistry.getNodeDefinition(source.classType)
                    val own = source.inputs.keys.filter { key ->
                        val literal = source.inputs[key] as? InputValue.Literal ?: return@filter false
                        if (literal.value !is String || isInstruction(key)) return@filter false
                        // An input its known definition lacks (a dynamic combo's "resize_type.crop") isn't text
                        val type = definition?.inputs?.find { it.name == key }?.type
                        if (definition != null && type == null) return@filter false
                        val textType = type == null || type == "STRING" || type == "*" || type.startsWith("COMFY_MATCHTYPE")
                        textType && (key in textInputKeys || anyType)
                    }
                    if (own.isNotEmpty()) {
                        own.forEach { sources.add(candidateFor(source, it)) }
                        continue
                    }
                    next += source.inputs.filter { (key, _) -> !isInstruction(key) }.values.filterIsInstance<InputValue.Connection>()
                }
                if (next.isEmpty()) return sources
                frontier = next
            }
            return sources
        }

        // An input whose sources can't be found (linked from a muted node, or from a generator without text of its
        // own) is offered itself, as a last resort: a prompt field is required
        fun textSourcesOrInput(node: WorkflowNode, inputKey: String) = textSources(node, inputKey).ifEmpty { listOf(candidateFor(node, inputKey)) }

        // Prompts traced to a positive or negative input go first, the last encoder's first (a refiner's prompts
        // come before the base's in the workflow), each encoder's sources nearest first; the rest after them
        val tracedPositive = mutableListOf<FieldCandidate>()
        val tracedNegative = mutableListOf<FieldCandidate>()
        fun MutableList<FieldCandidate>.addFirst(sources: List<FieldCandidate>) {
            val fresh = sources.filter { source -> none { it.nodeId == source.nodeId && it.inputKey == source.inputKey } }
            addAll(0, fresh)
        }
        for ((node, inputKey) in allTextEncoderNodes) {
            val sources = textSourcesOrInput(node, inputKey)
            // An encoder taking both prompts (TextEncodeQwenImage21): its negative one
            if (node.inputs.containsKey("negative_prompt")) tracedNegative.addFirst(textSourcesOrInput(node, "negative_prompt"))

            when (traceConditioningConnection(graph, node.id)) {
                "positive" -> tracedPositive.addFirst(sources)
                "negative" -> tracedNegative.addFirst(sources)
                else -> sources.forEach { candidate ->
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
        positiveTextCandidates.addAll(0, tracedPositive.filter { t -> positiveTextCandidates.none { it.nodeId == t.nodeId && it.inputKey == t.inputKey } })
        negativeTextCandidates.addAll(0, tracedNegative.filter { t -> negativeTextCandidates.none { it.nodeId == t.nodeId && it.inputKey == t.inputKey } })

        // Primitive String nodes not feeding a text encoder (e.g. prompts of API nodes without
        // CLIP encoding) as fallback candidates
        // CLIP encoding) as fallback candidates: ones feeding a text input or nothing at all, never only an enhancer's
        // instruction or inputs of other types (a switch's boolean)
        val consumers = graph.edges.groupBy { it.sourceNodeId }
        for ((nodeId, inputKey) in primitiveTextNodes) {
            val node = nodesById[nodeId] ?: continue
            val alreadyOffered = (positiveTextCandidates + negativeTextCandidates).any { it.nodeId == nodeId }
            if (alreadyOffered || isInstruction(node.title)) continue
            val feeds = consumers[nodeId].orEmpty()
            val feedsText = feeds.any { edge ->
                if (isInstruction(edge.targetInputName)) return@any false
                val target = nodesById[edge.targetNodeId] ?: return@any true
                val type = nodeTypeRegistry.getNodeDefinition(target.classType)?.inputs?.find { it.name == edge.targetInputName }?.type
                type == null || type == "STRING" || type == "*" || type.startsWith("COMFY_MATCHTYPE")
            }
            if (feeds.isNotEmpty() && !feedsText) continue
            val candidate = candidateFor(node, inputKey)
            val titleLower = node.title.lowercase()
            if (!titleLower.contains("negative")) positiveTextCandidates.addUnique(candidate)
            if (!titleLower.contains("positive")) negativeTextCandidates.addUnique(candidate)
        }

        // A node generating from a prompt itself (an API node: no conditioning, its "prompt" a literal) as a fallback for
        // the positive prompt; a text node's "text" isn't offered (string tools, notes)
        for (node in graph.nodes) {
            if (isTextEncoder(node) || node.inputs["prompt"] !is InputValue.Literal) continue
            if (node.outputs.none { it.type == "IMAGE" || it.type == "VIDEO" || it.type == "LATENT" }) continue
            positiveTextCandidates.addUnique(candidateFor(node, "prompt"))
        }

        return mapOf(
            "positive_text" to positiveTextCandidates,
            "negative_text" to negativeTextCandidates
        )
    }

    /** An enhancer's instruction (TextGenerate's system_prompt), not the prompt it rewrites. */
    private fun isInstruction(inputKey: String): Boolean = inputKey.contains("system", ignoreCase = true)

    /**
     * A text encoder: makes conditioning, by its outputs; by name only where they aren't known (a node missing on the
     * server). A node making a prompt for one (Flux3LayoutPrompt: STRING) is a source of its text, not an encoder.
     */
    private fun isTextEncoder(node: WorkflowNode): Boolean =
        if (node.outputs.isNotEmpty()) node.outputs.any { it.type == "CONDITIONING" }
        else node.classType.contains("TextEncode", ignoreCase = true) || node.classType.contains("Prompt", ignoreCase = true)

    /**
     * Choose the auto-selected candidate index for a field.
     *
     * Prefers the candidate whose current value already matches the expected placeholder
     * (set by a previous save), so re-editing a workflow keeps the correct node selected
     * rather than defaulting to whichever node happens to be first in iteration order.
     * Falls back to index 0 if no placeholder match is found.
     */
    /**
     * A primitive node (ComfyUI's Int, Float, Boolean, String…) as the source of a linked value: its own value, a
     * literal it holds with no links of its own; null for any other node.
     */
    private fun primitiveSource(node: WorkflowNode?): Pair<WorkflowNode, String>? {
        if (node == null || !node.classType.startsWith("Primitive")) return null
        if (node.inputs.values.any { it !is InputValue.Literal }) return null
        return if (node.inputs["value"] is InputValue.Literal) node to "value" else null
    }

    /**
     * A node passing a list on as the source of a linked list (KSampler Config's SAMPLER, an output whose type is a list
     * of options): its own list with the very same options, unlinked, whose value it sends; null when it has none, or
     * several (which one it sends can't be told).
     */
    private fun listSource(node: WorkflowNode?, outputIndex: Int, nodeTypeRegistry: NodeTypeRegistry): Pair<WorkflowNode, String>? {
        if (node == null) return null
        val options = nodeTypeRegistry.getOutputOptions(node.classType, outputIndex) ?: return null
        val own = nodeTypeRegistry.getNodeDefinition(node.classType)?.inputs.orEmpty()
            .filter { it.options == options && node.inputs[it.name] is InputValue.Literal }
        return own.singleOrNull()?.let { node to it.name }
    }

    private fun autoSelectIndex(fieldKey: String, candidates: List<FieldCandidate>): Int {
        if (candidates.isEmpty()) return -1
        val placeholderPattern = "{{${TemplateKeyRegistry.getPlaceholderForKey(fieldKey)}}}"
        val preferred = candidates.indexOfFirst { it.currentValue?.toString() == placeholderPattern }
        return if (preferred >= 0) preferred else 0
    }

    /**
     * Whether a text encoder's conditioning ends in a positive or a negative input: "positive", "negative", or null if
     * it reaches neither. Follows the conditioning downstream through the nodes that pass it on (zeroing it, combining,
     * guidance…) up to [MAX_TRACE_DEPTH] nodes away; any node's input named "positive" or "negative" ends a path (a node
     * taking both passes each on as its own output, which a trace by node can't tell apart), and so
     * does a "conditioning" input of a node that doesn't pass conditioning on (a guider, which has only the positive
     * one). A "conditioning" input of a node that passes it on (ConditioningZeroOut, often the negative) says nothing
     * by itself. An encoder reaching both is positive: Flux workflows feed the negative its zeroed copy.
     */
    fun traceConditioningConnection(graph: WorkflowGraph, conditioningNodeId: String): String? {
        val edgesFrom = graph.edges.groupBy { it.sourceNodeId }
        val nodesById = graph.nodes.associateBy { it.id }
        val visited = mutableSetOf(conditioningNodeId)
        var frontier = listOf(conditioningNodeId)
        var negative = false
        repeat(MAX_TRACE_DEPTH) {
            val next = mutableListOf<String>()
            for (nodeId in frontier) {
                for (edge in edgesFrom[nodeId].orEmpty()) {
                    val target = nodesById[edge.targetNodeId]
                    val passesOn = target?.outputs?.any { it.type == "CONDITIONING" } == true
                    when (edge.targetInputName.lowercase()) {
                        "positive" -> return "positive"
                        // Ends the path: a node taking both (WanImageToVideo) passes each on as its own output
                        "negative" -> { negative = true; continue }
                        "conditioning" -> if (!passesOn) return "positive"
                    }
                    if (passesOn && visited.add(edge.targetNodeId)) next.add(edge.targetNodeId)
                }
            }
            if (next.isEmpty()) return if (negative) "negative" else null
            frontier = next
        }
        return if (negative) "negative" else null
    }

    private const val MAX_TRACE_DEPTH = 6
}
