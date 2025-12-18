package sh.hnet.comfychair.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.ImageToVideoConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoEvent
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToVideoScreen(
    generationViewModel: GenerationViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val uiState by imageToVideoViewModel.uiState.collectAsState()

    // Check if THIS screen owns the current generation
    val isThisScreenGenerating = generationState.isGenerating &&
        generationState.ownerId == ImageToVideoViewModel.OWNER_ID

    var showMenu by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }

    // Track screen visibility for video playback control
    var isScreenVisible by remember { mutableStateOf(true) }

    // Video URI from ViewModel state
    val videoUri = uiState.currentVideoUri

    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToVideoViewModel.onSourceImageChange(context, it)
            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE)
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
            imageToVideoViewModel.initialize(context, client)
        }

        // Check if there's a pending generation that may have completed while we were away
        if (generationViewModel.generationState.value.isGenerating &&
            generationViewModel.generationState.value.ownerId == ImageToVideoViewModel.OWNER_ID) {
            generationViewModel.checkServerForCompletion { _, _ -> }
        }
    }

    // Handle events from ViewModel
    LaunchedEffect(Unit) {
        imageToVideoViewModel.events.collect { event ->
            when (event) {
                is ImageToVideoEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToVideoEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        imageToVideoViewModel.startListening(generationViewModel)
        onDispose {
            imageToVideoViewModel.stopListening(generationViewModel)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with upload and save/share actions
        TopAppBar(
            title = { Text(stringResource(R.string.image_to_video_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Upload image button
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.upload_source_image))
                }
                // Save to gallery button (only when video exists)
                if (videoUri != null && uiState.viewMode == ImageToVideoViewMode.PREVIEW) {
                    IconButton(onClick = {
                        scope.launch {
                            VideoUtils.saveVideoToGallery(context, videoUri, VideoUtils.GalleryPrefix.IMAGE_TO_VIDEO)
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        VideoUtils.shareVideo(context, videoUri)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    }
                }
                // Menu button
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_description_menu))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_settings)) },
                            onClick = {
                                showMenu = false
                                onNavigateToSettings()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_logout)) },
                            onClick = {
                                showMenu = false
                                onLogout()
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            }
                        )
                    }
                }
            }
        )

        // Preview area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(
                    enabled = (uiState.viewMode == ImageToVideoViewMode.PREVIEW && videoUri != null) ||
                              (uiState.viewMode == ImageToVideoViewMode.SOURCE && uiState.sourceImage != null),
                    onClick = {
                        when (uiState.viewMode) {
                            ImageToVideoViewMode.PREVIEW -> {
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
                            ImageToVideoViewMode.SOURCE -> {
                                // Source image is user-provided, no ComfyUI metadata
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
                ImageToVideoViewMode.SOURCE -> {
                    if (uiState.sourceImage != null) {
                        Image(
                            bitmap = uiState.sourceImage!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.source_image_tab),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Placeholder - app logo with tap to upload hint
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { imagePickerLauncher.launch("image/*") }
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(300.dp),
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
                ImageToVideoViewMode.PREVIEW -> {
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
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = stringResource(R.string.placeholder_video_description),
                                modifier = Modifier.size(300.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    // Progress indicator - only show if THIS screen started generation
                    if (isThisScreenGenerating && generationState.maxProgress > 0) {
                        GenerationProgressBar(
                            progress = generationState.progress,
                            maxProgress = generationState.maxProgress,
                            modifier = Modifier.align(Alignment.BottomCenter)
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
                selected = uiState.viewMode == ImageToVideoViewMode.SOURCE,
                onClick = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.source_image_tab))
            }
            SegmentedButton(
                selected = uiState.viewMode == ImageToVideoViewMode.PREVIEW,
                onClick = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.preview_tab))
            }
        }

        // Prompt Input
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = { imageToVideoViewModel.onPositivePromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.positivePrompt.isNotEmpty()) {
                    IconButton(onClick = { imageToVideoViewModel.onPositivePromptChange("") }) {
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
                isGenerating = isThisScreenGenerating,
                isEnabled = isThisScreenGenerating ||
                    (!generationState.isGenerating && imageToVideoViewModel.hasValidConfiguration() && uiState.positivePrompt.isNotBlank()),
                onGenerate = {
                    scope.launch {
                        val workflowJson = imageToVideoViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            imageToVideoViewModel.clearPreview()
                            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW)
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToVideoViewModel.OWNER_ID,
                                ContentType.VIDEO
                            ) { success, _, errorMessage ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.error_failed_start_generation, errorMessage ?: context.getString(R.string.error_unknown)),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                },
                onCancel = { generationViewModel.cancelGeneration { } },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedIconButton(
                onClick = { showOptionsSheet = true },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.button_options))
            }
        }
    }

    // Options bottom sheet
    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = configSheetState
        ) {
            ImageToVideoConfigBottomSheetContent(
                uiState = uiState,
                onWorkflowChange = imageToVideoViewModel::onWorkflowChange,
                onNegativePromptChange = imageToVideoViewModel::onNegativePromptChange,
                onHighnoiseUnetChange = imageToVideoViewModel::onHighnoiseUnetChange,
                onLownoiseUnetChange = imageToVideoViewModel::onLownoiseUnetChange,
                onHighnoiseLoraChange = imageToVideoViewModel::onHighnoiseLoraChange,
                onLownoiseLoraChange = imageToVideoViewModel::onLownoiseLoraChange,
                onVaeChange = imageToVideoViewModel::onVaeChange,
                onClipChange = imageToVideoViewModel::onClipChange,
                onWidthChange = imageToVideoViewModel::onWidthChange,
                onHeightChange = imageToVideoViewModel::onHeightChange,
                onLengthChange = imageToVideoViewModel::onLengthChange,
                onFpsChange = imageToVideoViewModel::onFpsChange,
                onAddHighnoiseLora = imageToVideoViewModel::onAddHighnoiseLora,
                onRemoveHighnoiseLora = imageToVideoViewModel::onRemoveHighnoiseLora,
                onHighnoiseLoraChainNameChange = imageToVideoViewModel::onHighnoiseLoraChainNameChange,
                onHighnoiseLoraChainStrengthChange = imageToVideoViewModel::onHighnoiseLoraChainStrengthChange,
                onAddLownoiseLora = imageToVideoViewModel::onAddLownoiseLora,
                onRemoveLownoiseLora = imageToVideoViewModel::onRemoveLownoiseLora,
                onLownoiseLoraChainNameChange = imageToVideoViewModel::onLownoiseLoraChainNameChange,
                onLownoiseLoraChainStrengthChange = imageToVideoViewModel::onLownoiseLoraChainStrengthChange
            )
        }
    }
}
