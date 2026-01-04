package sh.hnet.comfychair.util

import androidx.compose.ui.geometry.Offset
import sh.hnet.comfychair.workflow.InputValue
import sh.hnet.comfychair.workflow.MutableWorkflowGraph
import sh.hnet.comfychair.workflow.NodeTypeRegistry
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
     * Returns slots on OTHER nodes that are type-compatible with the output.
     * Only connection-type inputs (Connection or UnconnectedSlot) are valid targets.
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

                if (isConnectionInput) {
                    // Get input type - prefer from UnconnectedSlot, fallback to registry
                    val inputType = when (inputValue) {
                        is InputValue.UnconnectedSlot -> inputValue.slotType
                        else -> nodeTypeRegistry.getInputType(node.classType, inputName)
                    }

                    if (isTypeCompatible(outputType, inputType)) {
                        // Calculate input slot position (left side of node)
                        val slotY = node.y + WorkflowLayoutEngine.NODE_HEADER_HEIGHT + 20f +
                            (inputIndex * WorkflowLayoutEngine.INPUT_ROW_HEIGHT)

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
