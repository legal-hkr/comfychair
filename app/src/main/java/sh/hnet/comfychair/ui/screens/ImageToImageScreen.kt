package sh.hnet.comfychair.ui.screens

import android.content.ClipData
import android.graphics.Bitmap
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.SecondaryTabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageEvent
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
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

    // Error dialog state
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorDialogMessage by remember { mutableStateOf("") }

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

    // Check offline mode
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    var showOptionsSheet by remember { mutableStateOf(false) }

    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher for source image (system file picker - supports Downloads, file managers, gallery)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Take persistable permission so we can read the file later
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            imageToImageViewModel.onSourceImageChange(context, it)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
        }
    }

    // Additional source image pickers (slots 2, 3, 4)
    val imagePickerLauncher2 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            imageToImageViewModel.onAdditionalSourceImageChange(context, 2, it)
        }
    }

    val imagePickerLauncher3 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            imageToImageViewModel.onAdditionalSourceImageChange(context, 3, it)
        }
    }

    val imagePickerLauncher4 = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)
            imageToImageViewModel.onAdditionalSourceImageChange(context, 4, it)
        }
    }

    // ActivityResultLauncher for MediaViewer replace flow
    val mediaViewerReplaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Always clear bypass toggle callback when MediaViewer closes
        MediaViewerActivity.onBypassToggleCallback = null
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val replaceSlot = data.getIntExtra(MediaViewerActivity.RESULT_SLOT, -1)
            if (replaceSlot > 0) {
                when (replaceSlot) {
                    1 -> imagePickerLauncher.launch(arrayOf("image/*"))
                    2 -> imagePickerLauncher2.launch(arrayOf("image/*"))
                    3 -> imagePickerLauncher3.launch(arrayOf("image/*"))
                    4 -> imagePickerLauncher4.launch(arrayOf("image/*"))
                }
            }
        }
    }

    // PagerState for HorizontalPager — initial page 0 (source image 1)
    val pagerState = rememberPagerState(initialPage = 0) {
        // Page count: driven by workflow additionalImageSlotCount + preview tab
        val sourcePages = 1 + uiState.additionalImageSlotCount.coerceIn(0, 3)
        sourcePages + 1 // +1 for preview tab
    }

    // Sync pagerState with viewMode when viewMode changes externally
    LaunchedEffect(uiState.viewMode) {
        when (uiState.viewMode) {
            ImageToImageViewMode.PREVIEW -> {
                pagerState.scrollToPage(pagerState.pageCount - 1)
            }
            ImageToImageViewMode.SOURCE -> {
                // Stay on current source page (don't override user's scroll position)
            }
        }
    }

    // Clamp pagerState to valid range when pageCount decreases (workflow with fewer slots)
    LaunchedEffect(pagerState.pageCount) {
        if (pagerState.currentPage >= pagerState.pageCount) {
            pagerState.scrollToPage(maxOf(0, pagerState.pageCount - 1))
        }
    }

    // Compute tab list — source images driven by workflow additionalImageSlotCount + preview
    // Tabs reflect workflow capabilities (how many slots the workflow supports),
    // not whether images are already set (which would break workflow switching UX)
    data class ImagePage(val slot: Int, val title: String) // slot 1-4 for sources, 0 for preview
    val imagePages = buildList {
        // Slot 1 (primary source) is always available
        add(ImagePage(1, "原图1"))
        // Additional slots (2-4) depend on workflow additionalImageSlotCount
        val maxAdditional = uiState.additionalImageSlotCount.coerceIn(0, 3)
        if (maxAdditional >= 1) add(ImagePage(2, "原图2"))
        if (maxAdditional >= 2) add(ImagePage(3, "原图3"))
        if (maxAdditional >= 3) add(ImagePage(4, "原图4"))
    }
    val previewPageIndex = imagePages.size // preview is always last
    val isPreviewPage = pagerState.currentPage == previewPageIndex

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
    var pendingWorkflowJson by remember { mutableStateOf<String?>(null) }
    var showPlaceholderDialog by remember { mutableStateOf(false) }
    var pendingPlaceholders by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        imageToImageViewModel.events.collect { event ->
            when (event) {
                is ImageToImageEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToImageEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is ImageToImageEvent.UnresolvedPlaceholders -> {
                    pendingPlaceholders = event.placeholders
                    // pendingWorkflowJson was captured by the calling onGenerate callback
                    showPlaceholderDialog = true
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
                    Toast.makeText(context, context.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                }
                is PromptPresetEvent.MaxFavoritesReached -> {
                    Toast.makeText(context, context.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
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
                // Upload image button — routes to the correct slot based on current Pager page
                val currentSourceSlot = if (pagerState.currentPage < imagePages.size) {
                    imagePages[pagerState.currentPage].slot
                } else {
                    1 // preview page → upload to slot 1
                }
                val currentPicker = when (currentSourceSlot) {
                    1 -> imagePickerLauncher
                    2 -> imagePickerLauncher2
                    3 -> imagePickerLauncher3
                    4 -> imagePickerLauncher4
                    else -> imagePickerLauncher
                }
                IconButton(
                    onClick = {
                        when (currentSourceSlot) {
                            1 -> currentPicker.launch(arrayOf("image/*"))
                            else -> currentPicker.launch(arrayOf("image/*"))
                        }
                    }
                ) {
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

        // Image Preview Area — HorizontalPager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) { page ->
            val isThisPreviewPage = page == previewPageIndex
            val sourcePageIndex = if (isThisPreviewPage) -1 else page
            val sourceImage = when (sourcePageIndex) {
                0 -> uiState.sourceImage
                1 -> uiState.sourceImage2
                2 -> uiState.sourceImage3
                3 -> uiState.sourceImage4
                else -> null
            }
            val sourceSlot = when (sourcePageIndex) {
                0 -> 1
                1 -> 2
                2 -> 3
                3 -> 4
                else -> 0
            }
            val sourcePicker = when (sourceSlot) {
                1 -> imagePickerLauncher
                2 -> imagePickerLauncher2
                3 -> imagePickerLauncher3
                4 -> imagePickerLauncher4
                else -> null
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isThisPreviewPage) {
                    // Preview page
                    if (uiState.previewImage != null && !isThisScreenExecuting) {
                        Image(
                            bitmap = uiState.previewImage!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_description_preview),
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
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
                                },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_comfychair_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    // Source image page
                    if (sourceImage != null) {
                        if (uiState.mode == ImageToImageMode.INPAINTING && sourceSlot == 1) {
                            // Inpainting: source image 1 shows mask overlay
                            MaskPreview(
                                sourceImage = sourceImage,
                                maskPaths = uiState.maskPaths,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Image(
                                    bitmap = sourceImage.asImageBitmap(),
                                    contentDescription = stringResource(R.string.content_description_source_image),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            // Set bypass toggle callback before launching MediaViewer
                                            MediaViewerActivity.onBypassToggleCallback =
                                                { slot -> imageToImageViewModel.toggleBypassSourceImage(slot) }
                                            android.util.Log.d("ComfyChair", "Launch MediaViewer sourceSlot=$sourceSlot")
                                            val intent = MediaViewerActivity.createSingleImageIntent(
                                                context,
                                                sourceImage,
                                                replaceSlot = sourceSlot,
                                                bypassSlot = sourceSlot,
                                                isSlotBypassed = uiState.bypassedSourceSlots.contains(sourceSlot)
                                            )
                                            mediaViewerReplaceLauncher?.launch(intent)
                                        },
                                    contentScale = ContentScale.Crop
                                )
                                // Bypass overlay — dimmed + icon for slots 2/3/4 that are bypassed
                                if (sourceSlot >= 2 && uiState.bypassedSourceSlots.contains(sourceSlot)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.45f))
                                    )
                                    Icon(
                                        imageVector = Icons.Default.DoNotDisturb,
                                        contentDescription = stringResource(R.string.node_editor_bypass),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        tint = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    } else {
                        // Empty slot — placeholder with picker
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                sourcePicker?.launch(arrayOf("image/*"))
                            }
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
            }
        }

        // TabRow — one tab per image page + preview tab
        val allTabs = buildList {
            imagePages.forEach { add(it.title) }
            add("预览")
        }
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth()
        ) {
            allTabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch { pagerState.scrollToPage(index) }
                    },
                    text = { Text(title) }
                )
            }
        }

        // Prompt Input        // Prompt Input
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = {
                imageToImageViewModel.onPositivePromptChange(it)
                presetViewModel.clearActivePreset()
            },
            label = { Text(stringResource(R.string.hint_prompt)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
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
                isFetching = uiState.isFetching,
                isConnecting = isConnecting,
                onGenerate = {
                    scope.launch {
                        // In inpainting mode, require mask
                        if (uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.hint_paint_mask),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        // Capture for potential placeholder confirmation dialog
                        pendingWorkflowJson = workflowJson
                        if (workflowJson != null) {
                            // If UnresolvedPlaceholders was emitted, dialog is now showing.
                            // User clicks Confirm → startGeneration is called with captured workflowJson.
                            // If no unresolved placeholders (pendingPlaceholders still empty),
                            // dialog won't show and we proceed immediately.
                            if (pendingPlaceholders.isEmpty()) {
                                generationViewModel.startGeneration(
                                    workflowJson,
                                    ImageToImageViewModel.OWNER_ID
                                ) { success, _, errorMessage ->
                                    if (!success) {
                                        errorDialogMessage = errorMessage ?: context.getString(R.string.error_generation_failed)
                                        showErrorDialog = true
                                    }
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
                                context.getString(R.string.hint_paint_mask),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        // Capture for potential placeholder confirmation dialog
                        pendingWorkflowJson = workflowJson
                        if (workflowJson != null) {
                            if (pendingPlaceholders.isEmpty()) {
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
            sheetState = optionsSheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            val callbacks = remember(imageToImageViewModel) {
                UnifiedCallbacks(
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
                    onTextEncoderChange = imageToImageViewModel::onTextEncoderChange,
                    onLatentUpscaleModelChange = imageToImageViewModel::onLatentUpscaleModelChange,
                    // Editing model selection callbacks
                    onEditingUnetChange = imageToImageViewModel::onEditingUnetChange,
                    onEditingLoraChange = imageToImageViewModel::onEditingLoraChange,
                    onEditingVaeChange = imageToImageViewModel::onEditingVaeChange,
                    onEditingClipChange = imageToImageViewModel::onEditingClipChange,
                    onEditingClip1Change = imageToImageViewModel::onEditingClip1Change,
                    onEditingClip2Change = imageToImageViewModel::onEditingClip2Change,
                    onEditingClip3Change = imageToImageViewModel::onEditingClip3Change,
                    onEditingClip4Change = imageToImageViewModel::onEditingClip4Change,
                    onEditingTextEncoderChange = imageToImageViewModel::onEditingTextEncoderChange,
                    onEditingLatentUpscaleModelChange = imageToImageViewModel::onEditingLatentUpscaleModelChange,
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
                    onEditingLoraStrengthChange = imageToImageViewModel::onEditingLoraStrengthChange,
                    // Editing mode checkpoint (for checkpoint-based workflows)
                    onEditingCheckpointChange = imageToImageViewModel::onEditingCheckpointChange,
                    // Editing mode dual-model patterns (for video-style workflows)
                    onEditingHighnoiseUnetChange = imageToImageViewModel::onEditingHighnoiseUnetChange,
                    onEditingLownoiseUnetChange = imageToImageViewModel::onEditingLownoiseUnetChange,
                    onEditingHighnoiseLoraChange = imageToImageViewModel::onEditingHighnoiseLoraChange,
                    onEditingLownoiseLoraChange = imageToImageViewModel::onEditingLownoiseLoraChange,
                    // Editing mode dual LoRA chain callbacks
                    onAddEditingHighnoiseLora = imageToImageViewModel::onAddEditingHighnoiseLora,
                    onRemoveEditingHighnoiseLora = imageToImageViewModel::onRemoveEditingHighnoiseLora,
                    onEditingHighnoiseLoraNameChange = imageToImageViewModel::onEditingHighnoiseLoraNameChange,
                    onEditingHighnoiseLoraStrengthChange = imageToImageViewModel::onEditingHighnoiseLoraStrengthChange,
                    onAddEditingLownoiseLora = imageToImageViewModel::onAddEditingLownoiseLora,
                    onRemoveEditingLownoiseLora = imageToImageViewModel::onRemoveEditingLownoiseLora,
                    onEditingLownoiseLoraNameChange = imageToImageViewModel::onEditingLownoiseLoraNameChange,
                    onEditingLownoiseLoraStrengthChange = imageToImageViewModel::onEditingLownoiseLoraStrengthChange
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

    // Error dialog with copy support
    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text(text = stringResource(R.string.title_workflow_error)) },
            text = {
                Text(
                    text = errorDialogMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text(text = stringResource(R.string.button_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("error_message", errorDialogMessage))
                        Toast.makeText(context, R.string.msg_error_copied, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(text = stringResource(R.string.button_copy_to_clipboard))
                }
            }
        )
    }

    // Unresolved placeholders confirmation dialog
    if (showPlaceholderDialog) {
        AlertDialog(
            onDismissRequest = {
                showPlaceholderDialog = false
                pendingPlaceholders = emptyList()
            },
            title = { Text(text = stringResource(R.string.title_unresolved_placeholders)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.msg_unresolved_placeholders,
                        pendingPlaceholders.joinToString(", ") { "{{${it}}}" }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPlaceholderDialog = false
                        pendingPlaceholders = emptyList()
                        pendingWorkflowJson?.let { json ->
                            scope.launch {
                                generationViewModel.startGeneration(
                                    json,
                                    ImageToImageViewModel.OWNER_ID
                                ) { success, _, errorMessage ->
                                    if (!success) {
                                        errorDialogMessage = errorMessage ?: context.getString(R.string.error_generation_failed)
                                        showErrorDialog = true
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.button_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPlaceholderDialog = false
                        pendingPlaceholders = emptyList()
                    }
                ) {
                    Text(text = stringResource(R.string.button_cancel))
                }
            }
        )
    }
}
