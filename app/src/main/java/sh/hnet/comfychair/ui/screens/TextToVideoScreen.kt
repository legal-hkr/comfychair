package sh.hnet.comfychair.ui.screens

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.VideoConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoEvent
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToVideoScreen(
    generationViewModel: GenerationViewModel,
    textToVideoViewModel: TextToVideoViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val uiState by textToVideoViewModel.uiState.collectAsState()

    // Check if THIS screen owns the current generation
    val isThisScreenGenerating = generationState.isGenerating &&
        generationState.ownerId == TextToVideoViewModel.OWNER_ID

    var showMenu by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }

    // Track screen visibility for video playback control
    // This prevents video from rendering over navigation transitions
    var isScreenVisible by remember { mutableStateOf(true) }

    // Video state
    var videoUri by remember { mutableStateOf<Uri?>(null) }

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

        // Load last generated video only if not currently generating for this screen
        val currentState = generationViewModel.generationState.value
        val isGeneratingForThisScreen = currentState.isGenerating &&
            currentState.ownerId == TextToVideoViewModel.OWNER_ID

        if (!isGeneratingForThisScreen) {
            // Find the most recent generated video file
            val videoFile = context.filesDir.listFiles()
                ?.filter { it.name.startsWith("last_generated_video") && it.name.endsWith(".mp4") }
                ?.maxByOrNull { it.lastModified() }

            if (videoFile != null && videoFile.exists()) {
                videoUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    videoFile
                )
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        textToVideoViewModel.startListening(
            generationViewModel,
            onPreviewBitmap = { bitmap ->
                textToVideoViewModel.onPreviewBitmapChange(bitmap)
            },
            onVideoFetched = { promptId ->
                generationViewModel.getClient()?.let { client ->
                    VideoUtils.fetchVideoFromHistory(
                        context,
                        client,
                        promptId,
                        VideoUtils.FilePrefix.TEXT_TO_VIDEO
                    ) { uri ->
                        videoUri = uri
                        generationViewModel.completeGeneration()
                    }
                }
            }
        )
        onDispose {
            textToVideoViewModel.stopListening(generationViewModel)
        }
    }

    // Handle events (toasts)
    LaunchedEffect(Unit) {
        textToVideoViewModel.events.collect { event ->
            when (event) {
                is TextToVideoEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Clear preview when not generating
    LaunchedEffect(generationState.isGenerating) {
        if (!generationState.isGenerating) {
            textToVideoViewModel.clearPreview()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with save/share actions
        TopAppBar(
            title = { Text(stringResource(R.string.text_to_video_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Save to gallery button (only when video exists)
                if (videoUri != null) {
                    IconButton(onClick = {
                        scope.launch {
                            VideoUtils.saveVideoToGallery(context, videoUri, VideoUtils.GalleryPrefix.TEXT_TO_VIDEO)
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

        // Video preview area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = videoUri != null) {
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
                // Show placeholder - monochrome app logo
                else -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground_monochrome),
                        contentDescription = stringResource(R.string.placeholder_video_description),
                        modifier = Modifier.size(256.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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

        // Prompt Input
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = { textToVideoViewModel.onPositivePromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.positivePrompt.isNotEmpty()) {
                    IconButton(onClick = { textToVideoViewModel.onPositivePromptChange("") }) {
                        Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.content_description_clear))
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
                    (!generationState.isGenerating && textToVideoViewModel.hasValidConfiguration() && uiState.positivePrompt.isNotBlank()),
                onGenerate = {
                    val workflowJson = textToVideoViewModel.prepareWorkflow()
                    if (workflowJson != null) {
                        videoUri = null
                        textToVideoViewModel.clearPreview()
                        generationViewModel.startGeneration(
                            workflowJson,
                            TextToVideoViewModel.OWNER_ID
                        ) { success, _, errorMessage ->
                            if (!success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.error_failed_start_generation, errorMessage ?: context.getString(R.string.error_unknown)),
                                    Toast.LENGTH_SHORT
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
            VideoConfigBottomSheetContent(
                uiState = uiState,
                onWorkflowChange = textToVideoViewModel::onWorkflowChange,
                onHighnoiseUnetChange = textToVideoViewModel::onHighnoiseUnetChange,
                onLownoiseUnetChange = textToVideoViewModel::onLownoiseUnetChange,
                onHighnoiseLoraChange = textToVideoViewModel::onHighnoiseLoraChange,
                onLownoiseLoraChange = textToVideoViewModel::onLownoiseLoraChange,
                onVaeChange = textToVideoViewModel::onVaeChange,
                onClipChange = textToVideoViewModel::onClipChange,
                onWidthChange = textToVideoViewModel::onWidthChange,
                onHeightChange = textToVideoViewModel::onHeightChange,
                onLengthChange = textToVideoViewModel::onLengthChange,
                onFpsChange = textToVideoViewModel::onFpsChange,
                onAddHighnoiseLora = textToVideoViewModel::onAddHighnoiseLora,
                onRemoveHighnoiseLora = textToVideoViewModel::onRemoveHighnoiseLora,
                onHighnoiseLoraChainNameChange = textToVideoViewModel::onHighnoiseLoraChainNameChange,
                onHighnoiseLoraChainStrengthChange = textToVideoViewModel::onHighnoiseLoraChainStrengthChange,
                onAddLownoiseLora = textToVideoViewModel::onAddLownoiseLora,
                onRemoveLownoiseLora = textToVideoViewModel::onRemoveLownoiseLora,
                onLownoiseLoraChainNameChange = textToVideoViewModel::onLownoiseLoraChainNameChange,
                onLownoiseLoraChainStrengthChange = textToVideoViewModel::onLownoiseLoraChainStrengthChange
            )
        }
    }
}
