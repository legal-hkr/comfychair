package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Utilities for handling bypassed nodes in workflow execution.
 * When nodes are bypassed in ComfyUI (mode=4), connections need to be
 * rewired around them before submitting to the API.
 */
internal object BypassNodeResolver {

    private const val TAG = "BypassNodeResolver"

    /**
     * Apply bypass logic to a workflow JSON before submission to ComfyUI API.
     * ComfyUI's API doesn't automatically handle the mode property - we need to
     * manually remove bypassed nodes and rewire connections around them.
     *
     * Uses type-inference to correctly match inputs and outputs when rewiring:
     * 1. Infer the expected type from the target node's input name
     * 2. Find the matching type input on the bypassed node
     * 3. Follow the connection chain to the original non-bypassed source
     *
     * @param workflowJson The workflow JSON string (with or without "nodes" wrapper)
     * @return Modified workflow JSON with bypassed nodes removed and connections rewired
     */
    fun applyBypassedNodes(workflowJson: String): String {
        return try {
            val json = JSONObject(workflowJson)
            val hasWrapper = json.has("nodes") && json.optJSONObject("nodes") != null
            val nodes = if (hasWrapper) json.getJSONObject("nodes") else json

            // Find all bypassed nodes (mode=4)
            val bypassedNodeIds = mutableSetOf<String>()
            val nodeIds = nodes.keys().asSequence().toList()
            for (nodeId in nodeIds) {
                val node = nodes.optJSONObject(nodeId) ?: continue
                val mode = node.optInt("mode", 0)
                if (mode == 4) {
                    bypassedNodeIds.add(nodeId)
                    DebugLogger.d(TAG, "applyBypassedNodes: Found bypassed node $nodeId (${node.optString("class_type")})")
                }
            }

            if (bypassedNodeIds.isEmpty()) {
                DebugLogger.d(TAG, "applyBypassedNodes: No bypassed nodes found")
                return workflowJson
            }

            // Rewire connections using type-based matching
            for (nodeId in nodeIds) {
                if (nodeId in bypassedNodeIds) continue
                val node = nodes.optJSONObject(nodeId) ?: continue
                val inputs = node.optJSONObject("inputs") ?: continue

                val inputKeys = inputs.keys().asSequence().toList()
                for (inputKey in inputKeys) {
                    val inputValue = inputs.opt(inputKey)
                    if (inputValue is JSONArray && inputValue.length() >= 2) {
                        val sourceNodeId = inputValue.optString(0, "")

                        if (sourceNodeId in bypassedNodeIds) {
                            // This input references a bypassed node - need to rewire using type matching
                            val expectedType = inferTypeFromInputName(inputKey)

                            if (expectedType != null) {
                                DebugLogger.d(TAG, "applyBypassedNodes: Input '$inputKey' on node $nodeId expects type $expectedType")

                                val resolvedSource = resolveBypassChain(
                                    nodes,
                                    sourceNodeId,
                                    inputValue.optInt(1, 0),
                                    expectedType,
                                    bypassedNodeIds
                                )

                                if (resolvedSource != null) {
                                    DebugLogger.d(TAG, "applyBypassedNodes: Rewiring node $nodeId input '$inputKey' ($expectedType) from bypassed $sourceNodeId to ${resolvedSource.optString(0)}[${resolvedSource.optInt(1)}]")
                                    inputs.put(inputKey, resolvedSource)
                                } else {
                                    DebugLogger.w(TAG, "applyBypassedNodes: Could not find $expectedType source for node $nodeId input '$inputKey' - removing connection")
                                    inputs.remove(inputKey)
                                }
                            } else {
                                DebugLogger.w(TAG, "applyBypassedNodes: Unknown type for input '$inputKey' on node $nodeId - removing connection")
                                inputs.remove(inputKey)
                            }
                        }
                    }
                }
            }

            // Remove bypassed nodes from the JSON
            for (bypassedId in bypassedNodeIds) {
                nodes.remove(bypassedId)
                DebugLogger.d(TAG, "applyBypassedNodes: Removed bypassed node $bypassedId")
            }

            DebugLogger.i(TAG, "applyBypassedNodes: Processed ${bypassedNodeIds.size} bypassed nodes")
            json.toString()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "applyBypassedNodes: Error processing bypass: ${e.message}")
            workflowJson
        }
    }

    /**
     * Infer the expected data type from an input name using ComfyUI naming conventions.
     * This allows type-based matching when rewiring connections around bypassed nodes.
     */
    private fun inferTypeFromInputName(inputName: String): String? {
        val lowerName = inputName.lowercase()
        return when {
            lowerName in listOf("samples", "latent_image", "latent", "latent_images") -> "LATENT"
            lowerName in listOf("model", "unet") || lowerName.startsWith("model") -> "MODEL"
            lowerName in listOf("clip", "clip_l", "clip_g") || lowerName.startsWith("clip") -> "CLIP"
            lowerName == "vae" -> "VAE"
            lowerName in listOf("positive", "negative", "conditioning", "cond") -> "CONDITIONING"
            lowerName in listOf("image", "images", "pixels") -> "IMAGE"
            lowerName == "mask" -> "MASK"
            lowerName == "noise" -> "NOISE"
            else -> null
        }
    }

    /**
     * Find a connection input on a node that matches the expected type.
     * Returns the connection JSONArray [sourceNodeId, outputIndex] or null.
     */
    private fun findConnectionByType(nodeInputs: JSONObject, expectedType: String): JSONArray? {
        val inputKeys = nodeInputs.keys()
        while (inputKeys.hasNext()) {
            val inputKey = inputKeys.next()
            val inputValue = nodeInputs.opt(inputKey)

            // Only consider connections (array format)
            if (inputValue is JSONArray && inputValue.length() >= 2) {
                val inputType = inferTypeFromInputName(inputKey)
                if (inputType == expectedType) {
                    DebugLogger.d(TAG, "findConnectionByType: Found $expectedType input '$inputKey' -> [${inputValue.optString(0)}, ${inputValue.optInt(1)}]")
                    return inputValue
                }
            }
        }
        return null
    }

    /**
     * Follow a connection through any bypassed nodes to find the final non-bypassed source.
     * Uses type inference to match the correct input when traversing bypassed nodes.
     */
    private fun resolveBypassChain(
        nodes: JSONObject,
        sourceNodeId: String,
        sourceOutputIndex: Int,
        expectedType: String,
        bypassedNodeIds: Set<String>,
        depth: Int = 0
    ): JSONArray? {
        if (depth > 10) {
            DebugLogger.w(TAG, "resolveBypassChain: Max depth reached, possible cycle")
            return null
        }

        if (sourceNodeId !in bypassedNodeIds) {
            // Found non-bypassed source
            return JSONArray().apply {
                put(sourceNodeId)
                put(sourceOutputIndex)
            }
        }

        // Source is bypassed - find matching input and follow it
        val bypassedNode = nodes.optJSONObject(sourceNodeId) ?: return null
        val bypassedInputs = bypassedNode.optJSONObject("inputs") ?: return null

        val matchingConnection = findConnectionByType(bypassedInputs, expectedType)
        if (matchingConnection != null) {
            val nextSourceId = matchingConnection.optString(0, "")
            val nextOutputIndex = matchingConnection.optInt(1, 0)
            DebugLogger.d(TAG, "resolveBypassChain: Following $expectedType through bypassed $sourceNodeId to $nextSourceId[$nextOutputIndex]")
            return resolveBypassChain(nodes, nextSourceId, nextOutputIndex, expectedType, bypassedNodeIds, depth + 1)
        }

        DebugLogger.w(TAG, "resolveBypassChain: No $expectedType input found on bypassed node $sourceNodeId")
        return null
    }
}
