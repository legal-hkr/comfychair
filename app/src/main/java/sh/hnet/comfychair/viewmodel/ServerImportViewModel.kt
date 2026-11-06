package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.text.Normalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.ServerWorkflow
import sh.hnet.comfychair.connection.ServerWorkflows
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors

/** What the Import from server page shows in place of its list. */
sealed interface ServerListing {
    data object Loading : ServerListing
    data class Loaded(val workflows: List<ServerWorkflow>) : ServerListing
    data object MultiUser : ServerListing
    data class Failed(val details: String?) : ServerListing
}

data class ServerImportUiState(
    val listing: ServerListing = ServerListing.Loading,
    /** Pull to refresh with a list already shown. */
    val refreshing: Boolean = false,
    val query: String = "",
    /** The workflow being downloaded; the other rows wait. */
    val downloadingPath: String? = null
)

/**
 * The Import from server page: the connected server's saved workflows, searching them, and downloading one to import
 * (the import itself is [WorkflowManagementViewModel]'s, as for a file). Belongs to the page's entry: leaving the page
 * clears the search and cancels a download.
 */
class ServerImportViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ServerImportUiState())
    val uiState: StateFlow<ServerImportUiState> = _uiState.asStateFlow()
    private var download: Job? = null

    init {
        load()
    }

    /** Loads the list; with one shown, a failure keeps it and is reported instead. */
    fun load(context: Context? = null) {
        val shown = _uiState.value.listing as? ServerListing.Loaded
        _uiState.update { if (shown != null) it.copy(refreshing = true) else it.copy(listing = ServerListing.Loading) }
        viewModelScope.launch {
            val client = ConnectionManager.clientOrNull
            val result = if (client == null) ServerWorkflows.ListResult.Failed(null) else ServerWorkflows.list(client)
            _uiState.update { state ->
                when (result) {
                    is ServerWorkflows.ListResult.Ok -> state.copy(listing = ServerListing.Loaded(result.workflows), refreshing = false)
                    ServerWorkflows.ListResult.MultiUser -> state.copy(listing = ServerListing.MultiUser, refreshing = false)
                    is ServerWorkflows.ListResult.Failed -> if (shown != null) {
                        context?.let { AppErrors.report(AppError(it.getString(R.string.error_server_workflows_refresh), details = listOfNotNull(result.details))) }
                        state.copy(refreshing = false)
                    } else {
                        state.copy(listing = ServerListing.Failed(result.details), refreshing = false)
                    }
                }
            }
        }
    }

    fun onQueryChange(query: String) = _uiState.update { it.copy(query = query) }

    /**
     * Downloads [workflow]; a valid one goes to [onDownloaded], anything else is reported and the row is free again.
     */
    fun download(context: Context, workflow: ServerWorkflow, onDownloaded: (json: String) -> Unit) {
        if (_uiState.value.downloadingPath != null) return
        val appContext = context.applicationContext
        _uiState.update { it.copy(downloadingPath = workflow.path) }
        download = viewModelScope.launch {
            val client = ConnectionManager.clientOrNull
            val result = if (client == null) ServerWorkflows.DownloadResult.Failed(null) else ServerWorkflows.download(client, workflow)
            _uiState.update { it.copy(downloadingPath = null) }
            when (result) {
                is ServerWorkflows.DownloadResult.Ok -> onDownloaded(result.json)
                ServerWorkflows.DownloadResult.TooLarge -> AppErrors.report(
                    AppError(appContext.getString(R.string.error_server_workflow_too_large, workflow.name, Formatter.formatShortFileSize(appContext, ServerWorkflows.MAX_BYTES)))
                )
                is ServerWorkflows.DownloadResult.NotWorkflow -> AppErrors.report(
                    AppError(appContext.getString(R.string.error_server_workflow_not_workflow, workflow.name), details = listOfNotNull(result.details))
                )
                is ServerWorkflows.DownloadResult.Failed -> AppErrors.report(
                    AppError(appContext.getString(R.string.error_server_workflow_download, workflow.name), details = listOfNotNull(result.details))
                )
            }
        }
    }

    /** Back during a download stops it. */
    fun cancelDownload() {
        download?.cancel()
        download = null
        _uiState.update { it.copy(downloadingPath = null) }
    }

    companion object {
        /** Search from this many workflows. */
        const val SEARCH_FROM = 8

        /** Case- and accent-insensitive substring match on the name and the folder. */
        fun matches(workflow: ServerWorkflow, query: String): Boolean {
            val q = fold(query.trim())
            return q.isEmpty() || fold(workflow.path.removeSuffix(".json")).contains(q)
        }

        private fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    }
}
