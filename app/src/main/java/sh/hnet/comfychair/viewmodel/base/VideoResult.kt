package sh.hnet.comfychair.viewmodel.base

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import sh.hnet.comfychair.util.GenerationOutput

/**
 * Read-only view of a video screen's result: the live preview, the video shown and the job's result list.
 */
interface VideoResultState {
    /** Live preview frame while generating (cleared once the video arrives) */
    val previewBitmap: Bitmap?
    /** Video shown in the player */
    val currentVideoUri: Uri?
    /** All result files of the last job, in server order (the first video is the one shown) */
    val generationOutputs: List<GenerationOutput>
    /** Result shown in the preview (index into generationOutputs) */
    val currentResultIndex: Int
    /** Whether the finished video is being downloaded */
    val isFetching: Boolean
}

@Immutable
data class VideoResult(
    override val previewBitmap: Bitmap? = null,
    override val currentVideoUri: Uri? = null,
    override val generationOutputs: List<GenerationOutput> = emptyList(),
    override val currentResultIndex: Int = 0,
    override val isFetching: Boolean = false
) : VideoResultState
