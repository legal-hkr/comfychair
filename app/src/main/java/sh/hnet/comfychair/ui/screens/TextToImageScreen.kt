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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.queue.JobDescription
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.generation.EmptyResultState
import sh.hnet.comfychair.ui.components.generation.GenerationControls
import sh.hnet.comfychair.ui.components.generation.GenerationOptionsSheet
import sh.hnet.comfychair.ui.components.generation.GenerationPreviewArea
import sh.hnet.comfychair.ui.components.generation.GenerationScreenEffects
import sh.hnet.comfychair.ui.components.generation.GenerationTopBar
import sh.hnet.comfychair.ui.components.generation.PositivePromptField
import sh.hnet.comfychair.ui.components.generation.PromptPresetDialogs
import sh.hnet.comfychair.ui.components.generation.ResultActionsMenu
import sh.hnet.comfychair.ui.components.generation.ResultMedia
import sh.hnet.comfychair.ui.components.generation.ScreenJobStatus
import sh.hnet.comfychair.ui.components.generation.imageSendActions
import sh.hnet.comfychair.ui.components.generation.rememberPromptInputState
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel

@Composable
fun TextToImageScreen(
    generationViewModel: GenerationViewModel,
    textToImageViewModel: TextToImageViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val ownerId = TextToImageViewModel.OWNER_ID

    val uiState by textToImageViewModel.uiState.collectAsState()
    val previewBitmap = uiState.previewBitmap
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    GenerationScreenEffects(textToImageViewModel, generationViewModel, presetViewModel, ScreenType.TEXT_TO_IMAGE, ownerId)
    val promptInput = rememberPromptInputState(uiState.positivePrompt)
    var showOptionsSheet by remember { mutableStateOf(false) }

    val isThisScreenExecuting = queueState.executingOwnerId == ownerId
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting && queueState.hasWaitingJob(ownerId)

    Column(modifier = Modifier.fillMaxSize()) {
        GenerationTopBar(
            screen = MainRoute.TextToImage,
            ownerId = ownerId,
            generationViewModel = generationViewModel
        )

        GenerationPreviewArea(collapsed = promptInput.expanded) {
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
                            // A new menu for each result, so it doesn't stay open after swiping
                            key(current.id) {
                                val preloaded = uiState.previewBitmap.takeIf { current.filename == uiState.currentImageFilename }
                                ResultActionsMenu(
                                    media = ResultMedia.output(current, VideoUtils.GalleryPrefix.TEXT_TO_VIDEO, preloaded),
                                    sendActions = if (current.isVideo) emptyList() else {
                                        imageSendActions(onUseAsSource, { preloaded ?: loadResultBitmap(current) })
                                    },
                                    resultNumber = results.indexOf(current) + 1
                                )
                            }
                        }
                    ) { output, _ ->
                        // The image already shown on the screen doesn't need loading again
                        ResultHeroImage(
                            output = output,
                            preloaded = uiState.previewBitmap.takeIf { output.filename == uiState.currentImageFilename }
                        )
                    }
                } else if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.content_description_generated_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // While generating, the area shows live previews instead of a result
                    if (!isThisScreenExecuting) {
                        ResultActionsMenu(
                            media = ResultMedia.image(
                                previewBitmap, uiState.currentImageFilename, uiState.currentImageSubfolder, uiState.currentImageType
                            ),
                            sendActions = imageSendActions(onUseAsSource, { previewBitmap })
                        )
                    }
                } else {
                    // Nothing generated yet
                    EmptyResultState()
                }
                // Over the live frames while this screen's job runs
                ScreenJobStatus(ownerId, generationViewModel)
            }
        }

        // Hidden for workflows without a positive prompt placeholder (e.g. upscalers)
        if (uiState.capabilities.hasPositivePrompt) {
            PositivePromptField(
                prompt = uiState.positivePrompt,
                onPromptChange = textToImageViewModel::onPositivePromptChange,
                input = promptInput,
                presetViewModel = presetViewModel,
                presetUiState = presetUiState
            )
        }

        GenerationControls(
            // Read when submitting, for the queue sheet (kept in memory only)
            describeJob = { JobDescription(uiState.positivePrompt, uiState.selectedWorkflow) },
            // Read here, where the screen observes its state
            isEnabled = textToImageViewModel.hasValidConfiguration(),
            generationViewModel = generationViewModel,
            ownerId = ownerId,
            hasPositivePrompt = uiState.capabilities.hasPositivePrompt,
            isFetching = uiState.isFetching,
            optionsOpen = showOptionsSheet,
            onOpenOptions = { showOptionsSheet = true },
            prepareWorkflow = { textToImageViewModel.prepareWorkflowJson() }
        )
    }

    GenerationOptionsSheet(
        visible = showOptionsSheet,
        onDismiss = { showOptionsSheet = false },
        viewModel = textToImageViewModel,
        state = uiState,
        spellCheckEnabled = promptInput.spellCheckEnabled
    ) { callbacks -> uiState.toBottomSheetConfig(callbacks) }

    PromptPresetDialogs(presetViewModel, presetUiState)
}
