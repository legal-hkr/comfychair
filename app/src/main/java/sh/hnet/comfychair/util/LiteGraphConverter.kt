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

    /**
     * Subgraph (ComfyUI component) definition from definitions.subgraphs
     */
    private data class SubgraphDefinition(
        val id: String,
        val name: String,
        val inputs: List<SubgraphPort>,
        val outputs: List<SubgraphPort>,
        val nodes: JSONArray,
        val links: List<SubgraphLink>,
        val inputNodeId: Int,
        val outputNodeId: Int
    )

    /**
     * Input or output port of a subgraph
     */
    private data class SubgraphPort(
        val name: String,
        val type: String,
        val linkIds: List<Int>,
        val slotIndex: Int
    )

    /**
     * Internal link within a subgraph (uses object format, not array)
     */
    private data class SubgraphLink(
        val id: Int,
        val originId: Int,
        val originSlot: Int,
        val targetId: Int,
        val targetSlot: Int,
        val type: String
    )

    /**
     * Result of flattening a subgraph node
     */
    private data class FlattenResult(
        val nodes: Map<String, JSONObject>,
        val nodePositions: Map<Int, NodePosition>,
        val groupTitle: String,
        val memberNodeIds: List<String>,
        val subgraphMetadata: JSONObject,
        val newMaxNodeId: Int,
        val newMaxLinkId: Int
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

        // Parse subgraph definitions first
        val subgraphs = parseSubgraphDefinitions(liteGraphJson)
        if (subgraphs.isNotEmpty()) {
            DebugLogger.i(TAG, "Found ${subgraphs.size} subgraph definitions")
        }

        // Parse links into lookup map
        val linksArray = liteGraphJson.optJSONArray("links")
        val linkMap = parseLinkMap(linksArray)

        // Calculate max node and link IDs for subgraph flattening offsets
        val nodesArray = liteGraphJson.optJSONArray("nodes") ?: JSONArray()
        var maxNodeId = 0
        var maxLinkId = 0
        for (i in 0 until nodesArray.length()) {
            val nodeId = nodesArray.optJSONObject(i)?.optInt("id", 0) ?: 0
            if (nodeId > maxNodeId) maxNodeId = nodeId
        }
        for (link in linkMap.values) {
            if (link.linkId > maxLinkId) maxLinkId = link.linkId
        }

        // Track subgraph output mappings for remapping connections
        // Maps (subgraphNodeId, outputSlot) -> (internalNodeId, internalSlot)
        val subgraphOutputMappings = mutableMapOf<Pair<Int, Int>, Pair<String, Int>>()

        // Parse node positions for group membership
        val nodePositions = mutableMapOf<Int, NodePosition>()

        // Convert nodes
        val apiNodes = JSONObject()
        val notes = JSONArray()
        val subgraphGroups = mutableListOf<JSONObject>()

        // First pass: process subgraph nodes to build output mappings
        var currentNodeOffset = maxNodeId
        var currentLinkOffset = maxLinkId

        for (i in 0 until nodesArray.length()) {
            val node = nodesArray.optJSONObject(i) ?: continue
            val nodeId = node.optInt("id", -1)
            if (nodeId == -1) continue

            val nodeType = node.optString("type", "")

            // Check if this is a subgraph node
            val subgraphDef = subgraphs[nodeType]
            if (subgraphDef != null) {
                // Flatten the subgraph
                val flattenResult = flattenSubgraph(
                    node, subgraphDef, linkMap, currentNodeOffset, currentLinkOffset, warnings
                )

                // Add all flattened nodes
                for ((nodeIdStr, apiNode) in flattenResult.nodes) {
                    apiNodes.put(nodeIdStr, apiNode)
                }

                // Merge positions
                nodePositions.putAll(flattenResult.nodePositions)

                // Create group for flattened nodes
                if (flattenResult.memberNodeIds.isNotEmpty()) {
                    val groupId = currentNodeOffset + 10000 + subgraphGroups.size  // Unique group ID
                    subgraphGroups.add(JSONObject().apply {
                        put("id", groupId)
                        put("title", flattenResult.groupTitle)
                        put("member_nodes", JSONArray(flattenResult.memberNodeIds))
                        put("subgraph_metadata", flattenResult.subgraphMetadata)
                    })
                }

                // Store output mappings for this subgraph node
                // Parse from subgraphMetadata.outputs
                val outputsArray = flattenResult.subgraphMetadata.optJSONArray("outputs")
                if (outputsArray != null) {
                    for (j in 0 until outputsArray.length()) {
                        val out = outputsArray.optJSONObject(j) ?: continue
                        val slot = out.optInt("slot", -1)
                        val internalNodeId = out.optString("internal_node_id", "")
                        val internalSlot = out.optInt("internal_slot", 0)
                        if (slot >= 0 && internalNodeId.isNotEmpty()) {
                            subgraphOutputMappings[Pair(nodeId, slot)] = Pair(internalNodeId, internalSlot)
                        }
                    }
                }

                currentNodeOffset = flattenResult.newMaxNodeId
                currentLinkOffset = flattenResult.newMaxLinkId
                continue
            }

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

        // Second pass: remap connections that reference subgraph outputs
        if (subgraphOutputMappings.isNotEmpty()) {
            remapSubgraphConnections(apiNodes, linkMap, subgraphOutputMappings)
        }

        // Convert groups
        val groupsArray = liteGraphJson.optJSONArray("groups")
        val groups = convertGroups(groupsArray, nodePositions)

        // Add subgraph groups
        for (sg in subgraphGroups) {
            groups.put(sg)
        }

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
     * Remap connections in converted nodes that reference subgraph outputs
     */
    private fun remapSubgraphConnections(
        apiNodes: JSONObject,
        linkMap: Map<Int, Link>,
        subgraphOutputMappings: Map<Pair<Int, Int>, Pair<String, Int>>
    ) {
        // For each API node, check all its connection inputs
        val nodeIds = apiNodes.keys().asSequence().toList()
        for (nodeIdStr in nodeIds) {
            val apiNode = apiNodes.optJSONObject(nodeIdStr) ?: continue
            val inputs = apiNode.optJSONObject("inputs") ?: continue

            val inputNames = inputs.keys().asSequence().toList()
            for (inputName in inputNames) {
                val value = inputs.opt(inputName)
                if (value is JSONArray && value.length() == 2) {
                    // This is a connection: [srcNodeId, srcSlot]
                    val srcNodeIdStr = value.optString(0, "")
                    val srcSlot = value.optInt(1, 0)
                    val srcNodeId = srcNodeIdStr.toIntOrNull() ?: continue

                    // Check if this references a subgraph output
                    val mapping = subgraphOutputMappings[Pair(srcNodeId, srcSlot)]
                    if (mapping != null) {
                        // Replace with internal node reference
                        val newConnection = JSONArray().apply {
                            put(mapping.first)  // internal node ID
                            put(mapping.second) // internal slot
                        }
                        inputs.put(inputName, newConnection)
                        DebugLogger.d(TAG, "Remapped connection: $srcNodeIdStr:$srcSlot -> ${mapping.first}:${mapping.second}")
                    }
                }
            }
        }
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
     * Parse definitions.subgraphs section into a map
     */
    private fun parseSubgraphDefinitions(json: JSONObject): Map<String, SubgraphDefinition> {
        val subgraphs = mutableMapOf<String, SubgraphDefinition>()
        val definitions = json.optJSONObject("definitions") ?: return subgraphs
        val subgraphsArray = definitions.optJSONArray("subgraphs") ?: return subgraphs

        for (i in 0 until subgraphsArray.length()) {
            val sg = subgraphsArray.optJSONObject(i) ?: continue
            val id = sg.optString("id", "")
            if (id.isEmpty()) continue

            val name = sg.optString("name", "Subgraph")
            val inputNodeId = sg.optJSONObject("inputNode")?.optInt("id", -10) ?: -10
            val outputNodeId = sg.optJSONObject("outputNode")?.optInt("id", -20) ?: -20

            // Parse inputs
            val inputsArray = sg.optJSONArray("inputs") ?: JSONArray()
            val inputs = mutableListOf<SubgraphPort>()
            for (j in 0 until inputsArray.length()) {
                val inp = inputsArray.optJSONObject(j) ?: continue
                val linkIdsArray = inp.optJSONArray("linkIds") ?: JSONArray()
                val linkIds = (0 until linkIdsArray.length()).map { linkIdsArray.optInt(it) }
                inputs.add(SubgraphPort(
                    name = inp.optString("name", ""),
                    type = inp.optString("type", "*"),
                    linkIds = linkIds,
                    slotIndex = j
                ))
            }

            // Parse outputs
            val outputsArray = sg.optJSONArray("outputs") ?: JSONArray()
            val outputs = mutableListOf<SubgraphPort>()
            for (j in 0 until outputsArray.length()) {
                val out = outputsArray.optJSONObject(j) ?: continue
                val linkIdsArray = out.optJSONArray("linkIds") ?: JSONArray()
                val linkIds = (0 until linkIdsArray.length()).map { linkIdsArray.optInt(it) }
                outputs.add(SubgraphPort(
                    name = out.optString("name", ""),
                    type = out.optString("type", "*"),
                    linkIds = linkIds,
                    slotIndex = j
                ))
            }

            // Parse internal links (object format, not array)
            val linksArray = sg.optJSONArray("links") ?: JSONArray()
            val links = mutableListOf<SubgraphLink>()
            for (j in 0 until linksArray.length()) {
                val link = linksArray.optJSONObject(j) ?: continue
                links.add(SubgraphLink(
                    id = link.optInt("id", -1),
                    originId = link.optInt("origin_id", 0),
                    originSlot = link.optInt("origin_slot", 0),
                    targetId = link.optInt("target_id", 0),
                    targetSlot = link.optInt("target_slot", 0),
                    type = link.optString("type", "*")
                ))
            }

            val nodes = sg.optJSONArray("nodes") ?: JSONArray()

            subgraphs[id] = SubgraphDefinition(
                id = id,
                name = name,
                inputs = inputs,
                outputs = outputs,
                nodes = nodes,
                links = links,
                inputNodeId = inputNodeId,
                outputNodeId = outputNodeId
            )

            DebugLogger.d(TAG, "Parsed subgraph '$name': ${nodes.length()} nodes, ${links.size} links, ${inputs.size} inputs, ${outputs.size} outputs")
        }

        return subgraphs
    }

    /**
     * Flatten a subgraph node into its internal nodes.
     *
     * @param subgraphNode The main workflow node that references the subgraph
     * @param subgraphDef The subgraph definition
     * @param mainLinkMap Links from main workflow (for resolving external connections)
     * @param nodeIdOffset Offset to apply to internal node IDs to avoid collisions
     * @param linkIdOffset Offset to apply to internal link IDs
     * @return FlattenResult with converted nodes, positions, and metadata
     */
    private fun flattenSubgraph(
        subgraphNode: JSONObject,
        subgraphDef: SubgraphDefinition,
        mainLinkMap: Map<Int, Link>,
        nodeIdOffset: Int,
        linkIdOffset: Int,
        warnings: MutableList<String>
    ): FlattenResult {
        val subgraphNodeId = subgraphNode.optInt("id", -1)
        DebugLogger.d(TAG, "Flattening subgraph '${subgraphDef.name}' (node #$subgraphNodeId) with offset $nodeIdOffset")

        val apiNodes = mutableMapOf<String, JSONObject>()
        val nodePositions = mutableMapOf<Int, NodePosition>()
        val memberNodeIds = mutableListOf<String>()
        var maxNodeId = nodeIdOffset
        var maxLinkId = linkIdOffset

        // Build ID remapping tables
        val nodeIdRemap = mutableMapOf<Int, Int>()  // old internal ID -> new global ID
        for (i in 0 until subgraphDef.nodes.length()) {
            val node = subgraphDef.nodes.optJSONObject(i) ?: continue
            val oldId = node.optInt("id", -1)
            if (oldId == -1) continue
            val newId = oldId + nodeIdOffset
            nodeIdRemap[oldId] = newId
            if (newId > maxNodeId) maxNodeId = newId
        }

        // Remap link IDs
        val linkIdRemap = mutableMapOf<Int, Int>()  // old link ID -> new link ID
        for (link in subgraphDef.links) {
            val newLinkId = link.id + linkIdOffset
            linkIdRemap[link.id] = newLinkId
            if (newLinkId > maxLinkId) maxLinkId = newLinkId
        }

        // Parse subgraph node's external inputs (connections coming from main workflow)
        // These will replace connections from virtual input node (-10)
        val externalInputConnections = mutableMapOf<Int, Pair<Int, Int>>()  // slotIndex -> (srcNodeId, srcSlot)
        val subgraphNodeInputs = subgraphNode.optJSONArray("inputs")
        if (subgraphNodeInputs != null) {
            for (i in 0 until subgraphNodeInputs.length()) {
                val input = subgraphNodeInputs.optJSONObject(i) ?: continue
                val linkId = input.optInt("link", -1)
                if (linkId != -1) {
                    val link = mainLinkMap[linkId]
                    if (link != null) {
                        externalInputConnections[i] = Pair(link.srcNodeId, link.srcSlotIdx)
                    }
                }
            }
        }

        // Parse subgraph node's widget values for unconnected inputs
        val widgetValues = subgraphNode.optJSONArray("widgets_values")
        val proxyWidgets = subgraphNode.optJSONObject("properties")?.optJSONArray("proxyWidgets")

        // Build widget value map: inputName -> value
        val widgetValueMap = mutableMapOf<String, Any>()
        if (widgetValues != null && proxyWidgets != null) {
            for (i in 0 until proxyWidgets.length()) {
                val proxy = proxyWidgets.optJSONArray(i) ?: continue
                val inputName = proxy.optString(1, "")
                if (inputName.isNotEmpty() && i < widgetValues.length()) {
                    val value = widgetValues.opt(i)
                    if (value != null && value != JSONObject.NULL) {
                        widgetValueMap[inputName] = value
                    }
                }
            }
        }

        // Build link lookup for internal links: (targetNodeId, targetSlot) -> link
        val internalLinksByTarget = subgraphDef.links
            .filter { it.targetId != subgraphDef.outputNodeId }  // Exclude links to output node
            .groupBy { Pair(it.targetId, it.targetSlot) }

        // Build lookup for links FROM input node: slotIndex -> list of internal links
        val inputNodeLinks = mutableMapOf<Int, MutableList<SubgraphLink>>()
        for (link in subgraphDef.links) {
            if (link.originId == subgraphDef.inputNodeId) {
                inputNodeLinks.getOrPut(link.originSlot) { mutableListOf() }.add(link)
            }
        }

        // Find output mappings: which internal nodes connect to output virtual node
        // subgraph output slot -> (internal node ID, slot)
        val outputMappings = mutableMapOf<Int, Pair<Int, Int>>()
        for (link in subgraphDef.links) {
            if (link.targetId == subgraphDef.outputNodeId) {
                val remappedOriginId = nodeIdRemap[link.originId] ?: link.originId
                outputMappings[link.targetSlot] = Pair(remappedOriginId, link.originSlot)
            }
        }

        // Process each internal node
        for (i in 0 until subgraphDef.nodes.length()) {
            val node = subgraphDef.nodes.optJSONObject(i) ?: continue
            val oldNodeId = node.optInt("id", -1)
            if (oldNodeId == -1) continue

            val nodeType = node.optString("type", "")

            // Skip reroute nodes
            if (nodeType in REROUTE_NODE_TYPES) continue

            // Skip note nodes (handled separately in main conversion)
            if (nodeType in NOTE_NODE_TYPES) continue

            val newNodeId = nodeIdRemap[oldNodeId] ?: (oldNodeId + nodeIdOffset)
            val newNodeIdStr = newNodeId.toString()
            memberNodeIds.add(newNodeIdStr)

            // Store position for group membership
            val pos = node.optJSONArray("pos")
            val size = node.optJSONArray("size")
            if (pos != null && pos.length() >= 2) {
                nodePositions[newNodeId] = NodePosition(
                    id = newNodeId,
                    x = pos.optDouble(0, 0.0).toFloat(),
                    y = pos.optDouble(1, 0.0).toFloat(),
                    width = size?.optDouble(0, 200.0)?.toFloat() ?: 200f,
                    height = size?.optDouble(1, 100.0)?.toFloat() ?: 100f
                )
            }

            // Get node definition for widget mapping
            val nodeDef = getNodeDefinition(nodeType)

            // Build inputs map
            val inputs = JSONObject()

            // 1. Map widget values from internal node
            val internalWidgetValues = node.optJSONArray("widgets_values")
            if (internalWidgetValues != null && nodeDef != null) {
                mapWidgetValues(internalWidgetValues, nodeDef.inputs, inputs, nodeType, newNodeId, warnings)
            }

            // 2. Add connections from internal node's inputs array
            val nodeInputs = node.optJSONArray("inputs")
            if (nodeInputs != null) {
                for (j in 0 until nodeInputs.length()) {
                    val input = nodeInputs.optJSONObject(j) ?: continue
                    val inputName = input.optString("name", "")
                    val linkId = input.optInt("link", -1)

                    if (inputName.isEmpty() || linkId == -1) continue

                    // Find the internal link
                    val internalLink = subgraphDef.links.find { it.id == linkId }
                    if (internalLink != null) {
                        if (internalLink.originId == subgraphDef.inputNodeId) {
                            // Connection from virtual input node - resolve to external connection or widget value
                            val slotIndex = internalLink.originSlot
                            val subgraphInput = subgraphDef.inputs.getOrNull(slotIndex)

                            // Check if there's an external connection (takes priority over widget value)
                            val externalConn = externalInputConnections[slotIndex]
                            if (externalConn != null) {
                                // Use external connection
                                val connection = JSONArray().apply {
                                    put(externalConn.first.toString())
                                    put(externalConn.second)
                                }
                                inputs.put(inputName, connection)
                            } else if (subgraphInput != null && widgetValueMap.containsKey(subgraphInput.name)) {
                                // Use widget value from subgraph node
                                inputs.put(inputName, widgetValueMap[subgraphInput.name])
                            }
                        } else {
                            // Internal connection - remap source node ID
                            val remappedSrcId = nodeIdRemap[internalLink.originId] ?: internalLink.originId
                            val connection = JSONArray().apply {
                                put(remappedSrcId.toString())
                                put(internalLink.originSlot)
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

                // Preserve mode
                val mode = node.optInt("mode", 0)
                if (mode != 0) {
                    put("mode", mode)
                }

                // Add metadata
                val meta = JSONObject()
                val title = node.optString("title", "")
                if (title.isNotEmpty() && title != nodeType) {
                    meta.put("title", title)
                }
                if (meta.length() > 0) {
                    put("_meta", meta)
                }
            }

            apiNodes[newNodeIdStr] = apiNode
        }

        // Build subgraph metadata for future compact view support
        val subgraphMetadata = JSONObject().apply {
            put("original_subgraph_id", subgraphDef.id)
            put("display_mode", "expanded")  // Future: "collapsed" for compact view

            // Store input/output definitions for future compact view
            val inputsArray = JSONArray()
            for (input in subgraphDef.inputs) {
                inputsArray.put(JSONObject().apply {
                    put("name", input.name)
                    put("type", input.type)
                    put("slot", input.slotIndex)
                })
            }
            put("inputs", inputsArray)

            val outputsArray = JSONArray()
            for ((slot, mapping) in outputMappings) {
                val output = subgraphDef.outputs.getOrNull(slot)
                outputsArray.put(JSONObject().apply {
                    put("name", output?.name ?: "output_$slot")
                    put("type", output?.type ?: "*")
                    put("slot", slot)
                    put("internal_node_id", mapping.first.toString())
                    put("internal_slot", mapping.second)
                })
            }
            put("outputs", outputsArray)
        }

        DebugLogger.d(TAG, "Flattened subgraph '${subgraphDef.name}': ${apiNodes.size} nodes, ${outputMappings.size} outputs")

        return FlattenResult(
            nodes = apiNodes,
            nodePositions = nodePositions,
            groupTitle = subgraphDef.name,
            memberNodeIds = memberNodeIds,
            subgraphMetadata = subgraphMetadata,
            newMaxNodeId = maxNodeId,
            newMaxLinkId = maxLinkId
        )
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
