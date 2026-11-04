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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.ui.components.ResultHeroImage
import sh.hnet.comfychair.ui.components.ResultHeroVideo
import sh.hnet.comfychair.ui.components.ResultsPreview
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSourceButton
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.rememberResultsViewer
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.ui.components.config.toBottomSheetConfig
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.ui.components.generation.GenerationControls
import sh.hnet.comfychair.ui.components.generation.GenerationOptionsSheet
import sh.hnet.comfychair.ui.components.generation.GenerationPreviewArea
import sh.hnet.comfychair.ui.components.generation.GenerationScreenEffects
import sh.hnet.comfychair.ui.components.generation.GenerationTopBar
import sh.hnet.comfychair.ui.components.generation.PositivePromptField
import sh.hnet.comfychair.ui.components.generation.PromptPresetDialogs
import sh.hnet.comfychair.ui.components.generation.rememberPromptInputState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onUseAsSource: (SourceTarget, suspend () -> Bitmap?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
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
    val presetUiState by presetViewModel.uiState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    GenerationScreenEffects(
        imageToImageViewModel, generationViewModel, presetViewModel, ScreenType.IMAGE_TO_IMAGE, ownerId,
        onJobStarted = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) }
    )
    val promptInput = rememberPromptInputState(uiState.positivePrompt)
    var showOptionsSheet by remember { mutableStateOf(false) }

    val isThisScreenExecuting = queueState.executingOwnerId == ownerId
    // Several results of the last job are shown as a pager with a thumbnail strip
    val showResults = !isThisScreenExecuting && uiState.generationOutputs.size > 1
    val isThisScreenQueued = !isThisScreenExecuting && queueState.ownJobs.values.any { it.ownerId == ownerId }

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
    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with image options
        GenerationTopBar(
            titleRes = R.string.title_image_to_image,
            ownerId = ownerId,
            generationViewModel = generationViewModel,
            onNavigateToSettings = onNavigateToSettings,
            onLogout = onLogout
        ) {
            // Upload image button
            IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.button_upload_source_image))
            }
            // Edit mask button (only in inpainting mode when source image exists)
            if (sourceImage != null && uiState.mode == ImageToImageMode.INPAINTING) {
                IconButton(onClick = {
                    // Initialize state holder and launch mask editor activity
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
                }) {
                    Icon(Icons.Default.Brush, contentDescription = stringResource(R.string.button_edit_mask))
                }
                // Clear mask button
                IconButton(onClick = { imageToImageViewModel.clearMask() }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.button_clear_mask))
                }
            }
        }

        // Image Preview Area + view mode toggle — collapse when typing in the prompt field
        GenerationPreviewArea(collapsed = promptInput.expanded) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
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
                    when (uiState.viewMode) {
                        ImageToImageViewMode.SOURCE -> {
                            if (sourceImage != null) {
                                if (uiState.mode == ImageToImageMode.INPAINTING) {
                                    // Read-only preview of source image with mask overlay
                                    MaskPreview(
                                        sourceImage = sourceImage,
                                        maskPaths = uiState.maskPaths,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    // Editing mode: show plain source image without mask
                                    Image(
                                        bitmap = sourceImage.asImageBitmap(),
                                        contentDescription = stringResource(R.string.content_description_source_image),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                // Placeholder - app logo
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
                        ImageToImageViewMode.PREVIEW -> {
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
                                        if (!current.isVideo) UseAsSourceButton { target -> onUseAsSource(target) { loadResultBitmap(current) } }
                                    }
                                ) { output, isCurrent ->
                                    if (output.isVideo) {
                                        ResultHeroVideo(output = output, isCurrent = isCurrent, isActive = true)
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
                                    val result = uiState.previewImage
                                    UseAsSourceButton { target -> onUseAsSource(target) { result } }
                                }
                            } else {
                        // Placeholder - app logo
                                Image(
                                    painter = painterResource(R.drawable.ic_comfychair_foreground),
                                    contentDescription = null,
                                    modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                                    contentScale = ContentScale.Fit
                                )
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
                        selected = uiState.viewMode == ImageToImageViewMode.SOURCE,
                        onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_source_image))
                    }
                    SegmentedButton(
                        selected = uiState.viewMode == ImageToImageViewMode.PREVIEW,
                        onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) },
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
                onPromptChange = imageToImageViewModel::onPositivePromptChange,
                input = promptInput,
                presetViewModel = presetViewModel,
                presetUiState = presetUiState
            )
        }

        GenerationControls(
            // Read here, where the screen observes its state
            isEnabled = imageToImageViewModel.hasValidConfiguration(),
            generationViewModel = generationViewModel,
            ownerId = ownerId,
            hasPositivePrompt = uiState.capabilities.hasPositivePrompt,
            isFetching = uiState.isFetching,
            optionsOpen = showOptionsSheet,
            onOpenOptions = { showOptionsSheet = true },
            prepareWorkflow = { imageToImageViewModel.prepareWorkflow() },
            isUploading = uiState.isUploading,
            // Inpainting needs a mask
            generationBlocker = {
                R.string.hint_paint_mask.takeIf {
                    uiState.mode == ImageToImageMode.INPAINTING && !imageToImageViewModel.hasMask()
                }
            }
        )
    }

    GenerationOptionsSheet(
        visible = showOptionsSheet,
        onDismiss = { showOptionsSheet = false },
        viewModel = imageToImageViewModel,
        state = uiState,
        spellCheckEnabled = promptInput.spellCheckEnabled,
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
