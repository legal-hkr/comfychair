package sh.hnet.comfychair.cache

import android.graphics.Bitmap
import android.graphics.Path
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import sh.hnet.comfychair.viewmodel.MaskPathData

/**
 * Singleton that holds state for the Mask Editor activity.
 * Used to pass non-serializable data (Bitmap, Path objects) between activities.
 * Uses Compose State for reactive updates.
 */
object MaskEditorStateHolder {

    /** Source image to paint mask on */
    var sourceImage: Bitmap? = null
        private set

    /** Current mask paths - observable for Compose recomposition */
    private val _maskPaths = mutableStateListOf<MaskPathData>()
    val maskPaths: List<MaskPathData> get() = _maskPaths

    /** Current brush size - observable for Compose recomposition */
    private val _brushSize = mutableFloatStateOf(50f)
    var brushSize: Float
        get() = _brushSize.floatValue
        private set(value) { _brushSize.floatValue = value }

    /** Current eraser mode - observable for Compose recomposition */
    private val _isEraserMode = mutableStateOf(false)
    var isEraserMode: Boolean
        get() = _isEraserMode.value
        private set(value) { _isEraserMode.value = value }

    /** Callback for when a new path is added */
    var onPathAdded: ((Path, Boolean, Float) -> Unit)? = null
        private set

    /** Callback for clearing the mask */
    var onClearMask: (() -> Unit)? = null
        private set

    /** Callback for inverting the mask */
    var onInvertMask: (() -> Unit)? = null
        private set

    /** Callback for brush size change */
    var onBrushSizeChange: ((Float) -> Unit)? = null
        private set

    /** Callback for eraser mode change */
    var onEraserModeChange: ((Boolean) -> Unit)? = null
        private set

    /**
     * Initialize the state holder before launching MaskEditorActivity.
     */
    fun initialize(
        sourceImage: Bitmap,
        maskPaths: List<MaskPathData>,
        brushSize: Float,
        isEraserMode: Boolean,
        onPathAdded: (Path, Boolean, Float) -> Unit,
        onClearMask: () -> Unit,
        onInvertMask: () -> Unit,
        onBrushSizeChange: (Float) -> Unit,
        onEraserModeChange: (Boolean) -> Unit
    ) {
        this.sourceImage = sourceImage
        _maskPaths.clear()
        _maskPaths.addAll(maskPaths)
        this.brushSize = brushSize
        this.isEraserMode = isEraserMode
        this.onPathAdded = onPathAdded
        this.onClearMask = onClearMask
        this.onInvertMask = onInvertMask
        this.onBrushSizeChange = onBrushSizeChange
        this.onEraserModeChange = onEraserModeChange
    }

    /**
     * Update mask paths (called when paths change during editing).
     */
    fun updateMaskPaths(paths: List<MaskPathData>) {
        _maskPaths.clear()
        _maskPaths.addAll(paths)
    }

    /**
     * Update brush size.
     */
    fun updateBrushSize(size: Float) {
        this.brushSize = size
    }

    /**
     * Update eraser mode.
     */
    fun updateEraserMode(isEraser: Boolean) {
        this.isEraserMode = isEraser
    }

    /**
     * Clear the state when the activity is closed.
     */
    fun clear() {
        sourceImage = null
        _maskPaths.clear()
        brushSize = 50f
        isEraserMode = false
        onPathAdded = null
        onClearMask = null
        onInvertMask = null
        onBrushSizeChange = null
        onEraserModeChange = null
    }
}
