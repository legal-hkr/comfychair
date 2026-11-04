package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSourceButton
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.generation.GenerationControls
import sh.hnet.comfychair.ui.components.generation.GenerationOptionsSheet
import sh.hnet.comfychair.ui.components.generation.GenerationPreviewArea
import sh.hnet.comfychair.ui.components.generation.GenerationScreenEffects
import sh.hnet.comfychair.ui.components.generation.GenerationTopBar
import sh.hnet.comfychair.ui.components.generation.PositivePromptField
import sh.hnet.comfychair.ui.components.generation.PromptPresetDialogs
import sh.hnet.comfychair.ui.components.generation.rememberPromptInputState
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.ui.components.generation.SaveShareActions
import sh.hnet.comfychair.util.MediaExport

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToVideoScreen(
    generationViewModel: GenerationViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownerId = ImageToVideoViewModel.OWNER_ID

    val uiState by imageToVideoViewModel.uiState.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    GenerationScreenEffects(
        imageToVideoViewModel, generationViewModel, presetViewModel, ScreenType.IMAGE_TO_VIDEO, ownerId,
        onJobStarted = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW) }
    )
    val promptInput = rememberPromptInputState(uiState.positivePrompt)
    var showOptionsSheet by remember { mutableStateOf(false) }

    val isThisScreenExecuting = queueState.executingOwnerId == ownerId
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting && queueState.ownJobs.values.any { it.ownerId == ownerId }
    val videoUri = uiState.currentVideoUri

    // Video plays only while the screen is resumed, so it doesn't render over navigation transitions
    var isScreenVisible by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isScreenVisible = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            imageToVideoViewModel.onSourceImageChange(context, it)
            imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GenerationTopBar(
            titleRes = R.string.title_image_to_video,
            ownerId = ownerId,
            generationViewModel = generationViewModel,
            onNavigateToSettings = onNavigateToSettings,
            onLogout = onLogout
        ) {
            IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.button_upload_source_image))
            }
            if (videoUri != null && uiState.viewMode == ImageToVideoViewMode.PREVIEW) {
                SaveShareActions(
                    kind = MediaExport.Kind.VIDEO,
                    saveToGallery = { MediaExport.saveVideo(context, videoUri, VideoUtils.GalleryPrefix.IMAGE_TO_VIDEO) },
                    shareFile = { MediaExport.shareFile(context, videoUri) }
                )
            }
        }

        // Preview area with the Source/Preview toggle
        GenerationPreviewArea(collapsed = promptInput.expanded) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 150.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(
                            enabled = (uiState.viewMode == ImageToVideoViewMode.PREVIEW && videoUri != null && !showResults) ||
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
                                    contentDescription = stringResource(R.string.tab_source_image),
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
                        ImageToVideoViewMode.PREVIEW -> {
                            when {
                                showResults -> {
                                    val results = uiState.generationOutputs
                                    val openViewer = rememberResultsViewer(
                                        generationViewModel.getHostname(), generationViewModel.getPort(), results, imageToVideoViewModel::onCurrentResultChange
                                    )
                                    // The screen already plays the job's first video
                                    val firstVideo = results.firstOrNull { it.isVideo }
                                    ResultsPreview(
                                        results = results,
                                        currentIndex = uiState.currentResultIndex,
                                        onCurrentIndexChange = imageToVideoViewModel::onCurrentResultChange,
                                        onOpenViewer = openViewer,
                                        stripEnabled = !isThisScreenQueued,
                                        heroOverlay = { current ->
                                            // The source of Image to Video is an image
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
                        selected = uiState.viewMode == ImageToVideoViewMode.SOURCE,
                        onClick = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.SOURCE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_source_image))
                    }
                    SegmentedButton(
                        selected = uiState.viewMode == ImageToVideoViewMode.PREVIEW,
                        onClick = { imageToVideoViewModel.onViewModeChange(ImageToVideoViewMode.PREVIEW) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_preview))
                    }
                }
            }
        }

        // Hidden for workflows without a positive prompt placeholder (e.g. upscalers)
        if (uiState.capabilities.hasPositivePrompt) {
            PositivePromptField(
                prompt = uiState.positivePrompt,
                onPromptChange = imageToVideoViewModel::onPositivePromptChange,
                input = promptInput,
                presetViewModel = presetViewModel,
                presetUiState = presetUiState
            )
        }

        GenerationControls(
            // Read here, where the screen observes its state
            isEnabled = imageToVideoViewModel.hasValidConfiguration(),
            generationViewModel = generationViewModel,
            ownerId = ownerId,
            hasPositivePrompt = uiState.capabilities.hasPositivePrompt,
            isFetching = uiState.isFetching,
            optionsOpen = showOptionsSheet,
            onOpenOptions = { showOptionsSheet = true },
            prepareWorkflow = { imageToVideoViewModel.prepareWorkflow() },
            contentType = ContentType.VIDEO,
            isUploading = uiState.isUploading
        )
    }

    GenerationOptionsSheet(
        visible = showOptionsSheet,
        onDismiss = { showOptionsSheet = false },
        viewModel = imageToVideoViewModel,
        state = uiState,
        spellCheckEnabled = promptInput.spellCheckEnabled
    ) { callbacks -> uiState.toBottomSheetConfig(callbacks) }

    PromptPresetDialogs(presetViewModel, presetUiState)
}
