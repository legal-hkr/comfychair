package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.LoraSelection

/**
 * Utilities for injecting LoRA nodes into workflow JSON.
 * Handles LoRA chain injection during generation.
 *
 * Note: Different from LoraChainManager which manages LoRA data structures.
 * This injects LoRA nodes into workflow JSON for execution.
 */
internal object LoraInjectionUtils {

    /**
     * Inject a chain of LoRA nodes into a workflow.
     * Creates LoraLoaderModelOnly nodes between the model source and consumers.
     *
     * @param workflowJson The prepared workflow JSON string
     * @param loraChain List of LoRA selections to inject (can be empty)
     * @param workflowType The type of workflow (determines model source node type)
     * @return Modified workflow JSON with LoRA nodes injected, or original if chain is empty
     */
    fun injectLoraChain(
        workflowJson: String,
        loraChain: List<LoraSelection>,
        workflowType: WorkflowType
    ): String {
        if (loraChain.isEmpty()) return workflowJson

        try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: return workflowJson

            // Find the model source node
            val modelSourceId = findModelSourceNode(nodes, workflowType) ?: return workflowJson

            // Find all nodes that consume the model output
            val modelConsumers = findModelConsumers(nodes, modelSourceId)
            if (modelConsumers.isEmpty()) return workflowJson

            // Generate unique node IDs for LoRA nodes
            var nextNodeId = generateUniqueNodeId(nodes)

            // Track the current model source (starts with original, updates as we chain)
            var currentModelSource = modelSourceId
            val loraNodeIds = mutableListOf<String>()

            // Create LoRA nodes in chain
            for (lora in loraChain) {
                val loraNodeId = nextNodeId.toString()
                val loraNode = createLoraNode(lora.name, lora.strength, currentModelSource, 0)
                nodes.put(loraNodeId, loraNode)
                loraNodeIds.add(loraNodeId)
                currentModelSource = loraNodeId
                nextNodeId++
            }

            // Update all original model consumers to reference the last LoRA's output
            val lastLoraId = loraNodeIds.last()
            for ((consumerId, inputKey) in modelConsumers) {
                val consumerNode = nodes.optJSONObject(consumerId) ?: continue
                val inputs = consumerNode.optJSONObject("inputs") ?: continue
                // Update the model reference to point to last LoRA
                inputs.put(inputKey, JSONArray().apply {
                    put(lastLoraId)
                    put(0)
                })
            }

            return json.toString()
        } catch (e: Exception) {
            return workflowJson
        }
    }

    /**
     * Inject additional LoRAs into a video workflow AFTER the mandatory LightX2V LoRAs.
     * This handles the two-chain structure of video workflows (high noise and low noise).
     *
     * @param workflowJson The prepared workflow JSON string
     * @param additionalLoraChain List of additional LoRA selections
     * @param isHighNoise Whether to inject into the high noise chain (true) or low noise chain (false)
     * @return Modified workflow JSON with additional LoRA nodes injected
     */
    fun injectAdditionalVideoLoras(
        workflowJson: String,
        additionalLoraChain: List<LoraSelection>,
        isHighNoise: Boolean
    ): String {
        if (additionalLoraChain.isEmpty()) return workflowJson

        try {
            val json = JSONObject(workflowJson)
            val nodes = json.optJSONObject("nodes") ?: return workflowJson

            // For video workflows, we need to find the existing LoraLoaderModelOnly node
            // that feeds into ModelSamplingSD3 and chain after it
            val (existingLoraId, modelSamplingId) = findVideoLoraChainEnd(nodes, isHighNoise)
                ?: return workflowJson

            // Generate unique node IDs
            var nextNodeId = generateUniqueNodeId(nodes)

            // Track the current model source (starts with existing LoRA output)
            var currentModelSource = existingLoraId
            val loraNodeIds = mutableListOf<String>()

            // Create additional LoRA nodes in chain
            for (lora in additionalLoraChain) {
                val loraNodeId = nextNodeId.toString()
                val loraNode = createLoraNode(lora.name, lora.strength, currentModelSource, 0)
                nodes.put(loraNodeId, loraNode)
                loraNodeIds.add(loraNodeId)
                currentModelSource = loraNodeId
                nextNodeId++
            }

            // Update ModelSamplingSD3 to reference the last additional LoRA
            val modelSamplingNode = nodes.optJSONObject(modelSamplingId)
            val modelSamplingInputs = modelSamplingNode?.optJSONObject("inputs")
            modelSamplingInputs?.put("model", JSONArray().apply {
                put(loraNodeIds.last())
                put(0)
            })

            return json.toString()
        } catch (e: Exception) {
            return workflowJson
        }
    }

    /**
     * Find the model source node in a workflow.
     * Searches for CheckpointLoaderSimple first, then UNETLoader.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun findModelSourceNode(nodes: JSONObject, workflowType: WorkflowType): String? {
        // Search for both loader types - prioritize CheckpointLoaderSimple, then UNETLoader
        val loaderTypes = listOf("CheckpointLoaderSimple", "UNETLoader")

        for (targetClassType in loaderTypes) {
            val nodeIds = nodes.keys()
            while (nodeIds.hasNext()) {
                val nodeId = nodeIds.next()
                val node = nodes.optJSONObject(nodeId) ?: continue
                val classType = node.optString("class_type", "")
                if (classType == targetClassType) {
                    return nodeId
                }
            }
        }
        return null
    }

    /**
     * Find all nodes that consume the model output from a source node.
     * Returns list of (consumerId, inputKey) pairs.
     */
    private fun findModelConsumers(nodes: JSONObject, modelSourceId: String): List<Pair<String, String>> {
        val consumers = mutableListOf<Pair<String, String>>()

        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodes.optJSONObject(nodeId) ?: continue
            val inputs = node.optJSONObject("inputs") ?: continue

            val inputKeys = inputs.keys()
            while (inputKeys.hasNext()) {
                val inputKey = inputKeys.next()
                val inputValue = inputs.opt(inputKey)

                // Check if this input references the model source (array format: ["nodeId", outputIndex])
                if (inputValue is JSONArray && inputValue.length() >= 2) {
                    val refNodeId = inputValue.optString(0, "")
                    val refOutputIndex = inputValue.optInt(1, -1)
                    // Model output is always index 0
                    if (refNodeId == modelSourceId && refOutputIndex == 0) {
                        consumers.add(Pair(nodeId, inputKey))
                    }
                }
            }
        }
        return consumers
    }

    /**
     * Find the end of the existing LoRA chain in a video workflow.
     * Returns (lastLoraNodeId, modelSamplingNodeId) or null if not found.
     *
     * In video workflows:
     * - High noise path: UNETLoader → LoRA → ModelSamplingSD3 → KSamplerAdvanced (start_at_step=0)
     * - Low noise path: UNETLoader → LoRA → ModelSamplingSD3 → KSamplerAdvanced (start_at_step>0)
     */
    private fun findVideoLoraChainEnd(nodes: JSONObject, isHighNoise: Boolean): Pair<String, String>? {
        // Collect all relevant nodes by type
        val modelSamplingNodes = mutableListOf<String>()
        val loraNodes = mutableMapOf<String, JSONObject>()
        val kSamplerNodes = mutableMapOf<String, JSONObject>()

        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodes.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type", "")
            when (classType) {
                "ModelSamplingSD3" -> modelSamplingNodes.add(nodeId)
                "LoraLoaderModelOnly" -> loraNodes[nodeId] = node
                "KSamplerAdvanced" -> kSamplerNodes[nodeId] = node
            }
        }

        // For each ModelSamplingSD3, find which LoRA feeds into it and which KSampler consumes it
        for (modelSamplingId in modelSamplingNodes) {
            val modelSamplingNode = nodes.optJSONObject(modelSamplingId) ?: continue
            val inputs = modelSamplingNode.optJSONObject("inputs") ?: continue
            val modelInput = inputs.optJSONArray("model") ?: continue

            val sourceNodeId = modelInput.optString(0, "")
            if (sourceNodeId !in loraNodes) continue

            // Found a ModelSamplingSD3 that takes input from a LoRA
            // Now find which KSamplerAdvanced uses this ModelSamplingSD3
            for ((kSamplerId, kSamplerNode) in kSamplerNodes) {
                val kSamplerInputs = kSamplerNode.optJSONObject("inputs") ?: continue
                val kSamplerModelInput = kSamplerInputs.optJSONArray("model") ?: continue
                val kSamplerModelSource = kSamplerModelInput.optString(0, "")

                if (kSamplerModelSource == modelSamplingId) {
                    // This KSamplerAdvanced uses our ModelSamplingSD3
                    // Check start_at_step to determine if high noise or low noise
                    val startAtStep = kSamplerInputs.optInt("start_at_step", -1)
                    val isHighNoisePath = (startAtStep == 0)

                    if (isHighNoisePath == isHighNoise) {
                        // Found the correct path
                        return Pair(sourceNodeId, modelSamplingId)
                    }
                }
            }
        }
        return null
    }

    /**
     * Generate a unique node ID that doesn't conflict with existing nodes.
     */
    private fun generateUniqueNodeId(nodes: JSONObject): Int {
        var maxId = 99 // Start from 100 for injected nodes
        val nodeIds = nodes.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val numericId = nodeId.toIntOrNull()
            if (numericId != null && numericId > maxId) {
                maxId = numericId
            }
        }
        return maxId + 1
    }

    /**
     * Create a LoraLoaderModelOnly node JSON object.
     */
    private fun createLoraNode(loraName: String, strength: Float, modelSourceId: String, modelOutputIndex: Int): JSONObject {
        return JSONObject().apply {
            put("class_type", "LoraLoaderModelOnly")
            put("inputs", JSONObject().apply {
                put("lora_name", loraName)
                put("strength_model", strength.toDouble())
                put("model", JSONArray().apply {
                    put(modelSourceId)
                    put(modelOutputIndex)
                })
            })
        }
    }
}
