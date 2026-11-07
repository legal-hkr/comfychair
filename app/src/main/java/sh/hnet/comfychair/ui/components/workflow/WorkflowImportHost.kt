package sh.hnet.comfychair.ui.components.workflow

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.json.JSONArray
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.CustomFields
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.viewmodel.WorkflowManagementEvent
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Importing a workflow, after it was read from a file or downloaded from the server
 * ([WorkflowManagementViewModel.beginImport]): the Import workflow dialog, missing nodes or fields, and the field
 * mapping in the workflow editor. The page on top hosts it (Workflows, Import from server) and decides what a finished
 * import shows ([onImported] with the new workflow's ID). Events are taken only while the page is on top.
 */
@Composable
fun WorkflowImportHost(viewModel: WorkflowManagementViewModel, onImported: suspend (workflowId: String) -> Unit) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val imported by rememberUpdatedState(onImported)

    // The workflow editor, for mapping the fields the app fills
    val editorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val mappingsJson = result.data?.getStringExtra(WorkflowEditorActivity.EXTRA_RESULT_MAPPINGS)
            val customFields = result.data?.getStringExtra(WorkflowEditorActivity.EXTRA_RESULT_CUSTOM_FIELDS)
                ?.let { runCatching { CustomFields.fromJson(JSONArray(it)) }.getOrNull() }
            if (mappingsJson != null) viewModel.completeImport(parseMappingsFromJson(mappingsJson), customFields)
        } else {
            viewModel.cancelMapping()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.pageEvents.collect { event ->
                when (event) {
                    is WorkflowManagementEvent.ShowToast -> AppErrors.notify(context, event.messageResId)
                    is WorkflowManagementEvent.LaunchEditor -> {
                        val pending = event.pendingUpload
                        val mappingState = pending.mappingState
                        if (mappingState != null) {
                            editorLauncher.launch(
                                WorkflowEditorActivity.createIntentForMapping(
                                    context = context,
                                    jsonContent = pending.jsonContent,
                                    name = pending.name,
                                    description = pending.description,
                                    mappingState = mappingState
                                )
                            )
                        }
                    }
                    is WorkflowManagementEvent.WorkflowImported -> imported(event.workflowId)
                    // The detail page's
                    is WorkflowManagementEvent.LaunchExportFilePicker,
                    is WorkflowManagementEvent.DuplicateCreated,
                    is WorkflowManagementEvent.WorkflowDeleted -> Unit
                }
            }
        }
    }

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

    if (uiState.showMissingNodesDialog) {
        MissingNodesDialog(
            missingNodes = uiState.missingNodes,
            onDismiss = viewModel::dismissMissingNodesDialog
        )
    }

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
