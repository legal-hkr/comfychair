package sh.hnet.comfychair.viewmodel.base

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.util.LoraChainManager
import sh.hnet.comfychair.util.ValidationUtils

/**
 * Generation ViewModel that owns everything about [GenerationParams]: the change handlers for every
 * field, model lists from the server, loading and saving per-workflow values, and the common part of
 * validation. A screen only maps its UiState to the params being edited ([params]/[withParams]) and
 * persists its own preferences ([saveConfiguration]).
 *
 * Screens move here one at a time; once all have, this merges into [BaseGenerationViewModel].
 */
abstract class ParamsGenerationViewModel<UiState, Event> : BaseGenerationViewModel<UiState, Event>() {

    init {
        // Dispatched (not immediate): the subclass's initialState doesn't exist until its constructor has run
        viewModelScope.launch(Dispatchers.Main) {
            ConnectionManager.modelCache.collect { cache ->
                _uiState.update { state -> state.mapAllParams { it.withModelCache(cache) } }
            }
        }
    }

    // ===========================================
    // Generation parameters
    // ===========================================

    /** The params the screen currently edits (Image to Image: those of the current mode). */
    protected abstract fun UiState.params(): GenerationParams

    /** The state with the currently edited params replaced. */
    protected abstract fun UiState.withParams(params: GenerationParams): UiState

    /** Apply a change that concerns every params set of the screen (model lists); one set by default. */
    protected open fun UiState.mapAllParams(transform: (GenerationParams) -> GenerationParams): UiState =
        withParams(transform(params()))

    /** Values used when neither saved values nor the workflow template provide one. */
    protected open val fallbackParams: GenerationParams get() = GenerationParams()

    /** Persist the screen's preferences and the current workflow's values. */
    protected abstract fun saveConfiguration()

    protected val currentParams: GenerationParams get() = _uiState.value.params()

    /**
     * Change the edited params and save. With [save] false nothing is persisted, e.g. while a field
     * holds an invalid value, so the last valid one stays stored.
     */
    protected fun updateParams(save: Boolean = true, transform: (GenerationParams) -> GenerationParams) {
        _uiState.update { it.withParams(transform(it.params())) }
        if (save) saveConfiguration()
    }

    /**
     * The checks every screen shares: model lists loaded (saved selections can't be applied before),
     * a workflow selected (none when all are switched off), a prompt if the workflow takes one, and
     * no field with a validation error.
     */
    protected fun isReadyToGenerate(params: GenerationParams): Boolean =
        ConnectionManager.modelCache.value.isLoaded &&
            params.selectedWorkflowId.isNotEmpty() &&
            !(params.capabilities.hasPositivePrompt && params.positivePrompt.isBlank()) &&
            !params.hasErrors

    // Workflow values

    /** Load a workflow's saved values (or template defaults) into the edited params, without saving. */
    protected fun loadWorkflowValues(workflowId: String, workflowName: String) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val saved = storage.loadValues(serverId, workflowId)
        val defaults = WorkflowManager.getWorkflowDefaultsById(workflowId)
        val placeholders = WorkflowManager.getWorkflowPlaceholders(workflowId)
        val fallback = fallbackParams
        updateParams(save = false) { params ->
            params.copy(models = ConnectionManager.modelCache.value).withWorkflow(
                workflowId = workflowId,
                workflowName = workflowName,
                placeholders = placeholders,
                saved = saved,
                defaults = defaults,
                options = { fieldKey -> WorkflowManager.getNodeSpecificOptionsForField(workflowId, fieldKey) },
                fallback = fallback
            )
        }
    }

    /** Store the values of the workflow the params belong to, keeping node edits from the Workflow Editor. */
    protected fun saveWorkflowValues(params: GenerationParams = currentParams) {
        val storage = workflowValuesStorage ?: return
        val serverId = ConnectionManager.currentServerId ?: return
        val workflowId = params.selectedWorkflowId.takeIf { it.isNotEmpty() } ?: return
        storage.saveValues(serverId, workflowId, params.toWorkflowValues(storage.loadValues(serverId, workflowId)))
    }

    /** Switch to another workflow: keep the current one's values, then load the new one's. */
    protected fun switchWorkflow(workflowId: String, workflowName: String) {
        saveWorkflowValues()
        loadWorkflowValues(workflowId, workflowName)
        saveConfiguration()
    }

    // Prompts

    open fun onPositivePromptChange(positivePrompt: String) = updateParams { it.copy(positivePrompt = positivePrompt) }

    fun onNegativePromptChange(negativePrompt: String) = updateParams { it.copy(negativePrompt = negativePrompt) }

    // Models

    fun onModelChange(slot: ModelSlot, value: String) = updateParams { it.withSelection(slot, value) }

    fun onCheckpointChange(checkpoint: String) = onModelChange(ModelSlot.CHECKPOINT, checkpoint)
    fun onUnetChange(unet: String) = onModelChange(ModelSlot.UNET, unet)
    fun onVaeChange(vae: String) = onModelChange(ModelSlot.VAE, vae)
    fun onClipChange(clip: String) = onModelChange(ModelSlot.CLIP, clip)
    fun onClip1Change(clip: String) = onModelChange(ModelSlot.CLIP1, clip)
    fun onClip2Change(clip: String) = onModelChange(ModelSlot.CLIP2, clip)
    fun onClip3Change(clip: String) = onModelChange(ModelSlot.CLIP3, clip)
    fun onClip4Change(clip: String) = onModelChange(ModelSlot.CLIP4, clip)
    fun onTextEncoderChange(textEncoder: String) = onModelChange(ModelSlot.TEXT_ENCODER, textEncoder)
    fun onLatentUpscaleModelChange(model: String) = onModelChange(ModelSlot.LATENT_UPSCALE_MODEL, model)
    fun onMandatoryLoraChange(loraName: String) = onModelChange(ModelSlot.LORA, loraName)
    fun onHighnoiseUnetChange(unet: String) = onModelChange(ModelSlot.HIGHNOISE_UNET, unet)
    fun onLownoiseUnetChange(unet: String) = onModelChange(ModelSlot.LOWNOISE_UNET, unet)
    fun onHighnoiseLoraChange(lora: String) = onModelChange(ModelSlot.HIGHNOISE_LORA, lora)
    fun onLownoiseLoraChange(lora: String) = onModelChange(ModelSlot.LOWNOISE_LORA, lora)

    // Parameters with validation: an invalid value is shown with its error but not saved

    fun onWidthChange(width: String) {
        val error = ValidationUtils.validateDimension(width, applicationContext)
        updateParams(save = error == null) { it.copy(width = width, widthError = error) }
    }

    fun onHeightChange(height: String) {
        val error = ValidationUtils.validateDimension(height, applicationContext)
        updateParams(save = error == null) { it.copy(height = height, heightError = error) }
    }

    fun onMegapixelsChange(megapixels: String) {
        val error = ValidationUtils.validateMegapixels(megapixels, applicationContext)
        updateParams(save = error == null) { it.copy(megapixels = megapixels, megapixelsError = error) }
    }

    /** Length rule of the screen; video screens require the frame counts video latents accept. */
    protected open fun validateLength(length: String): String? = ValidationUtils.validateLength(length, applicationContext)

    fun onLengthChange(length: String) {
        val error = validateLength(length)
        updateParams(save = error == null) { it.copy(length = length, lengthError = error) }
    }

    fun onFpsChange(fps: String) {
        val error = ValidationUtils.validateFrameRate(fps, applicationContext)
        updateParams(save = error == null) { it.copy(fps = fps, fpsError = error) }
    }

    fun onStepsChange(steps: String) {
        val error = ValidationUtils.validateSteps(steps, applicationContext)
        updateParams(save = error == null) { it.copy(steps = steps, stepsError = error) }
    }

    fun onCfgChange(cfg: String) {
        val error = ValidationUtils.validateCfg(cfg, applicationContext)
        updateParams(save = error == null) { it.copy(cfg = cfg, cfgError = error) }
    }

    fun onSeedChange(seed: String) {
        val error = ValidationUtils.validateSeed(seed, applicationContext)
        updateParams(save = error == null) { it.copy(seed = seed, seedError = error) }
    }

    fun onDenoiseChange(denoise: String) {
        val error = ValidationUtils.validateDenoise(denoise, applicationContext)
        updateParams(save = error == null) { it.copy(denoise = denoise, denoiseError = error) }
    }

    fun onBatchSizeChange(batchSize: String) {
        val error = ValidationUtils.validateBatchSize(batchSize, applicationContext)
        updateParams(save = error == null) { it.copy(batchSize = batchSize, batchSizeError = error) }
    }

    fun onScaleByChange(scaleBy: String) {
        val error = ValidationUtils.validateScaleBy(scaleBy, applicationContext)
        updateParams(save = error == null) { it.copy(scaleBy = scaleBy, scaleByError = error) }
    }

    fun onStopAtClipLayerChange(layer: String) {
        val error = ValidationUtils.validateStopAtClipLayer(layer, applicationContext)
        updateParams(save = error == null) { it.copy(stopAtClipLayer = layer, stopAtClipLayerError = error) }
    }

    // Parameters without validation

    fun onSamplerChange(sampler: String) = updateParams { it.copy(sampler = sampler) }
    fun onSchedulerChange(scheduler: String) = updateParams { it.copy(scheduler = scheduler) }
    fun onUpscaleMethodChange(method: String) = updateParams { it.copy(upscaleMethod = method) }
    fun onRandomSeedToggle() = updateParams { it.copy(randomSeed = !it.randomSeed) }

    fun onRandomizeSeed() {
        val seed = kotlin.random.Random.nextLong(0, Long.MAX_VALUE).toString()
        updateParams { it.copy(seed = seed, seedError = null) }
    }

    // LoRA chains

    private fun updateLoraChain(kind: LoraChainKind, change: (GenerationParams) -> List<LoraSelection>) {
        val params = currentParams
        val chain = change(params)
        if (chain === params.loraChain(kind)) return // No change
        updateParams { it.withLoraChain(kind, chain) }
    }

    fun onAddLora(kind: LoraChainKind) =
        updateLoraChain(kind) { LoraChainManager.addLora(it.loraChain(kind), it.availableLoras) }

    fun onRemoveLora(kind: LoraChainKind, index: Int) =
        updateLoraChain(kind) { LoraChainManager.removeLora(it.loraChain(kind), index) }

    fun onLoraNameChange(kind: LoraChainKind, index: Int, name: String) =
        updateLoraChain(kind) { LoraChainManager.updateLoraName(it.loraChain(kind), index, name) }

    fun onLoraStrengthChange(kind: LoraChainKind, index: Int, strength: Float) =
        updateLoraChain(kind) { LoraChainManager.updateLoraStrength(it.loraChain(kind), index, strength) }

    fun onAddLora() = onAddLora(LoraChainKind.MAIN)
    fun onRemoveLora(index: Int) = onRemoveLora(LoraChainKind.MAIN, index)
    fun onLoraNameChange(index: Int, name: String) = onLoraNameChange(LoraChainKind.MAIN, index, name)
    fun onLoraStrengthChange(index: Int, strength: Float) = onLoraStrengthChange(LoraChainKind.MAIN, index, strength)

    fun onAddHighnoiseLora() = onAddLora(LoraChainKind.HIGHNOISE)
    fun onRemoveHighnoiseLora(index: Int) = onRemoveLora(LoraChainKind.HIGHNOISE, index)
    fun onHighnoiseLoraChainNameChange(index: Int, name: String) = onLoraNameChange(LoraChainKind.HIGHNOISE, index, name)
    fun onHighnoiseLoraChainStrengthChange(index: Int, strength: Float) = onLoraStrengthChange(LoraChainKind.HIGHNOISE, index, strength)

    fun onAddLownoiseLora() = onAddLora(LoraChainKind.LOWNOISE)
    fun onRemoveLownoiseLora(index: Int) = onRemoveLora(LoraChainKind.LOWNOISE, index)
    fun onLownoiseLoraChainNameChange(index: Int, name: String) = onLoraNameChange(LoraChainKind.LOWNOISE, index, name)
    fun onLownoiseLoraChainStrengthChange(index: Int, strength: Float) = onLoraStrengthChange(LoraChainKind.LOWNOISE, index, strength)
}
