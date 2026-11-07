package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.model.CustomField
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import sh.hnet.comfychair.WorkflowType

/**
 * Represents a single node in the workflow graph
 */
@Stable
data class WorkflowNode(
    val id: String,
    val classType: String,
    val title: String,
    val category: NodeCategory,
    val inputs: Map<String, InputValue>,
    val outputs: List<OutputSlot> = emptyList(),  // Output slots with type and name
    val templateInputKeys: Set<String>,
    val mode: Int = 0,  // 0=active, 2=muted, 4=bypassed
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f,
    var hasManualPosition: Boolean = false  // True if position was set manually (from JSON or drag)
) {
    /** True if this node has any template input keys */
    val hasTemplateVariables: Boolean get() = templateInputKeys.isNotEmpty()
    /** True if node is bypassed (mode=4) - execution skips this node but continues */
    val isBypassed: Boolean get() = mode == 4
    /** True if node is muted (mode=2) - execution stops at this node */
    val isMuted: Boolean get() = mode == 2
}

/**
 * Input value can be a literal value or a connection to another node
 */
sealed class InputValue {
    @Immutable
    data class Literal(val value: Any) : InputValue()
    @Immutable
    data class Connection(val sourceNodeId: String, val outputIndex: Int) : InputValue()
    /** Unconnected connection slot - will be converted to Connection when wired up */
    @Immutable
    data class UnconnectedSlot(val slotType: String) : InputValue()
}

/**
 * Sort inputs so Literals come first, then Connection types.
 * This ordering is required by the node layout model which positions
 * outputs after literal inputs in the connection area.
 */
fun sortInputsForLayout(inputs: Map<String, InputValue>): Map<String, InputValue> {
    return inputs.entries
        .sortedBy { (_, value) ->
            when (value) {
                is InputValue.Literal -> 0
                is InputValue.Connection -> 1
                is InputValue.UnconnectedSlot -> 1
            }
        }
        .associate { it.key to it.value }
}

/**
 * Represents a connection/edge between nodes
 */
@Immutable
data class WorkflowEdge(
    val sourceNodeId: String,
    val sourceOutputIndex: Int,
    val targetNodeId: String,
    val targetInputName: String,
    val slotType: String? = null  // Type for edge coloring (e.g., "MODEL", "CLIP", "LATENT")
)

/**
 * Node categories for color coding
 */
enum class NodeCategory {
    LOADER,      // CheckpointLoaderSimple, UNETLoader, VAELoader, CLIPLoader, LoraLoaderModelOnly
    ENCODER,     // CLIPTextEncode
    SAMPLER,     // KSampler, KSamplerAdvanced
    LATENT,      // EmptyLatentImage, EmptySD3LatentImage, EmptyHunyuanLatentVideo
    PROCESS,     // VAEDecode, ModelSamplingSD3, CreateVideo, ImageScale
    INPUT,       // LoadImage
    OUTPUT,      // SaveImage, SaveVideo
    OTHER        // Fallback
}

/** A node's category by its class type, for its colours (canvas, node browser). */
fun nodeCategoryOf(classType: String): NodeCategory {
    return when {
        // Loaders
        classType.contains("Loader", ignoreCase = true) -> NodeCategory.LOADER

        // Text encoders
        classType.contains("CLIPTextEncode", ignoreCase = true) ||
        classType.contains("TextEncode", ignoreCase = true) -> NodeCategory.ENCODER

        // Samplers
        classType.contains("Sampler", ignoreCase = true) -> NodeCategory.SAMPLER

        // Latent image creation
        classType.contains("EmptyLatent", ignoreCase = true) ||
        classType.contains("Empty") && classType.contains("Latent", ignoreCase = true) -> NodeCategory.LATENT

        // Input nodes
        classType.contains("LoadImage", ignoreCase = true) -> NodeCategory.INPUT

        // Output nodes
        classType.contains("Save", ignoreCase = true) ||
        classType.contains("Preview", ignoreCase = true) -> NodeCategory.OUTPUT

        // Processing nodes
        classType.contains("Decode", ignoreCase = true) ||
        classType.contains("Encode", ignoreCase = true) ||
        classType.contains("Scale", ignoreCase = true) ||
        classType.contains("Create", ignoreCase = true) ||
        classType.contains("Sampling", ignoreCase = true) ||
        classType.contains("ModelMerge", ignoreCase = true) -> NodeCategory.PROCESS

        else -> NodeCategory.OTHER
    }
}

/**
 * Represents a group container in the workflow graph.
 * Groups visually organize related nodes together.
 *
 * Note: Bounds (x, y, width, height) are computed from member nodes, not stored.
 * Use [RenderedGroup] for rendering with computed bounds.
 */
@Immutable
data class WorkflowGroup(
    val id: Int,
    val title: String,
    val memberNodeIds: Set<String>
)

/**
 * A group with computed rendering bounds.
 * Created by [WorkflowLayoutEngine.calculateRenderedGroups] from [WorkflowGroup] and node positions.
 */
@Immutable
data class RenderedGroup(
    val group: WorkflowGroup,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val id: Int get() = group.id
    val title: String get() = group.title
    val memberNodeIds: Set<String> get() = group.memberNodeIds
}

/**
 * Represents a markdown note in the workflow graph.
 * Notes look like nodes but are separate entities for documentation purposes.
 * They participate in layout and can be grouped with nodes.
 *
 * Note IDs are integers (like groups), prefixed as "note:N" when stored in group memberNodeIds.
 */
@Stable
data class WorkflowNote(
    val id: Int,
    val title: String,
    val content: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 480f,  // Same as NODE_WIDTH (6 grid cells)
    var height: Float = 0f,   // Set by renderer after TextMeasurer measurement
    val hasManualPosition: Boolean = false  // True if position was set manually (from JSON or drag)
) {
    companion object {
        /** Convert note ID to member ID string for group membership */
        fun noteIdToMemberId(noteId: Int): String = "note:$noteId"

        /** Extract note ID from member ID string, or null if not a note */
        fun memberIdToNoteId(memberId: String): Int? =
            memberId.removePrefix("note:").toIntOrNull()?.takeIf { memberId.startsWith("note:") }

        /** Check if a member ID represents a note */
        fun isNoteMemberId(memberId: String): Boolean = memberId.startsWith("note:")
    }
}

/**
 * Complete parsed workflow graph
 */
@Stable
data class WorkflowGraph(
    val name: String,
    val description: String,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val groups: List<WorkflowGroup> = emptyList(),
    val notes: List<WorkflowNote> = emptyList(),
    val templateVariables: Set<String>,
    /**
     * Maps (nodeId, inputName) pairs to placeholder names for fields that have {{...}} values.
     * Used to show "UI: X" labels even when saved edits replace placeholders with literal values.
     */
    val mappedFields: Map<Pair<String, String>, String> = emptyMap(),
    /**
     * Whether manual placement mode is enabled for this workflow.
     * When true, nodes/notes can be dragged to custom positions on a grid.
     * When false, auto-layout is used.
     */
    val isManualPlacementMode: Boolean = false,
    /** Fields the user made from node values (spec §18) */
    val customFields: List<CustomField> = emptyList(),
    /** The workflow's own labels for built-in fields, by field key */
    val fieldLabels: Map<String, String> = emptyMap(),
    /**
     * The workflow's subgraph definitions as stored (JSON text of its `subgraphs` object, [Subgraphs]), carried as they
     * are; instances show their values as their inputs (exposed). Null without subgraphs.
     */
    val subgraphs: String? = null
)

/**
 * Mutable version of WorkflowGraph for editing operations.
 * Changes are tracked and can be converted back to immutable WorkflowGraph.
 */
data class MutableWorkflowGraph(
    var name: String,
    var description: String,
    val nodes: MutableList<WorkflowNode>,
    val edges: MutableList<WorkflowEdge>,
    val groups: MutableList<WorkflowGroup>,
    val notes: MutableList<WorkflowNote>,
    val templateVariables: MutableSet<String>,
    /** The workflow's mappings; linking a mapped value removes its mapping (spec §17.2.3) */
    var mappedFields: Map<Pair<String, String>, String> = emptyMap(),
    /** Whether manual placement mode is enabled for this workflow */
    var isManualPlacementMode: Boolean = false,
    var customFields: List<CustomField> = emptyList(),
    var fieldLabels: Map<String, String> = emptyMap(),
    var subgraphs: String? = null
) {
    /**
     * Convert to immutable WorkflowGraph
     */
    fun toImmutable(): WorkflowGraph = WorkflowGraph(
        name = name,
        description = description,
        nodes = nodes.toList(),
        edges = edges.toList(),
        groups = groups.toList(),
        notes = notes.toList(),
        templateVariables = templateVariables.toSet(),
        mappedFields = mappedFields,
        isManualPlacementMode = isManualPlacementMode,
        customFields = customFields,
        fieldLabels = fieldLabels,
        subgraphs = subgraphs
    )

    companion object {
        /**
         * Create a mutable copy from an immutable WorkflowGraph
         */
        fun fromImmutable(graph: WorkflowGraph): MutableWorkflowGraph = MutableWorkflowGraph(
            name = graph.name,
            description = graph.description,
            nodes = graph.nodes.toMutableList(),
            edges = graph.edges.toMutableList(),
            groups = graph.groups.toMutableList(),
            notes = graph.notes.toMutableList(),
            templateVariables = graph.templateVariables.toMutableSet(),
            mappedFields = graph.mappedFields,
            isManualPlacementMode = graph.isManualPlacementMode,
            customFields = graph.customFields,
            fieldLabels = graph.fieldLabels,
            subgraphs = graph.subgraphs
        )
    }
}

/**
 * Represents a slot (input or output) position on a node for connection editing
 */
@Immutable
data class SlotPosition(
    val nodeId: String,
    val slotName: String,
    val isOutput: Boolean,
    val outputIndex: Int = 0,
    val center: Offset,
    val slotType: String?
)

/**
 * Direction of connection mode - determines which slot types are source vs target
 */
enum class ConnectionDirection {
    /** User tapped output, selecting input targets */
    OUTPUT_TO_INPUT,
    /** User tapped input, selecting output targets */
    INPUT_TO_OUTPUT
}

/**
 * State for tap-based connection mode.
 * Unified to handle both directions symmetrically:
 * - OUTPUT_TO_INPUT: tapping an output highlights valid inputs
 * - INPUT_TO_OUTPUT: tapping an input highlights valid outputs
 */
@Stable
/**
 * Picking a value on the graph for a custom field in Map fields (spec §18.3): [fieldId] null for a new field, else the
 * field getting another value; [targets] the values that can be picked.
 */
data class FieldPick(
    val fieldId: String?,
    val targets: List<SlotPosition>
)

data class ConnectionModeState(
    val direction: ConnectionDirection,
    val sourceSlot: SlotPosition,
    val validTargetSlots: List<SlotPosition>
)

/**
 * UI state for the workflow editor
 */
@Stable
data class WorkflowEditorUiState(
    val graph: WorkflowGraph? = null,
    val workflowName: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
    val graphBounds: GraphBounds = GraphBounds(),

    // Field mapping mode
    val isFieldMappingMode: Boolean = false,
    // Mapping while importing (Back asks to cancel the import); otherwise while saving from the editor (Back returns there)
    val isImportMapping: Boolean = false,
    val mappingState: WorkflowMappingState? = null,
    val selectedFieldKey: String? = null,
    val highlightedNodeIds: Set<String> = emptySet(),
    val canConfirmMapping: Boolean = false,

    // Node attribute editing mode
    val isEditingNode: Boolean = false,
    val selectedNodeForEditing: WorkflowNode? = null,
    val nodeAttributeEdits: Map<String, Map<String, Any>> = emptyMap(),
    val nodeDefinitions: Map<String, NodeTypeDefinition> = emptyMap(),
    val editableInputNames: Set<String> = emptySet(),
    val savedScaleBeforeEditing: Float? = null,
    val savedOffsetBeforeEditing: Offset? = null,

    // Graph editing mode (add/delete/duplicate nodes, edit connections)
    val isEditMode: Boolean = false,
    val selectedNodeIds: Set<String> = emptySet(),
    val selectedNoteIds: Set<Int> = emptySet(),
    val hasUnsavedChanges: Boolean = false,
    val showNodeBrowser: Boolean = false,
    val nodeInsertPosition: Offset? = null,

    // Connection mode (tap-based connection editing)
    val connectionModeState: ConnectionModeState? = null,
    /** A pending connection's target is a value the app fills: the editor asks before linking it (spec §17.2.3). */
    val pendingMappedLink: PendingMappedLink? = null,
    /** Map fields: picking the value a new field fills, or another value for a field (spec §18.3) */
    val fieldPick: FieldPick? = null,

    // Long-press connection mode (filtered node browser)
    val longPressSourceSlot: SlotPosition? = null,
    val showCompatibleNodeBrowser: Boolean = false,

    // Input selection dialog (when multiple compatible inputs exist)
    val showInputSelectionDialog: Boolean = false,
    val inputSelectionNodeType: NodeTypeDefinition? = null,
    val inputSelectionCompatibleInputs: List<InputDefinition> = emptyList(),

    // Output selection dialog (when multiple compatible outputs exist - reverse connection)
    val showOutputSelectionDialog: Boolean = false,
    val outputSelectionNodeType: NodeTypeDefinition? = null,
    val outputSelectionCompatibleOutputs: List<OutputSlot> = emptyList(),

    // Create mode (creating new workflow from scratch)
    val isCreateMode: Boolean = false,

    // Edit-existing mode (structural editing of existing user workflow)
    val isEditExistingMode: Boolean = false,
    val editingWorkflowId: String? = null,
    val editingWorkflowType: WorkflowType? = null,
    val originalWorkflowName: String = "",
    val originalWorkflowDescription: String = "",
    val viewingWorkflowIsBuiltIn: Boolean = false,

    // Save dialog state (shown when Done is pressed in create mode)
    val showSaveDialog: Boolean = false,
    val saveDialogSelectedType: WorkflowType? = null,
    val saveDialogName: String = "",
    val saveDialogDescription: String = "",
    val saveDialogNameError: String? = null,
    val isSaveValidating: Boolean = false,

    // Error dialogs for save validation
    val showMissingNodesDialog: Boolean = false,
    val missingNodes: List<String> = emptyList(),
    val showMissingFieldsDialog: Boolean = false,
    val missingFields: List<String> = emptyList(),

    // Discard confirmation
    val showDiscardConfirmation: Boolean = false,
    val discardAction: DiscardAction = DiscardAction.EXIT_EDIT_MODE,

    // Manual node placement drag state
    val draggingNodeId: String? = null,
    val dragOffset: Offset = Offset.Zero,
    val justDroppedNodeId: String? = null,  // Set after drop to skip animation

    // Manual note placement drag state
    val draggingNoteId: Int? = null,
    val justDroppedNoteId: Int? = null,  // Set after drop to skip animation

    // Per-workflow manual placement mode
    val isManualPlacementMode: Boolean = false,
    val showDisableManualPlacementConfirmation: Boolean = false
)

/**
 * Action to take after confirming discard
 */
enum class DiscardAction {
    /** Exit edit mode but stay in editor (return to view mode) */
    EXIT_EDIT_MODE,
    /** Close the editor entirely */
    CLOSE_EDITOR
}

/**
 * Bounds of the graph for proper centering and zoom calculations
 */
@Immutable
data class GraphBounds(
    val minX: Float = 0f,
    val minY: Float = 0f,
    val maxX: Float = 0f,
    val maxY: Float = 0f
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}

/**
 * Linking a value the app fills from a generation-screen field asks first: linking ends that mapping.
 *
 * @param target The slot that completes the connection (an input, or an output when linking from the value)
 * @param inputName The value's input name; [fieldName] the app's field; [sourceTitle] the node it will come from
 */
@Immutable
data class PendingMappedLink(
    val target: SlotPosition,
    val inputName: String,
    val fieldName: String,
    val sourceTitle: String,
    /** Linking to a node not added yet (Add a node, from the socket's menu) */
    val addNode: Boolean = false,
    /** The value is its custom field's last one: linking deletes the field */
    val deletesField: Boolean = false
)
