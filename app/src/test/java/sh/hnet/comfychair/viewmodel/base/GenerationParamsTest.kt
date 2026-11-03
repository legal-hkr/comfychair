package sh.hnet.comfychair.viewmodel.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.connection.ModelCache
import sh.hnet.comfychair.model.WorkflowDefaults
import sh.hnet.comfychair.model.WorkflowValues

class GenerationParamsTest {

    private val cache = ModelCache(
        checkpoints = listOf("sdxl.safetensors"),
        unets = listOf("flux.gguf", "wan_high.safetensors"),
        vaes = listOf("ae.safetensors"),
        isLoaded = true
    )

    private fun load(
        saved: WorkflowValues?,
        defaults: WorkflowDefaults? = null,
        start: GenerationParams = GenerationParams(models = cache)
    ) = start.withWorkflow(
        workflowId = "wf",
        workflowName = "Workflow",
        placeholders = setOf("unet_name", "positive_prompt"),
        saved = saved,
        defaults = defaults,
        options = { null },
        fallback = GenerationParams()
    )

    @Test
    fun unetSavedInLegacyModelField_isRestored() {
        // Text/Image to Image kept the UNET in `model` before unetModel was used everywhere
        val params = load(WorkflowValues(model = "flux.gguf"))
        assertEquals("flux.gguf", params.selectedUnet)
    }

    @Test
    fun checkpointInModelField_isNotTakenAsUnet() {
        val params = load(WorkflowValues(model = "sdxl.safetensors"))
        assertEquals("sdxl.safetensors", params.selectedCheckpoint)
        assertEquals("", params.selectedUnet)
    }

    @Test
    fun unetModelField_winsOverLegacyModelField() {
        val params = load(WorkflowValues(model = "sdxl.safetensors", unetModel = "wan_high.safetensors"))
        assertEquals("wan_high.safetensors", params.selectedUnet)
        assertEquals("sdxl.safetensors", params.selectedCheckpoint)
    }

    @Test
    fun selectionUnknownBeforeModelListsLoad_isAppliedWhenTheyArrive() {
        val params = load(WorkflowValues(unetModel = "flux.gguf", vaeModel = "ae.safetensors"), start = GenerationParams())
        assertEquals("", params.selectedUnet)
        val loaded = params.withModelCache(cache)
        assertEquals("flux.gguf", loaded.selectedUnet)
        assertEquals("ae.safetensors", loaded.selectedVae)
        assertTrue(loaded.deferredSelections.isEmpty())
    }

    @Test
    fun emptyListsBeforeServerAnswers_doNotDropWaitingSelections() {
        // The model list observer may deliver the initial, not yet loaded cache after the workflow was loaded
        val params = load(WorkflowValues(unetModel = "flux.gguf"), start = GenerationParams())
            .withModelCache(ModelCache())
        assertEquals("flux.gguf", params.withModelCache(cache).selectedUnet)
    }

    @Test
    fun savingBeforeListsArrive_keepsWaitingSelections() {
        val params = load(WorkflowValues(unetModel = "flux.gguf", vaeModel = "ae.safetensors"), start = GenerationParams())
        val values = params.toWorkflowValues(null)
        assertEquals("flux.gguf", values.unetModel)
        assertEquals("ae.safetensors", values.vaeModel)
    }

    @Test
    fun savedValuesWinOverDefaultsOverFallback() {
        val params = load(
            WorkflowValues(steps = 8),
            WorkflowDefaults(steps = 30, cfg = 1.0f)
        )
        assertEquals("8", params.steps)
        assertEquals("1.0", params.cfg)
        assertEquals(GenerationParams().sampler, params.sampler)
    }

    @Test
    fun toWorkflowValues_keepsNodeEditsAndStoresUnetSeparately() {
        val params = load(WorkflowValues(unetModel = "flux.gguf"))
        val values = params.toWorkflowValues(WorkflowValues(nodeAttributeEdits = "{\"5\":{}}"))
        assertEquals("flux.gguf", values.unetModel)
        assertEquals("{\"5\":{}}", values.nodeAttributeEdits)
        assertNull(values.loraChain)
    }

    @Test
    fun hasErrors_reflectsAnyFieldError() {
        assertEquals(false, GenerationParams().hasErrors)
        assertEquals(true, GenerationParams(fpsError = "x").hasErrors)
    }
}
