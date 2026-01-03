package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * Container that disables the Android 12+ stretch overscroll effect.
 * Use this to wrap scrollable content that experiences lag from the
 * overscroll animation (see Google Issue #233515751).
 */
@Composable
fun NoOverscrollContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        Box(modifier = modifier) {
            content()
        }
    }
}
