package sh.hnet.comfychair.ui.components.shared

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Displays a model path with the directory portion dimmed.
 * Handles both forward slashes (Unix) and backslashes (Windows).
 */
@Composable
fun ModelPathText(path: String) {
    // Find the last separator (either / or \)
    val lastSlashIndex = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))

    if (lastSlashIndex > 0) {
        val directoryPart = path.substring(0, lastSlashIndex + 1)
        val filenamePart = path.substring(lastSlashIndex + 1)

        val dimmedColor = LocalContentColor.current.copy(alpha = 0.5f)

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = dimmedColor)) {
                    append(directoryPart)
                }
                append(filenamePart)
            }
        )
    } else {
        Text(path)
    }
}
