@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.comfychair.ui.components.config

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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.LoraChainEditor
import sh.hnet.comfychair.ui.components.generation.WorkflowRow
import sh.hnet.comfychair.ui.components.settings.groupShapes
import sh.hnet.comfychair.ui.components.shared.ChoiceRow
import sh.hnet.comfychair.ui.components.shared.ConstrainedNumberField
import sh.hnet.comfychair.ui.components.shared.NumberParameter
import sh.hnet.comfychair.ui.components.shared.SeedRow
import sh.hnet.comfychair.ui.components.shared.segmentedItemShape

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
    /** Custom fields (spec §18.6): switch and choice rows next to each other form one group, others stand alone */
    data class Custom(val entries: List<CustomFieldEntry>) : SheetItem {
        override val key get() = "custom-${entries.joinToString { it.field.id }}"
    }
    data object CustomHeader : SheetItem {
        override val key get() = "header-custom"
    }
    data object CustomSummary : SheetItem {
        override val key get() = "summary-custom"
    }
}

/** Labels the workflow gives its built-in fields (spec §18.4), by placeholder key; read by the sheet's rows */
private val LocalFieldLabels = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** The placeholder key of a sheet field's built-in field (labels are stored by it); null for LoRA chains. */
private fun OptionField.placeholderKey(): String? = when (this) {
    OptionField.WORKFLOW, OptionField.LORA_CHAIN, OptionField.HIGHNOISE_LORA_CHAIN, OptionField.LOWNOISE_LORA_CHAIN -> null
    OptionField.SEED -> "seed"
    OptionField.WIDTH -> "width"
    OptionField.HEIGHT -> "height"
    OptionField.LENGTH -> "length"
    OptionField.FPS -> "frame_rate"
    OptionField.STEPS -> "steps"
    OptionField.MEGAPIXELS -> "megapixels"
    OptionField.BATCH_SIZE -> "batch_size"
    OptionField.SCALE_BY -> "scale_by"
    OptionField.SAMPLER -> "sampler_name"
    OptionField.SCHEDULER -> "scheduler"
    OptionField.CFG -> "cfg"
    OptionField.CLIP_LAYER -> "stop_at_clip_layer"
    OptionField.DENOISE -> "denoise"
    OptionField.UPSCALE_METHOD -> "upscale_method"
    OptionField.LORA_NAME -> "lora_name"
    OptionField.HIGHNOISE_LORA_NAME -> "highnoise_lora_name"
    OptionField.LOWNOISE_LORA_NAME -> "lownoise_lora_name"
    OptionField.CHECKPOINT -> "ckpt_name"
    OptionField.UNET -> "unet_name"
    OptionField.HIGHNOISE_UNET -> "highnoise_unet_name"
    OptionField.LOWNOISE_UNET -> "lownoise_unet_name"
    OptionField.VAE -> "vae_name"
    OptionField.AUDIO_VAE -> "audio_vae_name"
    OptionField.CLIP -> "clip_name"
    OptionField.CLIP1 -> "clip_name1"
    OptionField.CLIP2 -> "clip_name2"
    OptionField.CLIP3 -> "clip_name3"
    OptionField.CLIP4 -> "clip_name4"
    OptionField.TEXT_ENCODER -> "text_encoder_name"
    OptionField.LATENT_UPSCALE_MODEL -> "latent_upscale_model"
}

/** A field's name on the sheet: the workflow's label for it, else the app's. */
@Composable
private fun fieldLabel(field: OptionField, labelRes: Int): String =
    field.placeholderKey()?.let { LocalFieldLabels.current[it] } ?: stringResource(labelRes)

/** The custom fields' section (not a built-in [OptionsSection]: the workflow defines its fields) */
const val CUSTOM_SECTION = "CUSTOM"

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
    onOpenNodeValues: (() -> Unit)? = null,
    // Custom fields (spec §18.6): a last section, after LoRA
    customFields: List<CustomFieldEntry> = emptyList(),
    customValues: Map<String, String> = emptyMap(),
    customErrors: Map<String, String> = emptyMap(),
    onCustomFieldChange: (fieldId: String, value: String, error: String?) -> Unit = { _, _, _ -> },
    // Labels of the workflow's built-in fields (spec §18.4), by placeholder key
    fieldLabels: Map<String, String> = emptyMap()
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
    var customExpanded by remember(workflowId) { mutableStateOf(OptionsSectionPrefs.isExpanded(context, workflowId, CUSTOM_SECTION)) }
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
        if (customFields.isNotEmpty()) {
            // Collapsible as the other sections of a large workflow; in a small one the fields just follow
            if (layout.sectioned) add(SheetItem.CustomHeader)
            if (layout.sectioned && !customExpanded) {
                add(SheetItem.CustomSummary)
            } else {
                customFields.forEach { entry ->
                    val last = lastOrNull()
                    if (entry.isListRow() && last is SheetItem.Custom && last.entries.last().isListRow()) {
                        set(lastIndex, SheetItem.Custom(last.entries + entry))
                    } else {
                        add(SheetItem.Custom(listOf(entry)))
                    }
                }
            }
        }
    }
    fun setCustomExpanded(value: Boolean) {
        customExpanded = value
        OptionsSectionPrefs.setExpanded(context, workflowId, CUSTOM_SECTION, value)
    }

    CompositionLocalProvider(LocalFieldLabels provides fieldLabels) {
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
                        title = stringResource(sectionTitleRes(item.section)),
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
                SheetItem.CustomHeader -> item(key = item.key) {
                    SectionHeader(
                        title = stringResource(R.string.section_custom_fields),
                        expanded = customExpanded,
                        onCollapse = if (customExpanded) { { setCustomExpanded(false) } } else null
                    )
                }
                SheetItem.CustomSummary -> item(key = item.key) {
                    SummaryRow(stringResource(R.string.section_custom_fields), customFieldsSummary(customFields, customValues)) {
                        setCustomExpanded(true)
                    }
                }
                is SheetItem.Custom -> item(key = item.key) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.entries.forEachIndexed { index, entry ->
                            val id = entry.field.id
                            CustomFieldControl(
                                entry = entry,
                                value = customValues[id] ?: entry.field.default,
                                error = customErrors[id],
                                onValueChange = { value, error -> onCustomFieldChange(id, value, error) },
                                shape = segmentedItemShape(index, item.entries.size)
                            )
                        }
                    }
                }
            }
        }
        onOpenNodeValues?.let { open ->
            item(key = "node-values") { NodeValuesRow(open) }
        }
    }
    }
}

/** "All node values": the workflow's other values, in the editor. */
@Composable
private fun NodeValuesRow(onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = groupShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        supportingContent = { Text(stringResource(R.string.desc_all_node_values)) },
        leadingContent = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.label_all_node_values))
    }
}

/** Fields chosen from a list, shown as list rows rather than text fields. */
private val choiceFields = setOf(
    OptionField.SAMPLER, OptionField.SCHEDULER, OptionField.UPSCALE_METHOD,
    OptionField.LORA_NAME, OptionField.HIGHNOISE_LORA_NAME, OptionField.LOWNOISE_LORA_NAME,
    OptionField.CHECKPOINT, OptionField.UNET, OptionField.HIGHNOISE_UNET, OptionField.LOWNOISE_UNET, OptionField.VAE, OptionField.AUDIO_VAE,
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
            ConstrainedNumberField(
                parameter, workflowName, it.value, it.onValueChange, it.error, modifier,
                label = fieldLabel(field, parameter.labelRes)
            )
        }
    }
    fun dropdown(labelRes: Int, dropdown: DropdownField?) = @Composable {
        dropdown?.let {
            ChoiceRow(fieldLabel(field, labelRes), it.selectedValue, it.options, it.onValueChange, shape, modifier)
        }
    }
    // Models and single LoRAs: files, shown by name with their role and folder
    fun model(modelField: ModelField?) = @Composable {
        modelField?.let {
            ChoiceRow(
                fieldLabel(field, it.label), it.selectedValue, it.filteredOptions ?: it.options, it.onValueChange, shape, modifier,
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
        OptionField.AUDIO_VAE -> model(models.audioVae)()
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
    listOfNotNull(checkpoint, unet, highnoiseUnet, lownoiseUnet, vae, audioVae, clip, clip1, clip2, clip3, clip4, textEncoder, latentUpscaleModel)
        .count { it.isVisible }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onCollapse: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (onCollapse != null && expanded) {
            sh.hnet.comfychair.ui.components.shared.TooltipIconButton(
                Icons.Rounded.ExpandLess,
                stringResource(R.string.content_description_hide_section, title),
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
            params.cfg?.takeIf { it.isVisible }?.let { "${fieldLabel(OptionField.CFG, R.string.label_cfg)} ${it.value}" },
            params.stopAtClipLayer?.takeIf { it.isVisible }?.let { "${fieldLabel(OptionField.CLIP_LAYER, R.string.label_stop_at_clip_layer)} ${it.value}" },
            params.upscaleMethod?.selectedValue,
            params.scaleBy?.takeIf { it.isVisible }?.let { "${fieldLabel(OptionField.SCALE_BY, R.string.label_scale_by)} ${it.value}" }
        ).filter { it.isNotBlank() }.joinToString(", ")
        OptionsSection.LORA -> loraCount(config).let { count ->
            if (count == 0) stringResource(R.string.label_no_loras) else pluralStringResource(R.plurals.label_lora_count, count, count)
        }
        OptionsSection.MODELS -> modelCount(config).let { pluralStringResource(R.plurals.label_models_count, it, it) }
        else -> ""
    }
    SummaryRow(stringResource(sectionTitleRes(section)), summary, onExpand)
}

@Composable
private fun SummaryRow(title: String, summary: String, onExpand: () -> Unit) {
    val showLabel = stringResource(R.string.content_description_show_section, title)
    SegmentedListItem(
        onClick = onExpand,
        shapes = groupShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = showLabel }
    ) {
        Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Remembers per workflow which sections of the options sheet are open (UI preference, no generation data). */
object OptionsSectionPrefs {
    private const val PREFS = "OptionsSheetSections"

    fun isExpanded(context: Context, workflowId: String, section: OptionsSection): Boolean =
        isExpanded(context, workflowId, section.name)

    fun setExpanded(context: Context, workflowId: String, section: OptionsSection, expanded: Boolean) =
        setExpanded(context, workflowId, section.name, expanded)

    /** By the section's name: the built-in sections' enum names, or [CUSTOM_SECTION] */
    fun isExpanded(context: Context, workflowId: String, section: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("$workflowId:$section", false)

    fun setExpanded(context: Context, workflowId: String, section: String, expanded: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("$workflowId:$section", expanded).apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

@Composable
private fun WorkflowSection(workflow: WorkflowConfig) {
    WorkflowRow(workflow.selectedWorkflow, workflow.icon, workflow.groups)
}
