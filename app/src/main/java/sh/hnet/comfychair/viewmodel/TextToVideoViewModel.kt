package sh.hnet.comfychair.viewmodel

import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.util.SeasonalPrompts
import sh.hnet.comfychair.util.VideoUtils
import sh.hnet.comfychair.viewmodel.base.BaseVideoGenerationViewModel
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.viewmodel.base.VideoResult
import sh.hnet.comfychair.viewmodel.base.VideoResultState
import sh.hnet.comfychair.viewmodel.base.videoScreenDefaults

/**
 * UI state for the Text-to-Video screen.
 *
 * Generation parameters and the video result are exposed directly through delegation
 * (`state.steps`, `state.currentVideoUri` etc.).
 */
data class TextToVideoUiState(
    val params: GenerationParams = videoScreenDefaults,
    val video: VideoResult = VideoResult(),
    val availableWorkflows: List<WorkflowItem> = emptyList()
) : CommonGenerationState by params, VideoResultState by video

/**
 * ViewModel for the Text-to-Video screen
 */
class TextToVideoViewModel : BaseVideoGenerationViewModel<TextToVideoUiState>() {

    companion object {
        const val OWNER_ID = "TEXT_TO_VIDEO"
    }

    override val initialState = TextToVideoUiState()

    override val tag = "TextToVideo"
    override val ownerId = OWNER_ID
    override val workflowType = WorkflowType.TTV
    override val prefsName = "TextToVideoFragmentPrefs"
    override val videoFilePrefix = VideoUtils.FilePrefix.TEXT_TO_VIDEO
    override val previewKey = MediaStateHolder.MediaKey.TtvPreview
    override fun videoKey(promptId: String) = MediaStateHolder.MediaKey.TtvVideo(promptId)
    override fun currentVideoPromptId() = MediaStateHolder.getCurrentTtvPromptId()
    override fun clearCurrentVideoPromptId() = MediaStateHolder.clearCurrentTtvPromptId()
    override fun defaultPositivePrompt() = SeasonalPrompts.getTextToVideoPrompt()

    override fun TextToVideoUiState.params() = params
    override fun TextToVideoUiState.withParams(params: GenerationParams) = copy(params = params)
    override fun TextToVideoUiState.video() = video
    override fun TextToVideoUiState.withVideo(video: VideoResult) = copy(video = video)
    override fun TextToVideoUiState.workflows() = availableWorkflows
    override fun TextToVideoUiState.withWorkflows(workflows: List<WorkflowItem>) = copy(availableWorkflows = workflows)

    override fun hasValidConfiguration(): Boolean = isReadyToGenerate(_uiState.value.params)

    fun prepareWorkflow(): String? {
        val p = _uiState.value.params
        if (p.hasErrors) return null

        val width = p.width.toIntOrNull() ?: return null
        val height = p.height.toIntOrNull() ?: return null
        val length = p.length.toIntOrNull() ?: return null
        val fps = p.fps.toIntOrNull() ?: return null

        val baseWorkflow = WorkflowManager.prepareVideoWorkflowById(
            workflowId = p.selectedWorkflowId,
            positivePrompt = p.positivePrompt,
            negativePrompt = p.negativePrompt,
            // Single-model patterns (e.g., LTX 2.0)
            checkpoint = p.selectedCheckpoint,
            unet = p.selectedUnet,
            lora = p.selectedLoraName.takeIf { it.isNotEmpty() },
            // Dual-model patterns (e.g., Wan 2.2)
            highnoiseUnet = p.selectedHighnoiseUnet,
            lownoiseUnet = p.selectedLownoiseUnet,
            highnoiseLora = p.selectedHighnoiseLora,
            lownoiseLora = p.selectedLownoiseLora,
            // Common models
            vae = p.selectedVae,
            clip = p.selectedClip,
            clip1 = p.selectedClip1.takeIf { it.isNotEmpty() },
            clip2 = p.selectedClip2.takeIf { it.isNotEmpty() },
            clip3 = p.selectedClip3.takeIf { it.isNotEmpty() },
            clip4 = p.selectedClip4.takeIf { it.isNotEmpty() },
            textEncoder = p.selectedTextEncoder.takeIf { it.isNotEmpty() },
            latentUpscaleModel = p.selectedLatentUpscaleModel.takeIf { it.isNotEmpty() },
            width = width,
            height = height,
            length = length,
            fps = fps,
            // Generation parameters
            steps = p.steps.toIntOrNull(),
            cfg = p.cfg.toFloatOrNull(),
            samplerName = p.sampler,
            scheduler = p.scheduler,
            denoise = p.denoise.toFloatOrNull(),
            megapixels = p.megapixels.toFloatOrNull(),
            seed = p.seed.toLongOrNull(),
            randomSeed = p.randomSeed,
            upscaleMethod = p.upscaleMethod.takeIf { it.isNotEmpty() },
            scaleBy = p.scaleBy.toFloatOrNull(),
            stopAtClipLayer = p.stopAtClipLayer.toIntOrNull()
        ) ?: return null

        return injectVideoLoraChains(baseWorkflow, p)
    }
}
