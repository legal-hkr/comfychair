@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sh.hnet.comfychair.ui.components.settings

import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ListItemColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton

/**
 * A settings page: a flexible top bar collapsing on scroll (large on the Settings list, medium with Back on the pages
 * it opens) over a scrolling column of groups.
 *
 * @param onNavigateBack Null on the Settings list (a tab has no Back)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsPage(
    title: String,
    onNavigateBack: (() -> Unit)?,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize()) {
        val titleText: @Composable () -> Unit = { Text(title) }
        val subtitleText: (@Composable () -> Unit)? = subtitle?.let { { Text(it) } }
        if (onNavigateBack == null) {
            LargeFlexibleTopAppBar(
                title = titleText,
                subtitle = subtitleText,
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior
            )
        } else {
            MediumFlexibleTopAppBar(
                title = titleText,
                subtitle = subtitleText,
                navigationIcon = {
                    TooltipIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.content_description_back), onNavigateBack)
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior
            )
        }
        NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

/** Rows of a [SettingsGroup]; each gets the shapes of its place in the group. */
class SettingsGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun row(content: @Composable (shapes: ListItemShapes) -> Unit) {
        rows += content
    }
}

/**
 * A group of segmented rows, 2 dp apart, with round outer and small inner corners; optionally titled.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsGroup(title: String? = null, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    if (scope.rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            scope.rows.forEachIndexed { index, row ->
                row(ListItemDefaults.segmentedShapes(index, scope.rows.size))
            }
        }
    }
}

/** Explanation under the top bar of a page, before its groups. */
@Composable
fun SettingsDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/**
 * Row colours: segments on surfaceContainer, so they stand out from the page's background as one block.
 *
 * @param highlightSelected Whether a selected row (a chosen radio option) keeps the component's selected colours; a
 *   switch row's "on" isn't a selection and looks like the others
 */
@Composable
private fun rowColors(highlightSelected: Boolean = false): ListItemColors {
    val base = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    if (highlightSelected) return base
    return ListItemDefaults.segmentedColors(
        containerColor = base.containerColor,
        selectedContainerColor = base.containerColor,
        selectedContentColor = base.contentColor,
        selectedLeadingContentColor = base.leadingContentColor,
        selectedTrailingContentColor = base.trailingContentColor,
        selectedOverlineContentColor = base.overlineContentColor,
        selectedSupportingContentColor = base.supportingContentColor
    )
}

/** The row keeps its place's shape when selected (checked), so the group stays one block. */
@Composable
private fun steadyShapes(shapes: ListItemShapes): ListItemShapes = ListItemDefaults.shapes(
    shape = shapes.shape,
    selectedShape = shapes.shape,
    pressedShape = shapes.pressedShape,
    focusedShape = shapes.focusedShape,
    hoveredShape = shapes.hoveredShape,
    draggedShape = shapes.draggedShape
)

/** The tonal leading icon of a row. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** A row opening a page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsNavigationRow(
    shapes: ListItemShapes,
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = rowColors(),
        leadingContent = { RowIcon(icon) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } }
    ) {
        Text(title)
    }
}

/** A setting that's on or off: the whole row toggles, the switch shows the state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSwitchRow(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = steadyShapes(shapes),
        colors = rowColors(),
        enabled = enabled,
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        supportingContent = supporting?.let { { Text(it) } }
    ) {
        Text(title)
    }
}

/** One option of a longer choice, with what it means. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsRadioRow(
    shapes: ListItemShapes,
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    supporting: String? = null
) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = steadyShapes(shapes),
        // The chosen option stands out by colour only
        colors = rowColors(highlightSelected = true),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = supporting?.let { { Text(it) } }
    ) {
        Text(title)
    }
}

/**
 * An action. A destructive one is in the error colour; its caller confirms it before acting.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsActionRow(
    shapes: ListItemShapes,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    supporting: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    val defaults = rowColors()
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = rowColors(),
        // Not again while it runs
        enabled = enabled && !busy,
        trailingContent = if (busy) {
            { LoadingIndicator(Modifier.size(24.dp)) }
        } else null,
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (destructive && enabled) MaterialTheme.colorScheme.error else defaults.leadingContentColor)
        },
        supportingContent = supporting?.let { { Text(it) } }
    ) {
        Text(title, color = if (destructive && enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

/** A value to read (e.g. a version): not a button, in a segment of the group. */
@Composable
fun SettingsInfoRow(shapes: ListItemShapes, title: String, value: String) {
    Surface(
        shape = shapes.shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A short choice (up to three options) as connected toggle buttons, under its title, in a row of the group.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SettingsChoiceRow(
    shapes: ListItemShapes,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    supporting: String? = null
) {
    // Not a button itself: only the toggle buttons react
    Surface(
        shape = shapes.shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (value, label) ->
                    ToggleButton(
                        checked = value == selected,
                        onCheckedChange = { if (it) onSelect(value) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
        }
    }
}

/**
 * Confirmation of a destructive action: what will be lost, and the action in the error colour.
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}
