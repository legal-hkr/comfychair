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

    // === Dual-Model Patterns (e.g. Wan 2.2) ===
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

    // === Dual-Model LoRA Chains (e.g. Wan 2.2) ===
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
)
