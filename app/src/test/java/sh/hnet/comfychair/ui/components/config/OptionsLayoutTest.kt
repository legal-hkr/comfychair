package sh.hnet.comfychair.ui.components.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.ui.components.config.OptionField.*

class OptionsLayoutTest {

    @Test
    fun workflowWithOneFieldIsAFlatListAfterTheWorkflowRow() {
        val layout = optionsLayout(WorkflowCapabilities(hasSeed = true))
        assertFalse(layout.sectioned)
        assertEquals(1, layout.fieldCount)
        assertEquals(listOf(listOf(WORKFLOW)), layout.sections[0].rows)
        assertEquals(listOf(listOf(SEED)), layout.sections[1].rows)
        assertEquals(null, layout.sections[1].section)
    }

    @Test
    fun smallWorkflowPairsFieldsAcrossSections() {
        // Seed, steps, CFG, width, height: five fields, steps pairs with CFG from Sampling
        val caps = WorkflowCapabilities(hasSeed = true, hasSteps = true, hasCfg = true, hasWidth = true, hasHeight = true)
        val layout = optionsLayout(caps)
        assertFalse(layout.sectioned)
        assertEquals(5, layout.fieldCount)
        assertEquals(listOf(listOf(SEED), listOf(WIDTH, HEIGHT), listOf(STEPS, CFG)), layout.sections[1].rows)
    }

    @Test
    fun largeWorkflowGetsSectionsInOrderWithPairsWithinThem() {
        // A two-stage video workflow
        val caps = WorkflowCapabilities(
            hasSeed = true, hasLength = true, hasFrameRate = true, hasSteps = true, hasMegapixels = true,
            hasCfg = true, hasSamplerName = true, hasScheduler = true,
            hasHighnoiseUnetName = true, hasLownoiseUnetName = true, hasVaeName = true, hasClipName = true,
            hasHighnoiseLora = true, hasLownoiseLora = true
        )
        val layout = optionsLayout(caps)
        assertTrue(layout.sectioned)
        assertEquals(14, layout.fieldCount)
        assertEquals(
            listOf(OptionsSection.WORKFLOW, OptionsSection.QUICK, OptionsSection.SAMPLING, OptionsSection.MODELS, OptionsSection.LORA),
            layout.sections.map { it.section }
        )
        assertEquals(listOf(listOf(SEED), listOf(LENGTH, FPS), listOf(STEPS, MEGAPIXELS)), layout.sections[1].rows)
        assertEquals(listOf(listOf(SAMPLER), listOf(SCHEDULER), listOf(CFG)), layout.sections[2].rows)
        assertEquals(listOf(listOf(HIGHNOISE_LORA_CHAIN), listOf(LOWNOISE_LORA_CHAIN)), layout.sections[4].rows)
    }

    @Test
    fun emptySectionsAreLeftOut() {
        val caps = WorkflowCapabilities(
            hasSeed = true, hasSteps = true, hasCfg = true, hasSamplerName = true, hasScheduler = true,
            hasWidth = true, hasHeight = true, hasDenoise = true, hasBatchSize = true
        )
        val layout = optionsLayout(caps)
        assertTrue(layout.sectioned)
        assertEquals(listOf(OptionsSection.WORKFLOW, OptionsSection.QUICK, OptionsSection.SAMPLING), layout.sections.map { it.section })
    }

    @Test
    fun eightFieldsStayFlatNineGetSections() {
        val eight = WorkflowCapabilities(
            hasSeed = true, hasSteps = true, hasCfg = true, hasWidth = true, hasHeight = true,
            hasSamplerName = true, hasScheduler = true, hasDenoise = true
        )
        assertFalse(optionsLayout(eight).sectioned)
        assertTrue(optionsLayout(eight.copy(hasBatchSize = true)).sectioned)
    }

    @Test
    fun referenceImagesJoinTheWorkflowSectionWithoutCounting() {
        val layout = optionsLayout(WorkflowCapabilities(hasSeed = true), setOf(REFERENCE_IMAGES))
        assertEquals(listOf(listOf(WORKFLOW), listOf(REFERENCE_IMAGES)), layout.sections[0].rows)
        assertEquals(1, layout.fieldCount)
    }

    @Test
    fun aLoneHalfFieldStaysAlone() {
        val layout = optionsLayout(WorkflowCapabilities(hasSeed = true, hasDenoise = true))
        assertEquals(listOf(listOf(SEED), listOf(DENOISE)), layout.sections[1].rows)
    }
}
