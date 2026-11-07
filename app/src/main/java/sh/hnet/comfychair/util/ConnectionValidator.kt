package sh.hnet.comfychair.util

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
        nodeTypeRegistry: NodeTypeRegistry
    ): List<SlotPosition> {
        val validSlots = mutableListOf<SlotPosition>()

        // Check all other nodes
        for (node in graph.nodes) {
            // Skip the source node - can't connect to self
            if (node.id == sourceNodeId) continue

            // Check each input on this node
            var inputIndex = 0
            for ((inputName, inputValue) in node.inputs) {
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
        nodeTypeRegistry: NodeTypeRegistry
    ): List<SlotPosition> {
        val validSlots = mutableListOf<SlotPosition>()

        // Check all other nodes
        for (node in graph.nodes) {
            // Skip the target node - can't connect to self
            if (node.id == targetNodeId) continue

            // Check each output on this node
            node.outputs.forEachIndexed { outputIndex, output ->
                if (isTypeCompatible(output.type, inputType)) {
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

    /** Value types (ComfyUI widgets) that can take a link of their own type instead of a typed value. */
    val LINKABLE_VALUE_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN")

    /** The type of a value input that can take a link, or null (lists, connection types, unknown nodes). */
    fun linkableValueType(classType: String, inputName: String, nodeTypeRegistry: NodeTypeRegistry): String? =
        nodeTypeRegistry.getNodeDefinition(classType)?.inputs?.find { it.name == inputName }?.type?.uppercase()
            // A value promoted from inside a subgraph is set, not linked (a link would go to the subgraph's input)
            ?.takeIf { it in LINKABLE_VALUE_TYPES && !Subgraphs.isPromotedKey(inputName) }

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
