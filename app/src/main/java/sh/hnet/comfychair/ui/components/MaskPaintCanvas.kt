package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import sh.hnet.comfychair.viewmodel.MaskPathData

/**
 * Scale mode for image display in MaskPaintCanvas
 */
enum class ImageScaleMode {
    /** Scale to fill the canvas, cropping edges if necessary (like ContentScale.Crop) */
    CENTER_CROP,
    /** Scale to fit entirely within the canvas, with letterboxing if necessary (like ContentScale.Fit) */
    FIT_CENTER
}

/**
 * Composable that displays an image with a mask painting overlay.
 * Uses Canvas API for direct drawing with native Path objects.
 *
 * @param sourceImage The source image to display and paint over
 * @param maskPaths List of paths that make up the current mask (in image coordinates)
 * @param brushSize Current brush size for painting (in canvas coordinates)
 * @param isEraserMode Whether eraser mode is active
 * @param onPathAdded Callback when a new path is completed (path in image coordinates)
 * @param onLongPress Callback when user long presses on the canvas
 * @param scaleMode How to scale the image within the canvas
 * @param modifier Modifier for the canvas
 */
@Composable
fun MaskPaintCanvas(
    sourceImage: Bitmap?,
    maskPaths: List<MaskPathData>,
    brushSize: Float,
    isEraserMode: Boolean,
    onPathAdded: (AndroidPath, Boolean, Float) -> Unit,
    onLongPress: (() -> Unit)? = null,
    scaleMode: ImageScaleMode = ImageScaleMode.CENTER_CROP,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var currentPath by remember { mutableStateOf<AndroidPath?>(null) }
    var imageRect by remember { mutableStateOf(Rect.Zero) }
    var lastPoint by remember { mutableStateOf(Offset.Zero) }
    // Counter to force recomposition when path is modified
    var pathUpdateCounter by remember { mutableStateOf(0L) }

    // Calculate image rect based on scale mode
    fun calculateImageRect(canvasWidth: Int, canvasHeight: Int, imageWidth: Int, imageHeight: Int): Rect {
        if (imageWidth <= 0 || imageHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return Rect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        }

        val canvasAspect = canvasWidth.toFloat() / canvasHeight
        val imageAspect = imageWidth.toFloat() / imageHeight

        return when (scaleMode) {
            ImageScaleMode.CENTER_CROP -> {
                if (imageAspect > canvasAspect) {
                    // Image is wider - scale to fill height, crop sides
                    val scaledWidth = canvasHeight * imageAspect
                    val left = (canvasWidth - scaledWidth) / 2
                    Rect(left, 0f, left + scaledWidth, canvasHeight.toFloat())
                } else {
                    // Image is taller - scale to fill width, crop top/bottom
                    val scaledHeight = canvasWidth / imageAspect
                    val top = (canvasHeight - scaledHeight) / 2
                    Rect(0f, top, canvasWidth.toFloat(), top + scaledHeight)
                }
            }
            ImageScaleMode.FIT_CENTER -> {
                if (imageAspect > canvasAspect) {
                    // Image is wider - fit to width
                    val scaledHeight = canvasWidth / imageAspect
                    val top = (canvasHeight - scaledHeight) / 2
                    Rect(0f, top, canvasWidth.toFloat(), top + scaledHeight)
                } else {
                    // Image is taller - fit to height
                    val scaledWidth = canvasHeight * imageAspect
                    val left = (canvasWidth - scaledWidth) / 2
                    Rect(left, 0f, left + scaledWidth, canvasHeight.toFloat())
                }
            }
        }
    }

    // Convert canvas coordinates to image coordinates
    fun canvasToImageCoords(canvasX: Float, canvasY: Float, imgRect: Rect, img: Bitmap?): Offset {
        if (img == null || imgRect.width <= 0 || imgRect.height <= 0) {
            return Offset(canvasX, canvasY)
        }

        val imageX = (canvasX - imgRect.left) / imgRect.width * img.width
        val imageY = (canvasY - imgRect.top) / imgRect.height * img.height
        return Offset(imageX, imageY)
    }

    // Scale brush size from canvas to image coordinates
    fun scaleBrushSizeToImage(canvasBrushSize: Float, imgRect: Rect, img: Bitmap?): Float {
        if (img == null || imgRect.width <= 0) {
            return canvasBrushSize
        }
        return canvasBrushSize * img.width / imgRect.width
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { size ->
                canvasSize = size
                sourceImage?.let { img ->
                    imageRect = calculateImageRect(size.width, size.height, img.width, img.height)
                }
            }
            .pointerInput(onLongPress) {
                if (onLongPress == null) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val longPressTimeout = 500L

                    // Wait for up or movement, checking if long press time has passed
                    var pointer = down
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointer.id }

                        if (change == null || !change.pressed) {
                            // Finger lifted - check if it was a long press without much movement
                            break
                        }

                        // Check if finger moved too much (would be a drag, not long press)
                        val moved = (change.position - down.position).getDistance() > 20f
                        if (moved) {
                            break
                        }

                        // Check if long press timeout reached
                        if (System.currentTimeMillis() - downTime > longPressTimeout) {
                            onLongPress()
                            // Wait for release after long press
                            waitForUpOrCancellation()
                            break
                        }

                        pointer = change
                    }
                }
            }
            .pointerInput(brushSize, isEraserMode, sourceImage) {
                if (sourceImage == null) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        val imgRect = calculateImageRect(
                            canvasSize.width, canvasSize.height,
                            sourceImage.width, sourceImage.height
                        )
                        val imageCoords = canvasToImageCoords(offset.x, offset.y, imgRect, sourceImage)
                        currentPath = AndroidPath().apply {
                            moveTo(imageCoords.x, imageCoords.y)
                        }
                        lastPoint = imageCoords
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val imgRect = calculateImageRect(
                            canvasSize.width, canvasSize.height,
                            sourceImage.width, sourceImage.height
                        )
                        val imageCoords = canvasToImageCoords(change.position.x, change.position.y, imgRect, sourceImage)
                        // Use quadTo for smoother curves
                        currentPath?.quadTo(
                            lastPoint.x, lastPoint.y,
                            (imageCoords.x + lastPoint.x) / 2,
                            (imageCoords.y + lastPoint.y) / 2
                        )
                        lastPoint = imageCoords
                        // Increment counter to force canvas redraw
                        pathUpdateCounter++
                    },
                    onDragEnd = {
                        currentPath?.let { path ->
                            val imgRect = calculateImageRect(
                                canvasSize.width, canvasSize.height,
                                sourceImage.width, sourceImage.height
                            )
                            path.lineTo(lastPoint.x, lastPoint.y)
                            val imageBrushSize = scaleBrushSizeToImage(brushSize, imgRect, sourceImage)
                            onPathAdded(path, isEraserMode, imageBrushSize)
                        }
                        currentPath = null
                    },
                    onDragCancel = {
                        currentPath = null
                    }
                )
            }
    ) {
        // Reference pathUpdateCounter to trigger redraw when path changes during drag
        @Suppress("UNUSED_EXPRESSION")
        pathUpdateCounter

        // Update image rect if size changed
        val imgRect = sourceImage?.let { img ->
            calculateImageRect(size.width.toInt(), size.height.toInt(), img.width, img.height)
        } ?: Rect.Zero

        // Draw source image
        sourceImage?.let { img ->
            drawImage(
                image = img.asImageBitmap(),
                dstOffset = IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
                dstSize = IntSize(imgRect.width.toInt(), imgRect.height.toInt())
            )
        }

        // Draw mask paths using native canvas for proper transformation
        val maskPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(180, 255, 0, 0) // Semi-transparent red
            style = android.graphics.Paint.Style.STROKE
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
            isAntiAlias = true
        }

        val erasePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(100, 128, 128, 128) // Gray for eraser preview
            style = android.graphics.Paint.Style.STROKE
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
            isAntiAlias = true
        }

        drawContext.canvas.nativeCanvas.save()

        // Clip to image bounds to prevent mask drawing outside image
        if (sourceImage != null && imgRect.width > 0 && imgRect.height > 0) {
            drawContext.canvas.nativeCanvas.clipRect(
                imgRect.left,
                imgRect.top,
                imgRect.right,
                imgRect.bottom
            )
            drawContext.canvas.nativeCanvas.translate(imgRect.left, imgRect.top)
            drawContext.canvas.nativeCanvas.scale(
                imgRect.width / sourceImage.width,
                imgRect.height / sourceImage.height
            )
        }

        // Draw completed paths
        maskPaths.forEach { pathData ->
            if (!pathData.isEraser) {
                maskPaint.strokeWidth = pathData.brushSize
                drawContext.canvas.nativeCanvas.drawPath(pathData.path, maskPaint)
            }
        }

        // Draw current path being drawn
        currentPath?.let { path ->
            val paint = if (isEraserMode) erasePaint else maskPaint
            paint.strokeWidth = scaleBrushSizeToImage(brushSize, imgRect, sourceImage)
            drawContext.canvas.nativeCanvas.drawPath(path, paint)
        }

        drawContext.canvas.nativeCanvas.restore()
    }
}
