package sh.hnet.comfychair.workflow

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
    val outputs: List<String> = emptyList(),  // Output types: ["MODEL", "CLIP", "VAE"]
    val templateInputKeys: Set<String>,
    val mode: Int = 0,  // 0=active, 2=muted, 4=bypassed
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f
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
    val width: Float = 420f,  // Same as NODE_WIDTH
    var height: Float = 0f    // Set by renderer after TextMeasurer measurement
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
    val mappedFields: Map<Pair<String, String>, String> = emptyMap()
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
    /** Immutable - original workflow mappings don't change during editing */
    val mappedFields: Map<Pair<String, String>, String> = emptyMap()
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
        mappedFields = mappedFields
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
            mappedFields = graph.mappedFields
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
 * State for tap-based connection mode.
 * When active, tapping an output highlights valid inputs, then tapping an input creates the connection.
 */
@Stable
data class ConnectionModeState(
    val sourceOutputSlot: SlotPosition,
    val validInputSlots: List<SlotPosition>
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
    val saveDialogTypeDropdownExpanded: Boolean = false,
    val saveDialogName: String = "",
    val saveDialogDescription: String = "",
    val saveDialogNameError: String? = null,
    val saveDialogDescriptionError: String? = null,
    val isSaveValidating: Boolean = false,

    // Error dialogs for save validation
    val showMissingNodesDialog: Boolean = false,
    val missingNodes: List<String> = emptyList(),
    val showMissingFieldsDialog: Boolean = false,
    val missingFields: List<String> = emptyList(),
    val showDuplicateNameDialog: Boolean = false,

    // Discard confirmation
    val showDiscardConfirmation: Boolean = false,
    val discardAction: DiscardAction = DiscardAction.EXIT_EDIT_MODE
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
