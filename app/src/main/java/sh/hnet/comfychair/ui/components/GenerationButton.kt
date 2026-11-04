package sh.hnet.comfychair.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.R

/**
 * Split button for generation screens with queue management dropdown.
 *
 * Leading button: always says "Generate" and always submits to the end of the queue; the queue's state is shown
 * elsewhere, never in this label. Short transient states ("Connecting…", "Uploading…", "Fetching…") replace the label
 * while they last, with a loading indicator instead of the icon.
 * Trailing button: Dropdown menu with queue management actions
 *
 * @param disabledReason Why the button is disabled, read by TalkBack as its state; null when enabled
 * @param isExecuting Whether any job is currently executing on the server
 * @param isEnabled Whether the button should be enabled (valid input to submit)
 * @param isOfflineMode Whether the app is in offline mode (disables all generation)
 * @param isUploading Whether image upload is in progress (shows "Uploading...")
 * @param isFetching Whether result fetch is in progress (shows "Fetching...")
 * @param isConnecting Whether connection check is in progress (shows "Connecting...")
 * @param onGenerate Callback when Generate/Add to queue is clicked
 * @param onCancelCurrent Callback to cancel the currently executing job
 * @param onAddToFrontOfQueue Callback to add to front of queue
 * @param onClearQueue Callback to clear the server queue
 * @param modifier Modifier for the button layout
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationButton(
    isExecuting: Boolean,
    isEnabled: Boolean,
    isOfflineMode: Boolean = false,
    isUploading: Boolean = false,
    isFetching: Boolean = false,
    isConnecting: Boolean = false,
    onGenerate: () -> Unit,
    onCancelCurrent: () -> Unit,
    onAddToFrontOfQueue: () -> Unit = {},
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
    disabledReason: String? = null
) {
    val focusManager = LocalFocusManager.current
    var showMenu by remember { mutableStateOf(false) }

    val transientLabel = when {
        isConnecting -> stringResource(R.string.button_connecting)
        isUploading -> stringResource(R.string.button_uploading)
        isFetching -> stringResource(R.string.button_fetching)
        else -> null
    }
    val enabled = isEnabled && !isOfflineMode

    Row(modifier = modifier) {
        // Leading button - fills available width, always submits to the end of the queue
        SplitButtonDefaults.LeadingButton(
            onClick = {
                focusManager.clearFocus()
                onGenerate()
            },
            enabled = enabled,
            shapes = SplitButtonDefaults.leadingButtonShapesFor(ButtonHeight),
            contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(ButtonHeight),
            modifier = Modifier
                .weight(1f)
                .height(ButtonHeight)
                .semantics { if (!enabled && disabledReason != null) stateDescription = disabledReason }
        ) {
            val iconSize = SplitButtonDefaults.leadingButtonIconSizeFor(ButtonHeight)
            if (transientLabel != null) {
                // Drawn in the button's content colour, so it shows on the filled container
                LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(iconSize))
            } else {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = transientLabel ?: stringResource(R.string.button_generate),
                maxLines = 1,
                // Long translations shrink rather than wrap or cut
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 18.sp),
                style = LocalTextStyle.current.copy(fontSize = 18.sp)
            )
        }

        // Spacing between buttons (matches SplitButtonDefaults.Spacing)
        Spacer(modifier = Modifier.width(SplitButtonDefaults.Spacing))

        // Trailing button with dropdown - square button matching height of leading
        // Animate icon rotation: 0° when closed, 180° when open (arrow points up)
        val iconRotation by animateFloatAsState(
            targetValue = if (showMenu) 180f else 0f,
            label = "dropdown icon rotation"
        )

        // Trailing button - disabled in offline mode (no queue operations available)
        Box(modifier = Modifier.wrapContentWidth(unbounded = true)) {
            // Turns round while its menu is open
            SplitButtonDefaults.TrailingButton(
                checked = showMenu,
                onCheckedChange = { showMenu = it },
                enabled = !isOfflineMode,
                shapes = SplitButtonDefaults.trailingButtonShapesFor(ButtonHeight),
                contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(ButtonHeight),
                modifier = Modifier.size(ButtonHeight)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.content_description_queue_menu),
                    modifier = Modifier.rotate(iconRotation)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // Queue management actions (above gap)
                // Add to front of queue
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_add_to_front_of_queue)) },
                    onClick = {
                        showMenu = false
                        onAddToFrontOfQueue()
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = null)
                    }
                )
                // Clear queue
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.button_clear_queue)) },
                    onClick = {
                        showMenu = false
                        onClearQueue()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                )

                // Gap divider separating queue actions from cancel
                HorizontalDivider()

                // Cancel current (below gap) - destructive action separated
                // Use error/warning color when enabled to indicate destructive action
                val cancelColor = if (isExecuting) {
                    MaterialTheme.colorScheme.error
                } else {
                    MenuDefaults.itemColors().disabledTextColor
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_cancel_current), color = cancelColor) },
                    onClick = {
                        showMenu = false
                        onCancelCurrent()
                    },
                    enabled = isExecuting,
                    leadingIcon = {
                        Icon(Icons.Default.Cancel, contentDescription = null, tint = cancelColor)
                    }
                )
            }
        }
    }
}

private val ButtonHeight = 56.dp
