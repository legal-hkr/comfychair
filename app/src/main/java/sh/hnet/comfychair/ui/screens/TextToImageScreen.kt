package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.ResultHeroImage
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
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
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
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    // Prompt preset ViewModel
    val presetViewModel: PromptPresetViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Initialize ViewModels
    LaunchedEffect(Unit) {
        val client = generationViewModel.getClient()
        if (client != null) {
            textToImageViewModel.initialize(context, client)
        }
        presetViewModel.initialize(context, ScreenType.TEXT_TO_IMAGE)
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

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by textToImageViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == TextToImageViewModel.OWNER_ID
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting &&
            queueState.ownJobs.values.any { it.ownerId == TextToImageViewModel.OWNER_ID }

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

    // Preset event handling
    LaunchedEffect(Unit) {
        presetViewModel.events.collect { event ->
            when (event) {
                is PromptPresetEvent.PresetApplied -> {
                    textToImageViewModel.onPositivePromptChange(event.prompt)
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    textToImageViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // UI composition
    var showOptionsBottomSheet by remember { mutableStateOf(false) }
    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with save/share actions (outside content Box)
        TopAppBar(
            title = { Text(stringResource(R.string.nav_text_to_image)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Save to gallery button (only when image exists)
                if (uiState.previewBitmap != null) {
                    IconButton(onClick = {
                        textToImageViewModel.saveToGallery { success ->
                            val messageRes = if (success) R.string.msg_image_saved_to_gallery else R.string.error_save_image
                            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        textToImageViewModel.getShareIntent()?.let { intent ->
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_image)))
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.button_share))
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

        // Image Preview Area — collapses when typing in the prompt field
        AnimatedVisibility(
            visible = !expandPrompt,
            modifier = Modifier.weight(1f),
            enter = fadeIn(tween(150)),
            exit = ExitTransition.None
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 150.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = uiState.previewBitmap != null && !isThisScreenExecuting && !showResults) {
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
                if (showResults) {
                    val results = uiState.generationOutputs
                    val openViewer = rememberResultsViewer(
                        generationViewModel.getHostname(), generationViewModel.getPort(), results, textToImageViewModel::onCurrentResultChange
                    )
                    ResultsPreview(
                        results = results,
                        currentIndex = uiState.currentResultIndex,
                        onCurrentIndexChange = textToImageViewModel::onCurrentResultChange,
                        onOpenViewer = openViewer,
                        stripEnabled = !isThisScreenQueued,
                        heroOverlay = { current ->
                            if (!current.isVideo) UseAsSourceButton { target -> onUseAsSource(target) { loadResultBitmap(current) } }
                        }
                    ) { output, _ ->
                        // The image already shown on the screen doesn't need loading again
                        ResultHeroImage(
                            output = output,
                            preloaded = uiState.previewBitmap.takeIf { output.filename == uiState.currentImageFilename }
                        )
                    }
                } else if (uiState.previewBitmap != null) {
                    Image(
                        bitmap = uiState.previewBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.content_description_generated_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // While generating, the area shows live previews instead of a result
                    if (!isThisScreenExecuting) {
                        val result = uiState.previewBitmap
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

        // Prompt Input — expands to fill screen above keyboard when focused.
        // Hidden for workflows without a positive prompt placeholder (e.g. upscalers).
        if (uiState.capabilities.hasPositivePrompt) {
            OutlinedTextField(
                value = uiState.positivePrompt,
                onValueChange = {
                    textToImageViewModel.onPositivePromptChange(it)
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
                        IconButton(onClick = { textToImageViewModel.onPositivePromptChange("") }) {
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
                isEnabled = !uiState.capabilities.hasPositivePrompt || uiState.positivePrompt.isNotBlank(),
                isOfflineMode = isOfflineMode,
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
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
                        val messageRes = if (success) R.string.msg_queue_cleared_success
                                       else R.string.error_queue_clear
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
    } // End of outer Column

    // Options Bottom Sheet
    if (showOptionsBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsBottomSheet = false },
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(textToImageViewModel) {
                UnifiedCallbacks(
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
                    onTextEncoderChange = textToImageViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = textToImageViewModel::onLatentUpscaleModelChange,
                    onMandatoryLoraChange = textToImageViewModel::onMandatoryLoraChange,
                    onWidthChange = textToImageViewModel::onWidthChange,
                    onHeightChange = textToImageViewModel::onHeightChange,
                    onMegapixelsChange = textToImageViewModel::onMegapixelsChange,
                    onLengthChange = textToImageViewModel::onLengthChange,
                    onFpsChange = textToImageViewModel::onFpsChange,
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
