@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ToggleOff
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.LocalAppSnackbarHostState
import sh.hnet.comfychair.ui.components.WorkflowGraphPreview
import sh.hnet.comfychair.ui.components.dialogs.DialogTextField
import sh.hnet.comfychair.ui.components.dialogs.FormDialog
import sh.hnet.comfychair.ui.components.settings.ConfirmDestructiveDialog
import sh.hnet.comfychair.ui.components.settings.SettingsActionRow
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsPage
import sh.hnet.comfychair.ui.components.settings.SettingsSwitchRow
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.workflow.MissingFieldsDialog
import sh.hnet.comfychair.ui.components.workflow.MissingNodesDialog
import sh.hnet.comfychair.ui.components.workflow.WorkflowDetailsDialog
import sh.hnet.comfychair.viewmodel.ExportFormat
import sh.hnet.comfychair.viewmodel.WorkflowFilter
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsSettingsScreen(
    viewModel: WorkflowManagementViewModel,
    onNavigateBack: () -> Unit,
    onOpenDetails: (workflowId: String) -> Unit
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
            // The new workflow's page opens
            result.data?.getStringExtra(WorkflowEditorActivity.EXTRA_RESULT_WORKFLOW_ID)?.let(onOpenDetails)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // A just-imported workflow: briefly highlighted, scrolled into view
    var highlightedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) {
            delay(HIGHLIGHT_MS)
            highlightedId = null
        }
    }
    val snackbar = LocalAppSnackbarHostState.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Handle events, only while this page is on top (the detail page handles its own)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
          viewModel.pageEvents.collect { event ->
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
                is WorkflowManagementEvent.WorkflowImported -> {
                    val id = event.workflowId
                    highlightedId = id
                    // Its section's item first, so the row is composed and can bring itself into view
                    val type = WorkflowManager.getWorkflowById(id)?.type
                    val sectionIndex = sectionOrder.filter { it in viewModel.uiState.value.filter.types }.indexOf(type)
                    if (sectionIndex >= 0) listState.scrollToItem(1 + sectionIndex)
                    scope.launch {
                        val result = snackbar?.showSnackbar(
                            message = resources.getString(R.string.msg_workflow_import_success),
                            actionLabel = resources.getString(R.string.button_open),
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) onOpenDetails(id)
                    }
                }
                // From the detail page
                is WorkflowManagementEvent.LaunchExportFilePicker,
                is WorkflowManagementEvent.DuplicateCreated,
                is WorkflowManagementEvent.WorkflowDeleted -> Unit
            }
          }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Import and Create need the server: imports are checked against it, and the editor saves through it
    val offline = remember { AppSettings.isOfflineMode(context) }
    val allSections = listOf(
        WorkflowSectionData(WorkflowType.TTI, R.string.workflow_section_tti, uiState.ttiWorkflows),
        WorkflowSectionData(WorkflowType.ITI_EDITING, R.string.workflow_section_iti_editing, uiState.itiEditingWorkflows),
        WorkflowSectionData(WorkflowType.ITI_INPAINTING, R.string.workflow_section_iti_inpainting, uiState.itiInpaintingWorkflows),
        WorkflowSectionData(WorkflowType.TTV, R.string.workflow_section_ttv, uiState.ttvWorkflows),
        WorkflowSectionData(WorkflowType.ITV, R.string.workflow_section_itv, uiState.itvWorkflows)
    )
    val allWorkflows = allSections.flatMap { it.workflows }
    val enabledTotal = allWorkflows.count { it.id !in uiState.disabledWorkflowIds }
    val openDetails: (WorkflowManager.Workflow) -> Unit = { onOpenDetails(it.id) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.title_workflows_settings)) },
                subtitle = { Text(stringResource(R.string.label_workflows_enabled_count, enabledTotal, allWorkflows.size)) },
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
                        contentPadding = PaddingValues(bottom = 96.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                    ) {
                        item(key = "filters") { WorkflowFilterChips(uiState.filter, viewModel::onFilterChange) }
                        allSections.filter { it.type in uiState.filter.types }.forEach { section ->
                            item(key = section.type.name) {
                                WorkflowSection(
                                    section = section,
                                    disabledWorkflowIds = uiState.disabledWorkflowIds,
                                    highlightedId = highlightedId,
                                    onOpen = openDetails,
                                    onEnabledChange = { workflow, enabled -> viewModel.onWorkflowEnabledChange(workflow, enabled) },
                                    onSectionEnabledChange = { enabled -> viewModel.onSectionEnabledChange(section.type, enabled) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add a workflow: build one in the editor, or import a file (exported from ComfyUI or from this app)
        AddWorkflowMenu(
            offline = offline,
            onCreate = { createEditorLauncher.launch(WorkflowEditorActivity.createIntentForNewWorkflow(context)) },
            onImport = { jsonPickerLauncher.launch("application/json") }
        )
    }

    // Import dialog
    if (uiState.showImportDialog) {
        WorkflowDetailsDialog(
            titleRes = R.string.title_import_workflow,
            confirmLabelRes = R.string.button_continue,
            fileName = uiState.importFileName,
            selectedType = uiState.importSelectedType,
            onTypeSelected = viewModel::onImportTypeSelected,
            name = uiState.importName,
            onNameChange = viewModel::onImportNameChange,
            nameError = uiState.importNameError,
            description = uiState.importDescription,
            onDescriptionChange = viewModel::onImportDescriptionChange,
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
            type = uiState.importSelectedType,
            onDismiss = viewModel::dismissMissingFieldsDialog
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

/** Order of the sections on the list. */
private val sectionOrder = listOf(WorkflowType.TTI, WorkflowType.ITI_EDITING, WorkflowType.ITI_INPAINTING, WorkflowType.TTV, WorkflowType.ITV)

/** How long a just-imported workflow's row stays highlighted before fading back. */
private const val HIGHLIGHT_MS = 1000L

/** A type's workflows on the page, with its title. */
private class WorkflowSectionData(val type: WorkflowType, val titleRes: Int, val workflows: List<WorkflowManager.Workflow>)

private val filterLabels = listOf(
    WorkflowFilter.ALL to R.string.label_workflows_filter_all,
    WorkflowFilter.TTI to R.string.workflow_section_tti,
    WorkflowFilter.ITI to R.string.label_workflows_filter_iti,
    WorkflowFilter.TTV to R.string.workflow_section_ttv,
    WorkflowFilter.ITV to R.string.workflow_section_itv
)

/** Which types the page shows: one choice, scrolled into view when picked. */
@Composable
private fun WorkflowFilterChips(selected: WorkflowFilter, onSelect: (WorkflowFilter) -> Unit) {
    val rowState = rememberLazyListState()
    LaunchedEffect(selected) {
        rowState.animateScrollToItem(filterLabels.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    }
    val groupLabel = stringResource(R.string.content_description_workflows_filter)
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { contentDescription = groupLabel }
    ) {
        items(filterLabels, key = { it.first.name }) { (filter, labelRes) ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(labelRes)) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else null,
                modifier = Modifier.semantics { role = Role.RadioButton }
            )
        }
    }
}

/**
 * A type's workflows: its heading with the count of those switched on (a menu switches all on or off), a warning when
 * none is on, then your workflows (by name) and the built-in ones, each a segmented group of name-and-switch rows.
 */
@Composable
private fun WorkflowSection(
    section: WorkflowSectionData,
    disabledWorkflowIds: Set<String>,
    highlightedId: String?,
    onOpen: (WorkflowManager.Workflow) -> Unit,
    onEnabledChange: (WorkflowManager.Workflow, Boolean) -> Unit,
    onSectionEnabledChange: (Boolean) -> Unit
) {
    val title = stringResource(section.titleRes)
    val workflows = section.workflows
    val enabledCount = workflows.count { it.id !in disabledWorkflowIds }
    val own = workflows.filter { !it.isBuiltIn }.sortedBy { it.name.lowercase() }
    val builtIn = workflows.filter { it.isBuiltIn }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
                    .semantics { heading() }
            )
            if (workflows.isNotEmpty()) {
                SectionCountButton(enabledCount, workflows.size, onSectionEnabledChange)
            }
        }
        if (workflows.isEmpty()) {
            Text(
                text = stringResource(R.string.workflow_section_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else if (enabledCount == 0) {
            NoneOnCard(title)
        }
        listOf(R.string.label_workflows_yours to own, R.string.label_workflows_builtin to builtIn).forEach { (labelRes, group) ->
            if (group.isNotEmpty()) {
                SettingsGroup(stringResource(labelRes), subordinate = true) {
                    group.forEach { workflow ->
                        row { shapes ->
                            WorkflowRow(
                                shapes = shapes,
                                workflow = workflow,
                                enabled = workflow.id !in disabledWorkflowIds,
                                highlighted = workflow.id == highlightedId,
                                onOpen = { onOpen(workflow) },
                                onEnabledChange = { onEnabledChange(workflow, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "n of m on" as a button opening Switch all on / Switch all off; in the error colour when none is on. */
@Composable
private fun SectionCountButton(enabledCount: Int, total: Int, onSectionEnabledChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val count = stringResource(R.string.label_workflows_enabled_count, enabledCount, total)
    val description = stringResource(R.string.content_description_section_actions, count)
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (enabledCount == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.semantics { contentDescription = description }
        ) {
            Text(count, style = MaterialTheme.typography.labelLarge)
            Icon(
                if (expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                // The one that would change nothing is disabled
                listOf(
                    Triple(R.string.menu_switch_all_on, Icons.Rounded.ToggleOn, enabledCount < total),
                    Triple(R.string.menu_switch_all_off, Icons.Rounded.ToggleOff, enabledCount > 0)
                ).forEachIndexed { index, (labelRes, icon, active) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        shape = MenuDefaults.itemShape(index, 2).shape,
                        enabled = active,
                        onClick = {
                            expanded = false
                            onSectionEnabledChange(labelRes == R.string.menu_switch_all_on)
                        }
                    )
                }
            }
        }
    }
}

/** No workflow of this type is on: the screen can't generate. Switching all off is allowed; this is the warning. */
@Composable
private fun NoneOnCard(sectionTitle: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null)
            Text(stringResource(R.string.msg_workflows_none_on, sectionTitle), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A workflow: its name (opens its details) and, past a divider, its switch – two targets, so TalkBack reads them apart.
 * A switched-off row looks like the others; the switch says it.
 */
@Composable
private fun WorkflowRow(
    shapes: ListItemShapes,
    workflow: WorkflowManager.Workflow,
    enabled: Boolean,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    val details = stringResource(R.string.content_description_workflow_details, workflow.name)
    // Just imported: at once in the highlight, then fading back to the list's colour
    val container by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = if (highlighted) snap() else tween(HIGHLIGHT_MS.toInt(), easing = FastOutSlowInEasing),
        label = "row highlight"
    )
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) bringIntoView.bringIntoView() }
    Surface(
        shape = shapes.shape,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = details }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    workflow.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            VerticalDivider(Modifier.height(32.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = workflow.name }
            )
        }
    }
}

/**
 * Adding a workflow, as the result actions on the generation preview: a toggle FAB at the bottom end opening Create new
 * and Import upward, over a scrim; tapping the scrim or Back closes it.
 */
@Composable
private fun BoxScope.AddWorkflowMenu(offline: Boolean, onCreate: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val needsServer = stringResource(R.string.msg_needs_server)
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Both need the server (imports are checked against it, the editor saves through it): offline, they say why
    fun act(action: () -> Unit) {
        expanded = false
        if (offline) Toast.makeText(context, needsServer, Toast.LENGTH_SHORT).show() else action()
    }
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
            onClick = { act(onCreate) },
            icon = { Icon(Icons.Rounded.AccountTree, contentDescription = null) },
            text = { Text(stringResource(R.string.button_create_workflow)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        FloatingActionButtonMenuItem(
            onClick = { act(onImport) },
            icon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
            text = { Text(stringResource(R.string.button_import)) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** The name of a workflow type, as its section on the list. */
private fun WorkflowType.titleRes(): Int = when (this) {
    WorkflowType.TTI -> R.string.workflow_section_tti
    WorkflowType.ITI_EDITING -> R.string.workflow_section_iti_editing
    WorkflowType.ITI_INPAINTING -> R.string.workflow_section_iti_inpainting
    WorkflowType.TTV -> R.string.workflow_section_ttv
    WorkflowType.ITV -> R.string.workflow_section_itv
}

/** The generation screen that offers workflows of a type. */
private fun WorkflowType.screenRes(): Int = when (this) {
    WorkflowType.TTI -> R.string.nav_text_to_image
    WorkflowType.ITI_EDITING, WorkflowType.ITI_INPAINTING -> R.string.nav_image_to_image
    WorkflowType.TTV -> R.string.nav_text_to_video
    WorkflowType.ITV -> R.string.nav_image_to_video
}

/**
 * A workflow's details: its graph (a card opening the editor: read-only for a built-in one, for editing for yours),
 * description, whether the generation screens offer it, then what can be done with it and its export. Yours can also
 * be renamed and deleted (confirmed, apart, at the end); rows that don't apply are left out.
 *
 * @param onOpenDetails Opens another workflow's details in place of this one (a copy just made)
 */
@Composable
fun WorkflowDetailPage(
    viewModel: WorkflowManagementViewModel,
    workflowId: String,
    onNavigateBack: () -> Unit,
    onOpenDetails: (workflowId: String) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbar = LocalAppSnackbarHostState.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.initialize(context) }
    val current = remember(uiState, workflowId) {
        listOf(uiState.ttiWorkflows, uiState.itiEditingWorkflows, uiState.itiInpaintingWorkflows, uiState.ttvWorkflows, uiState.itvWorkflows)
            .flatten()
            .firstOrNull { it.id == workflowId }
    }
    // After deleting, the page goes back to the list and shows the workflow as it was until the transition ends
    val lastShown = remember { mutableStateOf<WorkflowManager.Workflow?>(null) }
    SideEffect { if (current != null) lastShown.value = current }
    val workflow = current ?: lastShown.value
    var confirmDelete by remember { mutableStateOf(false) }

    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // The editor saved the workflow's structure
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.reloadAndRefreshWorkflows()
            snackbar?.let { host -> scope.launch { host.showSnackbar(context.getString(R.string.msg_workflow_saved)) } }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.performExport(context, uri) else viewModel.cancelExport()
    }

    // Only while this page is on top (the list handles its own)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.pageEvents.collect { event ->
                when (event) {
                    is WorkflowManagementEvent.ShowToast -> Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                    is WorkflowManagementEvent.ShowToastMessage -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                    is WorkflowManagementEvent.LaunchExportFilePicker -> exportLauncher.launch(event.suggestedFilename)
                    is WorkflowManagementEvent.DuplicateCreated -> {
                        onOpenDetails(event.workflowId)
                        scope.launch { snackbar?.showSnackbar(resources.getString(R.string.msg_workflow_duplicate_success)) }
                    }
                    is WorkflowManagementEvent.WorkflowDeleted -> {
                        onNavigateBack()
                        scope.launch { snackbar?.showSnackbar(resources.getString(R.string.msg_workflow_delete_success)) }
                    }
                    is WorkflowManagementEvent.LaunchEditor,
                    is WorkflowManagementEvent.WorkflowImported -> Unit
                }
            }
        }
    }

    // Not loaded yet (no automatic Back here: after a deletion the page has gone back already, and a second Back
    // would leave the Workflows page too)
    if (workflow == null) return
    val builtIn = workflow.isBuiltIn
    val enabled = workflow.id !in uiState.disabledWorkflowIds
    val viewLabel = stringResource(R.string.button_view_graph)
    // The card shows the graph full screen, only to look at; editing (own workflows) is a row below
    val viewGraph = { context.startActivity(WorkflowEditorActivity.createIntent(context, workflow.id, readOnly = true)) }

    SettingsPage(
        title = workflow.name,
        onNavigateBack = onNavigateBack,
        subtitle = stringResource(
            R.string.label_workflow_subtitle,
            stringResource(workflow.type.titleRes()),
            stringResource(if (builtIn) R.string.label_workflow_kind_builtin else R.string.label_workflow_kind_yours)
        )
    ) {
        // The whole graph as the editor draws it; the whole card opens it
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(188.dp)
        ) {
            Box {
                WorkflowGraphPreview(
                    jsonContent = workflow.jsonContent,
                    description = viewLabel,
                    onClick = viewGraph,
                    modifier = Modifier.fillMaxSize()
                )
                // A button itself: a plain surface here would swallow the tap
                Surface(
                    onClick = viewGraph,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(viewLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (workflow.description.isNotBlank()) {
            var expanded by remember(workflow.id) { mutableStateOf(false) }
            Text(
                text = workflow.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .clickable { expanded = !expanded }
            )
        }

        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(
                    shapes = shapes,
                    title = stringResource(R.string.label_show_in_screens),
                    supporting = stringResource(workflow.type.screenRes()),
                    checked = enabled,
                    onCheckedChange = { viewModel.onWorkflowEnabledChange(workflow, it) }
                )
            }
        }

        SettingsGroup(stringResource(R.string.label_workflow)) {
            // Own workflows only: the card above already shows the graph
            if (!builtIn) {
                row { shapes ->
                    SettingsActionRow(
                        shapes = shapes,
                        icon = Icons.Rounded.Edit,
                        title = stringResource(R.string.button_edit_graph),
                        onClick = { editLauncher.launch(WorkflowEditorActivity.createIntentForEditingExisting(context, workflow.id)) }
                    )
                }
                row { shapes ->
                    SettingsActionRow(
                        shapes = shapes,
                        icon = Icons.AutoMirrored.Rounded.Notes,
                        title = stringResource(R.string.button_name_and_description),
                        onClick = { viewModel.onEditWorkflow(workflow) }
                    )
                }
            }
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(R.string.workflow_menu_duplicate),
                    supporting = if (builtIn) stringResource(R.string.desc_duplicate_builtin) else null,
                    onClick = { viewModel.onDuplicateWorkflow(workflow) }
                )
            }
        }

        SettingsGroup(stringResource(R.string.title_workflow_export)) {
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.button_export_app),
                    onClick = { viewModel.onExportWorkflow(workflow, ExportFormat.INTERNAL) }
                )
            }
            row { shapes ->
                SettingsActionRow(
                    shapes = shapes,
                    icon = Icons.Rounded.DataObject,
                    title = stringResource(R.string.button_export_api),
                    onClick = { viewModel.onExportWorkflow(workflow, ExportFormat.API) }
                )
            }
        }

        // Apart, at the end, confirmed
        if (!builtIn) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                row { shapes ->
                    SettingsActionRow(
                        shapes = shapes,
                        icon = Icons.Rounded.Delete,
                        title = stringResource(R.string.button_delete_workflow),
                        destructive = true,
                        onClick = { confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDestructiveDialog(
            title = stringResource(R.string.title_delete_named, workflow.name),
            text = stringResource(R.string.msg_delete_workflow_device),
            confirmLabel = stringResource(R.string.button_delete),
            icon = Icons.Rounded.Delete,
            onConfirm = { viewModel.deleteWorkflow(workflow) },
            onDismiss = { confirmDelete = false }
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
            onConfirm = viewModel::confirmEdit,
            onDismiss = viewModel::cancelEdit
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
            onConfirm = viewModel::confirmDuplicate,
            onDismiss = viewModel::cancelDuplicate
        )
    }
}

/** Name and description of an own workflow (form); a name already taken shows as the field's error. */
@Composable
private fun EditWorkflowDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    FormDialog(
        title = stringResource(R.string.button_name_and_description),
        confirmLabel = stringResource(R.string.button_save),
        confirmEnabled = name.isNotBlank() && nameError == null,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        DialogTextField(name, onNameChange, stringResource(R.string.label_workflow_name), error = nameError)
        DialogTextField(
            description,
            onDescriptionChange,
            stringResource(R.string.label_workflow_description),
            multiLine = true,
            optional = true
        )
    }
}

/** A copy of a workflow: its name (suggested as "… copy") and description (form). */
@Composable
private fun DuplicateWorkflowDialog(
    name: String,
    onNameChange: (String) -> Unit,
    nameError: String?,
    description: String,
    onDescriptionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    FormDialog(
        title = stringResource(R.string.title_duplicate_workflow),
        confirmLabel = stringResource(R.string.workflow_menu_duplicate),
        confirmEnabled = name.isNotBlank() && nameError == null,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        DialogTextField(name, onNameChange, stringResource(R.string.label_workflow_name), error = nameError)
        DialogTextField(
            description,
            onDescriptionChange,
            stringResource(R.string.label_workflow_description),
            multiLine = true,
            optional = true
        )
    }
}
