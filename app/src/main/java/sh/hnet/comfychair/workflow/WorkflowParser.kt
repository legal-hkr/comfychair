package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.model.CustomFields
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.Obfuscator

/**
 * Parses ComfyUI workflow JSON into a WorkflowGraph
 *
 * @param logDetails Log parsing details; disabled for thumbnails, which parse every workflow in
 *        the list on each refresh and would otherwise push everything else out of the debug log.
 *        Warnings are always logged.
 */
class WorkflowParser(private val logDetails: Boolean = true) {

    companion object {
        private const val TAG = "WorkflowParser"
    }

    private fun logD(message: String) {
        if (logDetails) DebugLogger.d(TAG, message)
    }

    private fun logI(message: String) {
        if (logDetails) DebugLogger.i(TAG, message)
    }

    /**
     * Parse workflow JSON content into a WorkflowGraph
     */
    fun parse(jsonContent: String, workflowName: String = "", workflowDescription: String = ""): WorkflowGraph {
        logD("Parsing workflow: name=$workflowName")
        val json = JSONObject(jsonContent)

        val nodes = mutableListOf<WorkflowNode>()
        val edges = mutableListOf<WorkflowEdge>()
        val groups = mutableListOf<WorkflowGroup>()
        val allTemplateVars = mutableSetOf<String>()
        val mappedFields = mutableMapOf<Pair<String, String>, String>()

        // Parse groups if present (at root level)
        if (json.has("groups")) {
            logD("Found 'groups' key in JSON at root level")
            val groupsArray = json.optJSONArray("groups")
            if (groupsArray != null) {
                logD("Groups array has ${groupsArray.length()} entries")
                groups.addAll(parseGroups(groupsArray))
            } else {
                DebugLogger.w(TAG, "'groups' key exists but is not a JSON array")
            }
        } else {
            logD("No 'groups' key found in JSON root")
        }

        // Determine the structure: our custom format has "nodes" object, ComfyUI API format has nodes at top level
        val nodesJson = if (json.has("nodes") && json.get("nodes") is JSONObject) {
            // Our custom workflow format: { "name": "...", "description": "...", "nodes": { ... } }
            json.getJSONObject("nodes")
        } else {
            // Standard ComfyUI API format: nodes at top level
            json
        }

        // Parse each node - workflow JSON has node IDs as keys
        val nodeIds = nodesJson.keys()
        for (nodeId in nodeIds) {
            val nodeValue = nodesJson.get(nodeId)
            if (nodeValue !is JSONObject) continue

            val nodeJson = nodeValue
            val classType = nodeJson.optString("class_type", "Unknown")
            val meta = nodeJson.optJSONObject("_meta")
            val title = meta?.optString("title")?.takeIf { it.isNotEmpty() } ?: classType

            val inputsJson = nodeJson.optJSONObject("inputs") ?: JSONObject()
            val inputs = parseInputs(inputsJson)

            // Find inputs that contain template placeholders like {{positive_prompt}}
            val templateKeys = mutableSetOf<String>()
            inputs.forEach { (key, value) ->
                if (value is InputValue.Literal) {
                    val stringValue = value.value.toString()
                    // Check for {{placeholder}} patterns
                    val placeholderRegex = """\{\{(\w+)\}\}""".toRegex()
                    val matches = placeholderRegex.findAll(stringValue)
                    for (match in matches) {
                        val placeholderName = match.groupValues[1]
                        // Store the input key that contains a template placeholder
                        templateKeys.add(key)
                        // Also track the placeholder name for the graph
                        allTemplateVars.add(placeholderName)
                        // Track mapped field: (nodeId, inputName) -> placeholderName
                        mappedFields[Pair(nodeId, key)] = placeholderName
                    }
                }
            }

            // Also check for keys that are known template targets (for workflows using direct key names)
            inputs.keys.filter { key ->
                TemplateKeyRegistry.isTemplateKey(key)
            }.forEach { key ->
                templateKeys.add(key)
                allTemplateVars.add(key)
            }

            // Parse node mode (0=active, 2=muted, 4=bypassed)
            val mode = nodeJson.optInt("mode", 0)
            if (mode != 0) {
                logD("Parsed node $nodeId with mode=$mode (${if (mode == 4) "bypassed" else if (mode == 2) "muted" else "unknown"})")
            }

            // Parse manual position from _meta.position array [x, y]
            val positionArray = meta?.optJSONArray("position")
            val hasManualPosition = positionArray != null && positionArray.length() >= 2
            val manualX = if (hasManualPosition) positionArray!!.optDouble(0, 0.0).toFloat() else 0f
            val manualY = if (hasManualPosition) positionArray!!.optDouble(1, 0.0).toFloat() else 0f
            if (hasManualPosition) {
                logD("Parsed node $nodeId with manual position: ($manualX, $manualY)")
            }

            nodes.add(
                WorkflowNode(
                    id = nodeId,
                    classType = classType,
                    title = title,
                    category = nodeCategoryOf(classType),
                    inputs = sortInputsForLayout(inputs),
                    templateInputKeys = templateKeys,
                    mode = mode,
                    x = manualX,
                    y = manualY,
                    hasManualPosition = hasManualPosition
                )
            )

            // Extract edges from connection inputs
            inputs.forEach { (inputName, value) ->
                if (value is InputValue.Connection) {
                    edges.add(
                        WorkflowEdge(
                            sourceNodeId = value.sourceNodeId,
                            sourceOutputIndex = value.outputIndex,
                            targetNodeId = nodeId,
                            targetInputName = inputName
                        )
                    )
                }
            }
        }

        // Parse notes if present (at root level)
        val notes = if (json.has("notes")) {
            logD("Found 'notes' key in JSON at root level")
            val notesArray = json.optJSONArray("notes")
            if (notesArray != null) {
                logD("Notes array has ${notesArray.length()} entries")
                parseNotes(notesArray)
            } else {
                DebugLogger.w(TAG, "'notes' key exists but is not a JSON array")
                emptyList()
            }
        } else {
            logD("No 'notes' key found in JSON root")
            emptyList()
        }

        // Parse manual placement mode flag from root level
        val isManualPlacementMode = json.optBoolean("manual_placement", false)
        if (isManualPlacementMode) {
            logD("Workflow has manual_placement mode enabled")
        }

        logD("Parsed workflow: ${nodes.size} nodes, ${edges.size} edges, ${groups.size} groups, ${notes.size} notes, ${allTemplateVars.size} template vars, ${mappedFields.size} mapped fields")

        return WorkflowGraph(
            name = workflowName,
            description = workflowDescription,
            nodes = nodes,
            edges = edges,
            groups = groups,
            notes = notes,
            templateVariables = allTemplateVars,
            mappedFields = mappedFields,
            isManualPlacementMode = isManualPlacementMode,
            customFields = CustomFields.fromJson(json.optJSONArray(CustomFields.FIELDS_KEY)),
            fieldLabels = CustomFields.labelsFromJson(json.optJSONObject(CustomFields.LABELS_KEY))
        )
    }

    /**
     * Parse groups array from workflow JSON.
     *
     * New format (ComfyChair): { id, title, member_nodes }
     * Legacy format (ComfyUI): { id, title, bounding } - groups without member_nodes are skipped
     *
     * Groups with no members are skipped.
     */
    private fun parseGroups(groupsArray: JSONArray): List<WorkflowGroup> {
        logD("parseGroups: parsing ${groupsArray.length()} group entries")
        val result = mutableListOf<WorkflowGroup>()

        for (i in 0 until groupsArray.length()) {
            val groupJson = groupsArray.optJSONObject(i)
            if (groupJson == null) {
                DebugLogger.w(TAG, "parseGroups: entry $i is not a JSON object, skipping")
                continue
            }

            val id = groupJson.optInt("id", -1)
            if (id < 0) {
                DebugLogger.w(TAG, "parseGroups: entry $i has invalid id ($id), skipping")
                continue
            }

            val title = groupJson.optString("title", "Group")
            val hasMemberNodes = groupJson.has("member_nodes")
            val hasBounding = groupJson.has("bounding")

            logD("parseGroups: entry $i - id=$id, title=${Obfuscator.title(title)}, has member_nodes=$hasMemberNodes, has bounding=$hasBounding")

            // Parse member node IDs (required for new format)
            val memberNodeIds = groupJson.optJSONArray("member_nodes")?.let { arr ->
                val ids = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
                logD("parseGroups: group $id member_nodes array has ${arr.length()} entries, parsed ${ids.size} valid IDs: $ids")
                ids
            } ?: run {
                logD("parseGroups: group $id has no member_nodes array (legacy format?)")
                emptySet()
            }

            // Skip groups with no members (including legacy groups without member_nodes)
            if (memberNodeIds.isEmpty()) {
                DebugLogger.w(TAG, "parseGroups: skipping group $id ${Obfuscator.title(title)}: has no members")
                continue
            }

            result.add(
                WorkflowGroup(
                    id = id,
                    title = title,
                    memberNodeIds = memberNodeIds
                )
            )
            logI("parseGroups: successfully parsed group $id ${Obfuscator.title(title)} with ${memberNodeIds.size} members")
        }

        logI("parseGroups: parsed ${result.size} valid groups out of ${groupsArray.length()} entries")
        return result
    }

    /**
     * Parse notes array from workflow JSON.
     *
     * Format: { id, title, content }
     */
    private fun parseNotes(notesArray: JSONArray): List<WorkflowNote> {
        logD("parseNotes: parsing ${notesArray.length()} note entries")
        val result = mutableListOf<WorkflowNote>()

        for (i in 0 until notesArray.length()) {
            val noteJson = notesArray.optJSONObject(i)
            if (noteJson == null) {
                DebugLogger.w(TAG, "parseNotes: entry $i is not a JSON object, skipping")
                continue
            }

            val id = noteJson.optInt("id", -1)
            if (id < 0) {
                DebugLogger.w(TAG, "parseNotes: entry $i has invalid id ($id), skipping")
                continue
            }

            val title = noteJson.optString("title", "Note")
            val content = noteJson.optString("content", "")

            // Parse manual position from position array [x, y]
            val positionArray = noteJson.optJSONArray("position")
            val hasManualPosition = positionArray != null && positionArray.length() >= 2
            val manualX = if (hasManualPosition) positionArray!!.optDouble(0, 0.0).toFloat() else 0f
            val manualY = if (hasManualPosition) positionArray!!.optDouble(1, 0.0).toFloat() else 0f

            result.add(
                WorkflowNote(
                    id = id,
                    title = title,
                    content = content,
                    x = manualX,
                    y = manualY,
                    hasManualPosition = hasManualPosition
                )
            )
            if (hasManualPosition) {
                logD("parseNotes: parsed note id=$id title=${Obfuscator.title(title)} with manual position: ($manualX, $manualY)")
            } else {
                logD("parseNotes: parsed note id=$id title=${Obfuscator.title(title)} (${content.length} chars)")
            }
        }

        logI("parseNotes: parsed ${result.size} notes out of ${notesArray.length()} entries")
        return result
    }

    /**
     * Parse inputs from a node's inputs JSON object
     */
    private fun parseInputs(inputsJson: JSONObject): Map<String, InputValue> {
        val result = mutableMapOf<String, InputValue>()

        for (key in inputsJson.keys()) {
            val value = inputsJson.get(key)
            result[key] = when {
                // Connection format: ["nodeId", outputIndex]
                value is JSONArray && value.length() == 2 -> {
                    try {
                        InputValue.Connection(
                            sourceNodeId = value.getString(0),
                            outputIndex = value.getInt(1)
                        )
                    } catch (e: Exception) {
                        // If parsing fails, treat as literal
                        DebugLogger.w(TAG, "Failed to parse connection for input '$key': ${Obfuscator.error(e)}")
                        InputValue.Literal(value.toString())
                    }
                }
                else -> InputValue.Literal(literalValue(value))
            }
        }

        return result
    }

    /**
     * A literal input value, kept whole: the graph is saved back from it (long file names, expressions and prompts
     * included). The canvas shortens what doesn't fit when it draws it.
     */
    private fun literalValue(value: Any): Any {
        return when (value) {
            is String, is Number, is Boolean -> value
            else -> value.toString()
        }
    }

}
