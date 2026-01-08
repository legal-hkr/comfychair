package sh.hnet.comfychair.ui.components.config

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/**
 * Unified configuration for the ConfigBottomSheetContent composable.
 * All fields are optional with visibility controlled by has* flags.
 */
@Stable
data class BottomSheetConfig(
    val workflow: WorkflowConfig,
    val prompts: PromptConfig,
    val itiConfig: ItiConfig? = null,
    val models: ModelConfig,
    val parameters: ParameterConfig,
    val lora: LoraConfig
)

/**
 * Workflow dropdown configuration
 */
@Stable
data class WorkflowConfig(
    val selectedWorkflow: String,
    val availableWorkflows: List<WorkflowItemBase>,
    val onWorkflowChange: (String) -> Unit,
    val onViewWorkflow: () -> Unit
)

/**
 * Prompt fields configuration
 */
@Stable
data class PromptConfig(
    val negativePrompt: String,
    val onNegativePromptChange: (String) -> Unit,
    val hasNegativePrompt: Boolean = true
)

/**
 * ITI-specific configuration (mode selector and reference images)
 */
@Stable
data class ItiConfig(
    val mode: ImageToImageMode,
    val onModeChange: (ImageToImageMode) -> Unit,
    val referenceImage1: Bitmap? = null,
    val onReferenceImage1Change: (Uri) -> Unit,
    val onClearReferenceImage1: () -> Unit,
    val hasReferenceImage1: Boolean = false,
    val referenceImage2: Bitmap? = null,
    val onReferenceImage2Change: (Uri) -> Unit,
    val onClearReferenceImage2: () -> Unit,
    val hasReferenceImage2: Boolean = false
)

/**
 * Model selection configuration - all optional with visibility flags
 */
@Stable
data class ModelConfig(
    val checkpoint: ModelField? = null,
    val unet: ModelField? = null,
    val latentUpscaleModel: ModelField? = null,
    val vae: ModelField? = null,
    val clip: ModelField? = null,
    val clip1: ModelField? = null,
    val clip2: ModelField? = null,
    val clip3: ModelField? = null,
    val clip4: ModelField? = null,
    val textEncoder: ModelField? = null,
    val highnoiseUnet: ModelField? = null,
    val lownoiseUnet: ModelField? = null,
    val highnoiseLora: ModelField? = null,
    val lownoiseLora: ModelField? = null
)

/**
 * Single model field configuration
 */
@Stable
data class ModelField(
    val label: Int,
    val selectedValue: String,
    val options: List<String>,
    val filteredOptions: List<String>? = null,
    val onValueChange: (String) -> Unit,
    val isVisible: Boolean = true
)

/**
 * Generation parameter configuration
 */
@Stable
data class ParameterConfig(
    val width: NumericField? = null,
    val height: NumericField? = null,
    val steps: NumericField? = null,
    val cfg: NumericField? = null,
    val megapixels: NumericField? = null,
    val length: NumericField? = null,
    val fps: NumericField? = null,
    val sampler: DropdownField? = null,
    val scheduler: DropdownField? = null,
    val seed: SeedConfig? = null,
    val denoise: NumericField? = null,
    val batchSize: NumericField? = null,
    val upscaleMethod: DropdownField? = null,
    val scaleBy: NumericField? = null,
    val stopAtClipLayer: NumericField? = null
)

/**
 * Seed field configuration with toggle and randomize controls
 */
@Stable
data class SeedConfig(
    val randomSeed: Boolean,
    val onRandomSeedToggle: () -> Unit,
    val seed: String,
    val onSeedChange: (String) -> Unit,
    val onRandomizeSeed: () -> Unit,
    val seedError: String? = null,
    val isVisible: Boolean = true
)

/**
 * Numeric input field configuration
 */
@Stable
data class NumericField(
    val value: String,
    val onValueChange: (String) -> Unit,
    val error: String? = null,
    val isVisible: Boolean = true
)

/**
 * Dropdown field configuration (for sampler/scheduler)
 */
@Stable
data class DropdownField(
    val selectedValue: String,
    val options: List<String>,
    val onValueChange: (String) -> Unit,
    val isVisible: Boolean = true
)

/**
 * LoRA chain configuration
 */
@Stable
data class LoraConfig(
    val primaryChain: LoraChainField? = null,
    val loraName: ModelField? = null,  // Mandatory LoRA dropdown (from lora_name placeholder)
    val highnoiseChain: LoraChainField? = null,
    val lownoiseChain: LoraChainField? = null
)

/**
 * LoRA chain editor configuration
 */
@Stable
data class LoraChainField(
    val title: Int,
    val chain: List<LoraSelection>,
    val availableLoras: List<String>,
    val onAdd: () -> Unit,
    val onRemove: (Int) -> Unit,
    val onNameChange: (Int, String) -> Unit,
    val onStrengthChange: (Int, Float) -> Unit,
    val isVisible: Boolean = true
)
