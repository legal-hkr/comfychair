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
    fun choiceAfterLoadingWithListsThere_survivesListsDeliveredAgain() {
        // Lists already loaded: the saved model is applied at once and mustn't come back at the next delivery
        val params = load(WorkflowValues(unetModel = "flux.gguf"))
        assertEquals("flux.gguf", params.selectedUnet)
        val chosen = params.withSelection(ModelSlot.UNET, "wan_high.safetensors")
        assertEquals("wan_high.safetensors", chosen.withModelCache(cache).selectedUnet)
    }

    @Test
    fun savedModelMissingFromLoadedLists_isAppliedWhenItAppears() {
        val withoutFlux = cache.copy(unets = listOf("wan_high.safetensors"))
        val params = load(WorkflowValues(unetModel = "flux.gguf"), start = GenerationParams(models = withoutFlux))
        assertEquals("flux.gguf", params.withModelCache(cache).selectedUnet)
    }

    @Test
    fun choiceBeforeListsArrive_winsOverTheSavedModel() {
        val params = load(WorkflowValues(unetModel = "flux.gguf"), start = GenerationParams())
            .withSelection(ModelSlot.UNET, "wan_high.safetensors")
        assertEquals("wan_high.safetensors", params.withModelCache(cache).selectedUnet)
    }

    @Test
    fun savingBeforeListsArrive_keepsWaitingSelections() {
        val params = load(WorkflowValues(unetModel = "flux.gguf", vaeModel = "ae.safetensors"), start = GenerationParams())
        val values = params.toWorkflowValues(null)
        assertEquals("flux.gguf", values.unetModel)
        assertEquals("ae.safetensors", values.vaeModel)
    }

    // #63: the latent upscale model mapped onto UpscaleModelLoader, whose models aren't LatentUpscaleModelLoader's
    private val upscaleCache = cache.copy(latentUpscaleModels = listOf("4x-UltraSharpV2.safetensors"))
    private val upscalerOptions = listOf("2x-AnimeSharpV4_RCAN.safetensors", "4x-AnimeSharp.pth")
    private val upscalerNode: (String) -> List<String>? = { key -> upscalerOptions.takeIf { key == "latent_upscale_model" } }

    private fun loadUpscaler(start: GenerationParams, options: (String) -> List<String>?) = start.withWorkflow(
        workflowId = "wf",
        workflowName = "Workflow",
        placeholders = setOf("latent_upscale_model", "positive_prompt"),
        saved = WorkflowValues(latentUpscaleModel = "2x-AnimeSharpV4_RCAN.safetensors"),
        defaults = null,
        options = options,
        fallback = GenerationParams()
    )

    @Test
    fun savedModelInTheNodesOwnOptions_isKeptThoughTheSlotsListLacksIt() {
        val params = loadUpscaler(GenerationParams(models = upscaleCache), upscalerNode)
        assertEquals("2x-AnimeSharpV4_RCAN.safetensors", params.selectedLatentUpscaleModel)
        assertEquals("2x-AnimeSharpV4_RCAN.safetensors", params.withModelCache(upscaleCache).selectedLatentUpscaleModel)
    }

    @Test
    fun workflowLoadedBeforeNodeDefinitions_getsTheNodesOptionsWithTheLists() {
        // At start the workflow is loaded before /object_info has been read: no node options, no lists
        val params = loadUpscaler(GenerationParams(), options = { null })
        val loaded = params.withModelCache(upscaleCache, upscalerNode)
        assertEquals(upscalerOptions, loaded.filteredLatentUpscaleModels)
        assertEquals("2x-AnimeSharpV4_RCAN.safetensors", loaded.selectedLatentUpscaleModel)
        // Lists delivered again without a workflow's options keep the ones found
        assertEquals(upscalerOptions, loaded.withModelCache(upscaleCache).filteredLatentUpscaleModels)
    }

    @Test
    fun savedModelMissingFromTheNodesOwnOptions_isNotSelected() {
        val params = loadUpscaler(GenerationParams(models = upscaleCache)) { key ->
            listOf("4x-AnimeSharp.pth").takeIf { key == "latent_upscale_model" }
        }
        assertEquals("", params.selectedLatentUpscaleModel)
    }

    @Test
    fun dualModelSlots_eachTakeTheirOwnNodesOptions() {
        // Wan 2.2 with the high-noise model on a GGUF loader and the low-noise one on UNETLoader
        val gguf = listOf("wan_high.gguf", "wan_low.gguf")
        val plain = listOf("wan_low.safetensors")
        val params = GenerationParams(models = cache).withWorkflow(
            workflowId = "wf",
            workflowName = "Workflow",
            placeholders = setOf("highnoise_unet_name", "lownoise_unet_name", "positive_prompt"),
            saved = WorkflowValues(highnoiseUnetModel = "wan_high.gguf", lownoiseUnetModel = "wan_low.safetensors"),
            defaults = null,
            options = { key -> when (key) { "highnoise_unet_name" -> gguf; "lownoise_unet_name" -> plain; else -> null } },
            fallback = GenerationParams()
        ).withModelCache(cache)
        assertEquals(gguf, params.filteredHighnoiseUnets)
        assertEquals(plain, params.filteredLownoiseUnets)
        assertEquals("wan_high.gguf", params.selectedHighnoiseUnet)
        assertEquals("wan_low.safetensors", params.selectedLownoiseUnet)
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
