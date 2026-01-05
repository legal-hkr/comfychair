package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.TextToImageCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.TextToImageEvent
import sh.hnet.comfychair.viewmodel.TextToImageViewModel

/**
 * Text-to-Image generation screen
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextToImageScreen(
    generationViewModel: GenerationViewModel,
    textToImageViewModel: TextToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    // State and effects
    // Initialize ViewModels
    LaunchedEffect(Unit) {
        val client = generationViewModel.getClient()
        if (client != null) {
            textToImageViewModel.initialize(context, client)
        }
    }

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by textToImageViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == TextToImageViewModel.OWNER_ID

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    // Fetch models when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            textToImageViewModel.fetchModels()
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        textToImageViewModel.startListening(generationViewModel)
        onDispose {
            textToImageViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == TextToImageViewModel.OWNER_ID && promptId != null) {
            textToImageViewModel.clearPreviewForExecution(promptId)
            textToImageViewModel.startListening(generationViewModel)
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        textToImageViewModel.events.collect { event ->
            when (event) {
                is TextToImageEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is TextToImageEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // UI composition
    var showOptionsBottomSheet by remember { mutableStateOf(false) }
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar with save/share actions
        TopAppBar(
            title = { Text(stringResource(R.string.nav_text_to_image)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Save to gallery button (only when image exists)
                if (uiState.previewBitmap != null) {
                    IconButton(onClick = {
                        textToImageViewModel.saveToGallery { success ->
                            val messageRes = if (success) R.string.image_saved_to_gallery else R.string.failed_save_image
                            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        textToImageViewModel.getShareIntent()?.let { intent ->
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_image)))
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    }
                }
                // Menu button
                AppMenuDropdown(
                    onSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }
        )

        // Progress indicator - below app bar, only show if THIS screen's job is executing
        if (isThisScreenExecuting) {
            GenerationProgressBar(
                progress = generationState.progress,
                maxProgress = generationState.maxProgress,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Image Preview Area
        // Only allow tapping final generated image, not live previews during generation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = uiState.previewBitmap != null && !isThisScreenExecuting) {
                    // Launch MediaViewer for single image
                    uiState.previewBitmap?.let { bitmap ->
                        val intent = MediaViewerActivity.createSingleImageIntent(
                            context = context,
                            bitmap = bitmap,
                            hostname = generationViewModel.getHostname(),
                            port = generationViewModel.getPort(),
                            filename = uiState.currentImageFilename,
                            subfolder = uiState.currentImageSubfolder,
                            type = uiState.currentImageType
                        )
                        context.startActivity(intent)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (uiState.previewBitmap != null) {
                Image(
                    bitmap = uiState.previewBitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.content_description_generated_image),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Placeholder - app logo
                Image(
                    painter = painterResource(R.drawable.ic_comfychair_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Prompt Input
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = { textToImageViewModel.onPositivePromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.positivePrompt.isNotEmpty()) {
                    IconButton(onClick = { textToImageViewModel.onPositivePromptChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
                    }
                }
            }
        )

        // Generate and Options buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            GenerationButton(
                queueSize = queueState.totalQueueSize,
                isExecuting = queueState.isExecuting,
                isEnabled = uiState.positivePrompt.isNotBlank(),
                isOfflineMode = isOfflineMode,
                onGenerate = {
                    if (textToImageViewModel.hasValidConfiguration()) {
                        val workflowJson = textToImageViewModel.prepareWorkflowJson()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                TextToImageViewModel.OWNER_ID
                            ) { success, _, errorMessage ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        errorMessage ?: context.getString(R.string.error_generation_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_failed_load_workflow),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    if (textToImageViewModel.hasValidConfiguration()) {
                        val workflowJson = textToImageViewModel.prepareWorkflowJson()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                TextToImageViewModel.OWNER_ID,
                                front = true
                            ) { success, _, errorMessage ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        errorMessage ?: context.getString(R.string.error_generation_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.error_failed_load_workflow),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onClearQueue = {
                    generationViewModel.getClient()?.clearQueue { success ->
                        val messageRes = if (success) R.string.queue_cleared_success
                                       else R.string.queue_cleared_failed
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Animate gear icon rotation when options sheet is shown
            val optionsIconRotation by animateFloatAsState(
                targetValue = if (showOptionsBottomSheet) 90f else 0f,
                label = "options icon rotation"
            )

            OutlinedIconButton(
                onClick = { showOptionsBottomSheet = true },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.button_options),
                    modifier = Modifier.rotate(optionsIconRotation)
                )
            }
        }
    }

    // Options Bottom Sheet
    if (showOptionsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsBottomSheet = false },
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(textToImageViewModel) {
                TextToImageCallbacks(
                    onWorkflowChange = textToImageViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    onNegativePromptChange = textToImageViewModel::onNegativePromptChange,
                    onCheckpointChange = textToImageViewModel::onCheckpointChange,
                    onUnetChange = textToImageViewModel::onUnetChange,
                    onVaeChange = textToImageViewModel::onVaeChange,
                    onClipChange = textToImageViewModel::onClipChange,
                    onClip1Change = textToImageViewModel::onClip1Change,
                    onClip2Change = textToImageViewModel::onClip2Change,
                    onClip3Change = textToImageViewModel::onClip3Change,
                    onClip4Change = textToImageViewModel::onClip4Change,
                    onWidthChange = textToImageViewModel::onWidthChange,
                    onHeightChange = textToImageViewModel::onHeightChange,
                    onStepsChange = textToImageViewModel::onStepsChange,
                    onCfgChange = textToImageViewModel::onCfgChange,
                    onSamplerChange = textToImageViewModel::onSamplerChange,
                    onSchedulerChange = textToImageViewModel::onSchedulerChange,
                    onRandomSeedToggle = textToImageViewModel::onRandomSeedToggle,
                    onSeedChange = textToImageViewModel::onSeedChange,
                    onRandomizeSeed = textToImageViewModel::onRandomizeSeed,
                    onDenoiseChange = textToImageViewModel::onDenoiseChange,
                    onBatchSizeChange = textToImageViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = textToImageViewModel::onUpscaleMethodChange,
                    onScaleByChange = textToImageViewModel::onScaleByChange,
                    onStopAtClipLayerChange = textToImageViewModel::onStopAtClipLayerChange,
                    onAddLora = textToImageViewModel::onAddLora,
                    onRemoveLora = textToImageViewModel::onRemoveLora,
                    onLoraNameChange = textToImageViewModel::onLoraNameChange,
                    onLoraStrengthChange = textToImageViewModel::onLoraStrengthChange
                )
            }
            val bottomSheetConfig = remember(uiState, callbacks) {
                uiState.toBottomSheetConfig(callbacks)
            }
            ConfigBottomSheetContent(
                config = bottomSheetConfig,
                workflowName = uiState.selectedWorkflow
            )
        }
    }
}
