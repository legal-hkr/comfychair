package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.workflow.InputDefinition
import sh.hnet.comfychair.workflow.NodeTypeDefinition

/**
 * Converts LiteGraph workflow format (ComfyUI's default "Save" format) to
 * ComfyChair/API format that can be used by the app.
 *
 * LiteGraph format has:
 * - nodes: Array of node objects with id, type, widgets_values, inputs, outputs
 * - links: Array of [linkId, srcNodeId, srcSlot, dstNodeId, dstSlot, type]
 * - groups: Array with bounding boxes
 *
 * API/ComfyChair format has:
 * - nodes: Object keyed by node ID with class_type, inputs (named map)
 * - groups: Array with member_nodes
 * - notes: Array with id, title, content
 */
class LiteGraphConverter(
    private val getNodeDefinition: (classType: String) -> NodeTypeDefinition?
) {

    /**
     * Result of LiteGraph to API format conversion
     */
    data class ConversionResult(
        val jsonContent: String,
        val warnings: List<String>
    )

    /**
     * Internal representation of a LiteGraph link
     */
    private data class Link(
        val linkId: Int,
        val srcNodeId: Int,
        val srcSlotIdx: Int,
        val dstNodeId: Int,
        val dstSlotIdx: Int,
        val type: String
    )

    /**
     * Internal representation of a LiteGraph node (for group membership calculation)
     */
    private data class NodePosition(
        val id: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    companion object {
        private const val TAG = "LiteGraphConverter"

        // Note node types that should be extracted to notes array
        private val NOTE_NODE_TYPES = setOf("Note", "MarkdownNote", "workflow/note")

        // Reroute node types that should be skipped
        private val REROUTE_NODE_TYPES = setOf("Reroute", "ReroutePrimitive")

        // Bypass mode value in LiteGraph
        private const val MODE_BYPASS = 4

        // Control widget values (frontend-only, appear after seed inputs in widgets_values)
        // These are not in server's /object_info and must be detected by value
        private val CONTROL_WIDGET_VALUES = setOf("fixed", "increment", "decrement", "randomize")

        // Input names that may have a control widget following them
        private val SEED_INPUT_NAMES = setOf("seed", "noise_seed")
    }

    /**
     * Convert LiteGraph format workflow to ComfyChair format
     */
    fun convert(liteGraphJson: JSONObject): ConversionResult {
        val warnings = mutableListOf<String>()
        val inputNodeCount = liteGraphJson.optJSONArray("nodes")?.length() ?: 0
        val inputGroupCount = liteGraphJson.optJSONArray("groups")?.length() ?: 0
        DebugLogger.i(TAG, "Converting LiteGraph workflow: $inputNodeCount nodes, $inputGroupCount groups")

        // Parse links into lookup map
        val linksArray = liteGraphJson.optJSONArray("links")
        val linkMap = parseLinkMap(linksArray)

        // Parse node positions for group membership
        val nodePositions = mutableMapOf<Int, NodePosition>()

        // Convert nodes
        val apiNodes = JSONObject()
        val notes = JSONArray()
        val nodesArray = liteGraphJson.optJSONArray("nodes") ?: JSONArray()

        for (i in 0 until nodesArray.length()) {
            val node = nodesArray.optJSONObject(i) ?: continue
            val nodeId = node.optInt("id", -1)
            if (nodeId == -1) continue

            val nodeType = node.optString("type", "")

            // Skip reroute nodes
            if (nodeType in REROUTE_NODE_TYPES) continue

            // Store position for group membership calculation
            val pos = node.optJSONArray("pos")
            val size = node.optJSONArray("size")
            if (pos != null && pos.length() >= 2) {
                nodePositions[nodeId] = NodePosition(
                    id = nodeId,
                    x = pos.optDouble(0, 0.0).toFloat(),
                    y = pos.optDouble(1, 0.0).toFloat(),
                    width = size?.optDouble(0, 200.0)?.toFloat() ?: 200f,
                    height = size?.optDouble(1, 100.0)?.toFloat() ?: 100f
                )
            }

            // Extract note nodes separately
            if (nodeType in NOTE_NODE_TYPES) {
                val noteObj = extractNote(node)
                if (noteObj != null) {
                    notes.put(noteObj)
                }
                continue
            }

            // Convert regular node
            val (apiNode, nodeWarnings) = convertNode(node, linkMap)
            if (apiNode != null) {
                apiNodes.put(nodeId.toString(), apiNode)
            }
            warnings.addAll(nodeWarnings)
        }

        // Convert groups
        val groupsArray = liteGraphJson.optJSONArray("groups")
        val groups = convertGroups(groupsArray, nodePositions)

        // Build final ComfyChair-format JSON
        val result = JSONObject().apply {
            put("nodes", apiNodes)
            if (groups.length() > 0) {
                put("groups", groups)
            }
            if (notes.length() > 0) {
                put("notes", notes)
            }
        }

        DebugLogger.i(TAG, "Conversion complete: ${apiNodes.length()} nodes, ${groups.length()} groups, ${notes.length()} notes")
        if (warnings.isNotEmpty()) {
            DebugLogger.w(TAG, "Conversion warnings: ${warnings.joinToString("; ")}")
        }

        return ConversionResult(result.toString(2), warnings)
    }

    /**
     * Parse links array into a map for quick lookup
     */
    private fun parseLinkMap(linksArray: JSONArray?): Map<Int, Link> {
        val linkMap = mutableMapOf<Int, Link>()
        if (linksArray == null) return linkMap

        for (i in 0 until linksArray.length()) {
            val linkArr = linksArray.optJSONArray(i) ?: continue
            if (linkArr.length() < 6) continue

            val linkId = linkArr.optInt(0, -1)
            if (linkId == -1) continue

            linkMap[linkId] = Link(
                linkId = linkId,
                srcNodeId = linkArr.optInt(1, 0),
                srcSlotIdx = linkArr.optInt(2, 0),
                dstNodeId = linkArr.optInt(3, 0),
                dstSlotIdx = linkArr.optInt(4, 0),
                type = linkArr.optString(5, "")
            )
        }

        return linkMap
    }

    /**
     * Convert a single LiteGraph node to API format
     */
    private fun convertNode(
        node: JSONObject,
        linkMap: Map<Int, Link>
    ): Pair<JSONObject?, List<String>> {
        val warnings = mutableListOf<String>()
        val nodeType = node.optString("type", "")
        val nodeId = node.optInt("id", -1)

        if (nodeType.isEmpty()) {
            return null to warnings
        }

        // Get node definition for widget mapping
        val nodeDef = getNodeDefinition(nodeType)

        // Build inputs map
        val inputs = JSONObject()

        // 1. Map widget values to named inputs
        val widgetValues = node.optJSONArray("widgets_values")
        if (widgetValues != null && nodeDef != null) {
            mapWidgetValues(widgetValues, nodeDef.inputs, inputs, nodeType, nodeId, warnings)
        } else if (widgetValues != null && nodeDef == null) {
            // Unknown node - can't map widget values
            DebugLogger.w(TAG, "Node #$nodeId ($nodeType): no definition found, cannot map widget values")
            warnings.add("Node \"$nodeType\" (#$nodeId): inputs could not be fully mapped")
        }

        // 2. Add connections from inputs array
        val nodeInputs = node.optJSONArray("inputs")
        if (nodeInputs != null) {
            for (i in 0 until nodeInputs.length()) {
                val input = nodeInputs.optJSONObject(i) ?: continue
                val inputName = input.optString("name", "")
                val linkId = input.optInt("link", -1)

                if (inputName.isNotEmpty() && linkId != -1) {
                    val link = linkMap[linkId]
                    if (link != null) {
                        // Connection format: [srcNodeId, srcSlotIdx]
                        val connection = JSONArray().apply {
                            put(link.srcNodeId.toString())
                            put(link.srcSlotIdx)
                        }
                        inputs.put(inputName, connection)
                    }
                }
            }
        }

        // Build API node
        val apiNode = JSONObject().apply {
            put("class_type", nodeType)
            put("inputs", inputs)

            // Preserve mode (0=active, 2=muted, 4=bypassed)
            val mode = node.optInt("mode", 0)
            if (mode != 0) {
                put("mode", mode)
            }

            // Add metadata (title only)
            val meta = JSONObject()
            val title = node.optString("title", "")
            if (title.isNotEmpty() && title != nodeType) {
                meta.put("title", title)
            }
            if (meta.length() > 0) {
                put("_meta", meta)
            }
        }

        return apiNode to warnings
    }

    /**
     * Map LiteGraph widgets_values array to named inputs using node definition
     */
    private fun mapWidgetValues(
        widgetValues: JSONArray,
        inputDefs: List<InputDefinition>,
        inputs: JSONObject,
        nodeType: String,
        nodeId: Int,
        warnings: MutableList<String>
    ) {
        // Filter to only editable inputs (not connection-only inputs)
        val editableInputs = inputDefs.filter { !it.forceInput && !isConnectionOnlyType(it.type) }

        var widgetIndex = 0
        for (inputDef in editableInputs) {
            if (widgetIndex >= widgetValues.length()) break

            val value = widgetValues.opt(widgetIndex)

            // Skip control widgets defined in server (rare, but handle it)
            if (inputDef.name == "control_after_generate" ||
                inputDef.name.startsWith("control_")) {
                widgetIndex++
                continue
            }

            // Map the value
            if (value != null && value != JSONObject.NULL) {
                inputs.put(inputDef.name, value)
            }

            widgetIndex++

            // After mapping a seed input, check if next widget value is a control value
            // (control_after_generate is frontend-only and not in server's /object_info)
            if (inputDef.name in SEED_INPUT_NAMES && widgetIndex < widgetValues.length()) {
                val nextValue = widgetValues.opt(widgetIndex)
                if (nextValue is String && nextValue in CONTROL_WIDGET_VALUES) {
                    widgetIndex++
                }
            }
        }
    }

    /**
     * Check if an input type is connection-only (not a widget)
     */
    private fun isConnectionOnlyType(type: String): Boolean {
        // Common connection-only types (not widgets)
        return type in setOf(
            "MODEL", "CLIP", "VAE", "CONDITIONING", "LATENT", "IMAGE",
            "MASK", "CONTROL_NET", "STYLE_MODEL", "CLIP_VISION", "CLIP_VISION_OUTPUT",
            "GLIGEN", "UPSCALE_MODEL", "SAMPLER", "SIGMAS", "NOISE", "GUIDER",
            "*"  // Wildcard type
        )
    }

    /**
     * Extract a Note node to notes array format
     */
    private fun extractNote(node: JSONObject): JSONObject? {
        val nodeId = node.optInt("id", -1)
        if (nodeId == -1) return null

        val title = node.optString("title", "Note")
        val widgetValues = node.optJSONArray("widgets_values")
        val content = widgetValues?.optString(0, "") ?: ""

        return JSONObject().apply {
            put("id", nodeId)
            put("title", title)
            put("content", content)
        }
    }

    /**
     * Convert LiteGraph groups (with bounding boxes) to ComfyChair format (with member_nodes)
     */
    private fun convertGroups(
        groupsArray: JSONArray?,
        nodePositions: Map<Int, NodePosition>
    ): JSONArray {
        val result = JSONArray()
        if (groupsArray == null) return result

        for (i in 0 until groupsArray.length()) {
            val group = groupsArray.optJSONObject(i) ?: continue

            val title = group.optString("title", "Group")
            val bounding = group.optJSONArray("bounding")

            if (bounding == null || bounding.length() < 4) continue

            val groupX = bounding.optDouble(0, 0.0).toFloat()
            val groupY = bounding.optDouble(1, 0.0).toFloat()
            val groupW = bounding.optDouble(2, 0.0).toFloat()
            val groupH = bounding.optDouble(3, 0.0).toFloat()

            // Find all nodes within this group's bounds
            val memberNodes = JSONArray()
            for ((nodeId, pos) in nodePositions) {
                if (isNodeInGroup(pos, groupX, groupY, groupW, groupH)) {
                    memberNodes.put(nodeId.toString())
                }
            }

            // Only add group if it has members
            if (memberNodes.length() > 0) {
                val groupId = group.optInt("id", i + 1)  // Use original id or fallback to index+1
                val groupObj = JSONObject().apply {
                    put("id", groupId)
                    put("title", title)
                    put("member_nodes", memberNodes)
                }
                result.put(groupObj)
            }
        }

        return result
    }

    /**
     * Check if a node is within a group's bounding box
     */
    private fun isNodeInGroup(
        nodePos: NodePosition,
        groupX: Float,
        groupY: Float,
        groupW: Float,
        groupH: Float
    ): Boolean {
        // Check if node's top-left corner is within the group bounds
        // (This is how LiteGraph typically determines group membership)
        return nodePos.x >= groupX &&
               nodePos.y >= groupY &&
               nodePos.x <= groupX + groupW &&
               nodePos.y <= groupY + groupH
    }
}
