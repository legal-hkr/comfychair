package sh.hnet.comfychair.ui.components.generation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowEditorActivity
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.queue.JobDescription
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.ui.components.GenerationButton
import sh.hnet.comfychair.ui.components.PromptLibraryDialog
import sh.hnet.comfychair.ui.components.PromptPresetDialog
import sh.hnet.comfychair.ui.components.config.BottomSheetConfig
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.ui.components.config.ConfigBottomSheetContent
import sh.hnet.comfychair.ui.components.config.UnifiedCallbacks
import sh.hnet.comfychair.ui.components.config.customFieldEntries
import sh.hnet.comfychair.ui.components.config.optionsLayout
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors
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
 *       PromptDock(...)                // prompt, opening the full-screen editor
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
                is GenerationScreenEvent.ShowToast -> AppErrors.notify(context, event.messageResId)
                // Only problems come as text (e.g. the connection lost during a job)
                is GenerationScreenEvent.ShowToastMessage -> AppErrors.report(event.message)
            }
        }
    }

    LaunchedEffect(Unit) {
        presetViewModel.events.collect { event ->
            when (event) {
                is PromptPresetEvent.PresetApplied -> viewModel.onPositivePromptChange(event.prompt)
                is PromptPresetEvent.ShowToast -> AppErrors.notify(context, event.messageResId)
                is PromptPresetEvent.MaxFavoritesReached ->
                    AppErrors.report(context, R.string.prompt_preset_max_favorites)
                is PromptPresetEvent.ResetPrompt -> viewModel.resetPromptToDefault()
            }
        }
    }
}

/** Prompt settings a screen reads: spell check, which may change while the screen is in the background. */
class PromptInputState(val spellCheckEnabled: Boolean)

@Composable
fun rememberPromptInputState(): PromptInputState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var spellCheckEnabled by remember { mutableStateOf(AppSettings.isPromptSpellCheckEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) spellCheckEnabled = AppSettings.isPromptSpellCheckEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return PromptInputState(spellCheckEnabled)
}

/**
 * Top app bar of a generation screen: source switch and queue chip. The running job's progress is shown on the
 * preview ([ScreenJobStatus]).
 *
 * @param actions The screen's own actions, shown after the queue chip
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationTopBar(
    screen: MainRoute,
    ownerId: String,
    generationViewModel: GenerationViewModel,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }
    val queueChipState = when {
        isOfflineMode -> QueueChipState.Offline
        isConnecting -> QueueChipState.Connecting
        else -> QueueChipState.Counts(own = queueState.ownActiveJobCount, others = queueState.otherJobCount)
    }

    TopAppBar(
        // What the screen generates from; the tab (Image or Video) is selected in the navigation bar. The switch
        // itself is drawn over the screens; the bar keeps its place
        title = { SourceSwitchPlaceholder(screen) },
        windowInsets = WindowInsets(0, 0, 0, 0),
        actions = {
            // The bar leaves 4 dp at the end for icon buttons, which bring their own inset; the chip doesn't, so it gets
            // the rest of the 16 dp the dock below keeps from the edge
            QueueChip(
                queueChipState,
                modifier = Modifier.padding(end = 12.dp),
                onClick = if (isOfflineMode) null else ({ QueueSheetController.visible = true })
            )
            actions()
        }
    )

    if (QueueSheetController.visible) {
        QueueSheet(generationViewModel, onDismiss = { QueueSheetController.visible = false })
    }
}

/**
 * The status card of this screen's running job ([GenerationStatusCard]), shown only while it runs. Cancel interrupts
 * only this job, not one that another client may have started meanwhile.
 */
@Composable
fun BoxScope.ScreenJobStatus(ownerId: String, generationViewModel: GenerationViewModel) {
    val generationState by generationViewModel.generationState.collectAsState()
    val queueState by JobRegistry.queueState.collectAsState()
    if (queueState.executingOwnerId != ownerId) return
    val promptId = queueState.executingPromptId
    GenerationStatusCard(
        jobKey = promptId,
        step = generationState.progress,
        steps = generationState.maxProgress,
        moreQueued = queueState.ownWaitingJobCount,
        onCancel = { generationViewModel.cancelGeneration(promptId) { } }
    )
}

/**
 * The screen's preview area: takes the space left by the controls, inset 12 dp from the sides with 28 dp corners,
.
 *
 * The corners are painted over in the screen's background colour instead of clipping: overlays such as the result
 * action menu may grow past the area's top edge, and a video surface ignores clipping anyway.
 */
@Composable
fun ColumnScope.GenerationPreviewArea(content: @Composable () -> Unit) {
    val cornerColor = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .drawWithContent {
                drawContent()
                drawCornerFillets(PreviewCornerRadius.toPx(), cornerColor)
            }
    ) {
        content()
    }
}

private val PreviewCornerRadius = 28.dp

/** Fills the four corners outside a rounded rectangle of the given radius. */
private fun DrawScope.drawCornerFillets(radius: Float, color: Color) {
    val r = radius.coerceAtMost(minOf(size.width, size.height) / 2)
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0f, 0f); lineTo(r, 0f); arcTo(Rect(0f, 0f, 2 * r, 2 * r), 270f, -90f, false); close()
        moveTo(w, 0f); lineTo(w, r); arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 0f, -90f, false); close()
        moveTo(w, h); lineTo(w - r, h); arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 90f, -90f, false); close()
        moveTo(0f, h); lineTo(0f, h - r); arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false); close()
    }
    drawPath(path, color)
}

/** Empty preview: the app mark in a soft Material shape and a line saying where results will appear. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyResultState(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(112.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(112.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.msg_results_appear_here),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Submits a job of a screen: checks what must be there first, prepares the workflow (uploading images if needed) and
 * queues it, reporting failures. Shared by the Generate button and the prompt editor's Generate.
 *
 * @param prepareWorkflow Builds the workflow JSON to submit; null on failure
 * @param describeJob What the queue sheet shows for the job (prompt and workflow), read when submitting
 * @param generationBlocker Message shown instead of generating, e.g. a missing mask; null to generate
 * @return Submits a job, at the front of the queue or at its end
 */
@Composable
fun rememberJobSubmitter(
    generationViewModel: GenerationViewModel,
    ownerId: String,
    prepareWorkflow: suspend () -> String?,
    contentType: ContentType = ContentType.IMAGE,
    describeJob: () -> JobDescription? = { null },
    generationBlocker: () -> Int? = { null }
): (front: Boolean) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // The latest lambdas, as they read the screen's current state
    val prepare by rememberUpdatedState(prepareWorkflow)
    val describe by rememberUpdatedState(describeJob)
    val blocker by rememberUpdatedState(generationBlocker)
    return remember(generationViewModel, ownerId, contentType) {
        { front ->
            scope.launch {
                blocker()?.let { messageRes ->
                    AppErrors.notify(context, messageRes)
                    return@launch
                }
                val workflowJson = prepare()
                if (workflowJson == null) {
                    AppErrors.report(context, R.string.error_failed_load_workflow)
                    return@launch
                }
                val description = describe()
                generationViewModel.startGeneration(workflowJson, ownerId, contentType, front) { success, promptId, errorMessage ->
                    if (success && promptId != null && description != null) JobRegistry.describeJob(promptId, description)
                    if (!success) {
                        // The server's reasons (e.g. the inputs that failed validation) behind Show
                        AppErrors.report(
                            AppError(
                                message = resources.getString(R.string.error_submit_failed),
                                details = errorMessage?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * The dock's recipe chips, between the prompt and Generate: the workflow chip first, which opens the workflow menu
 * (on Image to Image grouped by mode), then the parameter chips ([ParameterChips]). Scrolls sideways when the chips
 * don't fit. Without the prompt field above, the row keeps the gap it provided.
 */
@Composable
fun RecipeChips(
    state: CommonGenerationState,
    viewModel: BaseGenerationViewModel<*>,
    icon: ImageVector,
    groups: List<WorkflowGroup>
) {
    val workflowName = state.selectedWorkflow
    val hasPositivePrompt = state.capabilities.hasPositivePrompt
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (hasPositivePrompt) 0.dp else 12.dp, bottom = 6.dp)
    ) {
        item(key = "workflow") {
            WorkflowChip(workflowName, icon, groups)
        }
        item(key = "parameters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ParameterChips(state, viewModel) }
        }
    }
}

/**
 * Generate button (with its queue menu) and the options button.
 *
 * @param isEnabled Whether the screen is ready to generate; computed by the screen from its observed
 *   state, so a change always reaches this composable
 * @param prepareWorkflow Builds the workflow JSON to submit (uploading images if needed); null on failure
 * @param generationBlocker Message shown instead of generating, e.g. a missing mask; null to generate
 * @param hasPositivePrompt Without the prompt field above, the controls keep the gap it provided
 * @param missingSource The screen needs a source image it doesn't have yet (the reason given for a disabled button)
 * @param sourceIsVideo The missing source is a video (Video to Video)
 * @param describeJob What the queue sheet shows for a submitted job (prompt and workflow), read when submitting
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GenerationControls(
    isEnabled: Boolean,
    generationViewModel: GenerationViewModel,
    ownerId: String,
    hasPositivePrompt: Boolean,
    isFetching: Boolean,
    optionsOpen: Boolean,
    onOpenOptions: () -> Unit,
    submit: (front: Boolean) -> Unit,
    isUploading: Boolean = false,
    missingSource: Boolean = false,
    sourceIsVideo: Boolean = false
) {
    val context = LocalContext.current
    val queueState by JobRegistry.queueState.collectAsState()
    val isConnecting by ConnectionManager.isConnecting.collectAsState()
    val isOfflineMode = remember { AppSettings.isOfflineMode(context) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // The recipe chips above provide the gap
            .padding(bottom = 16.dp)
    ) {
        GenerationButton(
            disabledReason = when {
                isOfflineMode -> stringResource(R.string.state_generate_offline)
                missingSource -> stringResource(if (sourceIsVideo) R.string.state_generate_no_source_video else R.string.state_generate_no_source)
                !isEnabled -> stringResource(R.string.state_generate_invalid)
                else -> null
            },
            canCancelOwnJob = queueState.executingPromptId?.let { it in queueState.ownJobs } == true,
            isEnabled = isEnabled,
            isOfflineMode = isOfflineMode,
            isUploading = isUploading,
            isFetching = isFetching,
            isConnecting = isConnecting,
            onGenerate = { submit(false) },
            // Read when tapped: the running job may have changed since the menu was composed
            onCancelOwnJob = {
                val running = JobRegistry.queueState.value.executingPromptId
                if (running != null && running in JobRegistry.queueState.value.ownJobs) {
                    generationViewModel.cancelGeneration(running) { }
                }
            },
            onAddToFrontOfQueue = { submit(true) },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Rounded square, turning round while the options sheet is open
        val optionsLabel = stringResource(R.string.button_all_options)
        val optionsState = stringResource(if (optionsOpen) R.string.state_open else R.string.state_closed)
        FilledTonalIconToggleButton(
            checked = optionsOpen,
            onCheckedChange = { onOpenOptions() },
            shapes = IconButtonDefaults.toggleableShapes(
                shape = RoundedCornerShape(16.dp),
                pressedShape = RoundedCornerShape(12.dp),
                checkedShape = CircleShape
            ),
            modifier = Modifier
                .size(56.dp)
                .semantics { stateDescription = optionsState }
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = optionsLabel)
        }
    }
}

/**
 * Callbacks of the options sheet: every generation parameter handler of the ViewModel.
 */
fun generationCallbacks(viewModel: BaseGenerationViewModel<*>) = UnifiedCallbacks(
    onWorkflowChange = viewModel::onWorkflowChange,
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
    onAudioVaeChange = viewModel::onAudioVaeChange,
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
 * Options sheet of a generation screen, laid out by [optionsLayout]: small workflows as a flat list opening at content
 * height (the preview stays visible), large ones with sections opening at full height.
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
    extraCallbacks: (UnifiedCallbacks) -> UnifiedCallbacks = { it },
    buildConfig: (UnifiedCallbacks) -> BottomSheetConfig
) {
    // Stays composed while it slides away, also when closed by the Options button in its footer
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) shown = true }
    if (!shown) return
    val context = LocalContext.current
    // Read when tapped, so the editor opens the workflow selected at that moment
    val selectedWorkflowId by rememberUpdatedState(state.selectedWorkflowId)
    val openNodeValues = {
        selectedWorkflowId.takeIf { it.isNotEmpty() }?.let { workflowId ->
            context.startActivity(WorkflowEditorActivity.createIntent(context, workflowId))
        }
        Unit
    }
    val callbacks = remember(viewModel) { extraCallbacks(generationCallbacks(viewModel)) }
    val config = remember(state, callbacks) { buildConfig(callbacks) }
    // The workflow's custom fields with their inputs' definitions (spec §18.6)
    val customFields = remember(state.selectedWorkflowId, state.capabilities) { customFieldEntries(state.selectedWorkflowId) }
    val fieldLabels = remember(state.selectedWorkflowId, state.capabilities) {
        WorkflowManager.getWorkflowById(state.selectedWorkflowId)?.fieldLabels.orEmpty()
    }
    val layout = remember(state.capabilities, customFields.size) { optionsLayout(state.capabilities, customFields.size) }
    val sheetState = rememberBottomSheetState(
        SheetValue.Hidden,
        enabledValues = if (layout.sectioned) setOf(SheetValue.Hidden, SheetValue.Expanded) else SheetValue.entries.toSet()
    )
    LaunchedEffect(visible) {
        if (!visible) {
            sheetState.hide()
            shown = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        ConfigBottomSheetContent(
            config = config,
            layout = layout,
            workflowId = state.selectedWorkflowId,
            modifier = Modifier.navigationBarsPadding(),
            onOpenNodeValues = openNodeValues,
            customFields = customFields,
            customValues = state.customValues,
            customErrors = state.customErrors,
            onCustomFieldChange = viewModel::onCustomFieldChange,
            fieldLabels = fieldLabels
        )
    }
}

/**
 * Prompt library dialog and the save/edit preset dialog.
 */
@Composable
fun PromptPresetDialogs(
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState,
    // The screen's prompt, offered for saving when the library is empty
    currentPrompt: String = ""
) {
    if (presetUiState.showLibrarySideSheet) {
        PromptLibraryDialog(
            presets = presetViewModel.getFilteredPresets(),
            availableTags = presetUiState.availableTags,
            searchQuery = presetUiState.searchQuery,
            selectedTags = presetUiState.selectedTags,
            filterFavoritesOnly = presetUiState.filterFavoritesOnly,
            onSearchQueryChange = { presetViewModel.onSearchQueryChange(it) },
            onTagToggle = { presetViewModel.onTagToggle(it) },
            onToggleFavoritesFilter = { presetViewModel.onToggleFavoritesFilter() },
            onPresetSelected = { presetViewModel.onPresetSelected(it) },
            onToggleFavorite = { presetViewModel.onToggleFavorite(it) },
            onEditPreset = { presetViewModel.showEditDialog(it) },
            onDuplicatePreset = { presetViewModel.onDuplicatePreset(it) },
            onDeletePreset = { presetViewModel.onDeletePreset(it) },
            onDismiss = { presetViewModel.dismissLibrary() },
            onSaveCurrent = currentPrompt.takeIf { it.isNotBlank() }?.let { prompt -> { presetViewModel.showSaveDialog(prompt) } }
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
