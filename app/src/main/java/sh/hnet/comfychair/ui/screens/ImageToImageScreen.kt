package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MaskEditorActivity
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.cache.MaskEditorStateHolder
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.ImageToImageCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageEvent
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State and effects
    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by imageToImageViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == ImageToImageViewModel.OWNER_ID

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    var showOptionsSheet by remember { mutableStateOf(false) }

    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher for source image
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToImageViewModel.onSourceImageChange(context, it)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            imageToImageViewModel.initialize(context, client)
        }
    }

    // Fetch models when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            imageToImageViewModel.fetchModels()
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        imageToImageViewModel.events.collect { event ->
            when (event) {
                is ImageToImageEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToImageEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is ImageToImageEvent.ConnectionFailed -> {
                    // Handled by MainContainerActivity
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        imageToImageViewModel.startListening(generationViewModel)
        onDispose {
            imageToImageViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == ImageToImageViewModel.OWNER_ID && promptId != null) {
            imageToImageViewModel.clearPreviewForExecution(promptId)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW)
            imageToImageViewModel.startListening(generationViewModel)
        }
    }

    // UI composition
    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with image options
        TopAppBar(
            title = { Text(stringResource(R.string.image_to_image_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Upload image button
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.upload_source_image))
                }
                // Edit mask button (only in inpainting mode when source image exists)
                if (uiState.sourceImage != null && uiState.mode == ImageToImageMode.INPAINTING) {
                    IconButton(onClick = {
                        // Initialize state holder and launch mask editor activity
                        MaskEditorStateHolder.initialize(
                            sourceImage = uiState.sourceImage!!,
                            maskPaths = uiState.maskPaths,
                            brushSize = uiState.brushSize,
                            isEraserMode = uiState.isEraserMode,
                            onPathAdded = { path, isEraser, brushSize ->
                                imageToImageViewModel.addMaskPath(path, isEraser, brushSize)
                                // Update state holder with new paths
                                MaskEditorStateHolder.updateMaskPaths(imageToImageViewModel.uiState.value.maskPaths)
                            },
                            onClearMask = {
                                imageToImageViewModel.clearMask()
                                MaskEditorStateHolder.updateMaskPaths(emptyList())
                            },
                            onInvertMask = {
                                imageToImageViewModel.invertMask()
                                MaskEditorStateHolder.updateMaskPaths(imageToImageViewModel.uiState.value.maskPaths)
                            },
                            onBrushSizeChange = { imageToImageViewModel.onBrushSizeChange(it) },
                            onEraserModeChange = { imageToImageViewModel.onEraserModeChange(it) }
                        )
                        context.startActivity(MaskEditorActivity.createIntent(context))
                    }) {
                        Icon(Icons.Default.Brush, contentDescription = stringResource(R.string.edit_mask))
                    }
                    // Clear mask button
                    IconButton(onClick = { imageToImageViewModel.clearMask() }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_mask))
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
        // Only allow tapping final generated image or source image, not live previews during generation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(
                    enabled = (uiState.viewMode == ImageToImageViewMode.PREVIEW && uiState.previewImage != null && !isThisScreenExecuting) ||
                              (uiState.viewMode == ImageToImageViewMode.SOURCE && uiState.sourceImage != null),
                    onClick = {
                        when (uiState.viewMode) {
                            ImageToImageViewMode.PREVIEW -> {
                                // Launch MediaViewer for generated image
                                uiState.previewImage?.let { bitmap ->
                                    val intent = MediaViewerActivity.createSingleImageIntent(
                                        context = context,
                                        bitmap = bitmap,
                                        hostname = generationViewModel.getHostname(),
                                        port = generationViewModel.getPort(),
                                        filename = uiState.previewImageFilename,
                                        subfolder = uiState.previewImageSubfolder,
                                        type = uiState.previewImageType
                                    )
                                    context.startActivity(intent)
                                }
                            }
                            ImageToImageViewMode.SOURCE -> {
                                // Launch MediaViewer for source image (without mask)
                                uiState.sourceImage?.let { bitmap ->
                                    val intent = MediaViewerActivity.createSingleImageIntent(context, bitmap)
                                    context.startActivity(intent)
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (uiState.viewMode) {
                ImageToImageViewMode.SOURCE -> {
                    if (uiState.sourceImage != null) {
                        if (uiState.mode == ImageToImageMode.INPAINTING) {
                            // Read-only preview of source image with mask overlay
                            MaskPreview(
                                sourceImage = uiState.sourceImage,
                                maskPaths = uiState.maskPaths,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Editing mode: show plain source image without mask
                            Image(
                                bitmap = uiState.sourceImage!!.asImageBitmap(),
                                contentDescription = stringResource(R.string.content_description_source_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        // Placeholder - app logo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { imagePickerLauncher.launch("image/*") }
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_comfychair_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = stringResource(R.string.no_source_image),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                ImageToImageViewMode.PREVIEW -> {
                    if (uiState.previewImage != null) {
                        Image(
                            bitmap = uiState.previewImage!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_description_preview),
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
            }
        }

        // View mode toggle
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            SegmentedButton(
                selected = uiState.viewMode == ImageToImageViewMode.SOURCE,
                onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.source_image_tab))
            }
            SegmentedButton(
                selected = uiState.viewMode == ImageToImageViewMode.PREVIEW,
                onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.preview_tab))
            }
        }

        // Prompt Input
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = { imageToImageViewModel.onPositivePromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.positivePrompt.isNotEmpty()) {
                    IconButton(onClick = { imageToImageViewModel.onPositivePromptChange("") }) {
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
                isEnabled = imageToImageViewModel.hasValidConfiguration() &&
                    uiState.positivePrompt.isNotBlank() &&
                    uiState.sourceImage != null,
                isOfflineMode = isOfflineMode,
                isUploading = uiState.isUploading,
                onGenerate = {
                    scope.launch {
                        // In inpainting mode, require mask
                        if (uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.paint_mask_hint),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToImageViewModel.OWNER_ID
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
                                context.getString(R.string.error_generation_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    scope.launch {
                        // In inpainting mode, require mask
                        if (uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.paint_mask_hint),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToImageViewModel.OWNER_ID,
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
                                context.getString(R.string.error_generation_failed),
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
                targetValue = if (showOptionsSheet) 90f else 0f,
                label = "options icon rotation"
            )

            OutlinedIconButton(
                onClick = { showOptionsSheet = true },
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

    // Options bottom sheet
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(imageToImageViewModel) {
                ImageToImageCallbacks(
                    // Mode selection
                    onModeChange = imageToImageViewModel::onModeChange,
                    // Reference image callbacks (editing mode)
                    onReferenceImage1Change = { uri -> imageToImageViewModel.onReferenceImage1Change(context, uri) },
                    onClearReferenceImage1 = imageToImageViewModel::onClearReferenceImage1,
                    onReferenceImage2Change = { uri -> imageToImageViewModel.onReferenceImage2Change(context, uri) },
                    onClearReferenceImage2 = imageToImageViewModel::onClearReferenceImage2,
                    // Inpainting workflow callback
                    onWorkflowChange = imageToImageViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    // Editing workflow callback
                    onEditingWorkflowChange = imageToImageViewModel::onEditingWorkflowChange,
                    onViewEditingWorkflow = {
                        val workflowId = uiState.editingWorkflows
                            .find { it.name == uiState.selectedEditingWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    // Negative prompt
                    onNegativePromptChange = imageToImageViewModel::onNegativePromptChange,
                    // Inpainting model selection callbacks
                    onCheckpointChange = imageToImageViewModel::onCheckpointChange,
                    onUnetChange = imageToImageViewModel::onUnetChange,
                    onVaeChange = imageToImageViewModel::onVaeChange,
                    onClipChange = imageToImageViewModel::onClipChange,
                    onClip1Change = imageToImageViewModel::onClip1Change,
                    onClip2Change = imageToImageViewModel::onClip2Change,
                    onClip3Change = imageToImageViewModel::onClip3Change,
                    onClip4Change = imageToImageViewModel::onClip4Change,
                    // Editing model selection callbacks
                    onEditingUnetChange = imageToImageViewModel::onEditingUnetChange,
                    onEditingLoraChange = imageToImageViewModel::onEditingLoraChange,
                    onEditingVaeChange = imageToImageViewModel::onEditingVaeChange,
                    onEditingClipChange = imageToImageViewModel::onEditingClipChange,
                    onEditingClip1Change = imageToImageViewModel::onEditingClip1Change,
                    onEditingClip2Change = imageToImageViewModel::onEditingClip2Change,
                    onEditingClip3Change = imageToImageViewModel::onEditingClip3Change,
                    onEditingClip4Change = imageToImageViewModel::onEditingClip4Change,
                    // Inpainting parameter callbacks
                    onMegapixelsChange = imageToImageViewModel::onMegapixelsChange,
                    onStepsChange = imageToImageViewModel::onStepsChange,
                    onCfgChange = imageToImageViewModel::onCfgChange,
                    onSamplerChange = imageToImageViewModel::onSamplerChange,
                    onSchedulerChange = imageToImageViewModel::onSchedulerChange,
                    onRandomSeedToggle = imageToImageViewModel::onRandomSeedToggle,
                    onSeedChange = imageToImageViewModel::onSeedChange,
                    onRandomizeSeed = imageToImageViewModel::onRandomizeSeed,
                    onDenoiseChange = imageToImageViewModel::onDenoiseChange,
                    onBatchSizeChange = imageToImageViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = imageToImageViewModel::onUpscaleMethodChange,
                    onScaleByChange = imageToImageViewModel::onScaleByChange,
                    onStopAtClipLayerChange = imageToImageViewModel::onStopAtClipLayerChange,
                    // Editing parameter callbacks
                    onEditingMegapixelsChange = imageToImageViewModel::onEditingMegapixelsChange,
                    onEditingStepsChange = imageToImageViewModel::onEditingStepsChange,
                    onEditingCfgChange = imageToImageViewModel::onEditingCfgChange,
                    onEditingSamplerChange = imageToImageViewModel::onEditingSamplerChange,
                    onEditingSchedulerChange = imageToImageViewModel::onEditingSchedulerChange,
                    onEditingRandomSeedToggle = imageToImageViewModel::onEditingRandomSeedToggle,
                    onEditingSeedChange = imageToImageViewModel::onEditingSeedChange,
                    onEditingRandomizeSeed = imageToImageViewModel::onEditingRandomizeSeed,
                    onEditingDenoiseChange = imageToImageViewModel::onEditingDenoiseChange,
                    onEditingBatchSizeChange = imageToImageViewModel::onEditingBatchSizeChange,
                    onEditingUpscaleMethodChange = imageToImageViewModel::onEditingUpscaleMethodChange,
                    onEditingScaleByChange = imageToImageViewModel::onEditingScaleByChange,
                    onEditingStopAtClipLayerChange = imageToImageViewModel::onEditingStopAtClipLayerChange,
                    // Inpainting LoRA chain callbacks
                    onAddLora = imageToImageViewModel::onAddLora,
                    onRemoveLora = imageToImageViewModel::onRemoveLora,
                    onLoraNameChange = imageToImageViewModel::onLoraNameChange,
                    onLoraStrengthChange = imageToImageViewModel::onLoraStrengthChange,
                    // Editing LoRA chain callbacks
                    onAddEditingLora = imageToImageViewModel::onAddEditingLora,
                    onRemoveEditingLora = imageToImageViewModel::onRemoveEditingLora,
                    onEditingLoraNameChange = imageToImageViewModel::onEditingLoraNameChange,
                    onEditingLoraStrengthChange = imageToImageViewModel::onEditingLoraStrengthChange
                )
            }
            val bottomSheetConfig = remember(uiState, callbacks) {
                uiState.toBottomSheetConfig(callbacks)
            }
            ConfigBottomSheetContent(
                config = bottomSheetConfig,
                workflowName = if (uiState.mode == ImageToImageMode.EDITING)
                    uiState.selectedEditingWorkflow else uiState.selectedWorkflow
            )
        }
    }
}
