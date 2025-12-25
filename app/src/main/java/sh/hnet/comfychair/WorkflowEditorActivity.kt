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
        private const val EXTRA_WORKFLOW_ID = "workflow_id"
        private const val EXTRA_WORKFLOW_JSON = "workflow_json"
        private const val EXTRA_IS_MAPPING_MODE = "is_mapping_mode"
        private const val EXTRA_MAPPING_STATE_JSON = "mapping_state_json"
        private const val EXTRA_WORKFLOW_NAME = "workflow_name"
        private const val EXTRA_WORKFLOW_DESCRIPTION = "workflow_description"

        const val EXTRA_RESULT_MAPPINGS = "result_mappings"

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Get workflow data from intent
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)
        val workflowJson = intent.getStringExtra(EXTRA_WORKFLOW_JSON)
        val isMappingMode = intent.getBooleanExtra(EXTRA_IS_MAPPING_MODE, false)
        val mappingStateJson = intent.getStringExtra(EXTRA_MAPPING_STATE_JSON)
        val workflowName = intent.getStringExtra(EXTRA_WORKFLOW_NAME) ?: ""
        val workflowDescription = intent.getStringExtra(EXTRA_WORKFLOW_DESCRIPTION) ?: ""

        // Initialize ViewModel based on mode
        if (isMappingMode && workflowJson != null && mappingStateJson != null) {
            val mappingState = WorkflowMappingState.fromJson(mappingStateJson)
            if (mappingState != null) {
                viewModel.initializeForMapping(
                    jsonContent = workflowJson,
                    name = workflowName,
                    description = workflowDescription,
                    mappingState = mappingState
                )
            } else {
                // Fallback to view mode if mapping state is invalid
                viewModel.initialize(this, workflowId, workflowJson)
            }
        } else {
            viewModel.initialize(this, workflowId, workflowJson)
        }

        setContent {
            ComfyChairTheme {
                // Observe events for mapping mode
                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is WorkflowEditorEvent.MappingConfirmed -> {
                                setResult(Activity.RESULT_OK, Intent().apply {
                                    putExtra(EXTRA_RESULT_MAPPINGS, event.mappingsJson)
                                })
                                finish()
                            }
                            is WorkflowEditorEvent.MappingCancelled -> {
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
                            if (isMappingMode) {
                                viewModel.cancelMapping()
                            } else {
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }
}
