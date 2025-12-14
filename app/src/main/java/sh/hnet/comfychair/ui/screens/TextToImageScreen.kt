package sh.hnet.comfychair.ui.screens

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
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.ConfigBottomSheetContent
import sh.hnet.comfychair.viewmodel.ConnectionStatus
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel

/**
 * Text-to-Image generation screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToImageScreen(
    generationViewModel: GenerationViewModel,
    textToImageViewModel: TextToImageViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    // Initialize ViewModels
    LaunchedEffect(Unit) {
        val client = generationViewModel.getClient()
        if (client != null) {
            textToImageViewModel.initialize(context, client)
        }
    }

    // Collect state
    val generationState by generationViewModel.generationState.collectAsState()
    val connectionStatus by generationViewModel.connectionStatus.collectAsState()
    val uiState by textToImageViewModel.uiState.collectAsState()

    // Check if THIS screen owns the current generation
    val isThisScreenGenerating = generationState.isGenerating &&
        generationState.ownerId == TextToImageViewModel.OWNER_ID

    // Fetch models when connected
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            textToImageViewModel.fetchModels()
        }
    }

    // Register event handler when screen is active
    DisposableEffect(Unit) {
        textToImageViewModel.startListening(generationViewModel)
        onDispose {
            textToImageViewModel.stopListening(generationViewModel)
        }
    }

    // UI State
    var showOptionsBottomSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar with save/share actions
        TopAppBar(
            title = { Text(stringResource(R.string.nav_text_to_image)) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                // Save to gallery button (only when image exists)
                if (uiState.currentBitmap != null) {
                    IconButton(onClick = {
                        textToImageViewModel.saveToGallery { success ->
                            val messageRes = if (success) R.string.image_saved_to_gallery else R.string.failed_save_image
                            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save_to_gallery))
                    }
                    // Share button
                    IconButton(onClick = {
                        textToImageViewModel.getShareIntent()?.let { intent ->
                            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share_image)))
                        }
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
                .clickable(enabled = uiState.currentBitmap != null) {
                    // Launch MediaViewer for single image
                    uiState.currentBitmap?.let { bitmap ->
                        val intent = MediaViewerActivity.createSingleImageIntent(context, bitmap)
                        context.startActivity(intent)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (uiState.currentBitmap != null) {
                Image(
                    bitmap = uiState.currentBitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.content_description_generated_image),
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

        // Prompt Input
        OutlinedTextField(
            value = uiState.prompt,
            onValueChange = { textToImageViewModel.onPromptChange(it) },
            label = { Text(stringResource(R.string.prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minLines = 2,
            maxLines = 4,
            trailingIcon = {
                if (uiState.prompt.isNotEmpty()) {
                    IconButton(onClick = { textToImageViewModel.onPromptChange("") }) {
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
                        if (textToImageViewModel.validateConfiguration()) {
                            val workflowJson = textToImageViewModel.prepareWorkflowJson()
                            if (workflowJson != null) {
                                generationViewModel.startGeneration(
                                    workflowJson,
                                    TextToImageViewModel.OWNER_ID
                                ) { success, _, _ ->
                                    // Generation started or failed
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = isThisScreenGenerating ||
                    (!generationState.isGenerating && uiState.prompt.isNotBlank()),
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
                onClick = { showOptionsBottomSheet = true },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.button_options))
            }
        }
    }

    // Options Bottom Sheet
    if (showOptionsBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showOptionsBottomSheet = false },
            sheetState = sheetState
        ) {
            ConfigBottomSheetContent(
                uiState = uiState,
                onModeChange = { textToImageViewModel.onModeChange(it) },
                onCheckpointWorkflowChange = { textToImageViewModel.onCheckpointWorkflowChange(it) },
                onCheckpointChange = { textToImageViewModel.onCheckpointChange(it) },
                onCheckpointWidthChange = { textToImageViewModel.onCheckpointWidthChange(it) },
                onCheckpointHeightChange = { textToImageViewModel.onCheckpointHeightChange(it) },
                onCheckpointStepsChange = { textToImageViewModel.onCheckpointStepsChange(it) },
                onUnetWorkflowChange = { textToImageViewModel.onUnetWorkflowChange(it) },
                onUnetChange = { textToImageViewModel.onUnetChange(it) },
                onVaeChange = { textToImageViewModel.onVaeChange(it) },
                onClipChange = { textToImageViewModel.onClipChange(it) },
                onUnetWidthChange = { textToImageViewModel.onUnetWidthChange(it) },
                onUnetHeightChange = { textToImageViewModel.onUnetHeightChange(it) },
                onUnetStepsChange = { textToImageViewModel.onUnetStepsChange(it) }
            )
        }
    }
}
