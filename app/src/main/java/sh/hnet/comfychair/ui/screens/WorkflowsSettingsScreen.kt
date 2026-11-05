@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.ui.components.WorkflowThumbnail
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.workflow.DuplicateNameDialog
import sh.hnet.comfychair.ui.components.workflow.MissingFieldsDialog
import sh.hnet.comfychair.ui.components.workflow.MissingNodesDialog
import sh.hnet.comfychair.ui.components.workflow.WorkflowDetailsDialog
import sh.hnet.comfychair.viewmodel.ExportFormat
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsSettingsScreen(
    viewModel: WorkflowManagementViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // State and effects
    // JSON file picker
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.onFileSelected(context, it)
        }
    }

    // Editor launcher for mapping mode
    val editorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mappingsJson = result.data?.getStringExtra(WorkflowEditorActivity.EXTRA_RESULT_MAPPINGS)
            if (mappingsJson != null) {
                // Parse mappings and complete import
                val mappings = parseMappingsFromJson(mappingsJson)
                viewModel.completeImport(mappings)
            }
        } else {
            viewModel.cancelMapping()
        }
    }

    // Editor launcher for create new workflow mode
    val createEditorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Reload from disk and refresh UI - workflow was saved by WorkflowEditorViewModel
            viewModel.reloadAndRefreshWorkflows()
        }
    }

    // Editor launcher for editing existing workflow structure
    val editExistingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Reload from disk and refresh UI - workflow was updated by WorkflowEditorViewModel
            viewModel.reloadAndRefreshWorkflows()
        }
    }

    // Export file picker launcher
    var exportFilename by remember { mutableStateOf("workflow.json") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.performExport(context, uri)
        } else {
            viewModel.cancelExport()
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WorkflowManagementEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is WorkflowManagementEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                is WorkflowManagementEvent.LaunchEditor -> {
                    val pending = event.pendingUpload
                    val mappingState = pending.mappingState
                    if (mappingState != null) {
                        val intent = WorkflowEditorActivity.createIntentForMapping(
                            context = context,
                            jsonContent = pending.jsonContent,
                            name = pending.name,
                            description = pending.description,
                            mappingState = mappingState
                        )
                        editorLauncher.launch(intent)
                    }
                }
                is WorkflowManagementEvent.LaunchExportFilePicker -> {
                    exportFilename = event.suggestedFilename
                    exportLauncher.launch(event.suggestedFilename)
                }
                is WorkflowManagementEvent.WorkflowsChanged -> {
                    // Handled by MainContainerActivity (reload when leaving the Settings tab)
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val sections = listOf(
        R.string.workflow_section_tti to uiState.ttiWorkflows,
        R.string.workflow_section_iti_inpainting to uiState.itiInpaintingWorkflows,
        R.string.workflow_section_iti_editing to uiState.itiEditingWorkflows,
        R.string.workflow_section_ttv to uiState.ttvWorkflows,
        R.string.workflow_section_itv to uiState.itvWorkflows
    )
    val rowActions = WorkflowRowActions(
        onOpen = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
        onRename = { viewModel.onEditWorkflow(it) },
        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
        onExport = { workflow, format -> viewModel.onExportWorkflow(workflow, format) },
        onDelete = { viewModel.onDeleteWorkflow(it) },
        onEnabledChange = { workflow, enabled -> viewModel.onWorkflowEnabledChange(workflow, enabled) }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.title_workflows_settings)) },
                navigationIcon = {
                    TooltipIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.content_description_back), onNavigateBack)
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                scrollBehavior = scrollBehavior
            )

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        // Room under the last row for Import
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    ) {
                        sections.forEach { (titleRes, workflows) ->
                            item(key = "header_$titleRes") {
                                WorkflowSectionHeader(stringResource(titleRes), workflows, uiState.disabledWorkflowIds)
                            }
                            if (workflows.isEmpty()) {
                                item(key = "empty_$titleRes") {
                                    Text(
                                        text = stringResource(R.string.workflow_section_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            itemsIndexed(workflows, key = { _, workflow -> workflow.id }) { index, workflow ->
                                WorkflowRow(
                                    workflow = workflow,
                                    shapes = ListItemDefaults.segmentedShapes(index, workflows.size),
                                    enabled = workflow.id !in uiState.disabledWorkflowIds,
                                    actions = rowActions
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add a workflow: build one in the editor, or import a file (exported from ComfyUI or from this app)
        AddWorkflowMenu(
            onCreate = { createEditorLauncher.launch(WorkflowEditorActivity.createIntentForNewWorkflow(context)) },
            onImport = { jsonPickerLauncher.launch("application/json") }
        )
    }

    // Import dialog
    if (uiState.showImportDialog) {
        WorkflowDetailsDialog(
            titleRes = R.string.title_import_workflow,
            selectedType = uiState.importSelectedType,
            onTypeSelected = viewModel::onImportTypeSelected,
            isTypeDropdownExpanded = uiState.importTypeDropdownExpanded,
            onToggleTypeDropdown = viewModel::onToggleTypeDropdown,
            name = uiState.importName,
            onNameChange = viewModel::onImportNameChange,
            nameError = uiState.importNameError,
            description = uiState.importDescription,
            onDescriptionChange = viewModel::onImportDescriptionChange,
            descriptionError = uiState.importDescriptionError,
            isValidating = uiState.isValidatingNodes,
            onConfirm = { viewModel.proceedWithImport(context, ConnectionManager.client) },
            onDismiss = viewModel::cancelImport
        )
    }

    // Missing nodes dialog
    if (uiState.showMissingNodesDialog) {
        MissingNodesDialog(
            missingNodes = uiState.missingNodes,
            onDismiss = viewModel::dismissMissingNodesDialog
        )
    }

    // Missing fields dialog
    if (uiState.showMissingFieldsDialog) {
        MissingFieldsDialog(
            missingFields = uiState.missingFields,
            onDismiss = viewModel::dismissMissingFieldsDialog
        )
    }

    // Duplicate name dialog
    if (uiState.showDuplicateNameDialog) {
        DuplicateNameDialog(
            onDismiss = viewModel::dismissDuplicateNameDialog
        )
    }

    // Edit dialog
    if (uiState.showEditDialog) {
        EditWorkflowDialog(
            name = uiState.editName,
            onNameChange = viewModel::onEditNameChange,
            nameError = uiState.editNameError,
            description = uiState.editDescription,
            onDescriptionChange = viewModel::onEditDescriptionChange,
            descriptionError = uiState.editDescriptionError,
            onConfirm = viewModel::confirmEdit,
            onDismiss = viewModel::cancelEdit
        )
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        DeleteWorkflowDialog(
            workflowName = uiState.workflowToDelete?.name ?: "",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete
        )
    }

    // Duplicate dialog
    if (uiState.showDuplicateDialog) {
        DuplicateWorkflowDialog(
            name = uiState.duplicateName,
            onNameChange = viewModel::onDuplicateNameChange,
            nameError = uiState.duplicateNameError,
            description = uiState.duplicateDescription,
            onDescriptionChange = viewModel::onDuplicateDescriptionChange,
            descriptionError = uiState.duplicateDescriptionError,
            onConfirm = viewModel::confirmDuplicate,
            onDismiss = viewModel::cancelDuplicate
        )
    }
}

/**
 * Parse field mappings from JSON string
 */
private fun parseMappingsFromJson(jsonString: String): Map<String, Pair<String, String>> {
    val mappings = mutableMapOf<String, Pair<String, String>>()
    try {
        val json = org.json.JSONObject(jsonString)
        for (key in json.keys()) {
            val mapping = json.getJSONObject(key)
            mappings[key] = Pair(
                mapping.getString("nodeId"),
                mapping.getString("inputKey")
            )
        }
    } catch (e: Exception) {
        // Return empty mappings on error
    }
    return mappings
}

/**
 * Adding a workflow, as the result actions on the generation preview: a toggle FAB at the bottom end opening Create new
 * and Import upward, over a scrim; tapping the scrim or Back closes it.
 */
@Composable
private fun BoxScope.AddWorkflowMenu(onCreate: () -> Unit, onImport: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    // Standard M3 scrim, as under modal sheets
    val scrimAlpha by animateFloatAsState(
        targetValue = if (expanded) 0.32f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "add workflow scrim"
    )
    if (scrimAlpha > 0f) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                .pointerInput(Unit) { detectTapGestures { expanded = false } }
        )
    }

    val description = stringResource(R.string.content_description_add_workflow)
    FloatingActionButtonMenu(
        expanded = expanded,
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp),
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                modifier = Modifier.semantics { contentDescription = description }
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add } }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress })
                )
            }
        }
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onCreate()
            },
            icon = { Icon(Icons.Rounded.AccountTree, contentDescription = null) },
            text = { Text(stringResource(R.string.button_create_workflow)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onImport()
            },
            icon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
            text = { Text(stringResource(R.string.button_import)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** What a workflow row can do; the same for every section. */
private class WorkflowRowActions(
    val onOpen: (WorkflowManager.Workflow) -> Unit,
    val onEditStructure: (WorkflowManager.Workflow) -> Unit,
    val onRename: (WorkflowManager.Workflow) -> Unit,
    val onDuplicate: (WorkflowManager.Workflow) -> Unit,
    val onExport: (WorkflowManager.Workflow, ExportFormat) -> Unit,
    val onDelete: (WorkflowManager.Workflow) -> Unit,
    val onEnabledChange: (WorkflowManager.Workflow, Boolean) -> Unit
)

/** A section's title, with how many of its workflows the generation screens offer. */
@Composable
private fun WorkflowSectionHeader(title: String, workflows: List<WorkflowManager.Workflow>, disabledWorkflowIds: Set<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (workflows.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.label_workflows_enabled_count,
                    workflows.count { it.id !in disabledWorkflowIds },
                    workflows.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A workflow: its graph in small, name and description, whether the generation screens offer it (the switch takes
 * effect at once) and its menu. Tapping the row opens the graph. A switched-off workflow's text takes the list's
 * disabled colours.
 */
@Composable
private fun WorkflowRow(
    workflow: WorkflowManager.Workflow,
    shapes: ListItemShapes,
    enabled: Boolean,
    actions: WorkflowRowActions
) {
    val colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val switchDescription = stringResource(R.string.content_description_workflow_enabled, workflow.name)
    SegmentedListItem(
        onClick = { actions.onOpen(workflow) },
        shapes = shapes,
        colors = colors,
        leadingContent = {
            WorkflowThumbnail(
                jsonContent = workflow.jsonContent,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        },
        supportingContent = {
            Column {
                if (workflow.description.isNotEmpty()) {
                    Text(
                        workflow.description,
                        color = if (enabled) colors.supportingContentColor else colors.disabledSupportingContentColor
                    )
                }
                if (workflow.isBuiltIn) {
                    Text(
                        stringResource(R.string.label_workflow_built_in),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary else colors.disabledSupportingContentColor
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { actions.onEnabledChange(workflow, it) },
                    modifier = Modifier.semantics { contentDescription = switchDescription }
                )
                WorkflowMenu(workflow, actions)
            }
        }
    ) {
        Text(workflow.name, color = if (enabled) colors.contentColor else colors.disabledContentColor)
    }
}

/**
 * A workflow's menu, in groups: changing it (structure, name – own workflows only – and a copy), exporting it, and
 * deleting it (own workflows only, confirmed).
 */
@Composable
private fun WorkflowMenu(workflow: WorkflowManager.Workflow, actions: WorkflowRowActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(Icons.Rounded.MoreVert, stringResource(R.string.content_description_menu), { expanded = true })
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            val own = !workflow.isBuiltIn
            val groups = listOfNotNull(
                listOfNotNull(
                    if (own) Triple(R.string.workflow_menu_edit_structure, Icons.Rounded.Tune) { actions.onEditStructure(workflow) } else null,
                    if (own) Triple(R.string.workflow_menu_rename, Icons.Rounded.Edit) { actions.onRename(workflow) } else null,
                    Triple(R.string.workflow_menu_duplicate, Icons.Rounded.ContentCopy) { actions.onDuplicate(workflow) }
                ),
                listOf(
                    Triple(R.string.workflow_menu_export, Icons.Rounded.SaveAlt) { actions.onExport(workflow, ExportFormat.INTERNAL) },
                    Triple(R.string.workflow_menu_export_api, Icons.Rounded.SaveAlt) { actions.onExport(workflow, ExportFormat.API) }
                ),
                if (own) listOf(Triple(R.string.workflow_menu_delete, Icons.Rounded.Delete) { actions.onDelete(workflow) }) else null
            )
            groups.forEachIndexed { groupIndex, items ->
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                    items.forEachIndexed { index, (labelRes, icon, onClick) ->
                        val destructive = labelRes == R.string.workflow_menu_delete
                        val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes), color = color) },
                            leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
                            shape = MenuDefaults.itemShape(index, items.size).shape,
                            onClick = {
                                expanded = false
                                onClick()
                            }
                        )
                    }
                }
                if (groupIndex < groups.lastIndex) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditWorkflowDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_edit_workflow)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_workflow_name)) },
                    singleLine = true,
                    isError = nameError != null || name.isBlank(),
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_workflow_description)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank() && nameError == null && descriptionError == null
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun DeleteWorkflowDialog(
    workflowName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_delete_workflow)) },
        text = {
            Text(stringResource(R.string.msg_delete_workflow, workflowName))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.button_delete))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun DuplicateWorkflowDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_duplicate_workflow)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_workflow_name)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_workflow_description)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank() && nameError == null && descriptionError == null
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}
