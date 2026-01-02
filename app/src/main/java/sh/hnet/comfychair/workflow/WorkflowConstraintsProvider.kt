package sh.hnet.comfychair.workflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.json.JSONObject
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.DebugLogger

/**
 * Provides numeric constraints for workflow fields by looking up the actual
 * mapped node in the selected workflow and querying NodeTypeRegistry.
 *
 * This ensures constraints match the specific node used in each workflow,
 * since different workflows may use different nodes (e.g., KSampler vs
 * KSamplerAdvanced) that have different min/max/step values.
 */
object WorkflowConstraintsProvider {

    private const val TAG = "Constraints"

    data class NumericConstraints(
        val min: Float,
        val max: Float,
        val step: Float,
        val decimalPlaces: Int = 0
    )

    // Fallback defaults when workflow/server data unavailable
    private val FALLBACK_CONSTRAINTS = mapOf(
        "steps" to NumericConstraints(1f, 10000f, 1f, 0),
        "cfg" to NumericConstraints(0f, 100f, 0.1f, 1),
        "width" to NumericConstraints(16f, 16384f, 8f, 0),
        "height" to NumericConstraints(16f, 16384f, 8f, 0),
        "length" to NumericConstraints(1f, 1024f, 4f, 0),
        "frame_rate" to NumericConstraints(1f, 60f, 1f, 0),
        "fps" to NumericConstraints(1f, 60f, 1f, 0),
        "megapixels" to NumericConstraints(0.01f, 16f, 0.01f, 2),
        "seed" to NumericConstraints(0f, Float.MAX_VALUE, 1f, 0),
        "denoise" to NumericConstraints(0f, 1f, 0.05f, 2),
        "batch_size" to NumericConstraints(1f, 64f, 1f, 0),
        "scale_by" to NumericConstraints(0.01f, 8f, 0.1f, 2),
        "stop_at_clip_layer" to NumericConstraints(-24f, -1f, 1f, 0)
    )

    // Decimal places by field type (presentation concern, not from server)
    private val DECIMAL_PLACES = mapOf(
        "steps" to 0,
        "cfg" to 1,
        "width" to 0,
        "height" to 0,
        "length" to 0,
        "frame_rate" to 0,
        "fps" to 0,
        "megapixels" to 2,
        "seed" to 0,
        "denoise" to 2,
        "batch_size" to 0,
        "scale_by" to 2,
        "stop_at_clip_layer" to 0
    )

    /**
     * Get constraints for a field from the actual mapped node in the workflow.
     *
     * @param fieldKey The template field key (e.g., "steps", "cfg", "width")
     * @param workflowName The name of the currently selected workflow
     * @return NumericConstraints with min/max/step from the mapped node, or fallback defaults
     */
    fun getConstraints(fieldKey: String, workflowName: String): NumericConstraints {
        val fallback = FALLBACK_CONSTRAINTS[fieldKey]
            ?: NumericConstraints(0f, Float.MAX_VALUE, 1f, 0)
        val decimalPlaces = DECIMAL_PLACES[fieldKey] ?: 0
        val fallbackWithDecimals = fallback.copy(decimalPlaces = decimalPlaces)

        DebugLogger.d(TAG, "Looking up constraints: field='$fieldKey', workflow='$workflowName'")

        // Get workflow JSON
        val workflow = WorkflowManager.getWorkflowByName(workflowName)
        if (workflow == null) {
            DebugLogger.d(TAG, "  -> Workflow not found, using fallback")
            return fallbackWithDecimals
        }

        val json = try {
            JSONObject(workflow.jsonContent)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "  -> Failed to parse workflow JSON: ${e.message}")
            return fallbackWithDecimals
        }

        // Get nodes from workflow
        val nodesJson = json.optJSONObject("nodes")
        if (nodesJson == null) {
            DebugLogger.d(TAG, "  -> No nodes in workflow JSON, using fallback")
            return fallbackWithDecimals
        }

        // Try to get field mapping - first from explicit fieldMappings, then by scanning template placeholders
        val fieldMappings = json.optJSONObject("fieldMappings")
        val (nodeId, inputKey, classType) = if (fieldMappings != null) {
            // Use explicit fieldMappings (user-edited workflows)
            val mapping = fieldMappings.optJSONObject(fieldKey)
            if (mapping == null) {
                DebugLogger.d(TAG, "  -> Field '$fieldKey' not in fieldMappings, scanning nodes...")
                findFieldInNodes(fieldKey, nodesJson) ?: run {
                    DebugLogger.d(TAG, "  -> Field '$fieldKey' not found in nodes, using fallback")
                    return fallbackWithDecimals
                }
            } else {
                val nId = mapping.optString("nodeId")
                val iKey = mapping.optString("inputKey")
                if (nId.isEmpty() || iKey.isEmpty()) {
                    DebugLogger.d(TAG, "  -> Invalid mapping (nodeId='$nId', inputKey='$iKey'), using fallback")
                    return fallbackWithDecimals
                }
                val node = nodesJson.optJSONObject(nId)
                val cType = node?.optString("class_type") ?: ""
                if (cType.isEmpty()) {
                    DebugLogger.d(TAG, "  -> Node '$nId' has no class_type, using fallback")
                    return fallbackWithDecimals
                }
                Triple(nId, iKey, cType)
            }
        } else {
            // No explicit fieldMappings - scan nodes for template placeholders
            DebugLogger.d(TAG, "  -> No fieldMappings, scanning nodes for {{$fieldKey}}...")
            findFieldInNodes(fieldKey, nodesJson) ?: run {
                DebugLogger.d(TAG, "  -> Field '$fieldKey' not found in nodes, using fallback")
                return fallbackWithDecimals
            }
        }

        DebugLogger.d(TAG, "  -> Mapped to node: $classType.$inputKey (nodeId=$nodeId)")

        // Look up constraints from NodeTypeRegistry
        val registry = ConnectionManager.nodeTypeRegistry
        if (!registry.isPopulated()) {
            DebugLogger.d(TAG, "  -> NodeTypeRegistry not populated, using fallback")
            return fallbackWithDecimals
        }

        val inputDef = registry.getInputDefinition(classType, inputKey)
        if (inputDef == null) {
            DebugLogger.d(TAG, "  -> Input definition not found, using fallback")
            return fallbackWithDecimals
        }

        val result = NumericConstraints(
            min = inputDef.min?.toFloat() ?: fallback.min,
            max = inputDef.max?.toFloat() ?: fallback.max,
            step = inputDef.step?.toFloat() ?: fallback.step,
            decimalPlaces = decimalPlaces
        )

        DebugLogger.i(TAG, "Constraints for '$fieldKey': min=${result.min}, max=${result.max}, step=${result.step} (from $classType)")
        return result
    }

    /**
     * Composable helper to remember constraints, recomputing when workflow changes.
     */
    @Composable
    fun rememberConstraints(fieldKey: String, workflowName: String): NumericConstraints {
        return remember(fieldKey, workflowName) {
            getConstraints(fieldKey, workflowName)
        }
    }

    /**
     * Scan workflow nodes for a template placeholder like {{fieldKey}}.
     * Returns Triple(nodeId, inputKey, classType) if found, null otherwise.
     */
    private fun findFieldInNodes(fieldKey: String, nodesJson: JSONObject): Triple<String, String, String>? {
        val placeholder = "{{$fieldKey}}"

        val nodeIds = nodesJson.keys()
        while (nodeIds.hasNext()) {
            val nodeId = nodeIds.next()
            val node = nodesJson.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type")
            if (classType.isEmpty()) continue

            val inputs = node.optJSONObject("inputs") ?: continue
            val inputKeys = inputs.keys()
            while (inputKeys.hasNext()) {
                val inputKey = inputKeys.next()
                val value = inputs.opt(inputKey)
                // Check if this input contains the template placeholder
                if (value is String && value == placeholder) {
                    return Triple(nodeId, inputKey, classType)
                }
            }
        }
        return null
    }
}
