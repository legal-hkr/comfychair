package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Scrolling for an Expressive menu (`DropdownMenuPopup`), which doesn't scroll by itself, for long lists (models,
 * workflows, prompts). A scrolling container clips its content along the scroll, and the menu groups' shadows fall
 * outside the groups: the padding inside keeps them clear of the clip, so the bottom and the corners aren't cut off.
 */
@Composable
fun rememberMenuScrollModifier(): Modifier =
    Modifier
        .verticalScroll(rememberScrollState())
        .padding(vertical = MENU_SHADOW_ROOM)

/** Room for a menu group's shadow above and below it. */
private val MENU_SHADOW_ROOM = 8.dp
