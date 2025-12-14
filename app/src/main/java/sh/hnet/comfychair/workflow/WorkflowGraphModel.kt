package sh.hnet.comfychair.workflow

import androidx.compose.ui.geometry.Offset

/**
 * Represents a single node in the workflow graph
 */
data class WorkflowNode(
    val id: String,
    val classType: String,
    val title: String,
    val category: NodeCategory,
    val inputs: Map<String, InputValue>,
    val templateInputKeys: Set<String>,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f
) {
    /** True if this node has any template input keys */
    val hasTemplateVariables: Boolean get() = templateInputKeys.isNotEmpty()
}

/**
 * Input value can be a literal value or a connection to another node
 */
sealed class InputValue {
    data class Literal(val value: Any) : InputValue()
    data class Connection(val sourceNodeId: String, val outputIndex: Int) : InputValue()
}

/**
 * Represents a connection/edge between nodes
 */
data class WorkflowEdge(
    val sourceNodeId: String,
    val sourceOutputIndex: Int,
    val targetNodeId: String,
    val targetInputName: String
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
 * Complete parsed workflow graph
 */
data class WorkflowGraph(
    val name: String,
    val description: String,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val templateVariables: Set<String>
)

/**
 * UI state for the workflow previewer
 */
data class WorkflowPreviewerUiState(
    val graph: WorkflowGraph? = null,
    val workflowName: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val scale: Float = 1f,
    val offset: Offset = Offset.Zero,
    val showTemplateHighlight: Boolean = true,
    val graphBounds: GraphBounds = GraphBounds(),

    // Field mapping mode
    val isFieldMappingMode: Boolean = false,
    val mappingState: WorkflowMappingState? = null,
    val selectedFieldKey: String? = null,
    val highlightedNodeIds: Set<String> = emptySet(),
    val canConfirmMapping: Boolean = false
)

/**
 * Bounds of the graph for proper centering and zoom calculations
 */
data class GraphBounds(
    val minX: Float = 0f,
    val minY: Float = 0f,
    val maxX: Float = 0f,
    val maxY: Float = 0f
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
}
