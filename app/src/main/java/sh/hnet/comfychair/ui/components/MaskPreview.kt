package sh.hnet.comfychair.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import sh.hnet.comfychair.viewmodel.MaskPathData

/**
 * A read-only composable that displays a source image with mask overlay.
 * This is used for previewing the combined source image and mask without allowing editing.
 * The image is scaled to fill the container (center crop) and clipped to bounds.
 *
 * @param sourceImage The source image to display
 * @param maskPaths List of paths that make up the current mask (in image coordinates)
 * @param modifier Modifier for the canvas
 */
@Composable
fun MaskPreview(
    sourceImage: Bitmap?,
    maskPaths: List<MaskPathData>,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Calculate image rect for centerCrop scaling (fills the canvas, may crop image)
    fun calculateImageRect(canvasWidth: Int, canvasHeight: Int, imageWidth: Int, imageHeight: Int): Rect {
        if (imageWidth <= 0 || imageHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return Rect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        }

        val canvasAspect = canvasWidth.toFloat() / canvasHeight
        val imageAspect = imageWidth.toFloat() / imageHeight

        return if (imageAspect > canvasAspect) {
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

    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                canvasSize = size
            }
    ) {
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
        if (sourceImage != null && imgRect.width > 0 && imgRect.height > 0) {
            val maskPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(180, 255, 0, 0) // Semi-transparent red
                style = android.graphics.Paint.Style.STROKE
                strokeJoin = android.graphics.Paint.Join.ROUND
                strokeCap = android.graphics.Paint.Cap.ROUND
                isAntiAlias = true
            }

            drawContext.canvas.nativeCanvas.save()

            // Clip to canvas bounds
            drawContext.canvas.nativeCanvas.clipRect(0f, 0f, size.width, size.height)

            // Transform to image coordinates
            drawContext.canvas.nativeCanvas.translate(imgRect.left, imgRect.top)
            drawContext.canvas.nativeCanvas.scale(
                imgRect.width / sourceImage.width,
                imgRect.height / sourceImage.height
            )

            // Draw completed paths (non-eraser only)
            maskPaths.forEach { pathData ->
                if (!pathData.isEraser) {
                    maskPaint.strokeWidth = pathData.brushSize
                    drawContext.canvas.nativeCanvas.drawPath(pathData.path, maskPaint)
                }
            }

            drawContext.canvas.nativeCanvas.restore()
        }
    }
}
