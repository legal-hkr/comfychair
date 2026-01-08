package sh.hnet.comfychair.ui.components.config

import android.net.Uri
import androidx.compose.runtime.Stable
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/**
 * Unified callback interface for all generation screen types.
 *
 * Core callbacks (workflow, view, negative prompt) are required.
 * All other callbacks are nullable - screens only provide the callbacks they need.
 *
 * This consolidates the previous 4 separate callback classes:
 * - TextToImageCallbacks
 * - TextToVideoCallbacks
 * - ImageToVideoCallbacks
 * - ImageToImageCallbacks
 */
@Stable
data class UnifiedCallbacks(
    // === Core (required for ALL screens) ===
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit,
    val onNegativePromptChange: (String) -> Unit,

    // === Model Callbacks (common to all) ===
    val onCheckpointChange: ((String) -> Unit)? = null,
    val onUnetChange: ((String) -> Unit)? = null,
    val onVaeChange: ((String) -> Unit)? = null,
    val onClipChange: ((String) -> Unit)? = null,
    val onClip1Change: ((String) -> Unit)? = null,
    val onClip2Change: ((String) -> Unit)? = null,
    val onClip3Change: ((String) -> Unit)? = null,
    val onClip4Change: ((String) -> Unit)? = null,
    val onTextEncoderChange: ((String) -> Unit)? = null,
    val onLatentUpscaleModelChange: ((String) -> Unit)? = null,
    val onMandatoryLoraChange: ((String) -> Unit)? = null,

    // === Dual-Model Patterns (TTV/ITV only) ===
    val onHighnoiseUnetChange: ((String) -> Unit)? = null,
    val onLownoiseUnetChange: ((String) -> Unit)? = null,
    val onHighnoiseLoraChange: ((String) -> Unit)? = null,
    val onLownoiseLoraChange: ((String) -> Unit)? = null,

    // === Generation Parameters ===
    val onWidthChange: ((String) -> Unit)? = null,
    val onHeightChange: ((String) -> Unit)? = null,
    val onMegapixelsChange: ((String) -> Unit)? = null,
    val onLengthChange: ((String) -> Unit)? = null,
    val onFpsChange: ((String) -> Unit)? = null,
    val onStepsChange: ((String) -> Unit)? = null,
    val onCfgChange: ((String) -> Unit)? = null,
    val onSamplerChange: ((String) -> Unit)? = null,
    val onSchedulerChange: ((String) -> Unit)? = null,
    val onRandomSeedToggle: (() -> Unit)? = null,
    val onSeedChange: ((String) -> Unit)? = null,
    val onRandomizeSeed: (() -> Unit)? = null,
    val onDenoiseChange: ((String) -> Unit)? = null,
    val onBatchSizeChange: ((String) -> Unit)? = null,
    val onUpscaleMethodChange: ((String) -> Unit)? = null,
    val onScaleByChange: ((String) -> Unit)? = null,
    val onStopAtClipLayerChange: ((String) -> Unit)? = null,

    // === Primary LoRA Chain ===
    val onAddLora: (() -> Unit)? = null,
    val onRemoveLora: ((Int) -> Unit)? = null,
    val onLoraNameChange: ((Int, String) -> Unit)? = null,
    val onLoraStrengthChange: ((Int, Float) -> Unit)? = null,

    // === Dual-Model LoRA Chains (TTV/ITV) ===
    val onAddHighnoiseLora: (() -> Unit)? = null,
    val onRemoveHighnoiseLora: ((Int) -> Unit)? = null,
    val onHighnoiseLoraNameChange: ((Int, String) -> Unit)? = null,
    val onHighnoiseLoraStrengthChange: ((Int, Float) -> Unit)? = null,
    val onAddLownoiseLora: (() -> Unit)? = null,
    val onRemoveLownoiseLora: ((Int) -> Unit)? = null,
    val onLownoiseLoraNameChange: ((Int, String) -> Unit)? = null,
    val onLownoiseLoraStrengthChange: ((Int, Float) -> Unit)? = null,

    // === ITI-Specific: Mode Switching ===
    val onModeChange: ((ImageToImageMode) -> Unit)? = null,

    // === ITI-Specific: Reference Images (editing mode) ===
    val onReferenceImage1Change: ((Uri) -> Unit)? = null,
    val onClearReferenceImage1: (() -> Unit)? = null,
    val onReferenceImage2Change: ((Uri) -> Unit)? = null,
    val onClearReferenceImage2: (() -> Unit)? = null,

    // === ITI-Specific: Editing Workflow ===
    val onEditingWorkflowChange: ((String) -> Unit)? = null,
    val onViewEditingWorkflow: (() -> Unit)? = null,

    // === ITI-Specific: Editing Mode Models ===
    val onEditingUnetChange: ((String) -> Unit)? = null,
    val onEditingLoraChange: ((String) -> Unit)? = null,
    val onEditingVaeChange: ((String) -> Unit)? = null,
    val onEditingClipChange: ((String) -> Unit)? = null,
    val onEditingClip1Change: ((String) -> Unit)? = null,
    val onEditingClip2Change: ((String) -> Unit)? = null,
    val onEditingClip3Change: ((String) -> Unit)? = null,
    val onEditingClip4Change: ((String) -> Unit)? = null,
    val onEditingTextEncoderChange: ((String) -> Unit)? = null,
    val onEditingLatentUpscaleModelChange: ((String) -> Unit)? = null,

    // === ITI-Specific: Editing Mode Parameters ===
    val onEditingMegapixelsChange: ((String) -> Unit)? = null,
    val onEditingStepsChange: ((String) -> Unit)? = null,
    val onEditingCfgChange: ((String) -> Unit)? = null,
    val onEditingSamplerChange: ((String) -> Unit)? = null,
    val onEditingSchedulerChange: ((String) -> Unit)? = null,
    val onEditingRandomSeedToggle: (() -> Unit)? = null,
    val onEditingSeedChange: ((String) -> Unit)? = null,
    val onEditingRandomizeSeed: (() -> Unit)? = null,
    val onEditingDenoiseChange: ((String) -> Unit)? = null,
    val onEditingBatchSizeChange: ((String) -> Unit)? = null,
    val onEditingUpscaleMethodChange: ((String) -> Unit)? = null,
    val onEditingScaleByChange: ((String) -> Unit)? = null,
    val onEditingStopAtClipLayerChange: ((String) -> Unit)? = null,

    // === ITI-Specific: Editing Mode LoRA Chain ===
    val onAddEditingLora: (() -> Unit)? = null,
    val onRemoveEditingLora: ((Int) -> Unit)? = null,
    val onEditingLoraNameChange: ((Int, String) -> Unit)? = null,
    val onEditingLoraStrengthChange: ((Int, Float) -> Unit)? = null
)
