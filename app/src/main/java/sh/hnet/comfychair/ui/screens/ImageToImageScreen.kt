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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MaskEditorActivity
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MaskEditorStateHolder
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.queue.JobDescription
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.config.workflowGroups
import sh.hnet.comfychair.ui.components.config.workflowIcon
import sh.hnet.comfychair.ui.components.generation.EmptyResultState
import sh.hnet.comfychair.ui.components.generation.EmptySourcePicker
import sh.hnet.comfychair.ui.components.generation.GenerationControls
import sh.hnet.comfychair.ui.components.generation.GenerationOptionsSheet
import sh.hnet.comfychair.ui.components.generation.GenerationPreviewArea
import sh.hnet.comfychair.ui.components.generation.GenerationScreenEffects
import sh.hnet.comfychair.ui.components.generation.GenerationTopBar
import sh.hnet.comfychair.ui.components.generation.MaskLabel
import sh.hnet.comfychair.ui.components.generation.MaskTools
import sh.hnet.comfychair.ui.components.generation.PromptDock
import sh.hnet.comfychair.ui.components.generation.PromptPresetDialogs
import sh.hnet.comfychair.ui.components.generation.RecipeChips
import sh.hnet.comfychair.ui.components.generation.ReferenceSlot
import sh.hnet.comfychair.ui.components.generation.ReferenceSlots
import sh.hnet.comfychair.ui.components.generation.ResultActionsMenu
import sh.hnet.comfychair.ui.components.generation.ResultMedia
import sh.hnet.comfychair.ui.components.generation.ScreenJobStatus
import sh.hnet.comfychair.ui.components.generation.SourceResultSwitcher
import sh.hnet.comfychair.ui.components.generation.SourceToolbar
import sh.hnet.comfychair.ui.components.generation.imageSendActions
import sh.hnet.comfychair.ui.components.generation.rememberJobSubmitter
import sh.hnet.comfychair.ui.components.generation.rememberOutputThumbnail
import sh.hnet.comfychair.ui.components.generation.rememberPromptInputState
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    presetViewModel: PromptPresetViewModel = viewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    val ownerId = ImageToImageViewModel.OWNER_ID

    val uiState by imageToImageViewModel.uiState.collectAsState()
    // Read once per composition: also what click handlers below use
    val sourceImage = uiState.sourceImage
    val previewImage = uiState.previewImage
    // Offered only while Editing mode has a free reference slot
    val useAsReference: ((Bitmap) -> Unit)? =
        if (imageToImageViewModel.freeReferenceSlot(uiState) != null) imageToImageViewModel::useAsReference else null
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    GenerationScreenEffects(
        imageToImageViewModel, generationViewModel, presetViewModel, ScreenType.IMAGE_TO_IMAGE, ownerId,
        onJobStarted = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) }
    )
    val promptInput = rememberPromptInputState()
    var showOptionsSheet by remember { mutableStateOf(false) }

    val isThisScreenExecuting = queueState.executingOwnerId == ownerId
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting && queueState.hasWaitingJob(ownerId)

    // Image picker launcher for source image
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToImageViewModel.onSourceImageChange(context, it)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
        }
    }

    // UI composition
    // Opens the mask editor on the current source; its edits come back through the ViewModel
    fun openMaskEditor(sourceImage: Bitmap) {
        MaskEditorStateHolder.initialize(
            sourceImage = sourceImage,
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
    }

    // Whether the mask is drawn over the source; only a view setting, the mask itself stays
    var maskVisible by rememberSaveable { mutableStateOf(true) }

    // Generate from the dock and from the prompt editor
    val submitJob = rememberJobSubmitter(
        generationViewModel = generationViewModel,
        ownerId = ownerId,
        // Read when submitting, for the queue sheet (kept in memory only)
        describeJob = { JobDescription(uiState.positivePrompt, uiState.selectedWorkflow) },
        prepareWorkflow = { imageToImageViewModel.prepareWorkflow() },
        // Inpainting needs a mask
        generationBlocker = {
            R.string.hint_paint_mask.takeIf {
                uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()
            }
        }
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with image options
        GenerationTopBar(
            screen = MainRoute.ImageToImage,
            ownerId = ownerId,
            generationViewModel = generationViewModel
        )

        // Picture-in-picture thumbnails: the side that isn't shown full size
        val sourceThumb = remember(sourceImage) { sourceImage?.asImageBitmap() }
        val previewThumb = remember(previewImage) { previewImage?.asImageBitmap() }
        val outputThumb = rememberOutputThumbnail(uiState.generationOutputs.getOrNull(uiState.currentResultIndex).takeIf { showResults })
        val resultThumb = if (showResults) outputThumb ?: previewThumb else previewThumb

        // Preview area with the source and the result – collapses when typing in the prompt field
        GenerationPreviewArea {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 150.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(
                        enabled = (uiState.viewMode == ImageToImageViewMode.PREVIEW && uiState.previewImage != null && !isThisScreenExecuting && !showResults) ||
                                  (uiState.viewMode == ImageToImageViewMode.SOURCE && uiState.sourceImage != null),
                        onClick = {
                            when (uiState.viewMode) {
                                ImageToImageViewMode.PREVIEW -> {
                                    // Launch MediaViewer for generated image
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
                                }
                                ImageToImageViewMode.SOURCE -> {
                                    // Launch MediaViewer for source image (without mask)
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
                SourceResultSwitcher(
                    showSource = uiState.viewMode == ImageToImageViewMode.SOURCE,
                    onShowSourceChange = { showSource ->
                        imageToImageViewModel.onViewModeChange(
                            if (showSource) ImageToImageViewMode.SOURCE else ImageToImageViewMode.PREVIEW
                        )
                    },
                    sourceThumb = sourceThumb,
                    resultThumb = resultThumb,
                    source = { inTransition ->
                        val inpainting = uiState.mode == ImageToImageMode.INPAINTING
                        val hasMask = uiState.maskPaths.isNotEmpty()
                        if (sourceImage != null) {
                            if (inpainting && maskVisible) {
                                // Read-only preview of the source with the mask over it
                                MaskPreview(
                                    sourceImage = sourceImage,
                                    maskPaths = uiState.maskPaths,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Image(
                                    bitmap = sourceImage.asImageBitmap(),
                                    contentDescription = stringResource(R.string.content_description_source_image),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            EmptySourcePicker(onPick = { imagePickerLauncher.launch("image/*") })
                        }
                        // Tools appear once the swap animation is done
                        if (!inTransition) {
                            if (sourceImage != null) {
                                SourceToolbar(
                                    onReplace = { imagePickerLauncher.launch("image/*") },
                                    onRemove = imageToImageViewModel::clearSourceImage,
                                    maskTools = if (inpainting) MaskTools(
                                        hasMask = hasMask,
                                        maskVisible = maskVisible,
                                        onToggleMaskVisible = { maskVisible = !maskVisible },
                                        onClearMask = imageToImageViewModel::clearMask,
                                        onEditMask = { openMaskEditor(sourceImage) }
                                    ) else null
                                )
                                if (inpainting && maskVisible && hasMask) MaskLabel()
                            }
                            if (!inpainting) {
                                ReferenceSlots(
                                    listOfNotNull(
                                        ReferenceSlot(
                                            image = uiState.referenceImage1,
                                            description = stringResource(R.string.content_description_reference_image_1),
                                            onPick = { imageToImageViewModel.onReferenceImage1Change(context, it) },
                                            onClear = imageToImageViewModel::onClearReferenceImage1
                                        ).takeIf { uiState.capabilities.hasReferenceImage1 },
                                        ReferenceSlot(
                                            image = uiState.referenceImage2,
                                            description = stringResource(R.string.content_description_reference_image_2),
                                            onPick = { imageToImageViewModel.onReferenceImage2Change(context, it) },
                                            onClear = imageToImageViewModel::onClearReferenceImage2
                                        ).takeIf { uiState.capabilities.hasReferenceImage2 }
                                    )
                                )
                            }
                        }
                    },
                    // The video surface can't follow the swap animation, so a still stands in meanwhile
                    result = { inTransition ->
                        if (showResults) {
                            val results = uiState.generationOutputs
                            val openViewer = rememberResultsViewer(
                                generationViewModel.getHostname(), generationViewModel.getPort(), results, imageToImageViewModel::onCurrentResultChange
                            )
                            ResultsPreview(
                                results = results,
                                currentIndex = uiState.currentResultIndex,
                                onCurrentIndexChange = imageToImageViewModel::onCurrentResultChange,
                                onOpenViewer = openViewer,
                                stripEnabled = !isThisScreenQueued,
                                heroOverlay = { current ->
                                    // A new menu for each result, so it doesn't stay open after swiping
                                    key(current.id) {
                                        val preloaded = uiState.previewImage.takeIf { current.filename == uiState.previewImageFilename }
                                        val load: suspend () -> Bitmap? = { preloaded ?: loadResultBitmap(current) }
                                        ResultActionsMenu(
                                            media = ResultMedia.output(current, VideoUtils.GalleryPrefix.TEXT_TO_VIDEO, preloaded),
                                            sendActions = if (current.isVideo) emptyList() else imageSendActions(
                                                onUseAsSource, load,
                                                onUseAsReference = useAsReference?.let { use -> { scope.launch { load()?.let(use) } } }
                                            ),
                                            resultNumber = results.indexOf(current) + 1
                                        )
                                    }
                                }
                            ) { output, isCurrent ->
                                if (output.isVideo) {
                                    ResultHeroVideo(output = output, isCurrent = isCurrent, isActive = !inTransition)
                                } else {
                                    // The image already shown on the screen doesn't need loading again
                                    ResultHeroImage(
                                        output = output,
                                        preloaded = uiState.previewImage.takeIf { output.filename == uiState.previewImageFilename }
                                    )
                                }
                            }
                        } else if (previewImage != null) {
                            Image(
                                bitmap = previewImage.asImageBitmap(),
                                contentDescription = stringResource(R.string.content_description_preview),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // While generating, the area shows live previews instead of a result
                            if (!isThisScreenExecuting) {
                                ResultActionsMenu(
                                    media = ResultMedia.image(
                                        previewImage, uiState.previewImageFilename, uiState.previewImageSubfolder, uiState.previewImageType
                                    ),
                                    sendActions = imageSendActions(
                                        onUseAsSource, { previewImage },
                                        onUseAsReference = useAsReference?.let { use -> { use(previewImage) } }
                                    )
                                )
                            }
                        } else {
                    // Nothing generated yet
                            EmptyResultState()
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
            onPositiveChange = imageToImageViewModel::onPositivePromptChange,
            onNegativeChange = imageToImageViewModel::onNegativePromptChange,
            preview = uiState.previewImage ?: sourceImage,
            presetViewModel = presetViewModel,
            presetUiState = presetUiState,
            spellCheckEnabled = promptInput.spellCheckEnabled,
            generateEnabled = imageToImageViewModel.hasValidConfiguration(),
            onGenerate = { submitJob(false) }
        )

        RecipeChips(
            state = uiState,
            viewModel = imageToImageViewModel,
            icon = uiState.workflowIcon,
            groups = uiState.workflowGroups(imageToImageViewModel::onModeChange, imageToImageViewModel::onWorkflowChange)
        )

        GenerationControls(
            missingSource = sourceImage == null,
            // Read here, where the screen observes its state
            isEnabled = imageToImageViewModel.hasValidConfiguration(),
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
        viewModel = imageToImageViewModel,
        state = uiState,
        // Mode and reference images; the ViewModel applies every other change to the current mode
        extraCallbacks = { callbacks ->
            callbacks.copy(
                onModeChange = imageToImageViewModel::onModeChange,
                onReferenceImage1Change = { uri -> imageToImageViewModel.onReferenceImage1Change(context, uri) },
                onClearReferenceImage1 = imageToImageViewModel::onClearReferenceImage1,
                onReferenceImage2Change = { uri -> imageToImageViewModel.onReferenceImage2Change(context, uri) },
                onClearReferenceImage2 = imageToImageViewModel::onClearReferenceImage2
            )
        }
    ) { callbacks -> uiState.toBottomSheetConfig(callbacks) }

    PromptPresetDialogs(presetViewModel, presetUiState)
}
