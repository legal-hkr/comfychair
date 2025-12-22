package sh.hnet.comfychair.workflow

import androidx.compose.ui.graphics.Color

/**
 * Color mappings for slot types and node categories, matching ComfyUI's themes.
 * Colors extracted from ComfyUI's dark.json and light.json theme files.
 */
object SlotColors {

    // Dark theme slot colors (from ComfyUI dark.json node_slot)
    private val darkSlotColors = mapOf(
        "MODEL" to Color(0xFFB39DDB),
        "CLIP" to Color(0xFFFFD500),
        "CLIP_VISION" to Color(0xFFA8DADC),
        "CLIP_VISION_OUTPUT" to Color(0xFFAD7452),
        "CONDITIONING" to Color(0xFFFFA931),
        "CONTROL_NET" to Color(0xFF6EE7B7),
        "IMAGE" to Color(0xFF64B5F6),
        "LATENT" to Color(0xFFFF9CF9),
        "MASK" to Color(0xFF81C784),
        "STYLE_MODEL" to Color(0xFFC2FFAE),
        "VAE" to Color(0xFFFF6E6E),
        "NOISE" to Color(0xFFB0B0B0),
        "GUIDER" to Color(0xFF66FFFF),
        "SAMPLER" to Color(0xFFECB4B4),
        "SIGMAS" to Color(0xFFCDFFCD),
        "TAESD" to Color(0xFFDCC274),
        "AUDIO" to Color(0xFF47DFCF),
        "STRING" to Color(0xFF8AE234),
        "INT" to Color(0xFF29699C),
        "FLOAT" to Color(0xFF9A9A69),
        "BOOLEAN" to Color(0xFFA1A100)
    )

    // Light theme slot colors (from ComfyUI light.json node_slot)
    private val lightSlotColors = mapOf(
        "MODEL" to Color(0xFF7E57C2),
        "CLIP" to Color(0xFFFFA726),
        "CLIP_VISION" to Color(0xFF5C6BC0),
        "CLIP_VISION_OUTPUT" to Color(0xFF8D6E63),
        "CONDITIONING" to Color(0xFFEF5350),
        "CONTROL_NET" to Color(0xFF66BB6A),
        "IMAGE" to Color(0xFF42A5F5),
        "LATENT" to Color(0xFFAB47BC),
        "MASK" to Color(0xFF9CCC65),
        "STYLE_MODEL" to Color(0xFFD4E157),
        "VAE" to Color(0xFFFF7043),
        "NOISE" to Color(0xFFB0B0B0),
        "GUIDER" to Color(0xFF66FFFF),
        "SAMPLER" to Color(0xFFECB4B4),
        "SIGMAS" to Color(0xFFCDFFCD),
        "TAESD" to Color(0xFFDCC274),
        "AUDIO" to Color(0xFF47DFCF),
        "STRING" to Color(0xFF8AE234),
        "INT" to Color(0xFF29699C),
        "FLOAT" to Color(0xFF9A9A69),
        "BOOLEAN" to Color(0xFFA1A100)
    )

    /**
     * Color pair for node header and body
     */
    data class NodeColorPair(
        val header: Color,
        val body: Color
    )

    // Dark theme node category colors (header / body pairs from ComfyUI)
    private val darkCategoryColors = mapOf(
        NodeCategory.LOADER to NodeColorPair(Color(0xFF222233), Color(0xFF333355)),     // Dark blue
        NodeCategory.ENCODER to NodeColorPair(Color(0xFF332922), Color(0xFF593930)),    // Brown (default for CLIP)
        NodeCategory.SAMPLER to NodeColorPair(Color(0xFF443322), Color(0xFF665533)),    // Yellow
        NodeCategory.LATENT to NodeColorPair(Color(0xFF332233), Color(0xFF553355)),     // Purple
        NodeCategory.PROCESS to NodeColorPair(Color(0xFF2a363b), Color(0xFF3f5159)),    // Pale blue
        NodeCategory.INPUT to NodeColorPair(Color(0xFF223333), Color(0xFF335555)),      // Cyan
        NodeCategory.OUTPUT to NodeColorPair(Color(0xFF332233), Color(0xFF553355)),     // Purple
        NodeCategory.OTHER to NodeColorPair(Color(0xFF171718), Color(0xFF262729))       // No color
    )

    // Light theme node category colors (header / body pairs from ComfyUI)
    private val lightCategoryColors = mapOf(
        NodeCategory.LOADER to NodeColorPair(Color(0xFF9999bb), Color(0xFFb5b5d2)),     // Dark blue
        NodeCategory.ENCODER to NodeColorPair(Color(0xFFbba799), Color(0xFFd6bab2)),    // Brown (default for CLIP)
        NodeCategory.SAMPLER to NodeColorPair(Color(0xFFccb399), Color(0xFFddd2bb)),    // Yellow
        NodeCategory.LATENT to NodeColorPair(Color(0xFFbb99bb), Color(0xFFd2b5d2)),     // Purple
        NodeCategory.PROCESS to NodeColorPair(Color(0xFFa5b7bf), Color(0xFFc3cfd4)),    // Pale blue
        NodeCategory.INPUT to NodeColorPair(Color(0xFF99bbbb), Color(0xFFb5d2d2)),      // Cyan
        NodeCategory.OUTPUT to NodeColorPair(Color(0xFFbb99bb), Color(0xFFd2b5d2)),     // Purple
        NodeCategory.OTHER to NodeColorPair(Color(0xFFd9d9d9), Color(0xFFffffff))       // No color
    )

    // Special colors for positive/negative prompt nodes
    private val darkPositivePrompt = NodeColorPair(Color(0xFF223322), Color(0xFF335533))   // Green
    private val darkNegativePrompt = NodeColorPair(Color(0xFF332222), Color(0xFF553333))   // Red
    private val lightPositivePrompt = NodeColorPair(Color(0xFF99bb99), Color(0xFFb5d2b5))  // Green
    private val lightNegativePrompt = NodeColorPair(Color(0xFFbb9999), Color(0xFFd2b5b5))  // Red

    /**
     * Get slot colors map for the current theme.
     */
    fun getSlotColorMap(isDarkTheme: Boolean): Map<String, Color> {
        return if (isDarkTheme) darkSlotColors else lightSlotColors
    }

    /**
     * Get node category colors map for the current theme.
     */
    fun getCategoryColorMap(isDarkTheme: Boolean): Map<NodeCategory, NodeColorPair> {
        return if (isDarkTheme) darkCategoryColors else lightCategoryColors
    }

    /**
     * Get color pair for positive prompt nodes.
     */
    fun getPositivePromptColors(isDarkTheme: Boolean): NodeColorPair {
        return if (isDarkTheme) darkPositivePrompt else lightPositivePrompt
    }

    /**
     * Get color pair for negative prompt nodes.
     */
    fun getNegativePromptColors(isDarkTheme: Boolean): NodeColorPair {
        return if (isDarkTheme) darkNegativePrompt else lightNegativePrompt
    }

    /**
     * Get color for a specific slot type.
     * Returns null if the slot type is unknown.
     */
    fun getSlotColor(slotType: String?, isDarkTheme: Boolean): Color? {
        if (slotType == null) return null
        val colors = if (isDarkTheme) darkSlotColors else lightSlotColors
        return colors[slotType.uppercase()]
    }

    /**
     * Get color pair for a node category.
     * Falls back to OTHER category color if not found.
     */
    fun getCategoryColor(category: NodeCategory, isDarkTheme: Boolean): NodeColorPair {
        val colors = if (isDarkTheme) darkCategoryColors else lightCategoryColors
        return colors[category] ?: colors[NodeCategory.OTHER]!!
    }
}
