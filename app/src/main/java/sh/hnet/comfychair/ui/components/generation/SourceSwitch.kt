package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import sh.hnet.comfychair.ui.navigation.OutputTab
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.ui.navigation.LocalScreenNavigator
import sh.hnet.comfychair.ui.navigation.outputTab
import sh.hnet.comfychair.ui.navigation.sources

/**
 * What the screen generates from, in the top bar of the Image and Video tabs: connected toggle buttons with nouns
 * (Text, Image, Video). Each choice is its own screen with its own state; the switch only shows another one.
 *
 * One switch per tab is drawn over the screens ([SourceSwitchOverlay]), so a choice only changes its checked button
 * and animates as a toggle group does; a screen's own copy would be replaced with the screen. Each screen's top bar
 * keeps the switch's place with an invisible copy ([SourceSwitchPlaceholder]), which tells the overlay where it is.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceSwitch(screen: MainRoute, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val sources = screen.outputTab?.sources ?: return
    val navigate = LocalScreenNavigator.current
    val groupLabel = stringResource(R.string.content_description_source_switch)
    Row(
        modifier = modifier
            .selectableGroup()
            .semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        sources.forEachIndexed { index, source ->
            ToggleButton(
                checked = source.route == screen,
                onCheckedChange = { if (it) navigate(source.route) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    sources.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .semantics { role = Role.RadioButton }
            ) {
                Text(stringResource(source.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where each tab's switch stands on its screens, in window coordinates. */
class SourceSwitchAnchors {
    val bounds = mutableStateMapOf<OutputTab, Rect>()
}

val LocalSourceSwitchAnchors = staticCompositionLocalOf<SourceSwitchAnchors?> { null }

/**
 * The switch's place in a screen's top bar: an invisible, inert copy of the same size, reporting where it is. The
 * screens of a tab report the same place, as their bars are alike.
 */
@Composable
fun SourceSwitchPlaceholder(screen: MainRoute) {
    val tab = screen.outputTab ?: return
    val anchors = LocalSourceSwitchAnchors.current
    SourceSwitch(
        screen = screen,
        enabled = false,
        modifier = Modifier
            .alpha(0f)
            .clearAndSetSemantics {}
            .onGloballyPositioned { anchors?.bounds?.set(tab, it.boundsInWindow()) }
    )
}

/**
 * The tab's switch over the screens, at the place their bars keep for it. Between tabs it fades out and in with the
 * screens; within a tab it stays and only its checked button changes.
 */
@Composable
fun SourceSwitchOverlay(current: MainRoute?, anchors: SourceSwitchAnchors) {
    val motion = MaterialTheme.motionScheme
    var origin by remember { mutableStateOf(Offset.Zero) }
    // A tab fading out keeps showing the screen it was on (a plain map: read in this composition, after the write)
    val lastScreens = remember { HashMap<OutputTab, MainRoute>() }
    current?.outputTab?.let { lastScreens[it] = current }
    AnimatedContent(
        targetState = current?.outputTab,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
        label = "sourceSwitch"
    ) { tab ->
        val screen = tab?.let { lastScreens[it] }
        // Placed in the layout phase: the place moves while a tab's screen scales in, without recomposing
        Box(Modifier.fillMaxSize()) {
            if (screen != null && tab in anchors.bounds) {
                SourceSwitch(screen, modifier = Modifier.offset {
                    val place = anchors.bounds[tab] ?: return@offset IntOffset.Zero
                    (place.topLeft - origin).round()
                })
            }
        }
    }
}
