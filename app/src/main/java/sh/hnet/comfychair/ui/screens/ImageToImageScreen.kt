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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Upload
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
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.theme.Dimensions
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.ImageToImageConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.MaskEditorDialog
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageEvent
import sh.hnet.comfychair.viewmodel.ImageToImageViewMode
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToImageScreen(
    generationViewModel: GenerationViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by imageToImageViewModel.uiState.collectAsState()

    // Check if THIS screen owns the current generation
    val isThisScreenGenerating = generationState.isGenerating &&
        generationState.ownerId == ImageToImageViewModel.OWNER_ID

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showMaskEditor by remember { mutableStateOf(false) }

    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            imageToImageViewModel.onSourceImageChange(context, it)
            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE)
        }
    }

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

    // Handle Image-to-image events
    LaunchedEffect(Unit) {
        imageToImageViewModel.events.collect { event ->
            when (event) {
                is ImageToImageEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is ImageToImageEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with image options
        TopAppBar(
            title = { Text(stringResource(R.string.image_to_image_title)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Upload image button
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.upload_source_image))
                }
                // Edit mask button (only when source image exists)
                if (uiState.sourceImage != null) {
                    IconButton(onClick = { showMaskEditor = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_mask))
                    }
                    // Clear mask button
                    IconButton(onClick = { imageToImageViewModel.clearMask() }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_mask))
                    }
                }
                // Menu button
                AppMenuDropdown(
                    onSettings = onNavigateToSettings,
                    onLogout = onLogout
                )
            }
        )

        // Image Preview Area
        // Only allow tapping final generated image or source image, not live previews during generation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(
                    enabled = (uiState.viewMode == ImageToImageViewMode.PREVIEW && uiState.previewImage != null && !isThisScreenGenerating) ||
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
                    if (uiState.sourceImage != null) {
                        // Read-only preview of source image with mask overlay
                        MaskPreview(
                            sourceImage = uiState.sourceImage,
                            maskPaths = uiState.maskPaths,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Placeholder - app logo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { imagePickerLauncher.launch("image/*") }
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
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
                ImageToImageViewMode.PREVIEW -> {
                    if (uiState.previewImage != null) {
                        Image(
                            bitmap = uiState.previewImage!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_description_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Placeholder - app logo
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(Dimensions.PlaceholderLogoSize),
                            contentScale = ContentScale.Fit
                        )
                    }

                    // Progress indicator - only show if THIS screen started generation
                    if (isThisScreenGenerating) {
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
                selected = uiState.viewMode == ImageToImageViewMode.SOURCE,
                onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.SOURCE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.source_image_tab))
            }
            SegmentedButton(
                selected = uiState.viewMode == ImageToImageViewMode.PREVIEW,
                onClick = { imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.preview_tab))
            }
        }

        // Prompt Input
        OutlinedTextField(
            value = uiState.positivePrompt,
            onValueChange = { imageToImageViewModel.onPositivePromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
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
                isGenerating = isThisScreenGenerating,
                isEnabled = isThisScreenGenerating || (
                    !generationState.isGenerating &&
                    imageToImageViewModel.hasValidConfiguration() &&
                    uiState.positivePrompt.isNotBlank() &&
                    uiState.sourceImage != null
                ),
                onGenerate = {
                    scope.launch {
                        if (!imageToImageViewModel.hasMask()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.paint_mask_hint),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        val workflowJson = imageToImageViewModel.prepareWorkflow()
                        if (workflowJson != null) {
                            imageToImageViewModel.clearPreview()
                            imageToImageViewModel.onViewModeChange(ImageToImageViewMode.PREVIEW)
                            generationViewModel.startGeneration(
                                workflowJson,
                                ImageToImageViewModel.OWNER_ID
                            ) { _, _, _ -> }
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
            sheetState = optionsSheetState
        ) {
            ImageToImageConfigBottomSheetContent(
                uiState = uiState,
                // Unified workflow callback
                onWorkflowChange = imageToImageViewModel::onWorkflowChange,
                // Model selection callbacks
                onCheckpointChange = imageToImageViewModel::onCheckpointChange,
                onUnetChange = imageToImageViewModel::onUnetChange,
                onVaeChange = imageToImageViewModel::onVaeChange,
                onClipChange = imageToImageViewModel::onClipChange,
                // Unified parameter callbacks
                onNegativePromptChange = imageToImageViewModel::onNegativePromptChange,
                onMegapixelsChange = imageToImageViewModel::onMegapixelsChange,
                onStepsChange = imageToImageViewModel::onStepsChange,
                onCfgChange = imageToImageViewModel::onCfgChange,
                onSamplerChange = imageToImageViewModel::onSamplerChange,
                onSchedulerChange = imageToImageViewModel::onSchedulerChange,
                // Unified LoRA chain callbacks
                onAddLora = imageToImageViewModel::onAddLora,
                onRemoveLora = imageToImageViewModel::onRemoveLora,
                onLoraNameChange = imageToImageViewModel::onLoraNameChange,
                onLoraStrengthChange = imageToImageViewModel::onLoraStrengthChange
            )
        }
    }

    // Mask editor dialog
    if (showMaskEditor && uiState.sourceImage != null) {
        MaskEditorDialog(
            sourceImage = uiState.sourceImage!!,
            maskPaths = uiState.maskPaths,
            initialBrushSize = uiState.brushSize,
            isEraserMode = uiState.isEraserMode,
            onPathAdded = { path, isEraser, brushSize ->
                imageToImageViewModel.addMaskPath(path, isEraser, brushSize)
            },
            onClearMask = { imageToImageViewModel.clearMask() },
            onInvertMask = { imageToImageViewModel.invertMask() },
            onBrushSizeChange = { imageToImageViewModel.onBrushSizeChange(it) },
            onEraserModeChange = { imageToImageViewModel.onEraserModeChange(it) },
            onDismiss = { showMaskEditor = false }
        )
    }
}
