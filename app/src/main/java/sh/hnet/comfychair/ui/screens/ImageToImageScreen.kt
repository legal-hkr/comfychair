package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MaskEditorActivity
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.cache.MaskEditorStateHolder
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSourceButton
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.GenerationScreenEvent
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    // Prompt preset ViewModel
    val presetViewModel: PromptPresetViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by imageToImageViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()

    // Initialize preset ViewModel (shared for both inpainting and editing modes)
    LaunchedEffect(Unit) {
        presetViewModel.initialize(context, ScreenType.IMAGE_TO_IMAGE)
    }

    // Refresh presets when screen resumes (catches external changes from Media Viewer)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                presetViewModel.refreshPresets()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == ImageToImageViewModel.OWNER_ID
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting &&
            queueState.ownJobs.values.any { it.ownerId == ImageToImageViewModel.OWNER_ID }


    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }
    var spellCheckEnabled by remember { mutableStateOf(AppSettings.isPromptSpellCheckEnabled(context)) }
    var promptExpandEnabled by remember { mutableStateOf(AppSettings.isPromptExpandEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                spellCheckEnabled = AppSettings.isPromptSpellCheckEnabled(context)
                promptExpandEnabled = AppSettings.isPromptExpandEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val positivePromptTransformation = rememberSpellCheckVisualTransformation(
        text = uiState.positivePrompt,
        enabled = spellCheckEnabled
    )
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    var prevImeHeight by remember { mutableStateOf(imeHeight) }
    SideEffect { prevImeHeight = imeHeight }
    var promptFocused by remember { mutableStateOf(false) }
    val expandPrompt = promptExpandEnabled && promptFocused && imeHeight > 0 && imeHeight >= prevImeHeight

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
                is GenerationScreenEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is GenerationScreenEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Preset event handling
    LaunchedEffect(Unit) {
        presetViewModel.events.collect { event ->
            when (event) {
                is PromptPresetEvent.PresetApplied -> {
                    imageToImageViewModel.onPositivePromptChange(event.prompt)
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, resources.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, resources.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    imageToImageViewModel.resetPromptToDefault()
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
            title = { Text(stringResource(R.string.title_image_to_image)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Upload image button
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.button_upload_source_image))
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
                        Icon(Icons.Default.Brush, contentDescription = stringResource(R.string.button_edit_mask))
                    }
                    // Clear mask button
                    IconButton(onClick = { imageToImageViewModel.clearMask() }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.button_clear_mask))
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

        // Image Preview Area + view mode toggle — collapse when typing in the prompt field
        AnimatedVisibility(
            visible = !expandPrompt,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(150)),
            exit = ExitTransition.None
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 150.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(
                            enabled = (uiState.viewMode == ImageToImageViewMode.PREVIEW && uiState.previewImage != null && !isThisScreenExecuting && !showResults) ||
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
                                        text = stringResource(R.string.msg_no_source_image),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        ImageToImageViewMode.PREVIEW -> {
                            if (showResults) {
                                val results = uiState.generationOutputs
                                val openViewer = rememberResultsViewer(
                                    generationViewModel.getHostname(), generationViewModel.getPort(), results, imageToImageViewModel::onCurrentResultChange
                                )
                                ResultsPreview(
                                    results = results,
                                    currentIndex = uiState.currentResultIndex,
                                    onCurrentIndexChange = imageToImageViewModel::onCurrentResultChange,
                                    onOpenViewer = openViewer,
                                    stripEnabled = !isThisScreenQueued,
                                    heroOverlay = { current ->
                                        if (!current.isVideo) UseAsSourceButton { target -> onUseAsSource(target) { loadResultBitmap(current) } }
                                    }
                                ) { output, isCurrent ->
                                    if (output.isVideo) {
                                        ResultHeroVideo(output = output, isCurrent = isCurrent, isActive = true)
                                    } else {
                                        // The image already shown on the screen doesn't need loading again
                                        ResultHeroImage(
                                            output = output,
                                            preloaded = uiState.previewImage.takeIf { output.filename == uiState.previewImageFilename }
                                        )
                                    }
                                }
                            } else if (uiState.previewImage != null) {
                                Image(
                                    bitmap = uiState.previewImage!!.asImageBitmap(),
                                    contentDescription = stringResource(R.string.content_description_preview),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                // While generating, the area shows live previews instead of a result
                                if (!isThisScreenExecuting) {
                                    val result = uiState.previewImage
                                    UseAsSourceButton { target -> onUseAsSource(target) { result } }
                                }
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

                // View mode toggle, on the preview area's background like the results strip
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                ) {
                    SegmentedButton(
                        selected = uiState.viewMode == ImageToImageViewMode.SOURCE,
                        onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_source_image))
                    }
                    SegmentedButton(
                        selected = uiState.viewMode == ImageToImageViewMode.PREVIEW,
                        onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_preview))
                    }
                }
            }
        }

        // Prompt Input — expands to fill screen above keyboard when focused.
        // Hidden for workflows without a positive prompt placeholder (e.g. upscalers).
        if (uiState.capabilities.hasPositivePrompt) {
            OutlinedTextField(
                value = uiState.positivePrompt,
                onValueChange = {
                    imageToImageViewModel.onPositivePromptChange(it)
                    presetViewModel.clearActivePreset()
                },
                label = { Text(stringResource(R.string.hint_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (expandPrompt) Modifier.weight(1f) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onFocusChanged { promptFocused = it.isFocused },
                minLines = 2,
                maxLines = if (expandPrompt) Int.MAX_VALUE else 4,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = spellCheckEnabled),
                visualTransformation = positivePromptTransformation,
                leadingIcon = {
                    PromptPresetDropdown(
                        favorites = presetUiState.favorites,
                        activePresetId = presetUiState.activePresetId,
                        currentPromptIsEmpty = uiState.positivePrompt.isEmpty(),
                        onPresetSelected = { presetViewModel.onPresetSelected(it) },
                        onOpenLibrary = { presetViewModel.showLibrary() },
                        onSaveCurrentPrompt = { presetViewModel.showSaveDialog(uiState.positivePrompt) },
                        onResetPrompt = { presetViewModel.resetPrompt() }
                    )
                },
                trailingIcon = {
                    if (uiState.positivePrompt.isNotEmpty()) {
                        IconButton(onClick = { imageToImageViewModel.onPositivePromptChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
                        }
                    }
                }
            )
        }

        // Generate and Options buttons; without the prompt field, keep the gap it used to provide
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (uiState.capabilities.hasPositivePrompt) 0.dp else 16.dp, bottom = 16.dp)
        ) {
            GenerationButton(
                queueSize = queueState.totalQueueSize,
                isExecuting = queueState.isExecuting,
                isEnabled = imageToImageViewModel.hasValidConfiguration() &&
                    (!uiState.capabilities.hasPositivePrompt || uiState.positivePrompt.isNotBlank()) &&
                    uiState.sourceImage != null,
                isOfflineMode = isOfflineMode,
                isUploading = uiState.isUploading,
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    scope.launch {
                        // In inpainting mode, require mask
                        if (uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.hint_paint_mask),
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
                                        errorMessage ?: resources.getString(R.string.error_generation_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.error_generation_failed),
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
                                resources.getString(R.string.hint_paint_mask),
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
                                        errorMessage ?: resources.getString(R.string.error_generation_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.error_generation_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onClearQueue = {
                    generationViewModel.getClient()?.clearQueue { success ->
                        val messageRes = if (success) R.string.msg_queue_cleared_success
                                       else R.string.error_queue_clear
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, resources.getString(messageRes), Toast.LENGTH_SHORT).show()
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
    } // End of outer Column

    // Options bottom sheet
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            // The ViewModel applies every change to the current mode's workflow and parameters
            val callbacks = remember(imageToImageViewModel) {
                UnifiedCallbacks(
                    // Mode selection
                    onModeChange = imageToImageViewModel::onModeChange,
                    // Reference image callbacks (editing mode)
                    onReferenceImage1Change = { uri -> imageToImageViewModel.onReferenceImage1Change(context, uri) },
                    onClearReferenceImage1 = imageToImageViewModel::onClearReferenceImage1,
                    onReferenceImage2Change = { uri -> imageToImageViewModel.onReferenceImage2Change(context, uri) },
                    onClearReferenceImage2 = imageToImageViewModel::onClearReferenceImage2,
                    // Workflow
                    onWorkflowChange = imageToImageViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        uiState.selectedWorkflowId.takeIf { it.isNotEmpty() }?.let { workflowId ->
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    // Negative prompt
                    onNegativePromptChange = imageToImageViewModel::onNegativePromptChange,
                    // Models
                    onCheckpointChange = imageToImageViewModel::onCheckpointChange,
                    onUnetChange = imageToImageViewModel::onUnetChange,
                    onVaeChange = imageToImageViewModel::onVaeChange,
                    onClipChange = imageToImageViewModel::onClipChange,
                    onClip1Change = imageToImageViewModel::onClip1Change,
                    onClip2Change = imageToImageViewModel::onClip2Change,
                    onClip3Change = imageToImageViewModel::onClip3Change,
                    onClip4Change = imageToImageViewModel::onClip4Change,
                    onTextEncoderChange = imageToImageViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = imageToImageViewModel::onLatentUpscaleModelChange,
                    onMandatoryLoraChange = imageToImageViewModel::onMandatoryLoraChange,
                    onHighnoiseUnetChange = imageToImageViewModel::onHighnoiseUnetChange,
                    onLownoiseUnetChange = imageToImageViewModel::onLownoiseUnetChange,
                    onHighnoiseLoraChange = imageToImageViewModel::onHighnoiseLoraChange,
                    onLownoiseLoraChange = imageToImageViewModel::onLownoiseLoraChange,
                    // Parameters
                    onMegapixelsChange = imageToImageViewModel::onMegapixelsChange,
                    onWidthChange = imageToImageViewModel::onWidthChange,
                    onHeightChange = imageToImageViewModel::onHeightChange,
                    onLengthChange = imageToImageViewModel::onLengthChange,
                    onFpsChange = imageToImageViewModel::onFpsChange,
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
                    // LoRA chains
                    onAddLora = imageToImageViewModel::onAddLora,
                    onRemoveLora = imageToImageViewModel::onRemoveLora,
                    onLoraNameChange = imageToImageViewModel::onLoraNameChange,
                    onLoraStrengthChange = imageToImageViewModel::onLoraStrengthChange,
                    onAddHighnoiseLora = imageToImageViewModel::onAddHighnoiseLora,
                    onRemoveHighnoiseLora = imageToImageViewModel::onRemoveHighnoiseLora,
                    onHighnoiseLoraNameChange = imageToImageViewModel::onHighnoiseLoraChainNameChange,
                    onHighnoiseLoraStrengthChange = imageToImageViewModel::onHighnoiseLoraChainStrengthChange,
                    onAddLownoiseLora = imageToImageViewModel::onAddLownoiseLora,
                    onRemoveLownoiseLora = imageToImageViewModel::onRemoveLownoiseLora,
                    onLownoiseLoraNameChange = imageToImageViewModel::onLownoiseLoraChainNameChange,
                    onLownoiseLoraStrengthChange = imageToImageViewModel::onLownoiseLoraChainStrengthChange
                )
            }
            val bottomSheetConfig = remember(uiState, callbacks) {
                uiState.toBottomSheetConfig(callbacks)
            }
            ConfigBottomSheetContent(
                config = bottomSheetConfig,
                workflowName = uiState.selectedWorkflow,
                spellCheckEnabled = spellCheckEnabled
            )
        }
    }

    // Prompt Library Dialog
    if (presetUiState.showLibrarySideSheet) {
        PromptLibraryDialog(
            presets = presetViewModel.getFilteredPresets(),
            availableTags = presetUiState.availableTags,
            searchQuery = presetUiState.searchQuery,
            selectedTags = presetUiState.selectedTags,
            filterFavoritesOnly = presetUiState.filterFavoritesOnly,
            activePresetId = presetUiState.activePresetId,
            onSearchQueryChange = { presetViewModel.onSearchQueryChange(it) },
            onTagToggle = { presetViewModel.onTagToggle(it) },
            onToggleFavoritesFilter = { presetViewModel.onToggleFavoritesFilter() },
            onPresetSelected = { presetViewModel.onPresetSelected(it) },
            onToggleFavorite = { presetViewModel.onToggleFavorite(it) },
            onEditPreset = { presetViewModel.showEditDialog(it) },
            onDuplicatePreset = { presetViewModel.onDuplicatePreset(it) },
            onDeletePreset = { presetViewModel.onDeletePreset(it) },
            onDismiss = { presetViewModel.dismissLibrary() }
        )
    }

    // Prompt Preset Save/Edit Dialog
    if (presetUiState.showSaveDialog) {
        PromptPresetDialog(
            editingPreset = presetUiState.editingPreset,
            currentPrompt = presetUiState.currentPromptForSave,
            existingTags = presetUiState.availableTags,
            isNameTaken = { name, excludeId -> presetViewModel.isNameTaken(name, excludeId) },
            onDismiss = { presetViewModel.dismissSaveDialog() },
            onSave = { name, prompt, tags -> presetViewModel.onSavePreset(name, prompt, tags) }
        )
    }
}
