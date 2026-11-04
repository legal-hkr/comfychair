package sh.hnet.comfychair.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.queue.JobDescription
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.VideoPlayer
import sh.hnet.comfychair.ui.components.config.singleWorkflowGroup
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.generation.EmptyResultState
import sh.hnet.comfychair.ui.components.generation.GenerationControls
import sh.hnet.comfychair.ui.components.generation.GenerationOptionsSheet
import sh.hnet.comfychair.ui.components.generation.GenerationPreviewArea
import sh.hnet.comfychair.ui.components.generation.GenerationScreenEffects
import sh.hnet.comfychair.ui.components.generation.GenerationTopBar
import sh.hnet.comfychair.ui.components.generation.PositivePromptField
import sh.hnet.comfychair.ui.components.generation.PromptPresetDialogs
import sh.hnet.comfychair.ui.components.generation.RecipeChips
import sh.hnet.comfychair.ui.components.generation.ResultActionsMenu
import sh.hnet.comfychair.ui.components.generation.ResultMedia
import sh.hnet.comfychair.ui.components.generation.ScreenJobStatus
import sh.hnet.comfychair.ui.components.generation.WorkflowIcons
import sh.hnet.comfychair.ui.components.generation.imageSendActions
import sh.hnet.comfychair.ui.components.generation.rememberPromptInputState
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel

@Composable
fun TextToVideoScreen(
    generationViewModel: GenerationViewModel,
    textToVideoViewModel: TextToVideoViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownerId = TextToVideoViewModel.OWNER_ID

    val uiState by textToVideoViewModel.uiState.collectAsState()
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    GenerationScreenEffects(textToVideoViewModel, generationViewModel, presetViewModel, ScreenType.TEXT_TO_VIDEO, ownerId)
    val promptInput = rememberPromptInputState(uiState.positivePrompt)
    var showOptionsSheet by remember { mutableStateOf(false) }

    val isThisScreenExecuting = queueState.executingOwnerId == ownerId
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting && queueState.hasWaitingJob(ownerId)
    val videoUri = uiState.currentVideoUri
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

    Column(modifier = Modifier.fillMaxSize()) {
        GenerationTopBar(
            screen = MainRoute.TextToVideo,
            ownerId = ownerId,
            generationViewModel = generationViewModel
        )

        GenerationPreviewArea(collapsed = promptInput.expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 150.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = videoUri != null && !showResults) {
                        // Open the single video in the media viewer
                        videoUri?.let { uri ->
                            context.startActivity(
                                MediaViewerActivity.createSingleVideoIntent(
                                    context = context,
                                    videoUri = uri,
                                    hostname = generationViewModel.getHostname(),
                                    port = generationViewModel.getPort()
                                )
                            )
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
                                // A new menu for each result, so it doesn't stay open after swiping
                                key(current.id) {
                                    ResultActionsMenu(
                                        media = ResultMedia.output(current, VideoUtils.GalleryPrefix.TEXT_TO_VIDEO),
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
                                    isActive = isScreenVisible,
                                    preloadedUri = videoUri.takeIf { output == firstVideo }
                                )
                            } else {
                                ResultHeroImage(output)
                            }
                        }
                    }
                    // Live preview during generation
                    previewBitmap != null -> {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_description_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    videoUri != null -> {
                        VideoPlayer(videoUri = videoUri, modifier = Modifier.fillMaxSize(), isActive = isScreenVisible, showPlaybackStatus = true)
                        ResultActionsMenu(
                            media = ResultMedia.video(videoUri, VideoUtils.GalleryPrefix.TEXT_TO_VIDEO),
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
        }

        // Hidden for workflows without a positive prompt placeholder (e.g. upscalers)
        if (uiState.capabilities.hasPositivePrompt) {
            PositivePromptField(
                prompt = uiState.positivePrompt,
                onPromptChange = textToVideoViewModel::onPositivePromptChange,
                input = promptInput,
                presetViewModel = presetViewModel,
                presetUiState = presetUiState
            )
        }

        RecipeChips(
            state = uiState,
            viewModel = textToVideoViewModel,
            icon = WorkflowIcons.default,
            groups = singleWorkflowGroup(uiState.availableWorkflows, textToVideoViewModel::onWorkflowChange)
        )

        GenerationControls(
            // Read when submitting, for the queue sheet (kept in memory only)
            describeJob = { JobDescription(uiState.positivePrompt, uiState.selectedWorkflow) },
            // Read here, where the screen observes its state
            isEnabled = textToVideoViewModel.hasValidConfiguration(),
            generationViewModel = generationViewModel,
            ownerId = ownerId,
            hasPositivePrompt = uiState.capabilities.hasPositivePrompt,
            isFetching = uiState.isFetching,
            optionsOpen = showOptionsSheet,
            onOpenOptions = { showOptionsSheet = true },
            prepareWorkflow = { textToVideoViewModel.prepareWorkflow() },
            contentType = ContentType.VIDEO
        )
    }

    GenerationOptionsSheet(
        visible = showOptionsSheet,
        onDismiss = { showOptionsSheet = false },
        viewModel = textToVideoViewModel,
        state = uiState,
        spellCheckEnabled = promptInput.spellCheckEnabled
    ) { callbacks -> uiState.toBottomSheetConfig(callbacks) }

    PromptPresetDialogs(presetViewModel, presetUiState)
}
