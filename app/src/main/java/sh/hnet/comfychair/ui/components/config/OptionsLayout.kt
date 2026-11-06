package sh.hnet.comfychair.ui.components.config

import sh.hnet.comfychair.model.WorkflowCapabilities

/**
 * Sections of the options sheet, in order: the workflow decides everything, then what is changed most, then the models
 * and the LoRAs applied to them.
 */
enum class OptionsSection { WORKFLOW, QUICK, SAMPLING, MODELS, LORA }

/**
 * One field of the options sheet. [half] fields (numeric steppers) can share a row with another.
 */
enum class OptionField(val half: Boolean = false) {
    // Workflow section
    WORKFLOW,

    // Quick
    SEED, WIDTH(true), HEIGHT(true), LENGTH(true), FPS(true), STEPS(true), MEGAPIXELS(true), BATCH_SIZE(true), SCALE_BY(true),

    // Sampling
    SAMPLER, SCHEDULER, CFG(true), CLIP_LAYER(true), DENOISE(true), UPSCALE_METHOD,

    // LoRA
    LORA_NAME, HIGHNOISE_LORA_NAME, LOWNOISE_LORA_NAME, LORA_CHAIN, HIGHNOISE_LORA_CHAIN, LOWNOISE_LORA_CHAIN,

    // Models
    CHECKPOINT, UNET, HIGHNOISE_UNET, LOWNOISE_UNET, VAE, AUDIO_VAE, CLIP, CLIP1, CLIP2, CLIP3, CLIP4, TEXT_ENCODER,
    LATENT_UPSCALE_MODEL
}

/** A section with its rows; a row holds one field, or two half fields side by side. */
data class OptionsSectionLayout(val section: OptionsSection?, val rows: List<List<OptionField>>)

/**
 * How the options sheet lays out the current workflow's fields.
 *
 * @param sectioned Large workflows get section headers (and jump chips, collapsible summaries); small ones are a flat
 *   list in which [sections] holds the workflow section and one untitled section with everything else
 * @param fieldCount Fields outside the workflow section, which decide [sectioned]
 */
data class OptionsLayout(val sectioned: Boolean, val fieldCount: Int, val sections: List<OptionsSectionLayout>) {
    val fields: List<OptionField> get() = sections.flatMap { section -> section.rows.flatten() }
}

/** Above this many fields the sheet gets sections. */
const val FLAT_OPTIONS_MAX_FIELDS = 8

/**
 * Rows of each section, in order. Two fields in a template share a row when both are present; a field alone may
 * then pair with an adjacent half field (e.g. steps with CFG).
 */
private val templates: Map<OptionsSection, List<List<OptionField>>> = mapOf(
    OptionsSection.WORKFLOW to listOf(
        listOf(OptionField.WORKFLOW)
    ),
    OptionsSection.QUICK to listOf(
        listOf(OptionField.SEED),
        listOf(OptionField.WIDTH, OptionField.HEIGHT),
        listOf(OptionField.LENGTH, OptionField.FPS),
        listOf(OptionField.STEPS, OptionField.MEGAPIXELS),
        listOf(OptionField.BATCH_SIZE, OptionField.SCALE_BY)
    ),
    // Denoise stays at 1.0 but in a few cases, so it's not among the quick fields
    OptionsSection.SAMPLING to listOf(
        listOf(OptionField.SAMPLER), listOf(OptionField.SCHEDULER),
        listOf(OptionField.CFG, OptionField.CLIP_LAYER),
        listOf(OptionField.DENOISE), listOf(OptionField.UPSCALE_METHOD)
    ),
    OptionsSection.LORA to listOf(
        listOf(OptionField.LORA_NAME), listOf(OptionField.HIGHNOISE_LORA_NAME), listOf(OptionField.LOWNOISE_LORA_NAME),
        listOf(OptionField.LORA_CHAIN), listOf(OptionField.HIGHNOISE_LORA_CHAIN), listOf(OptionField.LOWNOISE_LORA_CHAIN)
    ),
    OptionsSection.MODELS to listOf(
        listOf(OptionField.CHECKPOINT), listOf(OptionField.UNET),
        listOf(OptionField.HIGHNOISE_UNET), listOf(OptionField.LOWNOISE_UNET),
        listOf(OptionField.VAE), listOf(OptionField.AUDIO_VAE), listOf(OptionField.CLIP),
        listOf(OptionField.CLIP1), listOf(OptionField.CLIP2), listOf(OptionField.CLIP3), listOf(OptionField.CLIP4),
        listOf(OptionField.TEXT_ENCODER), listOf(OptionField.LATENT_UPSCALE_MODEL)
    )
)

/** Whether the workflow has a field; the workflow row is always there. */
private fun WorkflowCapabilities.has(field: OptionField): Boolean = when (field) {
    OptionField.WORKFLOW -> true
    OptionField.SEED -> hasSeed
    OptionField.WIDTH -> hasWidth
    OptionField.HEIGHT -> hasHeight
    OptionField.LENGTH -> hasLength
    OptionField.FPS -> hasFrameRate
    OptionField.STEPS -> hasSteps
    OptionField.MEGAPIXELS -> hasMegapixels
    OptionField.DENOISE -> hasDenoise
    OptionField.BATCH_SIZE -> hasBatchSize
    OptionField.SAMPLER -> hasSamplerName
    OptionField.SCHEDULER -> hasScheduler
    OptionField.CFG -> hasCfg
    OptionField.CLIP_LAYER -> hasStopAtClipLayer
    OptionField.UPSCALE_METHOD -> hasUpscaleMethod
    OptionField.SCALE_BY -> hasScaleBy
    OptionField.LORA_NAME -> hasLoraName
    OptionField.HIGHNOISE_LORA_NAME -> hasHighnoiseLoraName
    OptionField.LOWNOISE_LORA_NAME -> hasLownoiseLoraName
    OptionField.LORA_CHAIN -> hasLora
    OptionField.HIGHNOISE_LORA_CHAIN -> hasHighnoiseLora
    OptionField.LOWNOISE_LORA_CHAIN -> hasLownoiseLora
    OptionField.CHECKPOINT -> hasCheckpointName
    OptionField.UNET -> hasUnetName
    OptionField.HIGHNOISE_UNET -> hasHighnoiseUnetName
    OptionField.LOWNOISE_UNET -> hasLownoiseUnetName
    OptionField.VAE -> hasVaeName
    OptionField.AUDIO_VAE -> hasAudioVaeName
    OptionField.CLIP -> hasClipName
    OptionField.CLIP1 -> hasClipName1
    OptionField.CLIP2 -> hasClipName2
    OptionField.CLIP3 -> hasClipName3
    OptionField.CLIP4 -> hasClipName4
    OptionField.TEXT_ENCODER -> hasTextEncoderName
    OptionField.LATENT_UPSCALE_MODEL -> hasLatentUpscaleModel
}

/** Rows from templates, keeping only present fields, then pairing adjacent single half fields. */
private fun rowsFor(templateRows: List<List<OptionField>>, present: (OptionField) -> Boolean): List<List<OptionField>> {
    val rows = templateRows.map { row -> row.filter(present) }.filter { it.isNotEmpty() }
    val merged = mutableListOf<List<OptionField>>()
    for (row in rows) {
        val last = merged.lastOrNull()
        if (row.size == 1 && row[0].half && last != null && last.size == 1 && last[0].half) {
            merged[merged.lastIndex] = last + row
        } else {
            merged += row
        }
    }
    return merged
}

/**
 * Lay out the options sheet for a workflow's capabilities. Only fields the workflow has appear, and a section exists
 * only when it has at least one; nothing depends on the screen. (Reference images are set on the source image, not here.)
 */
fun optionsLayout(capabilities: WorkflowCapabilities): OptionsLayout {
    val present: (OptionField) -> Boolean = { capabilities.has(it) }
    val workflow = OptionsSectionLayout(OptionsSection.WORKFLOW, rowsFor(templates.getValue(OptionsSection.WORKFLOW), present))
    val others = OptionsSection.entries.drop(1)
    val fieldCount = others.sumOf { section -> templates.getValue(section).flatten().count(present) }
    val sectioned = fieldCount > FLAT_OPTIONS_MAX_FIELDS

    val rest = if (sectioned) {
        others.map { OptionsSectionLayout(it, rowsFor(templates.getValue(it), present)) }.filter { it.rows.isNotEmpty() }
    } else {
        // One flat list: sections in order, pairs may form across them (e.g. steps with CFG)
        val rows = rowsFor(others.flatMap { templates.getValue(it) }, present)
        listOfNotNull(OptionsSectionLayout(null, rows).takeIf { rows.isNotEmpty() })
    }
    return OptionsLayout(sectioned, fieldCount, listOf(workflow) + rest)
}
