package sh.hnet.comfychair

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import sh.hnet.comfychair.ui.screens.WorkflowEditorScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.WorkflowEditorEvent
import sh.hnet.comfychair.viewmodel.WorkflowEditorViewModel
import sh.hnet.comfychair.workflow.WorkflowMappingState

/**
 * Fullscreen activity for editing workflow graphs.
 * Follows the MediaViewerActivity pattern.
 *
 * Can operate in two modes:
 * 1. View mode: Display an existing workflow graph
 * 2. Mapping mode: Allow user to confirm/adjust field-to-node mappings for new workflow upload
 */
class WorkflowEditorActivity : ComponentActivity() {

    private val viewModel: WorkflowEditorViewModel by viewModels()

    companion object {
        private const val TAG = "WorkflowEditor"
        private const val EXTRA_WORKFLOW_ID = "workflow_id"
        private const val EXTRA_WORKFLOW_JSON = "workflow_json"
        private const val EXTRA_IS_MAPPING_MODE = "is_mapping_mode"
        private const val EXTRA_IS_CREATE_MODE = "is_create_mode"
        private const val EXTRA_IS_EDIT_EXISTING_MODE = "is_edit_existing_mode"
        private const val EXTRA_MAPPING_STATE_JSON = "mapping_state_json"
        private const val EXTRA_WORKFLOW_NAME = "workflow_name"
        private const val EXTRA_WORKFLOW_DESCRIPTION = "workflow_description"

        const val EXTRA_RESULT_MAPPINGS = "result_mappings"
        const val EXTRA_RESULT_WORKFLOW_ID = "result_workflow_id"

        /**
         * Create intent to preview a workflow by ID
         */
        fun createIntent(context: Context, workflowId: String): Intent {
            return Intent(context, WorkflowEditorActivity::class.java).apply {
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
            }
        }

        /**
         * Create intent to preview raw workflow JSON
         */
        fun createIntentForJson(context: Context, jsonContent: String): Intent {
            return Intent(context, WorkflowEditorActivity::class.java).apply {
                putExtra(EXTRA_WORKFLOW_JSON, jsonContent)
            }
        }

        /**
         * Create intent for field mapping mode (new workflow upload)
         */
        fun createIntentForMapping(
            context: Context,
            jsonContent: String,
            name: String,
            description: String,
            mappingState: WorkflowMappingState
        ): Intent {
            return Intent(context, WorkflowEditorActivity::class.java).apply {
                putExtra(EXTRA_WORKFLOW_JSON, jsonContent)
                putExtra(EXTRA_IS_MAPPING_MODE, true)
                putExtra(EXTRA_WORKFLOW_NAME, name)
                putExtra(EXTRA_WORKFLOW_DESCRIPTION, description)
                putExtra(EXTRA_MAPPING_STATE_JSON, mappingState.toJson())
            }
        }

        /**
         * Create intent for creating a new workflow from scratch
         */
        fun createIntentForNewWorkflow(context: Context): Intent {
            return Intent(context, WorkflowEditorActivity::class.java).apply {
                putExtra(EXTRA_IS_CREATE_MODE, true)
            }
        }

        /**
         * Create intent for editing an existing user workflow's structure.
         * Unlike create mode, this loads the existing workflow and updates it in place.
         */
        fun createIntentForEditingExisting(context: Context, workflowId: String): Intent {
            return Intent(context, WorkflowEditorActivity::class.java).apply {
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
                putExtra(EXTRA_IS_EDIT_EXISTING_MODE, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Get workflow data from intent
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)
        val workflowJson = intent.getStringExtra(EXTRA_WORKFLOW_JSON)
        val isMappingMode = intent.getBooleanExtra(EXTRA_IS_MAPPING_MODE, false)
        val isCreateMode = intent.getBooleanExtra(EXTRA_IS_CREATE_MODE, false)
        val isEditExistingMode = intent.getBooleanExtra(EXTRA_IS_EDIT_EXISTING_MODE, false)
        val mappingStateJson = intent.getStringExtra(EXTRA_MAPPING_STATE_JSON)
        val workflowName = intent.getStringExtra(EXTRA_WORKFLOW_NAME) ?: ""
        val workflowDescription = intent.getStringExtra(EXTRA_WORKFLOW_DESCRIPTION) ?: ""

        // Initialize ViewModel based on mode
        when {
            isEditExistingMode && workflowId != null -> {
                DebugLogger.i(TAG, "Initializing for editing existing workflow: $workflowId")
                viewModel.initializeForEditingExisting(this, workflowId)
            }
            isCreateMode -> {
                DebugLogger.i(TAG, "Initializing for creating new workflow")
                viewModel.initializeForCreation(this)
            }
            isMappingMode && workflowJson != null && mappingStateJson != null -> {
                DebugLogger.i(TAG, "Initializing for mapping mode: name=$workflowName")
                val mappingState = WorkflowMappingState.fromJson(mappingStateJson)
                if (mappingState != null) {
                    viewModel.initializeForMapping(
                        jsonContent = workflowJson,
                        name = workflowName,
                        description = workflowDescription,
                        mappingState = mappingState
                    )
                } else {
                    DebugLogger.w(TAG, "Invalid mapping state, falling back to view mode")
                    viewModel.initialize(this, workflowId, workflowJson)
                }
            }
            else -> {
                DebugLogger.i(TAG, "Initializing for view mode: workflowId=$workflowId, hasJson=${workflowJson != null}")
                viewModel.initialize(this, workflowId, workflowJson)
            }
        }

        setContent {
            ComfyChairTheme {
                // Observe events
                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is WorkflowEditorEvent.MappingConfirmed -> {
                                DebugLogger.i(TAG, "Mapping confirmed")
                                setResult(Activity.RESULT_OK, Intent().apply {
                                    putExtra(EXTRA_RESULT_MAPPINGS, event.mappingsJson)
                                })
                                finish()
                            }
                            is WorkflowEditorEvent.MappingCancelled -> {
                                DebugLogger.i(TAG, "Mapping cancelled")
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            }
                            is WorkflowEditorEvent.WorkflowCreated -> {
                                DebugLogger.i(TAG, "Workflow created: ${event.workflowId}")
                                setResult(Activity.RESULT_OK, Intent().apply {
                                    putExtra(EXTRA_RESULT_WORKFLOW_ID, event.workflowId)
                                })
                                finish()
                            }
                            is WorkflowEditorEvent.WorkflowUpdated -> {
                                DebugLogger.i(TAG, "Workflow updated: ${event.workflowId}")
                                setResult(Activity.RESULT_OK, Intent().apply {
                                    putExtra(EXTRA_RESULT_WORKFLOW_ID, event.workflowId)
                                })
                                finish()
                            }
                            is WorkflowEditorEvent.CreateCancelled -> {
                                DebugLogger.i(TAG, "Create cancelled")
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            }
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    WorkflowEditorScreen(
                        viewModel = viewModel,
                        onClose = {
                            when {
                                isEditExistingMode -> viewModel.handleEditExistingModeClose()
                                isCreateMode -> viewModel.handleCreateModeClose()
                                isMappingMode -> viewModel.cancelMapping()
                                else -> finish()
                            }
                        }
                    )
                }
            }
        }
    }
}
