package sh.hnet.comfychair.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.ImageToVideoConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoEvent
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode
import java.io.File

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

    // Video state
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToVideoViewModel.setSourceImage(context, it)
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

        // Load last generated video
        val videoFile = File(context.filesDir, "image_to_video_last_generated.mp4")
        if (videoFile.exists()) {
            videoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
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
        imageToVideoViewModel.startListening(
            generationViewModel,
            onPreviewBitmap = { bitmap ->
                previewBitmap = bitmap
            },
            onVideoFetched = { promptId ->
                fetchVideoFromHistory(context, generationViewModel, promptId, "image_to_video_last_generated.mp4") { uri ->
                    videoUri = uri
                    generationViewModel.completeGeneration()
                }
            }
        )
        onDispose {
            imageToVideoViewModel.stopListening(generationViewModel)
        }
    }

    // Clear preview when not generating
    LaunchedEffect(generationState.isGenerating) {
        if (!generationState.isGenerating) {
            previewBitmap = null
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
                            saveVideoToGallery(context, videoUri)
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        shareVideo(context, videoUri)
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(
                    enabled = (uiState.viewMode == ImageToVideoViewMode.PREVIEW && videoUri != null) ||
                              (uiState.viewMode == ImageToVideoViewMode.SOURCE && uiState.sourceImage != null),
                    onClick = {
                        when (uiState.viewMode) {
                            ImageToVideoViewMode.PREVIEW -> {
                                videoUri?.let { uri ->
                                    val intent = MediaViewerActivity.createSingleVideoIntent(context, uri)
                                    context.startActivity(intent)
                                }
                            }
                            ImageToVideoViewMode.SOURCE -> {
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
                        // Placeholder - monochrome app logo with tap to upload hint
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { imagePickerLauncher.launch("image/*") }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_foreground_monochrome),
                                contentDescription = null,
                                modifier = Modifier.size(192.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                        previewBitmap != null -> {
                            Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
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
                                modifier = Modifier.size(192.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Progress indicator - only show if THIS screen started generation
                    if (isThisScreenGenerating && generationState.maxProgress > 0) {
                        val progress = generationState.progress.toFloat() / generationState.maxProgress.toFloat()
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .height(8.dp)
                                .align(Alignment.BottomCenter)
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
            value = uiState.prompt,
            onValueChange = { imageToVideoViewModel.onPromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.prompt.isNotEmpty()) {
                    IconButton(onClick = { imageToVideoViewModel.onPromptChange("") }) {
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
            ElevatedButton(
                onClick = {
                    if (isThisScreenGenerating) {
                        // Cancel generation (only if this screen started it)
                        generationViewModel.cancelGeneration { }
                    } else if (!generationState.isGenerating) {
                        // Start generation (only if no generation is running)
                        scope.launch {
                            val workflowJson = imageToVideoViewModel.prepareWorkflow()
                            if (workflowJson != null) {
                                // Clear preview and switch to preview view
                                videoUri = null
                                previewBitmap = null
                                imageToVideoViewModel.clearPreview()
                                imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW)

                                generationViewModel.startGeneration(
                                    workflowJson,
                                    ImageToVideoViewModel.OWNER_ID
                                ) { success, _, errorMessage ->
                                    if (!success) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.error_failed_start_generation, errorMessage ?: "Unknown error"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = isThisScreenGenerating ||
                    (!generationState.isGenerating && imageToVideoViewModel.hasValidConfiguration() && uiState.prompt.isNotBlank()),
                colors = if (isThisScreenGenerating) {
                    ButtonDefaults.elevatedButtonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.elevatedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            ) {
                Text(
                    text = if (isThisScreenGenerating) {
                        stringResource(R.string.button_cancel_generation)
                    } else {
                        stringResource(R.string.button_generate)
                    },
                    fontSize = 18.sp
                )
            }

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
                onHighnoiseUnetChange = imageToVideoViewModel::onHighnoiseUnetChange,
                onLownoiseUnetChange = imageToVideoViewModel::onLownoiseUnetChange,
                onHighnoiseLoraChange = imageToVideoViewModel::onHighnoiseLoraChange,
                onLownoiseLoraChange = imageToVideoViewModel::onLownoiseLoraChange,
                onVaeChange = imageToVideoViewModel::onVaeChange,
                onClipChange = imageToVideoViewModel::onClipChange,
                onWidthChange = imageToVideoViewModel::onWidthChange,
                onHeightChange = imageToVideoViewModel::onHeightChange,
                onLengthChange = imageToVideoViewModel::onLengthChange,
                onFpsChange = imageToVideoViewModel::onFpsChange
            )
        }
    }
}

private fun fetchVideoFromHistory(
    context: Context,
    generationViewModel: GenerationViewModel,
    promptId: String,
    videoFilename: String,
    onComplete: (Uri?) -> Unit
) {
    val client = generationViewModel.getClient() ?: run {
        onComplete(null)
        return
    }

    val mainHandler = Handler(Looper.getMainLooper())

    client.fetchHistory(promptId) { historyJson ->
        if (historyJson == null) {
            mainHandler.post { onComplete(null) }
            return@fetchHistory
        }

        // Parse video info from history
        val promptHistory = historyJson.optJSONObject(promptId)
        val outputs = promptHistory?.optJSONObject("outputs")

        if (outputs == null) {
            mainHandler.post { onComplete(null) }
            return@fetchHistory
        }

        // Find video in outputs
        val outputKeys = outputs.keys()
        while (outputKeys.hasNext()) {
            val nodeId = outputKeys.next()
            val nodeOutput = outputs.optJSONObject(nodeId)

            // Check videos, gifs, and images arrays for video files
            val videos = nodeOutput?.optJSONArray("videos")
                ?: nodeOutput?.optJSONArray("gifs")
                ?: nodeOutput?.optJSONArray("images")

            if (videos != null && videos.length() > 0) {
                val videoInfo = videos.optJSONObject(0)
                val filename = videoInfo?.optString("filename") ?: continue
                val subfolder = videoInfo.optString("subfolder", "")
                val type = videoInfo.optString("type", "output")

                // Fetch video bytes
                client.fetchVideo(filename, subfolder, type) { videoBytes ->
                    if (videoBytes == null) {
                        mainHandler.post { onComplete(null) }
                        return@fetchVideo
                    }

                    // Save to internal storage
                    val videoFile = File(context.filesDir, videoFilename)
                    videoFile.writeBytes(videoBytes)

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        videoFile
                    )

                    mainHandler.post { onComplete(uri) }
                }
                return@fetchHistory
            }
        }

        mainHandler.post { onComplete(null) }
    }
}

private suspend fun saveVideoToGallery(context: Context, videoUri: Uri?) {
    if (videoUri == null) return

    withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "ComfyChair_ITV_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ComfyChair")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let { outputUri ->
                resolver.openOutputStream(outputUri)?.use { outputStream ->
                    resolver.openInputStream(videoUri)?.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.video_saved_to_gallery),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.failed_save_video),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

private fun shareVideo(context: Context, videoUri: Uri?) {
    if (videoUri == null) return

    try {
        val shareIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_STREAM, videoUri)
            type = "video/mp4"
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            android.content.Intent.createChooser(
                shareIntent,
                context.getString(R.string.share_video)
            )
        )
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.failed_share_video),
            Toast.LENGTH_SHORT
        ).show()
    }
}
