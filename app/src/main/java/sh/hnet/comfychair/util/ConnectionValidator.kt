package sh.hnet.comfychair.util

import sh.hnet.comfychair.workflow.SubgraphLevels
import sh.hnet.comfychair.workflow.OutputSlot
import sh.hnet.comfychair.workflow.NodeTypeDefinition
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.LIST_OUTPUT_TYPE
import androidx.compose.ui.geometry.Offset
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.MutableWorkflowGraph
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.Subgraphs
import sh.hnet.comfychair.workflow.SlotPosition
import sh.hnet.comfychair.workflow.WorkflowLayoutEngine

/**
 * Utility for validating node connections and calculating valid connection targets.
 *
 * Provides pure functions for type compatibility checks and slot discovery.
 */
object ConnectionValidator {

    /**
     * Calculate all valid input slots that can receive a connection from the given output.
     * Returns slots on OTHER nodes that are type-compatible with the output: connection-type inputs (Connection or
     * UnconnectedSlot), and value rows that ComfyUI lets take a link of their type ([acceptsValueLink]).
     *
     * @param graph The workflow graph to search
     * @param sourceNodeId ID of the node with the output
     * @param outputType Type of the output (e.g., "MODEL", "CLIP", "IMAGE")
     * @param nodeTypeRegistry Registry to look up input types for nodes
     * @return List of valid input slot positions
     */
    fun calculateValidInputSlots(
        graph: MutableWorkflowGraph,
        sourceNodeId: String,
        outputType: String?,
        nodeTypeRegistry: NodeTypeRegistry,
        /** The options the output carries when it is a list ([acceptsListLink]) */
        outputOptions: List<String>? = null,
        /** The output is a subgraph's input (the Inputs strip of a level) */
        fromSubgraphInput: Boolean = false
    ): List<SlotPosition> {
        val validSlots = mutableListOf<SlotPosition>()

        // Check all other nodes
        for (node in graph.nodes) {
            // Skip the source node - can't connect to self
            if (node.id == sourceNodeId) continue

            // Check each input on this node
            var inputIndex = 0
            for ((inputName, inputValue) in node.inputs) {
                // A list, linked or not, takes what ComfyUI's server would take
                val list = linkableList(nodeTypeRegistry.getNodeDefinition(node.classType), inputName)
                if (list != null) {
                    if (acceptsListLink(outputType, outputOptions, list, fromSubgraphInput)) {
                        validSlots.add(
                            SlotPosition(
                                nodeId = node.id,
                                slotName = inputName,
                                isOutput = false,
                                outputIndex = 0,
                                center = Offset(node.x, node.y + WorkflowLayoutEngine.rowCenterY(inputIndex)),
                                slotType = LIST_OUTPUT_TYPE
                            )
                        )
                    }
                    inputIndex++
                    continue
                }
                // Only connection-type inputs can be connected
                val isConnectionInput = inputValue is InputValue.Connection ||
                    inputValue is InputValue.UnconnectedSlot

                val valueType = if (inputValue is InputValue.Literal) linkableValueType(node.classType, inputName, nodeTypeRegistry) else null
                if (valueType != null && acceptsValueLink(outputType, valueType)) {
                    validSlots.add(
                        SlotPosition(
                            nodeId = node.id,
                            slotName = inputName,
                            isOutput = false,
                            outputIndex = 0,
                            center = Offset(node.x, node.y + WorkflowLayoutEngine.rowCenterY(inputIndex)),
                            slotType = valueType
                        )
                    )
                } else if (isConnectionInput) {
                    // Get input type - prefer from UnconnectedSlot, fallback to registry
                    val inputType = when (inputValue) {
                        is InputValue.UnconnectedSlot -> inputValue.slotType
                        else -> nodeTypeRegistry.getInputType(node.classType, inputName)
                    }

                    if (isTypeCompatible(outputType, inputType)) {
                        // Calculate input slot position (left side of node)
                        val slotY = node.y + WorkflowLayoutEngine.rowCenterY(inputIndex)

                        validSlots.add(
                            SlotPosition(
                                nodeId = node.id,
                                slotName = inputName,
                                isOutput = false,
                                outputIndex = 0,
                                center = Offset(node.x, slotY),
                                slotType = inputType
                            )
                        )
                    }
                }
                // Always increment inputIndex to maintain correct Y position
                inputIndex++
            }
        }

        return validSlots
    }

    /**
     * Calculate all valid output slots that can provide a connection to the given input.
     * Returns slots on OTHER nodes that are type-compatible with the input.
     * This is the reverse of calculateValidInputSlots - used when user taps an input slot.
     *
     * @param graph The workflow graph to search
     * @param targetNodeId ID of the node with the input (the node being connected TO)
     * @param inputType Type of the input (e.g., "MODEL", "CLIP", "IMAGE")
     * @param nodeTypeRegistry Registry to look up output types for nodes
     * @return List of valid output slot positions
     */
    fun calculateValidOutputSlots(
        graph: MutableWorkflowGraph,
        targetNodeId: String,
        inputType: String?,
        nodeTypeRegistry: NodeTypeRegistry,
        /** The input when it is a list: outputs are then checked by [acceptsListLink] */
        listInput: InputDefinition? = null
    ): List<SlotPosition> {
        val validSlots = mutableListOf<SlotPosition>()

        // Check all other nodes
        for (node in graph.nodes) {
            // Skip the target node - can't connect to self
            if (node.id == targetNodeId) continue

            // Check each output on this node
            node.outputs.forEachIndexed { outputIndex, output ->
                val fits = if (listInput != null) {
                    acceptsListLink(output.type, output.options ?: nodeTypeRegistry.getOutputOptions(node.classType, outputIndex), listInput, SubgraphLevels.isPortNode(node))
                } else isTypeCompatible(output.type, inputType)
                if (fits) {
                    // Calculate output slot position (right side of node)
                    val slotY = node.y + WorkflowLayoutEngine.outputSlotY(node, outputIndex)

                    validSlots.add(
                        SlotPosition(
                            nodeId = node.id,
                            slotName = output.name,
                            isOutput = true,
                            outputIndex = outputIndex,
                            center = Offset(node.x + node.width, slotY),
                            slotType = output.type
                        )
                    )
                }
            }
        }

        return validSlots
    }

    // A switch's output of matched type (COMFY_MATCHTYPE_V3): whatever reaches it, the server takes as any type
    private const val MATCH_TYPE_PREFIX = "COMFY_MATCHTYPE"

    /** Whether [input] is a list (ComfyUI's combo: a dropdown of options). */
    fun isList(input: InputDefinition): Boolean = input.type == "ENUM" || input.type == LIST_OUTPUT_TYPE

    /** Whether [input] is a list that can take a link: all of them but those without a socket (socketless). */
    fun takesListLink(input: InputDefinition): Boolean = isList(input) && !input.socketless

    /**
     * Whether [input], a list, takes a link from an output of [outputType] carrying [outputOptions] (the options of an
     * output that is a list), as ComfyUI's server validates such a link (probed on a live server, see the project's
     * COMBO research): a wildcard output or a switch's matched type always; a list declared as COMBO
     * ([InputDefinition.comboDeclared]) any list; a list declared by its options only an output with the very same
     * options, in the same order. Text, numbers and other types never: ComfyUI wants a converting node for those. A
     * link from a subgraph's input ([fromSubgraphInput]) is resolved before the server sees it, so it fits any list.
     */
    fun acceptsListLink(
        outputType: String?,
        outputOptions: List<String>?,
        input: InputDefinition,
        fromSubgraphInput: Boolean = false
    ): Boolean {
        if (!takesListLink(input)) return false
        val type = outputType?.uppercase() ?: return false
        if (type == "*" || type.startsWith(MATCH_TYPE_PREFIX)) return true
        if (type != LIST_OUTPUT_TYPE) return false
        if (fromSubgraphInput || input.comboDeclared) return true
        return outputOptions != null && outputOptions == input.options
    }

    /** Value types (ComfyUI widgets) that can take a link of their own type instead of a typed value. */
    val LINKABLE_VALUE_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN")

    /**
     * The socket type of a value input that can take a link: its own (INT, FLOAT, STRING, BOOLEAN), or COMBO for a
     * list; null for connection types and unknown inputs. A value promoted from inside a subgraph is set, not linked (a
     * link would go to the subgraph's input).
     */
    fun linkableValueType(definition: NodeTypeDefinition?, inputName: String): String? {
        val input = definition?.inputs?.find { it.name == inputName } ?: return null
        if (Subgraphs.isPromotedKey(inputName)) return null
        if (takesListLink(input)) return LIST_OUTPUT_TYPE
        return input.type.uppercase().takeIf { it in LINKABLE_VALUE_TYPES }
    }

    fun linkableValueType(classType: String, inputName: String, nodeTypeRegistry: NodeTypeRegistry): String? =
        linkableValueType(nodeTypeRegistry.getNodeDefinition(classType), inputName)

    /** [inputName] of [definition] when it is a list that can take a link (not a value promoted from inside). */
    fun linkableList(definition: NodeTypeDefinition?, inputName: String): InputDefinition? =
        definition?.inputs?.find { it.name == inputName }?.takeIf { takesListLink(it) && !Subgraphs.isPromotedKey(inputName) }

    /** Socket types of value rows that can take a link: the value types and lists (COMBO). */
    val LINKABLE_SLOT_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", LIST_OUTPUT_TYPE)

    // Types a node's definition gives its values (widgets) rather than sockets
    private val VALUE_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "ENUM", LIST_OUTPUT_TYPE)

    /** Whether an input of [type] is a socket rather than a value (ComfyUI's widget) on a new node. */
    fun isConnectionType(type: String): Boolean = type.uppercase() !in VALUE_TYPES

    /**
     * The inputs of a node of [definition] that a link from an output of [outputType] (carrying [outputOptions] when it
     * is a list) can go to, for a node added from that output: sockets of a compatible type, and lists that take it.
     */
    fun inputsTaking(
        definition: NodeTypeDefinition,
        outputType: String?,
        outputOptions: List<String>?,
        fromSubgraphInput: Boolean = false
    ): List<InputDefinition> = definition.inputs.filter { input ->
        if (isList(input)) acceptsListLink(outputType, outputOptions, input, fromSubgraphInput)
        else (isConnectionType(input.type) || input.forceInput) && isTypeCompatible(outputType, input.type)
    }

    /**
     * The outputs of a node of [definition] that can feed an input of [inputType] ([listInput]: that input when it is a
     * list), for a node added to that input.
     */
    fun outputsFeeding(definition: NodeTypeDefinition, inputType: String?, listInput: InputDefinition?): List<OutputSlot> =
        definition.outputs.filter { output ->
            if (listInput != null) acceptsListLink(output.type, output.options, listInput) else isTypeCompatible(output.type, inputType)
        }

    /**
     * Whether a value input of [valueType] takes a link from an output of [outputType]: the same type (INT and FLOAT
     * don't mix, as in ComfyUI) or a wildcard output. Unlike connection inputs, an unknown output type doesn't count.
     */
    fun acceptsValueLink(outputType: String?, valueType: String): Boolean =
        outputType != null && (outputType == "*" || outputType.equals(valueType, ignoreCase = true))

    /**
     * Check if an output type is compatible with an input type.
     *
     * Compatibility rules:
     * - "*" (wildcard) matches anything
     * - Exact match (case-insensitive)
     * - null types are considered compatible (for flexibility)
     *
     * @param outputType Type of the output slot
     * @param inputType Type of the input slot
     * @return true if the types are compatible
     */
    fun isTypeCompatible(outputType: String?, inputType: String?): Boolean {
        return outputType == null ||
            inputType == null ||
            inputType == "*" ||
            outputType == "*" ||
            inputType.equals(outputType, ignoreCase = true)
    }
}
