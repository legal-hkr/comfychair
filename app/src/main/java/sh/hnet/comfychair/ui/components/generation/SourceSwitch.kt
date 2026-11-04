package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.layout.Arrangement
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
 * (Text, Image, later Video). Each choice is its own screen with its own state; the switch only shows another one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SourceSwitch(screen: MainRoute, modifier: Modifier = Modifier) {
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
