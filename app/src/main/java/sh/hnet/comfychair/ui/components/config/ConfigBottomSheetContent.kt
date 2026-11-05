package sh.hnet.comfychair.ui.components.config

import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LoraChainEditor
import sh.hnet.comfychair.ui.components.generation.WorkflowRow
import sh.hnet.comfychair.ui.components.shared.ChoiceRow
import sh.hnet.comfychair.ui.components.shared.ConstrainedNumberField
import sh.hnet.comfychair.ui.components.shared.NumberParameter
import sh.hnet.comfychair.ui.components.shared.ReferenceImageThumbnail
import sh.hnet.comfychair.ui.components.shared.SeedRow
import sh.hnet.comfychair.ui.components.shared.segmentedItemShape
import sh.hnet.comfychair.viewmodel.ImageToImageMode

/** One line of the sheet's list. */
private sealed interface SheetItem {
    val key: String

    data class Fields(val row: List<OptionField>, val index: Int) : SheetItem {
        override val key get() = "row-${row.joinToString()}-$index"
    }
    data class Header(val section: OptionsSection, val collapsible: Boolean) : SheetItem {
        override val key get() = "header-$section"
    }
    /** Choices next to each other (models, sampler…), shown as one group of list rows. */
    data class Choices(val fields: List<OptionField>) : SheetItem {
        override val key get() = "choices-${fields.joinToString()}"
    }
    data class Summary(val section: OptionsSection) : SheetItem {
        override val key get() = "summary-$section"
    }
}

/** Sections that collapse to a summary in a large workflow; Quick always stays open. */
private val collapsibleSections = setOf(OptionsSection.SAMPLING, OptionsSection.LORA, OptionsSection.MODELS)

/**
 * Options sheet content, laid out by [layout]: the workflow first, then (in large workflows) section headers, Quick
 * always open, and Sampling, Models and LoRA collapsed to a summary row whose open state is remembered per workflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheetContent(
    config: BottomSheetConfig,
    layout: OptionsLayout,
    workflowId: String,
    modifier: Modifier = Modifier,
    // Opens the workflow's node values in the editor: the last row, for values the sheet doesn't show
    onOpenNodeValues: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val workflowName = config.workflow.selectedWorkflow

    // Open sections of this workflow; read once from preferences, then kept here
    val expanded = remember(workflowId) {
        mutableStateMapOf<OptionsSection, Boolean>().apply {
            collapsibleSections.forEach { put(it, OptionsSectionPrefs.isExpanded(context, workflowId, it)) }
        }
    }
    fun setExpanded(section: OptionsSection, value: Boolean) {
        expanded[section] = value
        OptionsSectionPrefs.setExpanded(context, workflowId, section, value)
    }

    val items = buildList {
        layout.sections.forEach { section ->
            val title = section.section
            if (layout.sectioned && title != null && title != OptionsSection.WORKFLOW) {
                val collapsible = title in collapsibleSections
                add(SheetItem.Header(title, collapsible))
                if (collapsible && expanded[title] != true) {
                    add(SheetItem.Summary(title))
                    return@forEach
                }
            }
            section.rows.forEachIndexed { index, row ->
                val choice = row.singleOrNull()?.takeIf { it in choiceFields }
                val last = lastOrNull()
                when {
                    choice == null -> add(SheetItem.Fields(row, index))
                    // Joins the group of choices right before it
                    last is SheetItem.Choices -> set(lastIndex, SheetItem.Choices(last.fields + choice))
                    else -> add(SheetItem.Choices(listOf(choice)))
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items.forEach { item ->
            when (item) {
                is SheetItem.Header -> item(key = item.key) {
                    SectionHeader(
                        section = item.section,
                        expanded = expanded[item.section] == true,
                        onCollapse = if (item.collapsible && expanded[item.section] == true) {
                            { setExpanded(item.section, false) }
                        } else null
                    )
                }
                is SheetItem.Summary -> item(key = item.key) {
                    SectionSummary(item.section, config) { setExpanded(item.section, true) }
                }
                is SheetItem.Choices -> item(key = item.key) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.fields.forEachIndexed { index, field ->
                            Field(field, config, workflowName, Modifier.fillMaxWidth(), segmentedItemShape(index, item.fields.size))
                        }
                    }
                }
                is SheetItem.Fields -> item(key = item.key) {
                    FieldRow(item.row, config, workflowName)
                }
            }
        }
        onOpenNodeValues?.let { open ->
            item(key = "node-values") { NodeValuesRow(open) }
        }
    }
}

/** "All node values": the workflow's other values, in the editor. */
@Composable
private fun NodeValuesRow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.label_all_node_values)) },
            supportingContent = { Text(stringResource(R.string.desc_all_node_values)) },
            leadingContent = { Icon(Icons.Rounded.Tune, contentDescription = null) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    }
}

/** Fields chosen from a list, shown as list rows rather than text fields. */
private val choiceFields = setOf(
    OptionField.SAMPLER, OptionField.SCHEDULER, OptionField.UPSCALE_METHOD,
    OptionField.LORA_NAME, OptionField.HIGHNOISE_LORA_NAME, OptionField.LOWNOISE_LORA_NAME,
    OptionField.CHECKPOINT, OptionField.UNET, OptionField.HIGHNOISE_UNET, OptionField.LOWNOISE_UNET, OptionField.VAE,
    OptionField.CLIP, OptionField.CLIP1, OptionField.CLIP2, OptionField.CLIP3, OptionField.CLIP4,
    OptionField.TEXT_ENCODER, OptionField.LATENT_UPSCALE_MODEL
)

@Composable
private fun FieldRow(
    row: List<OptionField>,
    config: BottomSheetConfig,
    workflowName: String
) {
    if (row.size == 2) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { field -> Field(field, config, workflowName, Modifier.weight(1f)) }
        }
    } else {
        Field(row.single(), config, workflowName, Modifier.fillMaxWidth())
    }
}

/** One field, built from the screen's configuration; nothing is shown if the configuration lacks it. */
@Composable
private fun Field(
    field: OptionField,
    config: BottomSheetConfig,
    workflowName: String,
    modifier: Modifier,
    shape: Shape = RoundedCornerShape(20.dp)
) {
    val params = config.parameters
    val models = config.models
    fun number(parameter: NumberParameter, numeric: NumericField?) = @Composable {
        numeric?.let {
            ConstrainedNumberField(parameter, workflowName, it.value, it.onValueChange, it.error, modifier)
        }
    }
    fun dropdown(labelRes: Int, dropdown: DropdownField?) = @Composable {
        dropdown?.let {
            ChoiceRow(stringResource(labelRes), it.selectedValue, it.options, it.onValueChange, shape, modifier)
        }
    }
    // Models and single LoRAs: files, shown by name with their role and folder
    fun model(modelField: ModelField?) = @Composable {
        modelField?.let {
            ChoiceRow(
                stringResource(it.label), it.selectedValue, it.filteredOptions ?: it.options, it.onValueChange, shape, modifier,
                isFile = true
            )
        }
    }
    fun singleLora(modelField: ModelField?) = model(modelField)
    fun chain(chainField: LoraChainField?) = @Composable {
        chainField?.let {
            LoraChainEditor(
                title = stringResource(it.title),
                loraChain = it.chain,
                availableLoras = it.availableLoras,
                onAddLora = it.onAdd,
                onRemoveLora = it.onRemove,
                onLoraNameChange = it.onNameChange,
                onLoraStrengthChange = it.onStrengthChange,
                modifier = modifier
            )
        }
    }

    when (field) {
        OptionField.WORKFLOW -> WorkflowSection(config.workflow)
        OptionField.REFERENCE_IMAGES -> config.itiConfig?.let { ReferenceImagesSection(it) }
        OptionField.SEED -> params.seed?.let { seed ->
            SeedRow(
                workflowName = workflowName,
                randomSeed = seed.randomSeed,
                onRandomSeedToggle = seed.onRandomSeedToggle,
                seed = seed.seed,
                onSeedChange = seed.onSeedChange,
                onRandomizeSeed = seed.onRandomizeSeed,
                seedError = seed.seedError
            )
        }
        OptionField.WIDTH -> number(NumberParameter.WIDTH, params.width)()
        OptionField.HEIGHT -> number(NumberParameter.HEIGHT, params.height)()
        OptionField.LENGTH -> number(NumberParameter.LENGTH, params.length)()
        OptionField.FPS -> number(NumberParameter.FPS, params.fps)()
        OptionField.STEPS -> number(NumberParameter.STEPS, params.steps)()
        OptionField.MEGAPIXELS -> number(NumberParameter.MEGAPIXELS, params.megapixels)()
        OptionField.DENOISE -> number(NumberParameter.DENOISE, params.denoise)()
        OptionField.BATCH_SIZE -> number(NumberParameter.BATCH_SIZE, params.batchSize)()
        OptionField.CFG -> number(NumberParameter.CFG, params.cfg)()
        OptionField.CLIP_LAYER -> number(NumberParameter.CLIP_LAYER, params.stopAtClipLayer)()
        OptionField.SCALE_BY -> number(NumberParameter.SCALE_BY, params.scaleBy)()
        OptionField.SAMPLER -> dropdown(R.string.label_sampler, params.sampler)()
        OptionField.SCHEDULER -> dropdown(R.string.label_scheduler, params.scheduler)()
        OptionField.UPSCALE_METHOD -> dropdown(R.string.label_upscale_method, params.upscaleMethod)()
        OptionField.LORA_NAME -> singleLora(config.lora.loraName)()
        OptionField.HIGHNOISE_LORA_NAME -> singleLora(models.highnoiseLora)()
        OptionField.LOWNOISE_LORA_NAME -> singleLora(models.lownoiseLora)()
        OptionField.LORA_CHAIN -> chain(config.lora.primaryChain)()
        OptionField.HIGHNOISE_LORA_CHAIN -> chain(config.lora.highnoiseChain)()
        OptionField.LOWNOISE_LORA_CHAIN -> chain(config.lora.lownoiseChain)()
        OptionField.CHECKPOINT -> model(models.checkpoint)()
        OptionField.UNET -> model(models.unet)()
        OptionField.HIGHNOISE_UNET -> model(models.highnoiseUnet)()
        OptionField.LOWNOISE_UNET -> model(models.lownoiseUnet)()
        OptionField.VAE -> model(models.vae)()
        OptionField.CLIP -> model(models.clip)()
        OptionField.CLIP1 -> model(models.clip1)()
        OptionField.CLIP2 -> model(models.clip2)()
        OptionField.CLIP3 -> model(models.clip3)()
        OptionField.CLIP4 -> model(models.clip4)()
        OptionField.TEXT_ENCODER -> model(models.textEncoder)()
        OptionField.LATENT_UPSCALE_MODEL -> model(models.latentUpscaleModel)()
    }
}

private fun sectionTitleRes(section: OptionsSection): Int = when (section) {
    OptionsSection.WORKFLOW -> R.string.label_workflow
    OptionsSection.QUICK -> R.string.label_section_quick
    OptionsSection.SAMPLING -> R.string.label_section_sampling
    OptionsSection.LORA -> R.string.label_section_lora
    OptionsSection.MODELS -> R.string.label_section_models
}

/** LoRAs in use: single LoRA fields that are set, and the chains' entries. */
private fun loraCount(config: BottomSheetConfig): Int =
    listOfNotNull(config.lora.loraName, config.models.highnoiseLora, config.models.lownoiseLora)
        .count { it.selectedValue.isNotEmpty() } +
        listOfNotNull(config.lora.primaryChain, config.lora.highnoiseChain, config.lora.lownoiseChain)
            .filter { it.isVisible }.sumOf { it.chain.size }

private fun modelCount(config: BottomSheetConfig): Int = with(config.models) {
    listOfNotNull(checkpoint, unet, highnoiseUnet, lownoiseUnet, vae, clip, clip1, clip2, clip3, clip4, textEncoder, latentUpscaleModel)
        .count { it.isVisible }
}

@Composable
private fun SectionHeader(section: OptionsSection, expanded: Boolean, onCollapse: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(sectionTitleRes(section)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (onCollapse != null && expanded) {
            sh.hnet.comfychair.ui.components.shared.TooltipIconButton(
                Icons.Rounded.ExpandLess,
                stringResource(R.string.content_description_hide_section, stringResource(sectionTitleRes(section))),
                onCollapse
            )
        }
    }
}

/** A collapsed section: what it's set to, and what it holds; tapping opens it. */
@Composable
private fun SectionSummary(section: OptionsSection, config: BottomSheetConfig, onExpand: () -> Unit) {
    val params = config.parameters
    val summary = when (section) {
        OptionsSection.SAMPLING -> listOfNotNull(
            params.sampler?.selectedValue,
            params.scheduler?.selectedValue,
            params.cfg?.takeIf { it.isVisible }?.let { "${stringResource(R.string.label_cfg)} ${it.value}" },
            params.stopAtClipLayer?.takeIf { it.isVisible }?.let { "${stringResource(R.string.label_stop_at_clip_layer)} ${it.value}" },
            params.upscaleMethod?.selectedValue,
            params.scaleBy?.takeIf { it.isVisible }?.let { "${stringResource(R.string.label_scale_by)} ${it.value}" }
        ).filter { it.isNotBlank() }.joinToString(", ")
        OptionsSection.LORA -> loraCount(config).let { count ->
            if (count == 0) stringResource(R.string.label_no_loras) else pluralStringResource(R.plurals.label_lora_count, count, count)
        }
        OptionsSection.MODELS -> modelCount(config).let { pluralStringResource(R.plurals.label_models_count, it, it) }
        else -> ""
    }
    val title = stringResource(sectionTitleRes(section))
    val showLabel = stringResource(R.string.content_description_show_section, title)
    Surface(
        onClick = onExpand,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                stateDescription = showLabel
            }
    ) {
        ListItem(
            headlineContent = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    }
}

/** Remembers per workflow which sections of the options sheet are open (UI preference, no generation data). */
object OptionsSectionPrefs {
    private const val PREFS = "OptionsSheetSections"

    fun isExpanded(context: Context, workflowId: String, section: OptionsSection): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("$workflowId:${section.name}", false)

    fun setExpanded(context: Context, workflowId: String, section: OptionsSection, expanded: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("$workflowId:${section.name}", expanded).apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

@Composable
private fun ReferenceImagesSection(itiConfig: ItiConfig) {
    val hasAnyReferenceImages = itiConfig.hasReferenceImage1 || itiConfig.hasReferenceImage2
    if (itiConfig.mode == ImageToImageMode.EDITING && hasAnyReferenceImages) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (itiConfig.hasReferenceImage1) {
                ReferenceImageThumbnail(
                    image = itiConfig.referenceImage1,
                    contentDescription = stringResource(R.string.content_description_reference_image_1),
                    onImageSelected = itiConfig.onReferenceImage1Change,
                    onClear = itiConfig.onClearReferenceImage1,
                    modifier = Modifier.weight(1f)
                )
            }
            if (itiConfig.hasReferenceImage2) {
                ReferenceImageThumbnail(
                    image = itiConfig.referenceImage2,
                    contentDescription = stringResource(R.string.content_description_reference_image_2),
                    onImageSelected = itiConfig.onReferenceImage2Change,
                    onClear = itiConfig.onClearReferenceImage2,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WorkflowSection(workflow: WorkflowConfig) {
    WorkflowRow(workflow.selectedWorkflow, workflow.icon, workflow.groups, workflow.onViewWorkflow)
}
