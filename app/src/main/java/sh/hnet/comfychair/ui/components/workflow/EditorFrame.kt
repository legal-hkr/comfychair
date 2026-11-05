package sh.hnet.comfychair.ui.components.workflow

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton

/*
 * The workflow editor's frame (spec §10.2): Back and a title pill at the top, one floating toolbar at the bottom whose
 * contents and primary action (FAB) depend on the mode.
 */

/** What the editor is opened for; decides the pill, the toolbar and what Back does. */
enum class EditorMode {
    /** Only to look at (a workflow's detail page) */
    GRAPH,
    /** Values of the nodes, saved per workflow (a generation screen's workflow menu or options sheet) */
    NODE_VALUES,
    /** The workflow's structure (own workflows, Create new) */
    EDIT,
    /** Assigning the app's fields to nodes while importing */
    MAPPING
}

/** The pill's second line: the mode and its state. */
sealed interface EditorStatus {
    data object Graph : EditorStatus
    data class NodeValues(val changed: Int) : EditorStatus
    data class Editing(val unsaved: Boolean, val placement: Boolean) : EditorStatus
    data object Mapping : EditorStatus
    /** A connection waits for its other end: the pill turns into a status pill with a way out */
    data class Connecting(val slotType: String, val fromOutput: Boolean) : EditorStatus
}

/**
 * Back (48 dp, circular), the title pill (workflow name and mode), and a control at the end: Edit structure for own
 * workflows in Node values, or the selection count that clears the selection.
 */
@Composable
fun EditorTopRow(
    workflowName: String,
    status: EditorStatus,
    onBack: () -> Unit,
    onCancelConnection: () -> Unit,
    modifier: Modifier = Modifier,
    onEditStructure: (() -> Unit)? = null,
    selectedCount: Int = 0,
    onClearSelection: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
        }

        // The pill as wide as its text; the rest of the row stays free, the control sits at the end
        Box(Modifier.weight(1f)) {
            TitlePill(workflowName, status, onCancelConnection, Modifier)
        }

        when {
            status is EditorStatus.Connecting -> Unit
            selectedCount > 0 -> {
                val clear = stringResource(R.string.content_description_clear_selection)
                FilledTonalButton(
                    onClick = onClearSelection,
                    contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "$clear, $selectedCount" }
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(selectedCount.toString())
                }
            }
            onEditStructure != null -> FilledTonalIconButton(
                onClick = onEditStructure,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.button_edit_structure))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TitlePill(workflowName: String, status: EditorStatus, onCancelConnection: () -> Unit, modifier: Modifier) {
    val connecting = status as? EditorStatus.Connecting
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Surface(
        shape = CircleShape,
        color = if (connecting != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (connecting != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .heightIn(min = 48.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
            // Mode changes (connecting, unsaved changes) are read out without moving focus
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        AnimatedContent(
            targetState = status,
            contentKey = { it::class },
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "pill"
        ) { current ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(start = 20.dp, end = if (current is EditorStatus.Connecting) 0.dp else 20.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    val (line1, line2) = pillLines(workflowName, current)
                    Text(line1, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        line2,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current is EditorStatus.Connecting) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (current is EditorStatus.Connecting) {
                    IconButton(onClick = onCancelConnection) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.content_description_cancel_connection))
                    }
                }
            }
        }
    }
}

@Composable
private fun pillLines(workflowName: String, status: EditorStatus): Pair<String, String> {
    val name = workflowName.ifEmpty { stringResource(R.string.label_new_workflow) }
    return when (status) {
        is EditorStatus.Connecting -> stringResource(R.string.label_editor_connecting, status.slotType) to
            stringResource(if (status.fromOutput) R.string.hint_editor_tap_input else R.string.hint_editor_tap_output)
        EditorStatus.Graph -> name to stringResource(R.string.label_editor_mode_graph)
        is EditorStatus.NodeValues -> name to (
            if (status.changed > 0) pluralStringResource(R.plurals.label_editor_mode_node_values_changed, status.changed, status.changed)
            else stringResource(R.string.label_editor_mode_node_values)
        )
        is EditorStatus.Editing -> name to stringResource(
            when {
                status.placement -> R.string.label_editor_mode_placement
                status.unsaved -> R.string.label_editor_mode_editing_unsaved
                else -> R.string.label_editor_mode_editing
            }
        )
        EditorStatus.Mapping -> name to stringResource(R.string.label_editor_mode_mapping)
    }
}

/** What the toolbar shows and does; actions not given aren't shown. */
class EditorToolbarActions(
    val scale: Float,
    val onZoomIn: () -> Unit,
    val onZoomOut: () -> Unit,
    val onZoom100: () -> Unit,
    val onFit: () -> Unit
)

/** Structure editing actions; with a selection the toolbar shows the actions on it. */
class EditorEditActions(
    val selectedNodes: Int,
    val selectedItems: Int,
    val canUngroup: Boolean,
    val placement: Boolean,
    val onAddNode: () -> Unit,
    val onAddNote: () -> Unit,
    val onCleanUp: () -> Unit,
    val onTogglePlacement: () -> Unit,
    val onDuplicate: () -> Unit,
    val onBypass: () -> Unit,
    val onGroup: () -> Unit,
    val onUngroup: () -> Unit,
    val onDelete: () -> Unit,
    // In the ⋮ menu with a selection, each only when it applies: one node, one note, a selection inside a group
    val onRenameNode: (() -> Unit)? = null,
    val onEditNote: (() -> Unit)? = null,
    val onRenameGroup: (() -> Unit)? = null,
    val onSave: () -> Unit
)

/**
 * The bottom toolbar. Zoom group in every mode except structure editing (and while a connection waits, also there);
 * structure editing shows Add node, Add note and ⋮, or with a selection Duplicate, Bypass, Group, ⋮ and Delete last,
 * with Save as the FAB. Map fields adds its confirmation.
 *
 * @param edit Structure editing, null in the other modes
 * @param onFinishMapping Map fields: confirms the mapping; null elsewhere
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EditorToolbar(
    zoom: EditorToolbarActions,
    modifier: Modifier = Modifier,
    edit: EditorEditActions? = null,
    onFinishMapping: (() -> Unit)? = null,
    canFinishMapping: Boolean = false
) {
    if (edit != null) {
        val save = stringResource(R.string.button_save)
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = modifier,
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = edit.onSave,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = save }
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                }
            }
        ) {
            Row(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalAlignment = Alignment.CenterVertically) {
                // The whole graph's actions: in ⋮ with or without a selection, so the menu is always there
                val graphActions = buildList {
                    if (!edit.placement) add(MenuAction(R.string.workflow_editor_cleanup, Icons.Rounded.AutoFixHigh, edit.onCleanUp))
                    add(
                        MenuAction(
                            if (edit.placement) R.string.menu_disable_manual_placement else R.string.menu_enable_manual_placement,
                            Icons.Rounded.GridView,
                            edit.onTogglePlacement
                        )
                    )
                }
                if (edit.selectedItems == 0) {
                    TooltipIconButton(Icons.Rounded.Add, stringResource(R.string.workflow_editor_add_node), edit.onAddNode)
                    TooltipIconButton(Icons.Rounded.StickyNote2, stringResource(R.string.workflow_editor_add_note), edit.onAddNote)
                    EditMoreMenu(listOf(graphActions))
                } else {
                    val nodes = edit.selectedNodes > 0
                    TooltipIconButton(Icons.Rounded.ContentCopy, stringResource(R.string.workflow_editor_duplicate_node), edit.onDuplicate, enabled = nodes)
                    TooltipIconButton(Icons.Rounded.DoNotDisturbOn, stringResource(R.string.node_editor_bypass), edit.onBypass, enabled = nodes)
                    TooltipIconButton(Icons.Rounded.SelectAll, stringResource(R.string.workflow_editor_group), edit.onGroup)
                    val more = listOfNotNull(
                        edit.onRenameNode?.let { MenuAction(R.string.title_node_editor_rename, Icons.Rounded.DriveFileRenameOutline, it) },
                        edit.onEditNote?.let { MenuAction(R.string.workflow_editor_edit_note_content, Icons.Rounded.EditNote, it) },
                        edit.onRenameGroup?.let { MenuAction(R.string.title_workflow_editor_rename_group, Icons.Rounded.DriveFileRenameOutline, it) },
                        MenuAction(R.string.workflow_editor_ungroup, Icons.Rounded.ViewModule, edit.onUngroup).takeIf { edit.canUngroup }
                    )
                    // The selection's actions first, then the graph's
                    EditMoreMenu(listOf(more, graphActions).filter { it.isNotEmpty() })
                    TooltipIconButton(
                        Icons.Rounded.Delete,
                        stringResource(R.string.workflow_editor_delete_node),
                        edit.onDelete,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    } else {
        HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
            ZoomGroup(zoom)
            onFinishMapping?.let {
                TooltipIconButton(Icons.Rounded.Check, stringResource(R.string.button_confirm), it, enabled = canFinishMapping)
            }
        }
    }
}

/** Zoom out, the percentage (tap for 100 %), zoom in, fit to screen. */
@Composable
private fun ZoomGroup(zoom: EditorToolbarActions) {
    val percent = (zoom.scale * 100).toInt()
    TooltipIconButton(Icons.Rounded.Remove, stringResource(R.string.workflow_editor_zoom_out), zoom.onZoomOut)
    val zoomLabel = stringResource(R.string.content_description_zoom_level, percent)
    TextButton(
        onClick = zoom.onZoom100,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.semantics { contentDescription = zoomLabel }
    ) {
        // Fixed width for three digits, so the toolbar doesn't jump while zooming
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.workflow_editor_zoom_percentage, percent), maxLines = 1)
        }
    }
    TooltipIconButton(Icons.Rounded.Add, stringResource(R.string.workflow_editor_zoom_in), zoom.onZoomIn)
    TooltipIconButton(Icons.Rounded.FitScreen, stringResource(R.string.workflow_editor_reset_zoom), zoom.onFit)
}

private class MenuAction(val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/** ⋮ with its actions in groups (Expressive grouped menu). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditMoreMenu(groups: List<List<MenuAction>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Rounded.MoreVert, stringResource(R.string.workflow_editor_more), { open = true })
        DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
            groups.forEachIndexed { groupIndex, actions ->
                if (groupIndex > 0) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                    actions.forEachIndexed { index, action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.labelRes)) },
                            leadingIcon = { Icon(action.icon, contentDescription = null) },
                            shape = MenuDefaults.itemShape(index, actions.size).shape,
                            onClick = {
                                open = false
                                action.onClick()
                            }
                        )
                    }
                }
            }
        }
    }
}
