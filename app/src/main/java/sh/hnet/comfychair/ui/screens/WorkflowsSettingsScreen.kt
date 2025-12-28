package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import sh.hnet.comfychair.ui.components.WorkflowThumbnail
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.SettingsMenuDropdown
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsSettingsScreen(
    viewModel: WorkflowManagementViewModel,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
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
                    // Handled by SettingsContainerActivity to set result
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar
        TopAppBar(
            title = { Text(stringResource(R.string.workflows_settings_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // New workflow button
                IconButton(onClick = {
                    createEditorLauncher.launch(
                        WorkflowEditorActivity.createIntentForNewWorkflow(context)
                    )
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.button_new_workflow))
                }
                // Import button
                IconButton(onClick = { jsonPickerLauncher.launch("application/json") }) {
                    Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.button_import))
                }
                // Menu button
                SettingsMenuDropdown(
                    onGeneration = onNavigateToGeneration,
                    onLogout = onLogout
                )
            }
        )

        // Loading indicator
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // Workflow list organized by type
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Text-to-Image (Checkpoint)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_tti_checkpoint),
                        workflows = uiState.ttiCheckpointWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Text-to-Image (UNET)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_tti_unet),
                        workflows = uiState.ttiUnetWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Image editing (UNET)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_ite_unet),
                        workflows = uiState.iteUnetWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Inpainting (Checkpoint)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_iti_checkpoint),
                        workflows = uiState.itiCheckpointWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Inpainting (UNET)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_iti_unet),
                        workflows = uiState.itiUnetWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Text-to-Video (UNET)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_ttv_unet),
                        workflows = uiState.ttvUnetWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Image-to-Video (UNET)
                item {
                    WorkflowSection(
                        title = stringResource(R.string.workflow_section_itv_unet),
                        workflows = uiState.itvUnetWorkflows,
                        onWorkflowClick = { context.startActivity(WorkflowEditorActivity.createIntent(context, it.id)) },
                        onEditStructure = { editExistingLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, it.id)) },
                        onRename = { viewModel.onEditWorkflow(it) },
                        onDuplicate = { viewModel.onDuplicateWorkflow(it) },
                        onExport = { viewModel.onExportWorkflow(it) },
                        onDelete = { viewModel.onDeleteWorkflow(it) }
                    )
                }

                // Bottom padding
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Import dialog
    if (uiState.showImportDialog) {
        ImportWorkflowDialog(
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
 * A complete workflow section with header and segmented list card.
 */
@Composable
private fun WorkflowSection(
    title: String,
    workflows: List<WorkflowManager.Workflow>,
    onWorkflowClick: (WorkflowManager.Workflow) -> Unit,
    onEditStructure: (WorkflowManager.Workflow) -> Unit,
    onRename: (WorkflowManager.Workflow) -> Unit,
    onDuplicate: (WorkflowManager.Workflow) -> Unit,
    onExport: (WorkflowManager.Workflow) -> Unit,
    onDelete: (WorkflowManager.Workflow) -> Unit
) {
    Column {
        // Section header
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )

        if (workflows.isEmpty()) {
            // Empty state
            Text(
                text = stringResource(R.string.workflow_section_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // Card containing all workflows with dividers
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    workflows.forEachIndexed { index, workflow ->
                        WorkflowListItemContent(
                            workflow = workflow,
                            onClick = { onWorkflowClick(workflow) },
                            onEditStructure = { onEditStructure(workflow) },
                            onRename = { onRename(workflow) },
                            onDuplicate = { onDuplicate(workflow) },
                            onExport = { onExport(workflow) },
                            onDelete = { onDelete(workflow) }
                        )
                        if (index < workflows.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Content for a single workflow item (without Card wrapper).
 */
@Composable
private fun WorkflowListItemContent(
    workflow: WorkflowManager.Workflow,
    onClick: () -> Unit,
    onEditStructure: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Workflow graph thumbnail
        WorkflowThumbnail(
            jsonContent = workflow.jsonContent,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workflow.name,
                style = MaterialTheme.typography.titleSmall
            )
            if (workflow.description.isNotEmpty()) {
                Text(
                    text = workflow.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (workflow.isBuiltIn) {
                Text(
                    text = stringResource(R.string.workflow_built_in_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Context menu
        Box {
            IconButton(onClick = { showContextMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.content_description_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                // Edit workflow (structure) - only for custom workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_edit_structure)) },
                    onClick = {
                        showContextMenu = false
                        onEditStructure()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Tune, contentDescription = null)
                    },
                    enabled = !workflow.isBuiltIn
                )

                // Rename - only for custom workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_rename)) },
                    onClick = {
                        showContextMenu = false
                        onRename()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                    enabled = !workflow.isBuiltIn
                )

                // Duplicate - available for all workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )

                // Export - available for all workflows
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workflow_menu_export)) },
                    onClick = {
                        showContextMenu = false
                        onExport()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                    }
                )

                // Divider above Delete
                HorizontalDivider()

                // Delete - only for custom workflows
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.workflow_menu_delete),
                            color = if (!workflow.isBuiltIn) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = if (!workflow.isBuiltIn) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    },
                    enabled = !workflow.isBuiltIn
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportWorkflowDialog(
    selectedType: WorkflowType?,
    onTypeSelected: (WorkflowType) -> Unit,
    isTypeDropdownExpanded: Boolean,
    onToggleTypeDropdown: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    descriptionError: String?,
    isValidating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val allTypes = listOf(
        WorkflowType.TTI_CHECKPOINT to stringResource(R.string.workflow_section_tti_checkpoint),
        WorkflowType.TTI_UNET to stringResource(R.string.workflow_section_tti_unet),
        WorkflowType.ITE_UNET to stringResource(R.string.workflow_section_ite_unet),
        WorkflowType.ITI_CHECKPOINT to stringResource(R.string.workflow_section_iti_checkpoint),
        WorkflowType.ITI_UNET to stringResource(R.string.workflow_section_iti_unet),
        WorkflowType.TTV_UNET to stringResource(R.string.workflow_section_ttv_unet),
        WorkflowType.ITV_UNET to stringResource(R.string.workflow_section_itv_unet)
    )

    val selectedTypeName = allTypes.find { it.first == selectedType }?.second ?: ""

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text(stringResource(R.string.import_workflow_title)) },
        text = {
            Column {
                // Type dropdown
                Text(
                    text = stringResource(R.string.workflow_type_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = isTypeDropdownExpanded,
                    onExpandedChange = { if (!isValidating) onToggleTypeDropdown() }
                ) {
                    OutlinedTextField(
                        value = selectedTypeName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTypeDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        enabled = !isValidating
                    )
                    ExposedDropdownMenu(
                        expanded = isTypeDropdownExpanded,
                        onDismissRequest = onToggleTypeDropdown
                    ) {
                        allTypes.forEach { (type, displayName) ->
                            DropdownMenuItem(
                                text = { Text(displayName) },
                                onClick = { onTypeSelected(type) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.workflow_name_label)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.workflow_description_label)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating
                )

                if (isValidating) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.validating_workflow))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedType != null && name.isNotBlank() && !isValidating
            ) {
                Text(stringResource(R.string.button_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isValidating) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

@Composable
private fun MissingNodesDialog(
    missingNodes: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.missing_nodes_title)) },
        text = {
            Column {
                Text(stringResource(R.string.missing_nodes_message))
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(missingNodes) { node ->
                        Text(
                            text = "- $node",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

@Composable
private fun MissingFieldsDialog(
    missingFields: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.missing_fields_title)) },
        text = {
            Column {
                Text(stringResource(R.string.missing_fields_message))
                Spacer(modifier = Modifier.height(8.dp))
                missingFields.forEach { field ->
                    Text(
                        text = "- $field",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_dismiss))
            }
        }
    )
}

@Composable
private fun DuplicateNameDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_name_title)) },
        text = { Text(stringResource(R.string.duplicate_name_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_ok))
            }
        }
    )
}

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
        title = { Text(stringResource(R.string.edit_workflow_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.workflow_name_label)) },
                    singleLine = true,
                    isError = nameError != null || name.isBlank(),
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.workflow_description_label)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && nameError == null && descriptionError == null
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
        title = { Text(stringResource(R.string.delete_workflow_title)) },
        text = {
            Text(stringResource(R.string.delete_workflow_message, workflowName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.button_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
        title = { Text(stringResource(R.string.duplicate_workflow_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.workflow_name_label)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.workflow_description_label)) },
                    maxLines = 3,
                    isError = descriptionError != null,
                    supportingText = descriptionError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && nameError == null && descriptionError == null
            ) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}
