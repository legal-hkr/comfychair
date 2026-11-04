package sh.hnet.comfychair.cache

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.ui.components.SourceTarget

/**
 * An image the media viewer hands to a generation screen as its source ("Use as source"), kept in RAM only until the
 * main activity takes it.
 */
object SourceHandoff {
    class Request(val target: SourceTarget, val bitmap: Bitmap)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun offer(target: SourceTarget, bitmap: Bitmap) {
        _pending.value = Request(target, bitmap)
    }

    /** The waiting request, once. */
    fun take(): Request? = _pending.value.also { _pending.value = null }
}
