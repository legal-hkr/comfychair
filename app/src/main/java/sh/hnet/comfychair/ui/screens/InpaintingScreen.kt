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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.InpaintingConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.MaskEditorDialog
import sh.hnet.comfychair.ui.components.MaskPreview
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.InpaintingEvent
import sh.hnet.comfychair.viewmodel.InpaintingViewMode
import sh.hnet.comfychair.viewmodel.InpaintingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InpaintingScreen(
    generationViewModel: GenerationViewModel,
    inpaintingViewModel: InpaintingViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by inpaintingViewModel.uiState.collectAsState()

    // Check if THIS screen owns the current generation
    val isThisScreenGenerating = generationState.isGenerating &&
        generationState.ownerId == InpaintingViewModel.OWNER_ID

    var showMenu by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showMaskEditor by remember { mutableStateOf(false) }

    val optionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            inpaintingViewModel.onSourceImageChange(context, it)
            inpaintingViewModel.onViewModeChange(InpaintingViewMode.SOURCE)
        }
    }

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client ->
            inpaintingViewModel.initialize(context, client)
        }
    }

    // Fetch models when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            inpaintingViewModel.fetchModels()
        }
    }

    // Handle inpainting events
    LaunchedEffect(Unit) {
        inpaintingViewModel.events.collect { event ->
            when (event) {
                is InpaintingEvent.ShowToast -> {
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
                }
                is InpaintingEvent.ShowToastMessage -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        inpaintingViewModel.startListening(generationViewModel)
        onDispose {
            inpaintingViewModel.stopListening(generationViewModel)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with image options
        TopAppBar(
            title = { Text(stringResource(R.string.inpainting_title)) },
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
                    IconButton(onClick = { inpaintingViewModel.clearMask() }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_mask))
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
                            text = { Text(stringResource(R.string.action_settings)) },
                            onClick = {
                                showMenu = false
                                onNavigateToSettings()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_logout)) },
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

        // Image Preview Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 150.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(
                    onClick = {
                        if (uiState.viewMode == InpaintingViewMode.PREVIEW && uiState.previewImage != null) {
                            // Launch MediaViewer for single image
                            val bitmap = uiState.previewImage!!
                            val intent = MediaViewerActivity.createSingleImageIntent(context, bitmap)
                            context.startActivity(intent)
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (uiState.viewMode) {
                InpaintingViewMode.SOURCE -> {
                    if (uiState.sourceImage != null) {
                        // Read-only preview of source image with mask overlay
                        MaskPreview(
                            sourceImage = uiState.sourceImage,
                            maskPaths = uiState.maskPaths,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Placeholder - monochrome app logo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { imagePickerLauncher.launch("image/*") }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_foreground_monochrome),
                                contentDescription = null,
                                modifier = Modifier.size(256.dp),
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
                InpaintingViewMode.PREVIEW -> {
                    if (uiState.previewImage != null) {
                        Image(
                            bitmap = uiState.previewImage!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_description_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Placeholder - monochrome app logo
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_foreground_monochrome),
                            contentDescription = null,
                            modifier = Modifier.size(256.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Progress indicator - only show if THIS screen started generation
                    if (isThisScreenGenerating) {
                        LinearProgressIndicator(
                            progress = {
                                if (generationState.maxProgress > 0) {
                                    generationState.progress.toFloat() / generationState.maxProgress
                                } else 0f
                            },
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
                selected = uiState.viewMode == InpaintingViewMode.SOURCE,
                onClick = { inpaintingViewModel.onViewModeChange(InpaintingViewMode.SOURCE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.source_image_tab))
            }
            SegmentedButton(
                selected = uiState.viewMode == InpaintingViewMode.PREVIEW,
                onClick = { inpaintingViewModel.onViewModeChange(InpaintingViewMode.PREVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.preview_tab))
            }
        }

        // Prompt Input
        OutlinedTextField(
            value = uiState.prompt,
            onValueChange = { inpaintingViewModel.onPromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.prompt.isNotEmpty()) {
                    IconButton(onClick = { inpaintingViewModel.onPromptChange("") }) {
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
                            if (!inpaintingViewModel.hasMask()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.paint_mask_hint),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }

                            val workflowJson = inpaintingViewModel.prepareWorkflow()
                            if (workflowJson != null) {
                                // Clear preview and switch to preview view
                                inpaintingViewModel.clearPreview()
                                inpaintingViewModel.onViewModeChange(InpaintingViewMode.PREVIEW)

                                generationViewModel.startGeneration(
                                    workflowJson,
                                    InpaintingViewModel.OWNER_ID
                                ) { _, _, _ ->
                                    // Generation started
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = isThisScreenGenerating || (
                    !generationState.isGenerating &&
                    inpaintingViewModel.hasValidConfiguration() &&
                    uiState.prompt.isNotBlank() &&
                    uiState.sourceImage != null
                ),
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
            sheetState = optionsSheetState
        ) {
            InpaintingConfigBottomSheetContent(
                uiState = uiState,
                onConfigModeChange = inpaintingViewModel::onConfigModeChange,
                onCheckpointWorkflowChange = inpaintingViewModel::onCheckpointWorkflowChange,
                onCheckpointChange = inpaintingViewModel::onCheckpointChange,
                onMegapixelsChange = inpaintingViewModel::onMegapixelsChange,
                onCheckpointStepsChange = inpaintingViewModel::onCheckpointStepsChange,
                onUnetWorkflowChange = inpaintingViewModel::onUnetWorkflowChange,
                onUnetChange = inpaintingViewModel::onUnetChange,
                onVaeChange = inpaintingViewModel::onVaeChange,
                onClipChange = inpaintingViewModel::onClipChange,
                onUnetStepsChange = inpaintingViewModel::onUnetStepsChange
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
                inpaintingViewModel.addMaskPath(path, isEraser, brushSize)
            },
            onClearMask = { inpaintingViewModel.clearMask() },
            onInvertMask = { inpaintingViewModel.invertMask() },
            onBrushSizeChange = { inpaintingViewModel.onBrushSizeChange(it) },
            onEraserModeChange = { inpaintingViewModel.onEraserModeChange(it) },
            onDismiss = { showMaskEditor = false }
        )
    }
}
