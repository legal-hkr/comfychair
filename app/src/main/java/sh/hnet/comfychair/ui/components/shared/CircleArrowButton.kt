package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Round previous/next button used over media: the results strip and the full-screen viewer.
 * A light container with a shadow stands out over any image, unlike a tonal button.
 * Surface(onClick) keeps the touch target at least 48 dp.
 *
 * @param forward Points to the next item (auto-mirrored for RTL)
 */
@Composable
fun CircleArrowButton(
    forward: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (forward) Icons.AutoMirrored.Rounded.KeyboardArrowRight
                else Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
