package sh.hnet.comfychair.workflow

import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The graph's only colours that aren't theme roles (spec §12.2): ComfyUI's colours, with ComfyUI's hue and a tone set
 * per theme, so nodes and wires stay recognisable but sit calmly on the canvas in both themes. Computed in CIELAB LCh:
 * L* is the same tone HCT uses, so the tones (and with them the contrast) are the spec's; hue and chroma differ from
 * HCT slightly.
 */
object GraphColors {

    /** ComfyUI's node colours (LGraphCanvas.node_colors, the body colour) by category; OTHER has none. */
    private val categorySources = mapOf(
        NodeCategory.LOADER to 0xFF333355.toInt(),    // blue
        NodeCategory.ENCODER to 0xFF593930.toInt(),   // brown
        NodeCategory.SAMPLER to 0xFF665533.toInt(),   // yellow
        NodeCategory.LATENT to 0xFF553355.toInt(),    // purple
        NodeCategory.PROCESS to 0xFF3F5159.toInt(),   // pale blue
        NodeCategory.INPUT to 0xFF335555.toInt(),     // cyan
        NodeCategory.OUTPUT to 0xFF553355.toInt()     // purple
    )
    private const val POSITIVE_SOURCE = 0xFF335533.toInt()  // green
    private const val NEGATIVE_SOURCE = 0xFF553333.toInt()  // red

    /** ComfyUI's link colours by slot type (its dark palette, the most saturated). */
    private val slotSources = mapOf(
        "MODEL" to 0xFFB39DDB.toInt(),
        "CLIP" to 0xFFFFD500.toInt(),
        "CLIP_VISION" to 0xFFA8DADC.toInt(),
        "CLIP_VISION_OUTPUT" to 0xFFAD7452.toInt(),
        "CONDITIONING" to 0xFFFFA931.toInt(),
        "CONTROL_NET" to 0xFF6EE7B7.toInt(),
        "IMAGE" to 0xFF64B5F6.toInt(),
        "LATENT" to 0xFFFF9CF9.toInt(),
        "MASK" to 0xFF81C784.toInt(),
        "STYLE_MODEL" to 0xFFC2FFAE.toInt(),
        "VAE" to 0xFFFF6E6E.toInt(),
        "NOISE" to 0xFFB0B0B0.toInt(),
        "GUIDER" to 0xFF66FFFF.toInt(),
        "SAMPLER" to 0xFFECB4B4.toInt(),
        "SIGMAS" to 0xFFCDFFCD.toInt(),
        "TAESD" to 0xFFDCC274.toInt(),
        "AUDIO" to 0xFF47DFCF.toInt(),
        "STRING" to 0xFF8AE234.toInt(),
        "INT" to 0xFF29699C.toInt(),
        "FLOAT" to 0xFF9A9A69.toInt(),
        "BOOLEAN" to 0xFFA1A100.toInt()
    )

    /** Below this chroma a source counts as grey: no tint (the caller uses a neutral role). */
    private const val GREY_CHROMA = 8.0

    private val palettes = ConcurrentHashMap<Boolean, Palette>()

    /** The colours for a theme, computed once per theme and cached. */
    fun palette(dark: Boolean): Palette = palettes.getOrPut(dark) { Palette(dark) }

    class Palette internal constructor(private val dark: Boolean) {
        private val slots = ConcurrentHashMap<String, Any>()
        private val categories = categorySources.mapValues { (_, source) -> titleTint(source, dark) }

        /** Title bar tint of a category; null: none (use `surfaceContainerHighest`). */
        fun categoryTint(category: NodeCategory): Color? = categories[category]

        val positiveTint: Color? = titleTint(POSITIVE_SOURCE, dark)
        val negativeTint: Color? = titleTint(NEGATIVE_SOURCE, dark)

        /** A node's title bar tint: prompts by their title (green, red), the rest by category. */
        fun titleTintOf(node: WorkflowNode): Color? {
            val title = node.title.lowercase()
            return when {
                "positive" in title -> positiveTint
                "negative" in title -> negativeTint
                else -> categoryTint(node.category)
            }
        }

        /**
         * Sockets, wires and port labels of a slot type; an unknown type gets a stable hue from its name. Null for a
         * grey source or no type (use `onSurfaceVariant`).
         */
        fun slot(type: String?): Color? {
            if (type.isNullOrEmpty()) return null
            val key = type.uppercase()
            val cached = slots.getOrPut(key) { slotColor(key, dark) ?: NONE }
            return cached as? Color
        }
    }

    private val NONE = Any()

    private fun titleTint(source: Int, dark: Boolean): Color? {
        val (_, c, h) = toLch(source)
        if (c < GREY_CHROMA) return null
        return fromLch(if (dark) 30.0 else 90.0, c.coerceAtMost(36.0), h)
    }

    private fun slotColor(type: String, dark: Boolean): Color? {
        val source = slotSources[type]
        val (c, h) = if (source != null) {
            val (_, c, h) = toLch(source)
            if (c < GREY_CHROMA) return null
            c to h
        } else {
            48.0 to Math.floorMod(type.hashCode(), 360).toDouble()
        }
        return fromLch(if (dark) 75.0 else 40.0, c.coerceIn(36.0, 60.0), h)
    }

    // CIELAB (D65) conversions

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(v: Double): Double = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    private fun f(t: Double): Double = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116

    private fun fInverse(t: Double): Double = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)

    /** L*, chroma and hue (degrees) of an ARGB colour. */
    internal fun toLch(argb: Int): Triple<Double, Double, Double> {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val l = 116 * f(y) - 16
        val a = 500 * (f(x) - f(y))
        val bb = 200 * (f(y) - f(z))
        val hue = Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
        return Triple(l, hypot(a, bb), hue)
    }

    /** sRGB channels of an LCh colour, or null when it's outside sRGB. */
    private fun toRgb(l: Double, c: Double, h: Double): DoubleArray? {
        val a = c * cos(Math.toRadians(h))
        val b = c * sin(Math.toRadians(h))
        val fy = (l + 16) / 116
        val x = XN * fInverse(fy + a / 500)
        val y = YN * fInverse(fy)
        val z = ZN * fInverse(fy - b / 200)
        val rgb = doubleArrayOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        )
        if (rgb.any { it < -1e-4 || it > 1 + 1e-4 }) return null
        return DoubleArray(3) { gamma(rgb[it].coerceIn(0.0, 1.0)) }
    }

    /** The colour of a tone and hue with the highest chroma up to [chroma] that sRGB can show (tone kept exact). */
    internal fun fromLch(tone: Double, chroma: Double, hue: Double): Color {
        var rgb = toRgb(tone, chroma, hue)
        if (rgb == null) {
            var low = 0.0
            var high = chroma
            repeat(20) {
                val mid = (low + high) / 2
                if (toRgb(tone, mid, hue) != null) low = mid else high = mid
            }
            rgb = toRgb(tone, low, hue) ?: toRgb(tone, 0.0, hue)!!
        }
        val (r, g, b) = rgb.map { (it * 255).roundToInt() }
        return Color(red = r, green = g, blue = b)
    }
}
