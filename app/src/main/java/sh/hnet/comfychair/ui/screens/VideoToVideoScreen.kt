package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.VideoToVideoEvent
import sh.hnet.comfychair.viewmodel.VideoToVideoViewModel
import sh.hnet.comfychair.viewmodel.VideoToVideoViewMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoToVideoScreen(
    generationViewModel: GenerationViewModel,
    videoToVideoViewModel: VideoToVideoViewModel,
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
    val uiState by videoToVideoViewModel.uiState.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()

    // Check if THIS screen owns the currently executing job (for progress bar)
    val isThisScreenExecuting = queueState.executingOwnerId == VideoToVideoViewModel.OWNER_ID

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
    var isScreenVisible by remember { mutableStateOf(true) }

    // Video URI from ViewModel state
    val videoUri = uiState.currentVideoUri

    // Whether the selected workflow exposes a reference image ({{image_filename}}) field
    val hasReferenceImageField = uiState.workflowPlaceholders.contains("image_filename")

    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Driving video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            videoToVideoViewModel.onDrivingVideoChange(it)
            videoToVideoViewModel.onViewModeChange(VideoToVideoViewMode.SOURCE)
        }
    }

    // Reference image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            videoToVideoViewModel.onReferenceImageChange(context, it)
        }
    }

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
            videoToVideoViewModel.initialize(context, client)
        }
        presetViewModel.initialize(context, ScreenType.VIDEO_TO_VIDEO)

        // Check if there's a pending generation that may have completed while we were away
        if (generationViewModel.generationState.value.isGenerating &&
            generationViewModel.generationState.value.ownerId == VideoToVideoViewModel.OWNER_ID) {
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

    // Event handling
    LaunchedEffect(Unit) {
        videoToVideoViewModel.events.collect { event ->
            when (event) {
                is VideoToVideoEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is VideoToVideoEvent.ShowToastMessage -> {
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
                    videoToVideoViewModel.onPositivePromptChange(event.prompt)
                }
                is PromptPresetEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.ResetPrompt -> {
                    videoToVideoViewModel.resetPromptToDefault()
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        videoToVideoViewModel.startListening(generationViewModel)
        onDispose {
            videoToVideoViewModel.stopListening(generationViewModel)
        }
    }

    // Handle when a NEW job starts executing for this screen
    // Using both executingPromptId and executingOwnerId as keys handles the race condition
    // where execution_start arrives before job registration (owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == VideoToVideoViewModel.OWNER_ID && promptId != null) {
            videoToVideoViewModel.clearPreviewForExecution(promptId)
            videoToVideoViewModel.onViewModeChange(VideoToVideoViewMode.PREVIEW)
            videoToVideoViewModel.startListening(generationViewModel)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with upload and save/share actions
        TopAppBar(
            title = { Text(stringResource(R.string.title_video_to_video)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Upload driving video button
                IconButton(onClick = { videoPickerLauncher.launch("video/*") }) {
                    Icon(Icons.Default.VideoFile, contentDescription = stringResource(R.string.button_upload_driving_video))
                }
                // Upload reference image button (only for workflows with an image field)
                if (hasReferenceImageField) {
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.button_upload_reference_image))
                    }
                }
                // Save to gallery button (only when video exists)
                if (videoUri != null && uiState.viewMode == VideoToVideoViewMode.PREVIEW) {
                    IconButton(onClick = {
                        scope.launch {
                            VideoUtils.saveVideoToGallery(context, videoUri, VideoUtils.GalleryPrefix.VIDEO_TO_VIDEO)
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.button_save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        VideoUtils.shareVideo(context, videoUri)
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

        // Preview area + view mode toggle — collapse when typing in the prompt field
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
                            enabled = (uiState.viewMode == VideoToVideoViewMode.PREVIEW && videoUri != null) ||
                                      uiState.drivingVideoUri != null ||
                                      uiState.referenceImage != null,
                            onClick = {
                                when {
                                    uiState.viewMode == VideoToVideoViewMode.PREVIEW -> {
                                        videoUri?.let { uri ->
                                            val intent = MediaViewerActivity.createSingleVideoIntent(
                                                context = context,
                                                videoUri = uri,
                                                hostname = generationViewModel.getHostname(),
                                                port = generationViewModel.getPort()
                                            )
                                            context.startActivity(intent)
                                        }
                                    }
                                    uiState.viewMode == VideoToVideoViewMode.SOURCE && uiState.drivingVideoUri != null -> {
                                        // Driving video is user-provided, no ComfyUI metadata
                                        val intent = MediaViewerActivity.createSingleVideoIntent(context, uiState.drivingVideoUri!!)
                                        context.startActivity(intent)
                                    }
                                    uiState.viewMode == VideoToVideoViewMode.SOURCE && uiState.referenceImage != null -> {
                                        // Reference image is user-provided, no ComfyUI metadata
                                        val intent = MediaViewerActivity.createSingleImageIntent(context, uiState.referenceImage!!)
                                        context.startActivity(intent)
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (uiState.viewMode) {
                        VideoToVideoViewMode.SOURCE -> {
                            // Show the driving video if available, otherwise the reference image
                            when {
                                uiState.drivingVideoUri != null -> {
                                    VideoPlayer(
                                        videoUri = uiState.drivingVideoUri!!,
                                        modifier = Modifier.fillMaxSize(),
                                        isActive = isScreenVisible
                                    )
                                }
                                uiState.referenceImage != null -> {
                                    Image(
                                        bitmap = uiState.referenceImage!!.asImageBitmap(),
                                        contentDescription = stringResource(R.string.tab_reference_image),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                // Placeholder - app logo with tap to upload hints
                                else -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { videoPickerLauncher.launch("video/*") }
                                    ) {
                                        Image(
                                            painter = painterResource(R.drawable.ic_comfychair_foreground),
                                            contentDescription = null,
                                            modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                                            contentScale = ContentScale.Fit
                                        )
                                        Text(
                                            text = if (uiState.availableWorkflows.isEmpty()) {
                                                stringResource(R.string.msg_no_v2v_workflows)
                                            } else {
                                                stringResource(R.string.msg_no_driving_video)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        VideoToVideoViewMode.PREVIEW -> {
                            when {
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
                }

                // View mode toggle
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp)
                ) {
                    SegmentedButton(
                        selected = uiState.viewMode == VideoToVideoViewMode.SOURCE,
                        onClick = { videoToVideoViewModel.onViewModeChange(VideoToVideoViewMode.SOURCE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_source))
                    }
                    SegmentedButton(
                        selected = uiState.viewMode == VideoToVideoViewMode.PREVIEW,
                        onClick = { videoToVideoViewModel.onViewModeChange(VideoToVideoViewMode.PREVIEW) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_preview))
                    }
                }

                // Source sub-tabs (driving video / reference image) - only in source mode
                if (uiState.viewMode == VideoToVideoViewMode.SOURCE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { videoPickerLauncher.launch("video/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.VideoFile, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.tab_driving_video))
                        }
                        if (hasReferenceImageField) {
                            OutlinedButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.tab_reference_image))
                            }
                        }
                    }
                }
            }
        }

        // Prompt Input — expands to fill screen above keyboard when focused
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = {
                videoToVideoViewModel.onPositivePromptChange(it)
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
                        videoToVideoViewModel.onPositivePromptChange("")
                        presetViewModel.clearActivePreset()
                    }) {
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
                isEnabled = videoToVideoViewModel.hasValidConfiguration() && uiState.positivePrompt.isNotBlank(),
                isOfflineMode = isOfflineMode,
                isUploading = uiState.isUploading,
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    scope.launch {
                        val workflowJson = videoToVideoViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                VideoToVideoViewModel.OWNER_ID,
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
                    }
                },
                onCancelCurrent = { generationViewModel.cancelGeneration { } },
                onAddToFrontOfQueue = {
                    scope.launch {
                        val workflowJson = videoToVideoViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            generationViewModel.startGeneration(
                                workflowJson,
                                VideoToVideoViewModel.OWNER_ID,
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
            val callbacks = remember(videoToVideoViewModel) {
                UnifiedCallbacks(
                    onWorkflowChange = videoToVideoViewModel::onWorkflowChange,
                    onViewWorkflow = {
                        val workflowId = uiState.availableWorkflows
                            .find { it.name == uiState.selectedWorkflow }?.id
                        if (workflowId != null) {
                            context.startActivity(
                                WorkflowEditorActivity.createIntent(context, workflowId)
                            )
                        }
                    },
                    onNegativePromptChange = videoToVideoViewModel::onNegativePromptChange,
                    // Single-model patterns
                    onCheckpointChange = videoToVideoViewModel::onCheckpointChange,
                    onUnetChange = videoToVideoViewModel::onUnetChange,
                    onMandatoryLoraChange = videoToVideoViewModel::onMandatoryLoraChange,
                    // Dual-model patterns
                    onHighnoiseUnetChange = videoToVideoViewModel::onHighnoiseUnetChange,
                    onLownoiseUnetChange = videoToVideoViewModel::onLownoiseUnetChange,
                    onHighnoiseLoraChange = videoToVideoViewModel::onHighnoiseLoraChange,
                    onLownoiseLoraChange = videoToVideoViewModel::onLownoiseLoraChange,
                    onVaeChange = videoToVideoViewModel::onVaeChange,
                    onClipChange = videoToVideoViewModel::onClipChange,
                    onClip1Change = videoToVideoViewModel::onClip1Change,
                    onClip2Change = videoToVideoViewModel::onClip2Change,
                    onClip3Change = videoToVideoViewModel::onClip3Change,
                    onClip4Change = videoToVideoViewModel::onClip4Change,
                    onTextEncoderChange = videoToVideoViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = videoToVideoViewModel::onLatentUpscaleModelChange,
                    onWidthChange = videoToVideoViewModel::onWidthChange,
                    onHeightChange = videoToVideoViewModel::onHeightChange,
                    onMegapixelsChange = videoToVideoViewModel::onMegapixelsChange,
                    onLengthChange = videoToVideoViewModel::onLengthChange,
                    onFpsChange = videoToVideoViewModel::onFpsChange,
                    onStepsChange = videoToVideoViewModel::onStepsChange,
                    onCfgChange = videoToVideoViewModel::onCfgChange,
                    onSamplerChange = videoToVideoViewModel::onSamplerChange,
                    onSchedulerChange = videoToVideoViewModel::onSchedulerChange,
                    onRandomSeedToggle = videoToVideoViewModel::onRandomSeedToggle,
                    onSeedChange = videoToVideoViewModel::onSeedChange,
                    onRandomizeSeed = videoToVideoViewModel::onRandomizeSeed,
                    onDenoiseChange = videoToVideoViewModel::onDenoiseChange,
                    onBatchSizeChange = videoToVideoViewModel::onBatchSizeChange,
                    onUpscaleMethodChange = videoToVideoViewModel::onUpscaleMethodChange,
                    onScaleByChange = videoToVideoViewModel::onScaleByChange,
                    onStopAtClipLayerChange = videoToVideoViewModel::onStopAtClipLayerChange,
                    // Primary LoRA chain (for single-model workflows like LTX 2.0)
                    onAddLora = videoToVideoViewModel::onAddLora,
                    onRemoveLora = videoToVideoViewModel::onRemoveLora,
                    onLoraNameChange = videoToVideoViewModel::onLoraNameChange,
                    onLoraStrengthChange = videoToVideoViewModel::onLoraStrengthChange,
                    // Dual-model LoRA chains
                    onAddHighnoiseLora = videoToVideoViewModel::onAddHighnoiseLora,
                    onRemoveHighnoiseLora = videoToVideoViewModel::onRemoveHighnoiseLora,
                    onHighnoiseLoraNameChange = videoToVideoViewModel::onHighnoiseLoraChainNameChange,
                    onHighnoiseLoraStrengthChange = videoToVideoViewModel::onHighnoiseLoraChainStrengthChange,
                    onAddLownoiseLora = videoToVideoViewModel::onAddLownoiseLora,
                    onRemoveLownoiseLora = videoToVideoViewModel::onRemoveLownoiseLora,
                    onLownoiseLoraNameChange = videoToVideoViewModel::onLownoiseLoraChainNameChange,
                    onLownoiseLoraStrengthChange = videoToVideoViewModel::onLownoiseLoraChainStrengthChange
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
