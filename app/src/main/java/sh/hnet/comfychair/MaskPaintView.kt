package sh.hnet.comfychair

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Custom view for painting inpainting masks
 * Allows users to draw on top of an image to mark areas for inpainting
 */
class MaskPaintView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paint for drawing the mask
    private val maskPaint = Paint().apply {
        color = Color.argb(180, 255, 0, 0) // Semi-transparent red
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        strokeWidth = 50f
    }

    // Paint for erasing
    private val erasePaint = Paint().apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        strokeWidth = 50f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    // Current path being drawn
    private var currentPath = Path()

    // Bitmap to store the mask
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    // Source image dimensions for scaling
    private var sourceImageWidth: Int = 0
    private var sourceImageHeight: Int = 0

    // Display rect for the image in the view
    private var imageRect = RectF()

    // Track touch state
    private var lastX = 0f
    private var lastY = 0f

    // Eraser mode
    var isEraserMode = false

    // Brush size
    var brushSize: Float = 50f
        set(value) {
            field = value
            maskPaint.strokeWidth = value
            erasePaint.strokeWidth = value
        }

    /**
     * Set the source image dimensions for proper mask alignment
     */
    fun setSourceImageSize(width: Int, height: Int) {
        sourceImageWidth = width
        sourceImageHeight = height
        createMaskBitmap()
    }

    /**
     * Create or recreate the mask bitmap based on source image size
     */
    private fun createMaskBitmap() {
        if (sourceImageWidth > 0 && sourceImageHeight > 0) {
            // Create mask at source image resolution for proper alignment
            maskBitmap?.recycle()
            maskBitmap = Bitmap.createBitmap(sourceImageWidth, sourceImageHeight, Bitmap.Config.ARGB_8888)
            maskCanvas = Canvas(maskBitmap!!)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateImageRect()
    }

    /**
     * Calculate the rect where the image is displayed (fitCenter scaling)
     */
    private fun calculateImageRect() {
        if (sourceImageWidth <= 0 || sourceImageHeight <= 0 || width <= 0 || height <= 0) {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }

        val viewAspect = width.toFloat() / height
        val imageAspect = sourceImageWidth.toFloat() / sourceImageHeight

        if (imageAspect > viewAspect) {
            // Image is wider - fit to width
            val scaledHeight = width / imageAspect
            val top = (height - scaledHeight) / 2
            imageRect.set(0f, top, width.toFloat(), top + scaledHeight)
        } else {
            // Image is taller - fit to height
            val scaledWidth = height * imageAspect
            val left = (width - scaledWidth) / 2
            imageRect.set(left, 0f, left + scaledWidth, height.toFloat())
        }
    }

    /**
     * Convert view coordinates to mask bitmap coordinates
     */
    private fun viewToMaskCoords(viewX: Float, viewY: Float): Pair<Float, Float> {
        if (sourceImageWidth <= 0 || sourceImageHeight <= 0) {
            return Pair(viewX, viewY)
        }

        val maskX = (viewX - imageRect.left) / imageRect.width() * sourceImageWidth
        val maskY = (viewY - imageRect.top) / imageRect.height() * sourceImageHeight
        return Pair(maskX, maskY)
    }

    /**
     * Convert mask bitmap coordinates to view coordinates
     */
    private fun maskToViewCoords(maskX: Float, maskY: Float): Pair<Float, Float> {
        if (sourceImageWidth <= 0 || sourceImageHeight <= 0) {
            return Pair(maskX, maskY)
        }

        val viewX = maskX / sourceImageWidth * imageRect.width() + imageRect.left
        val viewY = maskY / sourceImageHeight * imageRect.height() + imageRect.top
        return Pair(viewX, viewY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val (maskX, maskY) = viewToMaskCoords(event.x, event.y)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.reset()
                currentPath.moveTo(maskX, maskY)
                lastX = maskX
                lastY = maskY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.quadTo(lastX, lastY, (maskX + lastX) / 2, (maskY + lastY) / 2)
                lastX = maskX
                lastY = maskY

                // Draw to mask bitmap
                maskCanvas?.let { canvas ->
                    val paint = if (isEraserMode) erasePaint else maskPaint
                    // Scale brush size to mask coordinates
                    val scaledBrushSize = brushSize * sourceImageWidth / imageRect.width()
                    paint.strokeWidth = scaledBrushSize
                    canvas.drawPath(currentPath, paint)
                }

                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(maskX, maskY)

                // Final draw to mask bitmap
                maskCanvas?.let { canvas ->
                    val paint = if (isEraserMode) erasePaint else maskPaint
                    val scaledBrushSize = brushSize * sourceImageWidth / imageRect.width()
                    paint.strokeWidth = scaledBrushSize
                    canvas.drawPath(currentPath, paint)
                }

                currentPath.reset()
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw the mask bitmap scaled to fit the view
        maskBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, imageRect, null)
        }
    }

    /**
     * Clear all mask strokes
     */
    fun clearMask() {
        maskBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    /**
     * Invert the mask (swap painted and unpainted areas)
     */
    fun invertMask() {
        maskBitmap?.let { bitmap ->
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val alpha = Color.alpha(pixels[i])
                if (alpha > 0) {
                    // Painted area -> make transparent
                    pixels[i] = Color.TRANSPARENT
                } else {
                    // Unpainted area -> make painted
                    pixels[i] = Color.argb(180, 255, 0, 0)
                }
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            invalidate()
        }
    }

    /**
     * Get the mask as a black and white bitmap (white = inpaint area)
     * This is the format expected by ComfyUI
     * Applies feathering to create smooth edges for better blending
     */
    fun getMaskBitmap(): Bitmap? {
        val sourceMask = maskBitmap ?: return null

        // Create a new bitmap with black background and white painted areas
        val resultBitmap = Bitmap.createBitmap(sourceMask.width, sourceMask.height, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(resultBitmap)

        // Fill with black (non-inpaint area)
        resultCanvas.drawColor(Color.BLACK)

        // Get pixels from mask and convert painted areas to white
        val width = sourceMask.width
        val height = sourceMask.height
        val sourcePixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)

        sourceMask.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        resultBitmap.getPixels(resultPixels, 0, width, 0, 0, width, height)

        for (i in sourcePixels.indices) {
            val alpha = Color.alpha(sourcePixels[i])
            if (alpha > 0) {
                // Painted area -> white
                resultPixels[i] = Color.WHITE
            }
            // Non-painted stays black from initial fill
        }

        resultBitmap.setPixels(resultPixels, 0, width, 0, 0, width, height)

        // Apply feathering to soften edges
        return applyFeathering(resultBitmap, FEATHER_RADIUS)
    }

    /**
     * Apply feathering (blur) to mask edges for smooth blending
     * Uses a box blur algorithm for performance
     */
    private fun applyFeathering(mask: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return mask

        val width = mask.width
        val height = mask.height
        val pixels = IntArray(width * height)
        mask.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to grayscale values (0-255)
        val values = IntArray(width * height) { i ->
            Color.red(pixels[i]) // Since it's black/white, R=G=B
        }

        // Horizontal pass
        val tempValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until width) {
                        sum += values[y * width + nx]
                        count++
                    }
                }
                tempValues[y * width + x] = sum / count
            }
        }

        // Vertical pass
        val blurredValues = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until height) {
                        sum += tempValues[ny * width + x]
                        count++
                    }
                }
                blurredValues[y * width + x] = sum / count
            }
        }

        // Convert back to pixels
        for (i in pixels.indices) {
            val v = blurredValues[i]
            pixels[i] = Color.rgb(v, v, v)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        mask.recycle()
        return result
    }

    companion object {
        // Feather radius in pixels - adjust for more/less edge softening
        private const val FEATHER_RADIUS = 8
    }

    /**
     * Set the mask from an existing bitmap
     */
    fun setMaskBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            clearMask()
            return
        }

        // Ensure we have a mask canvas at the right size
        if (maskBitmap == null || maskBitmap!!.width != bitmap.width || maskBitmap!!.height != bitmap.height) {
            setSourceImageSize(bitmap.width, bitmap.height)
        }

        maskCanvas?.let { canvas ->
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Convert black/white mask to display mask (red overlay)
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val brightness = Color.red(pixels[i]) // Assuming grayscale
                if (brightness > 128) {
                    // White area -> painted (inpaint area)
                    pixels[i] = Color.argb(180, 255, 0, 0)
                } else {
                    // Black area -> transparent
                    pixels[i] = Color.TRANSPARENT
                }
            }

            val displayMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            displayMask.setPixels(pixels, 0, width, 0, 0, width, height)
            canvas.drawBitmap(displayMask, 0f, 0f, null)
            displayMask.recycle()
        }

        invalidate()
    }

    /**
     * Check if mask has any painted areas
     */
    fun hasMask(): Boolean {
        val bitmap = maskBitmap ?: return false
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (pixel in pixels) {
            if (Color.alpha(pixel) > 0) {
                return true
            }
        }
        return false
    }

    /**
     * Clean up bitmap resources
     */
    fun recycle() {
        maskBitmap?.recycle()
        maskBitmap = null
        maskCanvas = null
    }
}
