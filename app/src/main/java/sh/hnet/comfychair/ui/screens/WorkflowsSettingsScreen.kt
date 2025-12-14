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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowPreviewerActivity
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsSettingsScreen(
    viewModel: WorkflowManagementViewModel,
    comfyUIClient: ComfyUIClient,
    onNavigateToGeneration: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    // JSON file picker
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.onFileSelected(context, it)
        }
    }

    // Previewer launcher for mapping mode
    val previewerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mappingsJson = result.data?.getStringExtra(WorkflowPreviewerActivity.EXTRA_RESULT_MAPPINGS)
            if (mappingsJson != null) {
                // Parse mappings and complete upload
                val mappings = parseMappingsFromJson(mappingsJson)
                viewModel.completeUpload(mappings)
            }
        } else {
            viewModel.cancelMapping()
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
                is WorkflowManagementEvent.LaunchPreviewer -> {
                    val pending = event.pendingUpload
                    val mappingState = pending.mappingState
                    if (mappingState != null) {
                        val intent = WorkflowPreviewerActivity.createIntentForMapping(
                            context = context,
                            jsonContent = pending.jsonContent,
                            name = pending.name,
                            description = pending.description,
                            mappingState = mappingState
                        )
                        previewerLauncher.launch(intent)
                    }
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
                // Upload button
                IconButton(onClick = { jsonPickerLauncher.launch("application/json") }) {
                    Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.button_upload))
                }
                // Menu button
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_description_menu))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_generation)) },
                            onClick = {
                                showMenu = false
                                onNavigateToGeneration()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_logout)) },
                            onClick = {
                                showMenu = false
                                onLogout()
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            }
                        )
                    }
                }
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
                    WorkflowSectionHeader(stringResource(R.string.workflow_section_tti_checkpoint))
                }
                if (uiState.ttiCheckpointWorkflows.isEmpty()) {
                    item { EmptySection() }
                } else {
                    items(uiState.ttiCheckpointWorkflows) { workflow ->
                        WorkflowListItem(
                            workflow = workflow,
                            onClick = { context.startActivity(WorkflowPreviewerActivity.createIntent(context, workflow.id)) },
                            onEdit = { viewModel.onEditWorkflow(it) },
                            onDelete = { viewModel.onDeleteWorkflow(it) }
                        )
                    }
                }

                // Text-to-Image (UNET)
                item {
                    WorkflowSectionHeader(stringResource(R.string.workflow_section_tti_unet))
                }
                if (uiState.ttiUnetWorkflows.isEmpty()) {
                    item { EmptySection() }
                } else {
                    items(uiState.ttiUnetWorkflows) { workflow ->
                        WorkflowListItem(
                            workflow = workflow,
                            onClick = { context.startActivity(WorkflowPreviewerActivity.createIntent(context, workflow.id)) },
                            onEdit = { viewModel.onEditWorkflow(it) },
                            onDelete = { viewModel.onDeleteWorkflow(it) }
                        )
                    }
                }

                // Text-to-Video (UNET)
                item {
                    WorkflowSectionHeader(stringResource(R.string.workflow_section_ttv_unet))
                }
                if (uiState.ttvUnetWorkflows.isEmpty()) {
                    item { EmptySection() }
                } else {
                    items(uiState.ttvUnetWorkflows) { workflow ->
                        WorkflowListItem(
                            workflow = workflow,
                            onClick = { context.startActivity(WorkflowPreviewerActivity.createIntent(context, workflow.id)) },
                            onEdit = { viewModel.onEditWorkflow(it) },
                            onDelete = { viewModel.onDeleteWorkflow(it) }
                        )
                    }
                }

                // Image-to-Video (UNET)
                item {
                    WorkflowSectionHeader(stringResource(R.string.workflow_section_itv_unet))
                }
                if (uiState.itvUnetWorkflows.isEmpty()) {
                    item { EmptySection() }
                } else {
                    items(uiState.itvUnetWorkflows) { workflow ->
                        WorkflowListItem(
                            workflow = workflow,
                            onClick = { context.startActivity(WorkflowPreviewerActivity.createIntent(context, workflow.id)) },
                            onEdit = { viewModel.onEditWorkflow(it) },
                            onDelete = { viewModel.onDeleteWorkflow(it) }
                        )
                    }
                }

                // Inpainting (Checkpoint)
                item {
                    WorkflowSectionHeader(stringResource(R.string.workflow_section_iip_checkpoint))
                }
                if (uiState.iipCheckpointWorkflows.isEmpty()) {
                    item { EmptySection() }
                } else {
                    items(uiState.iipCheckpointWorkflows) { workflow ->
                        WorkflowListItem(
                            workflow = workflow,
                            onClick = { context.startActivity(WorkflowPreviewerActivity.createIntent(context, workflow.id)) },
                            onEdit = { viewModel.onEditWorkflow(it) },
                            onDelete = { viewModel.onDeleteWorkflow(it) }
                        )
                    }
                }

                // Inpainting (UNET)
                item {
                    WorkflowSectionHeader(stringResource(R.string.workflow_section_iip_unet))
                }
                if (uiState.iipUnetWorkflows.isEmpty()) {
                    item { EmptySection() }
                } else {
                    items(uiState.iipUnetWorkflows) { workflow ->
                        WorkflowListItem(
                            workflow = workflow,
                            onClick = { context.startActivity(WorkflowPreviewerActivity.createIntent(context, workflow.id)) },
                            onEdit = { viewModel.onEditWorkflow(it) },
                            onDelete = { viewModel.onDeleteWorkflow(it) }
                        )
                    }
                }

                // Bottom padding
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    // Upload dialog
    if (uiState.showUploadDialog) {
        UploadWorkflowDialog(
            selectedType = uiState.uploadSelectedType,
            onTypeSelected = viewModel::onUploadTypeSelected,
            isTypeDropdownExpanded = uiState.uploadTypeDropdownExpanded,
            onToggleTypeDropdown = viewModel::onToggleTypeDropdown,
            name = uiState.uploadName,
            onNameChange = viewModel::onUploadNameChange,
            nameError = uiState.uploadNameError,
            description = uiState.uploadDescription,
            onDescriptionChange = viewModel::onUploadDescriptionChange,
            descriptionError = uiState.uploadDescriptionError,
            isValidating = uiState.isValidatingNodes,
            onConfirm = { viewModel.proceedWithUpload(comfyUIClient) },
            onDismiss = viewModel::cancelUpload
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
            description = uiState.editDescription,
            onDescriptionChange = viewModel::onEditDescriptionChange,
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

@Composable
private fun WorkflowSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun EmptySection() {
    Text(
        text = stringResource(R.string.workflow_section_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun WorkflowListItem(
    workflow: WorkflowManager.Workflow,
    onClick: () -> Unit,
    onEdit: (WorkflowManager.Workflow) -> Unit,
    onDelete: (WorkflowManager.Workflow) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            // Edit/Delete buttons (only for user workflows)
            if (!workflow.isBuiltIn) {
                IconButton(onClick = { onEdit(workflow) }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onDelete(workflow) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadWorkflowDialog(
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
        WorkflowType.TTV_UNET to stringResource(R.string.workflow_section_ttv_unet),
        WorkflowType.ITV_UNET to stringResource(R.string.workflow_section_itv_unet),
        WorkflowType.IIP_CHECKPOINT to stringResource(R.string.workflow_section_iip_checkpoint),
        WorkflowType.IIP_UNET to stringResource(R.string.workflow_section_iip_unet)
    )

    val selectedTypeName = allTypes.find { it.first == selectedType }?.second ?: ""

    AlertDialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text(stringResource(R.string.upload_workflow_title)) },
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
    description: String,
    onDescriptionChange: (String) -> Unit,
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
                    isError = name.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.workflow_description_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank()
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
