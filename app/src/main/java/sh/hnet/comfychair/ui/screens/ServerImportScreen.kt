package sh.hnet.comfychair.ui.screens

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Calendar
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ServerWorkflow
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.ErrorDetailsDialog
import sh.hnet.comfychair.ui.components.settings.groupShapes
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.workflow.WorkflowImportHost
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.viewmodel.ServerImportViewModel
import sh.hnet.comfychair.viewmodel.ServerListing
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Import from server (spec §16): the workflows saved in ComfyUI's web UI on the connected server, newest first, with a
 * search from [ServerImportViewModel.SEARCH_FROM] workflows. Tapping one downloads it and opens the same Import workflow
 * dialog as a file; a finished import opens the workflow's page over this one, so Back returns here for the next.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ServerImportScreen(
    viewModel: WorkflowManagementViewModel,
    onNavigateBack: () -> Unit,
    onOpenDetails: (workflowId: String) -> Unit
) {
    val context = LocalContext.current
    val listViewModel: ServerImportViewModel = viewModel()
    val state by listViewModel.uiState.collectAsState()
    val serverName = remember {
        ServerStorage(context).getServers().find { it.id == ConnectionManager.currentServerId }?.name.orEmpty()
    }
    // The user's own workflows by name, for the Imported hint; refreshed when one is added
    val workflowsVersion by WorkflowManager.workflowsVersion.collectAsState()
    val ownNames = remember(workflowsVersion) {
        WorkflowManager.getAllWorkflows().filter { !it.isBuiltIn }.map { it.name.trim().lowercase() }.toSet()
    }
    var shownError by remember { mutableStateOf<AppError?>(null) }

    BackHandler(enabled = state.downloadingPath != null) { listViewModel.cancelDownload() }
    WorkflowImportHost(viewModel) { id -> onOpenDetails(id) }
    // Multi-user servers: the file import instead
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onFileSelected(context, it) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize()) {
        MediumFlexibleTopAppBar(
            title = { Text(stringResource(R.string.title_server_import)) },
            subtitle = serverName.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
            navigationIcon = {
                TooltipIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.content_description_back), onNavigateBack)
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior
        )
        when (val listing = state.listing) {
            ServerListing.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            is ServerListing.Failed -> {
                val title = stringResource(R.string.title_server_import_failed)
                StatePanel(Icons.Rounded.CloudOff, title, stringResource(R.string.desc_server_import_failed)) {
                    FilledTonalButton(onClick = { listViewModel.load(context) }) {
                        ButtonIcon(Icons.Rounded.Refresh)
                        Text(stringResource(R.string.button_retry))
                    }
                    TextButton(onClick = { shownError = AppError(title, details = listOfNotNull(listing.details)) }) {
                        Text(stringResource(R.string.button_view))
                    }
                }
            }
            ServerListing.MultiUser -> StatePanel(
                Icons.Rounded.Group,
                stringResource(R.string.title_server_import_multiuser),
                stringResource(R.string.desc_server_import_multiuser)
            ) {
                FilledTonalButton(onClick = { filePicker.launch("application/json") }) {
                    ButtonIcon(Icons.Rounded.UploadFile)
                    Text(stringResource(R.string.button_import_from_file))
                }
            }
            is ServerListing.Loaded -> if (listing.workflows.isEmpty()) {
                StatePanel(Icons.Rounded.FolderOff, stringResource(R.string.title_server_import_empty), stringResource(R.string.desc_server_import_empty)) {
                    FilledTonalButton(onClick = { listViewModel.load(context) }) {
                        ButtonIcon(Icons.Rounded.Refresh)
                        Text(stringResource(R.string.button_refresh))
                    }
                }
            } else {
                WorkflowList(
                    workflows = listing.workflows,
                    query = state.query,
                    onQueryChange = listViewModel::onQueryChange,
                    refreshing = state.refreshing,
                    onRefresh = { listViewModel.load(context) },
                    downloadingPath = state.downloadingPath,
                    ownNames = ownNames,
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    onOpen = { workflow ->
                        listViewModel.download(context, workflow) { json ->
                            viewModel.beginImport(json, source = listOf(serverName, workflow.path).filter { it.isNotEmpty() }.joinToString(SEPARATOR), suggestedName = workflow.name)
                        }
                    }
                )
            }
        }
    }
    shownError?.let { ErrorDetailsDialog(it, onDismiss = { shownError = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowList(
    workflows: List<ServerWorkflow>,
    query: String,
    onQueryChange: (String) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    downloadingPath: String?,
    ownNames: Set<String>,
    modifier: Modifier,
    onOpen: (ServerWorkflow) -> Unit
) {
    val searchable = workflows.size >= ServerImportViewModel.SEARCH_FROM
    val shown = if (searchable) workflows.filter { ServerImportViewModel.matches(it, query) } else workflows
    val filtering = searchable && query.isNotBlank()
    Column(Modifier.fillMaxSize()) {
        if (searchable) {
            // The input field gets its container and shape from the (collapsed) search bar
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = { },
                        expanded = false,
                        onExpandedChange = { },
                        placeholder = { Text(pluralStringResource(R.plurals.hint_server_import_search, workflows.size, workflows.size)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.button_clear_search))
                                }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                expanded = false,
                onExpandedChange = { },
                // The page already sits below the status bar
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) { }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            NoOverscrollContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = modifier.fillMaxSize()
                ) {
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            Text(
                                stringResource(R.string.msg_server_import_no_match, query.trim()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 32.dp)
                            )
                        }
                    }
                    itemsIndexed(shown, key = { _, workflow -> workflow.path }) { index, workflow ->
                        ServerWorkflowRow(
                            workflow = workflow,
                            index = index,
                            count = shown.size,
                            downloading = workflow.path == downloadingPath,
                            imported = workflow.name.trim().lowercase() in ownNames,
                            onOpen = { onOpen(workflow) }
                        )
                    }
                    if (filtering && shown.isNotEmpty()) {
                        item(key = "count") {
                            Text(
                                pluralStringResource(R.plurals.label_server_import_count, workflows.size, shown.size, workflows.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 10.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }
                    }
                    item(key = "insets") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
                }
            }
        }
    }
}

/** A saved workflow: its name, folder and date; an Imported mark or downloading at the end. Too large: disabled, with its size. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServerWorkflowRow(workflow: ServerWorkflow, index: Int, count: Int, downloading: Boolean, imported: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val supporting = buildList {
        if (workflow.folder.isNotEmpty()) add(workflow.folder)
        if (workflow.modified > 0) add(formatModified(context, workflow.modified))
        if (workflow.isTooLarge) add(stringResource(R.string.label_server_workflow_too_large, Formatter.formatShortFileSize(context, workflow.size)))
    }.joinToString(SEPARATOR)
    val downloadingLabel = stringResource(R.string.content_description_downloading)
    SegmentedListItem(
        onClick = onOpen,
        shapes = groupShapes(index, count),
        enabled = !workflow.isTooLarge,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        trailingContent = when {
            downloading -> { { LoadingIndicator(Modifier.size(24.dp)) } }
            // The mark alone, to leave the name its room; TalkBack reads it with the row
            imported -> { { Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.label_server_workflow_imported)) } }
            else -> null
        },
        supportingContent = supporting.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        // The default moves the icon and the mark to the top on tall rows (a wrapped name): in this list they would
        // jump between rows, so they stay centred
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { if (downloading) stateDescription = downloadingLabel }
    ) {
        Text(workflow.name)
    }
}

/** A centred state in place of the list: icon, title, a sentence, buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatePanel(icon: ImageVector, title: String, body: String, buttons: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, bottom = 80.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) { buttons() }
    }
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}

/** Relative within a week ("2 hours ago"), then a short date, with the year only when not this year. */
private fun formatModified(context: Context, millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis in 0 until 7 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val year = { t: Long -> Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR) }
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
        if (year(millis) == year(now)) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, millis, flags)
}

/** Between the parts of a line (folder, date; server, path): the app's separator in every language. */
private const val SEPARATOR = " – "
