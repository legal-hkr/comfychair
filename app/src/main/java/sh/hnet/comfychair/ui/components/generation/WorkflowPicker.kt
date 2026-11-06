@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.settings.groupShapes
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase

/**
 * A group of the workflow menu. Image to Image has one per mode, so choosing a workflow of the other group switches
 * the mode; other screens have one untitled group.
 *
 * @param isCurrent The group of the screen's current mode (only there is the selected workflow marked)
 * @param onSelect Selects a workflow of this group by name (switching the mode first where needed)
 */
class WorkflowGroup(
    val titleRes: Int?,
    val workflows: List<WorkflowItemBase>,
    val isCurrent: Boolean,
    val icon: ImageVector,
    val onSelect: (String) -> Unit
)

/** Icon of a workflow by kind: editing, inpainting, or any other. */
object WorkflowIcons {
    val default: ImageVector get() = Icons.Rounded.AccountTree
    val editing: ImageVector get() = Icons.Rounded.Edit
    val inpainting: ImageVector get() = Icons.Rounded.Brush
}

/**
 * The workflow menu: groups (titled by mode on Image to Image), the selected workflow checked. A workflow's node values
 * are opened from the options sheet only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    groups: List<WorkflowGroup>,
    selectedWorkflow: String
) {
    val visibleGroups = groups.filter { it.workflows.isNotEmpty() }
    val count = visibleGroups.size
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        visibleGroups.forEachIndexed { groupIndex, group ->
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, count)) {
                group.titleRes?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                    )
                }
                group.workflows.forEachIndexed { index, workflow ->
                    val selected = group.isCurrent && workflow.name == selectedWorkflow
                    DropdownMenuItem(
                        text = { Text(workflow.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = MenuDefaults.itemShape(index, group.workflows.size).shape,
                        leadingIcon = {
                            if (selected) Icon(Icons.Rounded.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            onDismiss()
                            if (!selected) group.onSelect(workflow.name)
                        },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        }
                    )
                }
            }
            if (groupIndex < count - 1) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        }
    }
}

/**
 * The workflow chip, first in the dock: the workflow's kind as icon and its name; it opens the workflow menu above it.
 * The workflow decides every other field, so it's one tap away from anywhere.
 */
@Composable
fun WorkflowChip(
    workflowName: String,
    icon: ImageVector,
    groups: List<WorkflowGroup>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.content_description_workflow_chip, workflowName)
    Box(modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    workflowName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
            },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            trailingIcon = {
                Icon(
                    if (expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            border = null,
            modifier = Modifier.semantics { contentDescription = description }
        )
        WorkflowMenu(expanded, { expanded = false }, groups, workflowName)
    }
}

/**
 * The workflow in the options sheet, as a list row like the other choices there; it opens the workflow chip's menu
 * without Node values, which the sheet has as its last row.
 */
@Composable
fun WorkflowRow(
    workflowName: String,
    icon: ImageVector,
    groups: List<WorkflowGroup>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val role = stringResource(R.string.label_workflow)
    Box(modifier) {
        SegmentedListItem(
            onClick = { expanded = true },
            shapes = groupShapes(0, 1),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            supportingContent = { Text(role) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = {
                Icon(if (expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown, contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$role, $workflowName" }
        ) {
            Text(workflowName.ifEmpty { role }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WorkflowMenu(expanded, { expanded = false }, groups, workflowName)
    }
}
