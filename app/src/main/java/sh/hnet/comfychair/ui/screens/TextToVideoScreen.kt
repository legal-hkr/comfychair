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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.res.painterResource
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSourceButton
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoEvent
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TextToVideoScreen(
    generationViewModel: GenerationViewModel,
    textToVideoViewModel: TextToVideoViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State and effects
    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val uiState by textToVideoViewModel.uiState.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == TextToVideoViewModel.OWNER_ID
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting &&
            queueState.ownJobs.values.any { it.ownerId == TextToVideoViewModel.OWNER_ID }

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

    // Track screen visibility for video playback control
    // This prevents video from rendering over navigation transitions
    var isScreenVisible by remember { mutableStateOf(true) }

    // Video URI from ViewModel state
    val videoUri = uiState.currentVideoUri

    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Observe lifecycle to control video playback during navigation transitions
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            isScreenVisible = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            textToVideoViewModel.initialize(context, client)
        }
        presetViewModel.initialize(context, ScreenType.TEXT_TO_VIDEO)

        // Check if there's a pending generation that may have completed while we were away
        if (generationViewModel.generationState.value.isGenerating &&
            generationViewModel.generationState.value.ownerId == TextToVideoViewModel.OWNER_ID) {
            generationViewModel.checkServerForCompletion { _, _ -> }
        }
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

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        textToVideoViewModel.startListening(generationViewModel)
        onDispose {
            textToVideoViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == TextToVideoViewModel.OWNER_ID && promptId != null) {
            textToVideoViewModel.clearPreviewForExecution(promptId)
            textToVideoViewModel.startListening(generationViewModel)
        }
    }

    // Event handling
    LaunchedEffect(Unit) {
        textToVideoViewModel.events.collect { event ->
            when (event) {
                is TextToVideoEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is TextToVideoEvent.ShowToastMessage -> {
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
                    textToVideoViewModel.onPositivePromptChange(event.prompt)
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    textToVideoViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // UI composition
    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with save/share actions
        TopAppBar(
            title = { Text(stringResource(R.string.title_text_to_video)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Save to gallery button (only when video exists)
                if (videoUri != null) {
                    IconButton(onClick = {
                        scope.launch {
                            VideoUtils.saveVideoToGallery(context, videoUri, VideoUtils.GalleryPrefix.TEXT_TO_VIDEO)
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        scope.launch { VideoUtils.shareVideo(context, videoUri) }
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
        if (isThisScreenExecuting && generationState.maxProgress > 0) {
            GenerationProgressBar(
                progress = generationState.progress,
                maxProgress = generationState.maxProgress,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Video preview area — collapses when typing in the prompt field
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
                    .clickable(enabled = videoUri != null && !showResults) {
                    // Launch MediaViewer for single video
                        videoUri?.let { uri ->
                            val intent = MediaViewerActivity.createSingleVideoIntent(
                                context = context,
                                videoUri = uri,
                                hostname = generationViewModel.getHostname(),
                                port = generationViewModel.getPort()
                            )
                            context.startActivity(intent)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    showResults -> {
                        val results = uiState.generationOutputs
                        val openViewer = rememberResultsViewer(
                            generationViewModel.getHostname(), generationViewModel.getPort(), results, textToVideoViewModel::onCurrentResultChange
                        )
                        // The screen already plays the job's first video
                        val firstVideo = results.firstOrNull { it.isVideo }
                        ResultsPreview(
                            results = results,
                            currentIndex = uiState.currentResultIndex,
                            onCurrentIndexChange = textToVideoViewModel::onCurrentResultChange,
                            onOpenViewer = openViewer,
                            stripEnabled = !isThisScreenQueued,
                            heroOverlay = { current ->
                                if (!current.isVideo) UseAsSourceButton { target -> onUseAsSource(target) { loadResultBitmap(current) } }
                            }
                        ) { output, isCurrent ->
                            if (output.isVideo) {
                                ResultHeroVideo(
                                    output = output,
                                    isCurrent = isCurrent,
                                    isActive = isScreenVisible,
                                    preloadedUri = videoUri.takeIf { output == firstVideo }
                                )
                            } else {
                                ResultHeroImage(output)
                            }
                        }
                    }
                    // Show preview bitmap during generation
                    uiState.previewBitmap != null -> {
                        Image(
                            bitmap = uiState.previewBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_description_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    // Show video player when video is available
                    videoUri != null -> {
                        VideoPlayer(
                            videoUri = videoUri,
                            modifier = Modifier.fillMaxSize(),
                            isActive = isScreenVisible
                        )
                    }
                    // Show placeholder - app logo
                    else -> {
                        Image(
                            painter = painterResource(R.drawable.ic_comfychair_foreground),
                            contentDescription = stringResource(R.string.placeholder_video),
                            modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                            contentScale = ContentScale.Fit
                        )
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
                    textToVideoViewModel.onPositivePromptChange(it)
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
                        IconButton(onClick = {
                            textToVideoViewModel.onPositivePromptChange("")
                            presetViewModel.clearActivePreset()
                        }) {
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
                isEnabled = textToVideoViewModel.hasValidConfiguration() &&
                    (!uiState.capabilities.hasPositivePrompt || uiState.positivePrompt.isNotBlank()),
                isOfflineMode = isOfflineMode,
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    val workflowJson = textToVideoViewModel.prepareWorkflow()
                    if (workflowJson != null) {
                        generationViewModel.startGeneration(
                            workflowJson,
                            TextToVideoViewModel.OWNER_ID,
                            ContentType.VIDEO
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
                },
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    val workflowJson = textToVideoViewModel.prepareWorkflow()
                    if (workflowJson != null) {
                        generationViewModel.startGeneration(
                            workflowJson,
                            TextToVideoViewModel.OWNER_ID,
                            ContentType.VIDEO,
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
            sheetState = configSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(textToVideoViewModel) {
                UnifiedCallbacks(
                    onWorkflowChange = textToVideoViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    onNegativePromptChange = textToVideoViewModel::onNegativePromptChange,
                    // Single-model patterns
                    onCheckpointChange = textToVideoViewModel::onCheckpointChange,
                    onUnetChange = textToVideoViewModel::onUnetChange,
                    onMandatoryLoraChange = textToVideoViewModel::onMandatoryLoraChange,
                    // Dual-model patterns
                    onHighnoiseUnetChange = textToVideoViewModel::onHighnoiseUnetChange,
                    onLownoiseUnetChange = textToVideoViewModel::onLownoiseUnetChange,
                    onHighnoiseLoraChange = textToVideoViewModel::onHighnoiseLoraChange,
                    onLownoiseLoraChange = textToVideoViewModel::onLownoiseLoraChange,
                    onVaeChange = textToVideoViewModel::onVaeChange,
                    onClipChange = textToVideoViewModel::onClipChange,
                    onClip1Change = textToVideoViewModel::onClip1Change,
                    onClip2Change = textToVideoViewModel::onClip2Change,
                    onClip3Change = textToVideoViewModel::onClip3Change,
                    onClip4Change = textToVideoViewModel::onClip4Change,
                    onTextEncoderChange = textToVideoViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = textToVideoViewModel::onLatentUpscaleModelChange,
                    onWidthChange = textToVideoViewModel::onWidthChange,
                    onHeightChange = textToVideoViewModel::onHeightChange,
                    onMegapixelsChange = textToVideoViewModel::onMegapixelsChange,
                    onLengthChange = textToVideoViewModel::onLengthChange,
                    onFpsChange = textToVideoViewModel::onFpsChange,
                    onStepsChange = textToVideoViewModel::onStepsChange,
                    onCfgChange = textToVideoViewModel::onCfgChange,
                    onSamplerChange = textToVideoViewModel::onSamplerChange,
                    onSchedulerChange = textToVideoViewModel::onSchedulerChange,
                    onRandomSeedToggle = textToVideoViewModel::onRandomSeedToggle,
                    onSeedChange = textToVideoViewModel::onSeedChange,
                    onRandomizeSeed = textToVideoViewModel::onRandomizeSeed,
                    onDenoiseChange = textToVideoViewModel::onDenoiseChange,
                    onBatchSizeChange = textToVideoViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = textToVideoViewModel::onUpscaleMethodChange,
                    onScaleByChange = textToVideoViewModel::onScaleByChange,
                    onStopAtClipLayerChange = textToVideoViewModel::onStopAtClipLayerChange,
                    // Primary LoRA chain (for single-model workflows like LTX 2.0)
                    onAddLora = textToVideoViewModel::onAddLora,
                    onRemoveLora = textToVideoViewModel::onRemoveLora,
                    onLoraNameChange = textToVideoViewModel::onLoraNameChange,
                    onLoraStrengthChange = textToVideoViewModel::onLoraStrengthChange,
                    // Dual-model LoRA chains
                    onAddHighnoiseLora = textToVideoViewModel::onAddHighnoiseLora,
                    onRemoveHighnoiseLora = textToVideoViewModel::onRemoveHighnoiseLora,
                    onHighnoiseLoraNameChange = textToVideoViewModel::onHighnoiseLoraChainNameChange,
                    onHighnoiseLoraStrengthChange = textToVideoViewModel::onHighnoiseLoraChainStrengthChange,
                    onAddLownoiseLora = textToVideoViewModel::onAddLownoiseLora,
                    onRemoveLownoiseLora = textToVideoViewModel::onRemoveLownoiseLora,
                    onLownoiseLoraNameChange = textToVideoViewModel::onLownoiseLoraChainNameChange,
                    onLownoiseLoraStrengthChange = textToVideoViewModel::onLownoiseLoraChainStrengthChange
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

    // Prompt Preset Dialog
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
