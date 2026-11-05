package sh.hnet.comfychair.ui.screens

import sh.hnet.comfychair.ui.components.mediaOrigin
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.queue.JobDescription
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.LocalMediaViewer
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.config.singleWorkflowGroup
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.generation.EmptyResultState
import sh.hnet.comfychair.ui.components.generation.EmptySourcePicker
import sh.hnet.comfychair.ui.components.generation.GenerationControls
import sh.hnet.comfychair.ui.components.generation.GenerationOptionsSheet
import sh.hnet.comfychair.ui.components.generation.GenerationPreviewArea
import sh.hnet.comfychair.ui.components.generation.GenerationScreenEffects
import sh.hnet.comfychair.ui.components.generation.GenerationTopBar
import sh.hnet.comfychair.ui.components.generation.PromptDock
import sh.hnet.comfychair.ui.components.generation.PromptPresetDialogs
import sh.hnet.comfychair.ui.components.generation.RecipeChips
import sh.hnet.comfychair.ui.components.generation.ResultActionsMenu
import sh.hnet.comfychair.ui.components.generation.ResultMedia
import sh.hnet.comfychair.ui.components.generation.ScreenJobStatus
import sh.hnet.comfychair.ui.components.generation.SourceResultSwitcher
import sh.hnet.comfychair.ui.components.generation.SourceToolbar
import sh.hnet.comfychair.ui.components.generation.WorkflowIcons
import sh.hnet.comfychair.ui.components.generation.imageSendActions
import sh.hnet.comfychair.ui.components.generation.rememberJobSubmitter
import sh.hnet.comfychair.ui.components.generation.rememberOutputThumbnail
import sh.hnet.comfychair.ui.components.generation.rememberPromptInputState
import sh.hnet.comfychair.ui.components.generation.rememberVideoFrame
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToVideoScreen(
    generationViewModel: GenerationViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
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
    val promptInput = rememberPromptInputState()
    var showOptionsSheet by remember { mutableStateOf(false) }

    val isThisScreenExecuting = queueState.executingOwnerId == ownerId
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting && queueState.hasWaitingJob(ownerId)
    val videoUri = uiState.currentVideoUri
    val sourceImage = uiState.sourceImage
    val previewBitmap = uiState.previewBitmap

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

    val mediaViewer = LocalMediaViewer.current

    // Generate from the dock and from the prompt editor
    val submitJob = rememberJobSubmitter(
        generationViewModel = generationViewModel,
        ownerId = ownerId,
        // Read when submitting, for the queue sheet (kept in memory only)
        describeJob = { JobDescription(uiState.positivePrompt, uiState.selectedWorkflow) },
        prepareWorkflow = { imageToVideoViewModel.prepareWorkflow() },
        contentType = ContentType.VIDEO
    )

    Column(modifier = Modifier.fillMaxSize()) {
        GenerationTopBar(
            screen = MainRoute.ImageToVideo,
            ownerId = ownerId,
            generationViewModel = generationViewModel
        )

        // Picture-in-picture thumbnails: the side that isn't shown full size
        val sourceThumb = remember(sourceImage) { sourceImage?.asImageBitmap() }
        val liveThumb = remember(previewBitmap) { previewBitmap?.asImageBitmap() }
        val outputThumb = rememberOutputThumbnail(uiState.generationOutputs.getOrNull(uiState.currentResultIndex).takeIf { showResults })
        // Large enough to stand in for the video while the sides swap
        val videoFrame = rememberVideoFrame(videoUri.takeIf { !showResults }, sizeDp = 180.dp)
        val resultThumb = liveThumb ?: (if (showResults) outputThumb else videoFrame)

        // Preview area with the source and the result – collapses when typing in the prompt field
        GenerationPreviewArea {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 150.dp)
                    // The viewer flies the source image out of the preview and back (a video has no still to fly)
                    .mediaOrigin(
                        SOURCE_ORIGIN.takeIf { uiState.viewMode == ImageToVideoViewMode.SOURCE },
                        fit = true,
                        corner = 28.dp
                    )
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(
                        enabled = (uiState.viewMode == ImageToVideoViewMode.PREVIEW && videoUri != null && !showResults) ||
                                  (uiState.viewMode == ImageToVideoViewMode.SOURCE && uiState.sourceImage != null),
                        onClick = {
                            when (uiState.viewMode) {
                                ImageToVideoViewMode.PREVIEW -> {
                                    videoUri?.let { uri -> mediaViewer.openVideo(uri) }
                                }
                                ImageToVideoViewMode.SOURCE -> {
                                    // Source image is user-provided, no ComfyUI metadata
                                    uiState.sourceImage?.let { bitmap -> mediaViewer.openImage(bitmap, originKey = SOURCE_ORIGIN) }
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                SourceResultSwitcher(
                    showSource = uiState.viewMode == ImageToVideoViewMode.SOURCE,
                    onShowSourceChange = { showSource ->
                        imageToVideoViewModel.onViewModeChange(
                            if (showSource) ImageToVideoViewMode.SOURCE else ImageToVideoViewMode.PREVIEW
                        )
                    },
                    sourceThumb = sourceThumb,
                    resultThumb = resultThumb,
                    source = { inTransition ->
                        if (sourceImage != null) {
                            Image(
                                bitmap = sourceImage.asImageBitmap(),
                                contentDescription = stringResource(R.string.content_description_source_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // Tools appear once the swap animation is done
                            if (!inTransition) {
                                SourceToolbar(
                                    onReplace = { imagePickerLauncher.launch("image/*") },
                                    onRemove = imageToVideoViewModel::clearSourceImage
                                )
                            }
                        } else {
                            EmptySourcePicker(onPick = { imagePickerLauncher.launch("image/*") })
                        }
                    },
                    // The video surface can't follow the swap animation, so a still stands in meanwhile
                    result = { inTransition ->
                        when {
                            showResults -> {
                                val results = uiState.generationOutputs
                                val openViewer = rememberResultsViewer(results, imageToVideoViewModel::onCurrentResultChange)
                                // The screen already plays the job's first video
                                val firstVideo = results.firstOrNull { it.isVideo }
                                ResultsPreview(
                                    results = results,
                                    currentIndex = uiState.currentResultIndex,
                                    onCurrentIndexChange = imageToVideoViewModel::onCurrentResultChange,
                                    onOpenViewer = openViewer,
                                    stripEnabled = !isThisScreenQueued,
                                    heroOverlay = { current ->
                                        // A new menu for each result, so it doesn't stay open after swiping
                                        key(current.id) {
                                            ResultActionsMenu(
                                                media = ResultMedia.output(current, VideoUtils.GalleryPrefix.IMAGE_TO_VIDEO),
                                                sendActions = if (current.isVideo) emptyList() else {
                                                    imageSendActions(onUseAsSource, { loadResultBitmap(current) })
                                                },
                                                resultNumber = results.indexOf(current) + 1
                                            )
                                        }
                                    }
                                ) { output, isCurrent ->
                                    if (output.isVideo) {
                                        ResultHeroVideo(
                                            output = output,
                                            isCurrent = isCurrent,
                                            isActive = isScreenVisible && !inTransition,
                                            preloadedUri = videoUri.takeIf { output == firstVideo }
                                        )
                                    } else {
                                        ResultHeroImage(output)
                                    }
                                }
                            }
                            // Show preview bitmap during generation
                            previewBitmap != null -> {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
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
                                    isActive = isScreenVisible && !inTransition,
                                    showPlaybackStatus = true,
                                    // The frame that stood in during the swap, so the player shows it right away
                                    placeholder = videoFrame?.asAndroidBitmap()
                                )
                                ResultActionsMenu(
                                    media = ResultMedia.video(videoUri, VideoUtils.GalleryPrefix.IMAGE_TO_VIDEO),
                                    sendActions = emptyList()
                                )
                            }
                            // Nothing generated yet
                            else -> {
                                EmptyResultState()
                            }
                        }
                        // Over the live frames while this screen's job runs
                        ScreenJobStatus(ownerId, generationViewModel)
                    }
                )
            }
        }

        // The prompt; tapping it opens the full-screen editor (absent for workflows without prompts)
        PromptDock(
            state = uiState,
            onPositiveChange = imageToVideoViewModel::onPositivePromptChange,
            onNegativeChange = imageToVideoViewModel::onNegativePromptChange,
            preview = uiState.previewBitmap ?: sourceImage,
            presetViewModel = presetViewModel,
            presetUiState = presetUiState,
            spellCheckEnabled = promptInput.spellCheckEnabled,
            generateEnabled = imageToVideoViewModel.hasValidConfiguration(),
            onGenerate = { submitJob(false) }
        )

        RecipeChips(
            state = uiState,
            viewModel = imageToVideoViewModel,
            icon = WorkflowIcons.default,
            groups = singleWorkflowGroup(uiState.availableWorkflows, imageToVideoViewModel::onWorkflowChange)
        )

        GenerationControls(
            missingSource = sourceImage == null,
            // Read here, where the screen observes its state
            isEnabled = imageToVideoViewModel.hasValidConfiguration(),
            generationViewModel = generationViewModel,
            ownerId = ownerId,
            hasPositivePrompt = uiState.capabilities.hasPositivePrompt,
            isFetching = uiState.isFetching,
            optionsOpen = showOptionsSheet,
            onOpenOptions = { showOptionsSheet = true },
            isUploading = uiState.isUploading,
            submit = submitJob
        )
    }

    GenerationOptionsSheet(
        visible = showOptionsSheet,
        onDismiss = { showOptionsSheet = false },
        viewModel = imageToVideoViewModel,
        state = uiState
    ) { callbacks -> uiState.toBottomSheetConfig(callbacks) }

    PromptPresetDialogs(presetViewModel, presetUiState, uiState.positivePrompt)
}

/** Media origin of this screen's source image in the preview. */
private const val SOURCE_ORIGIN = "itv_source"
