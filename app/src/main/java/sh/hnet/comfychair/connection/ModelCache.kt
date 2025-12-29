package sh.hnet.comfychair.connection

/**
 * Centralized cache for model lists fetched from ComfyUI server.
 * Populated from /object_info response on connection.
 *
 * This cache is shared across all generation screens via ConnectionManager,
 * eliminating redundant API calls and enabling user-triggered refresh.
 */
data class ModelCache(
    val checkpoints: List<String> = emptyList(),
    val unets: List<String> = emptyList(),
    val vaes: List<String> = emptyList(),
    val clips: List<String> = emptyList(),
    val loras: List<String> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val lastError: String? = null
)
