package sh.hnet.comfychair.ui.components.generation

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.AppMenuDropdown
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.GenerationProgressBar
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.config.BottomSheetConfig
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.viewmodel.ContentType
import sh.hnet.comfychair.viewmodel.GenerationScreenEvent
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.PromptPresetEvent
import sh.hnet.comfychair.viewmodel.PromptPresetUiState
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel
import sh.hnet.comfychair.viewmodel.base.BaseGenerationViewModel

/*
 * Parts every generation screen (Text to Image, Image to Image, Text to Video, Image to Video) shares.
 * A screen composes them around its own preview area:
 *
 *   GenerationScreenEffects(...)
 *   Column {
 *       GenerationTopBar(...)          // title, menu, progress
 *       GenerationPreviewArea(...)     // the screen's own preview
 *       PositivePromptField(...)
 *       GenerationControls(...)        // Generate + options button
 *   }
 *   GenerationOptionsSheet(...)
 *   PromptPresetDialogs(...)
 */

/**
 * Lifecycle work of a generation screen: initialize its ViewModel and the prompt library, check
 * whether a job finished while away, receive generation events while shown, clear the previous
 * result when a new job of this screen starts, and show the ViewModels' messages.
 *
 * @param onJobStarted The screen's own reaction to a new job, e.g. switching to its preview
 */
@Composable
fun GenerationScreenEffects(
    viewModel: BaseGenerationViewModel<*>,
    generationViewModel: GenerationViewModel,
    presetViewModel: PromptPresetViewModel,
    screenType: ScreenType,
    ownerId: String,
    onJobStarted: () -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val queueState by JobRegistry.queueState.collectAsState()

    LaunchedEffect(Unit) {
        generationViewModel.getClient()?.let { client -> viewModel.initialize(context, client) }
        presetViewModel.initialize(context, screenType)

        // A job of this screen may have finished while the screen was away
        val generation = generationViewModel.generationState.value
        if (generation.isGenerating && generation.ownerId == ownerId) {
            generationViewModel.checkServerForCompletion { _, _ -> }
        }
    }

    // Refresh presets when the screen resumes (catches changes made in the Media Viewer)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) presetViewModel.refreshPresets()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Receive generation events while the screen is shown
    DisposableEffect(Unit) {
        viewModel.startListening(generationViewModel)
        onDispose { viewModel.stopListening(generationViewModel) }
    }

    // A NEW job of this screen started. Both keys handle execution_start arriving before the job is
    // registered (its owner becomes known later)
    LaunchedEffect(queueState.executingPromptId, queueState.executingOwnerId) {
        val promptId = queueState.executingPromptId
        if (queueState.executingOwnerId == ownerId && promptId != null) {
            viewModel.clearPreviewForExecution(promptId)
            onJobStarted()
            viewModel.startListening(generationViewModel)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GenerationScreenEvent.ShowToast ->
                    Toast.makeText(context, resources.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                is GenerationScreenEvent.ShowToastMessage ->
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        presetViewModel.events.collect { event ->
            when (event) {
                is PromptPresetEvent.PresetApplied -> viewModel.onPositivePromptChange(event.prompt)
                is PromptPresetEvent.ShowToast ->
                    Toast.makeText(context, resources.getString(event.messageResId), Toast.LENGTH_SHORT).show()
                is PromptPresetEvent.MaxFavoritesReached ->
                    Toast.makeText(context, resources.getString(R.string.prompt_preset_max_favorites), Toast.LENGTH_SHORT).show()
                is PromptPresetEvent.ResetPrompt -> viewModel.resetPromptToDefault()
            }
        }
    }
}

/**
 * Prompt field settings and state: spell check, and whether the field expands to fill the screen
 * above the keyboard while typing (the preview area collapses meanwhile).
 */
class PromptInputState(
    val spellCheckEnabled: Boolean,
    val transformation: VisualTransformation,
    val expanded: Boolean,
    val onFocusChange: (Boolean) -> Unit
)

@Composable
fun rememberPromptInputState(prompt: String): PromptInputState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var spellCheckEnabled by remember { mutableStateOf(AppSettings.isPromptSpellCheckEnabled(context)) }
    var expandEnabled by remember { mutableStateOf(AppSettings.isPromptExpandEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        // The settings may change while the screen is in the background
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                spellCheckEnabled = AppSettings.isPromptSpellCheckEnabled(context)
                expandEnabled = AppSettings.isPromptExpandEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val transformation = rememberSpellCheckVisualTransformation(text = prompt, enabled = spellCheckEnabled)

    // Expand only while the keyboard is up and not closing
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    var prevImeHeight by remember { mutableStateOf(imeHeight) }
    SideEffect { prevImeHeight = imeHeight }
    var focused by remember { mutableStateOf(false) }
    val expanded = expandEnabled && focused && imeHeight > 0 && imeHeight >= prevImeHeight

    return PromptInputState(spellCheckEnabled, transformation, expanded) { focused = it }
}

/**
 * Top app bar of a generation screen with the progress bar below it, shown while this screen's job runs.
 *
 * @param actions The screen's own actions (e.g. picking a source image), shown before the app menu
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationTopBar(
    titleRes: Int,
    ownerId: String,
    generationViewModel: GenerationViewModel,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val generationState by generationViewModel.generationState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()

    TopAppBar(
        title = { Text(stringResource(titleRes)) },
        windowInsets = WindowInsets(0, 0, 0, 0),
        actions = {
            actions()
            AppMenuDropdown(onSettings = onNavigateToSettings, onLogout = onLogout)
        }
    )

    // Shown from the start of the job (empty until the step count is known), so it's clear the job runs
    if (queueState.executingOwnerId == ownerId) {
        GenerationProgressBar(
            progress = generationState.progress,
            maxProgress = generationState.maxProgress,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * The screen's preview area: takes the space left by the controls and collapses while the prompt
 * field is expanded above the keyboard.
 */
@Composable
fun ColumnScope.GenerationPreviewArea(
    collapsed: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = !collapsed,
        modifier = Modifier.weight(1f),
        enter = fadeIn(tween(150)),
        exit = ExitTransition.None
    ) {
        content()
    }
}

/**
 * Positive prompt field with the prompt library menu and a clear button. Hidden by the caller for
 * workflows without a positive prompt (e.g. upscalers).
 */
@Composable
fun ColumnScope.PositivePromptField(
    prompt: String,
    onPromptChange: (String) -> Unit,
    input: PromptInputState,
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState
) {
    OutlinedTextField(
        value = prompt,
        onValueChange = {
            onPromptChange(it)
            presetViewModel.clearActivePreset()
        },
        label = { Text(stringResource(R.string.hint_prompt)) },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (input.expanded) Modifier.weight(1f) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .onFocusChanged { input.onFocusChange(it.isFocused) },
        minLines = 2,
        maxLines = if (input.expanded) Int.MAX_VALUE else 4,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = input.spellCheckEnabled),
        visualTransformation = input.transformation,
        leadingIcon = {
            PromptPresetDropdown(
                favorites = presetUiState.favorites,
                activePresetId = presetUiState.activePresetId,
                currentPromptIsEmpty = prompt.isEmpty(),
                onPresetSelected = { presetViewModel.onPresetSelected(it) },
                onOpenLibrary = { presetViewModel.showLibrary() },
                onSaveCurrentPrompt = { presetViewModel.showSaveDialog(prompt) },
                onResetPrompt = { presetViewModel.resetPrompt() }
            )
        },
        trailingIcon = {
            if (prompt.isNotEmpty()) {
                IconButton(onClick = {
                    onPromptChange("")
                    presetViewModel.clearActivePreset()
                }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.content_description_clear))
                }
            }
        }
    )
}

/**
 * Generate button (with its queue menu) and the options button.
 *
 * @param isEnabled Whether the screen is ready to generate; computed by the screen from its observed
 *   state, so a change always reaches this composable
 * @param prepareWorkflow Builds the workflow JSON to submit (uploading images if needed); null on failure
 * @param generationBlocker Message shown instead of generating, e.g. a missing mask; null to generate
 * @param hasPositivePrompt Without the prompt field above, the controls keep the gap it provided
 */
@Composable
fun GenerationControls(
    isEnabled: Boolean,
    generationViewModel: GenerationViewModel,
    ownerId: String,
    hasPositivePrompt: Boolean,
    isFetching: Boolean,
    optionsOpen: Boolean,
    onOpenOptions: () -> Unit,
    prepareWorkflow: suspend () -> String?,
    contentType: ContentType = ContentType.IMAGE,
    isUploading: Boolean = false,
    generationBlocker: () -> Int? = { null }
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    fun submit(front: Boolean) {
        scope.launch {
            generationBlocker()?.let { messageRes ->
                Toast.makeText(context, resources.getString(messageRes), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val workflowJson = prepareWorkflow()
            if (workflowJson == null) {
                Toast.makeText(context, resources.getString(R.string.error_failed_load_workflow), Toast.LENGTH_SHORT).show()
                return@launch
            }
            generationViewModel.startGeneration(workflowJson, ownerId, contentType, front) { success, _, errorMessage ->
                if (!success) {
                    Toast.makeText(
                        context,
                        errorMessage ?: resources.getString(R.string.error_generation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = if (hasPositivePrompt) 0.dp else 16.dp, bottom = 16.dp)
    ) {
        GenerationButton(
            queueSize = queueState.totalQueueSize,
            isExecuting = queueState.isExecuting,
            isEnabled = isEnabled,
            isOfflineMode = isOfflineMode,
            isUploading = isUploading,
            isFetching = isFetching,
            isConnecting = isConnecting,
            onGenerate = { submit(front = false) },
            onCancelCurrent = { generationViewModel.cancelGeneration { } },
            onAddToFrontOfQueue = { submit(front = true) },
            onClearQueue = {
                generationViewModel.getClient()?.clearQueue { success ->
                    val messageRes = if (success) R.string.msg_queue_cleared_success else R.string.error_queue_clear
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, resources.getString(messageRes), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Gear icon turns while the options sheet is shown
        val optionsIconRotation by animateFloatAsState(
            targetValue = if (optionsOpen) 90f else 0f,
            label = "options icon rotation"
        )
        OutlinedIconButton(onClick = onOpenOptions, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.button_options),
                modifier = Modifier.rotate(optionsIconRotation)
            )
        }
    }
}

/**
 * Callbacks of the options sheet: every generation parameter handler of the ViewModel, plus opening
 * the selected workflow in the editor.
 */
fun generationCallbacks(
    viewModel: BaseGenerationViewModel<*>,
    onViewWorkflow: () -> Unit
) = UnifiedCallbacks(
    onWorkflowChange = viewModel::onWorkflowChange,
    onViewWorkflow = onViewWorkflow,
    onNegativePromptChange = viewModel::onNegativePromptChange,
    // Models
    onCheckpointChange = viewModel::onCheckpointChange,
    onUnetChange = viewModel::onUnetChange,
    onVaeChange = viewModel::onVaeChange,
    onClipChange = viewModel::onClipChange,
    onClip1Change = viewModel::onClip1Change,
    onClip2Change = viewModel::onClip2Change,
    onClip3Change = viewModel::onClip3Change,
    onClip4Change = viewModel::onClip4Change,
    onTextEncoderChange = viewModel::onTextEncoderChange,
    onLatentUpscaleModelChange = viewModel::onLatentUpscaleModelChange,
    onMandatoryLoraChange = viewModel::onMandatoryLoraChange,
    onHighnoiseUnetChange = viewModel::onHighnoiseUnetChange,
    onLownoiseUnetChange = viewModel::onLownoiseUnetChange,
    onHighnoiseLoraChange = viewModel::onHighnoiseLoraChange,
    onLownoiseLoraChange = viewModel::onLownoiseLoraChange,
    // Parameters
    onWidthChange = viewModel::onWidthChange,
    onHeightChange = viewModel::onHeightChange,
    onMegapixelsChange = viewModel::onMegapixelsChange,
    onLengthChange = viewModel::onLengthChange,
    onFpsChange = viewModel::onFpsChange,
    onStepsChange = viewModel::onStepsChange,
    onCfgChange = viewModel::onCfgChange,
    onSamplerChange = viewModel::onSamplerChange,
    onSchedulerChange = viewModel::onSchedulerChange,
    onRandomSeedToggle = viewModel::onRandomSeedToggle,
    onSeedChange = viewModel::onSeedChange,
    onRandomizeSeed = viewModel::onRandomizeSeed,
    onDenoiseChange = viewModel::onDenoiseChange,
    onBatchSizeChange = viewModel::onBatchSizeChange,
    onUpscaleMethodChange = viewModel::onUpscaleMethodChange,
    onScaleByChange = viewModel::onScaleByChange,
    onStopAtClipLayerChange = viewModel::onStopAtClipLayerChange,
    // LoRA chains
    onAddLora = { viewModel.onAddLora() },
    onRemoveLora = { viewModel.onRemoveLora(it) },
    onLoraNameChange = { index, name -> viewModel.onLoraNameChange(index, name) },
    onLoraStrengthChange = { index, strength -> viewModel.onLoraStrengthChange(index, strength) },
    onAddHighnoiseLora = viewModel::onAddHighnoiseLora,
    onRemoveHighnoiseLora = viewModel::onRemoveHighnoiseLora,
    onHighnoiseLoraNameChange = viewModel::onHighnoiseLoraChainNameChange,
    onHighnoiseLoraStrengthChange = viewModel::onHighnoiseLoraChainStrengthChange,
    onAddLownoiseLora = viewModel::onAddLownoiseLora,
    onRemoveLownoiseLora = viewModel::onRemoveLownoiseLora,
    onLownoiseLoraNameChange = viewModel::onLownoiseLoraChainNameChange,
    onLownoiseLoraStrengthChange = viewModel::onLownoiseLoraChainStrengthChange
)

/**
 * Options bottom sheet of a generation screen.
 *
 * @param extraCallbacks Adds the screen's own callbacks (Image to Image: mode, reference images)
 * @param buildConfig The sheet configuration for the screen's state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationOptionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: BaseGenerationViewModel<*>,
    state: CommonGenerationState,
    spellCheckEnabled: Boolean,
    extraCallbacks: (UnifiedCallbacks) -> UnifiedCallbacks = { it },
    buildConfig: (UnifiedCallbacks) -> BottomSheetConfig
) {
    if (!visible) return
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Read when tapped, so the editor opens the workflow selected at that moment
    val selectedWorkflowId by rememberUpdatedState(state.selectedWorkflowId)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        val callbacks = remember(viewModel) {
            extraCallbacks(
                generationCallbacks(viewModel) {
                    selectedWorkflowId.takeIf { it.isNotEmpty() }?.let { workflowId ->
                        context.startActivity(WorkflowEditorActivity.createIntent(context, workflowId))
                    }
                }
            )
        }
        val config = remember(state, callbacks) { buildConfig(callbacks) }
        ConfigBottomSheetContent(
            config = config,
            workflowName = state.selectedWorkflow,
            spellCheckEnabled = spellCheckEnabled
        )
    }
}

/**
 * Prompt library dialog and the save/edit preset dialog.
 */
@Composable
fun PromptPresetDialogs(
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState
) {
    if (presetUiState.showLibrarySideSheet) {
        PromptLibraryDialog(
            presets = presetViewModel.getFilteredPresets(),
            availableTags = presetUiState.availableTags,
            searchQuery = presetUiState.searchQuery,
            selectedTags = presetUiState.selectedTags,
            filterFavoritesOnly = presetUiState.filterFavoritesOnly,
            activePresetId = presetUiState.activePresetId,
            onSearchQueryChange = { presetViewModel.onSearchQueryChange(it) },
            onTagToggle = { presetViewModel.onTagToggle(it) },
            onToggleFavoritesFilter = { presetViewModel.onToggleFavoritesFilter() },
            onPresetSelected = { presetViewModel.onPresetSelected(it) },
            onToggleFavorite = { presetViewModel.onToggleFavorite(it) },
            onEditPreset = { presetViewModel.showEditDialog(it) },
            onDuplicatePreset = { presetViewModel.onDuplicatePreset(it) },
            onDeletePreset = { presetViewModel.onDeletePreset(it) },
            onDismiss = { presetViewModel.dismissLibrary() }
        )
    }

    if (presetUiState.showSaveDialog) {
        PromptPresetDialog(
            editingPreset = presetUiState.editingPreset,
            currentPrompt = presetUiState.currentPromptForSave,
            existingTags = presetUiState.availableTags,
            isNameTaken = { name, excludeId -> presetViewModel.isNameTaken(name, excludeId) },
            onDismiss = { presetViewModel.dismissSaveDialog() },
            onSave = { name, prompt, tags -> presetViewModel.onSavePreset(name, prompt, tags) }
        )
    }
}
