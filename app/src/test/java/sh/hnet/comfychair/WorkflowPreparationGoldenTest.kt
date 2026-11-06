package sh.hnet.comfychair

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.viewmodel.base.GenerationParams
import sh.hnet.comfychair.viewmodel.base.ModelSlot
import java.io.File

/**
 * What the app sends to ComfyUI for every built-in template, compared with recorded golden files
 * (src/test/resources/prepare-golden). Guards workflow preparation against unintended changes.
 *
 * Record new golden files after an intended change with:
 *   RECORD_GOLDEN=true ./gradlew --no-daemon :app:testDebugUnitTest --tests '*WorkflowPreparationGoldenTest*'
 * and review the diff of the golden files before committing.
 */
class WorkflowPreparationGoldenTest {

    private val rawDir = File("src/main/res/raw")
    private val goldenDir = File("src/test/resources/prepare-golden")
    private val record = System.getenv("RECORD_GOLDEN") == "true"

    // Fixed inputs: a prompt with characters that need escaping, every model slot, every parameter
    private val positive = "A \"red\" fox\nin snow \\ test"
    private val negative = "blurry, \"bad\""
    private val seed = 1234567890123L
    private val mainChain = listOf(LoraSelection("main_lora.safetensors", 0.8f))
    private val highChain = listOf(LoraSelection("high_lora.safetensors", 0.5f))
    private val lowChain = listOf(LoraSelection("low_lora.safetensors", 0.6f))

    @Before
    fun loadBuiltInTemplates() {
        @Suppress("UNCHECKED_CAST")
        val workflows = WorkflowManager::class.java.getDeclaredField("workflows")
            .apply { isAccessible = true }
            .get(WorkflowManager) as MutableList<WorkflowManager.Workflow>
        workflows.clear()
        rawDir.listFiles { f -> f.extension == "json" }!!.sorted().forEach { file ->
            val content = file.readText()
            val type = WorkflowManager.parseWorkflowType(file.nameWithoutExtension) ?: return@forEach
            workflows.add(
                WorkflowManager.Workflow(
                    id = file.nameWithoutExtension,
                    name = JSONObject(content).optString("name"),
                    description = "",
                    jsonContent = content,
                    type = type,
                    isBuiltIn = true
                )
            )
        }
    }

    @Test
    fun builtInTemplates_matchGoldenFiles() {
        val outputs = WorkflowManager.getAllWorkflows().flatMap { workflow ->
            prepare(workflow).map { (variant, json) ->
                val name = workflow.id + (variant?.let { "__$it" } ?: "")
                name to json
            }
        }
        assertTrue("no templates found in $rawDir", outputs.isNotEmpty())

        if (record) {
            goldenDir.mkdirs()
            goldenDir.listFiles()?.forEach { it.delete() }
            outputs.forEach { (name, json) ->
                File(goldenDir, "$name.json").writeText(canonical(json) + "\n")
            }
            return
        }

        val missing = outputs.map { it.first }.filterNot { File(goldenDir, "$it.json").exists() }
        assertTrue("golden files missing (record them first): $missing", missing.isEmpty())
        outputs.forEach { (name, json) ->
            assertEquals("prepared workflow differs from golden: $name",
                File(goldenDir, "$name.json").readText().trimEnd(), canonical(json))
        }
    }

    /** Every parameter and model slot set, as on a screen; video screens use their own height. */
    private fun params(height: String) = GenerationParams(
        positivePrompt = positive, negativePrompt = negative,
        selections = mapOf(
            ModelSlot.CHECKPOINT to "ckpt.safetensors", ModelSlot.UNET to "unet.safetensors",
            ModelSlot.LORA to "mandatory_lora.safetensors",
            ModelSlot.HIGHNOISE_UNET to "high_unet.safetensors", ModelSlot.LOWNOISE_UNET to "low_unet.safetensors",
            ModelSlot.HIGHNOISE_LORA to "high_lora_m.safetensors", ModelSlot.LOWNOISE_LORA to "low_lora_m.safetensors",
            ModelSlot.VAE to "vae.safetensors", ModelSlot.AUDIO_VAE to "audio_vae.safetensors",
            ModelSlot.CLIP to "clip.safetensors",
            ModelSlot.CLIP1 to "clip1.safetensors", ModelSlot.CLIP2 to "clip2.safetensors",
            ModelSlot.CLIP3 to "clip3.safetensors", ModelSlot.CLIP4 to "clip4.safetensors",
            ModelSlot.TEXT_ENCODER to "te.safetensors", ModelSlot.LATENT_UPSCALE_MODEL to "lu.safetensors"
        ),
        width = "832", height = height, megapixels = "1.5", length = "49", fps = "24",
        steps = "28", cfg = "4.5", sampler = "dpmpp_2m", scheduler = "karras",
        randomSeed = false, seed = seed.toString(), denoise = "0.75", batchSize = "2",
        upscaleMethod = "bislerp", scaleBy = "1.25", stopAtClipLayer = "-2",
        loraChain = mainChain, highnoiseLoraChain = highChain, lownoiseLoraChain = lowChain
    )

    /** The workflow JSON the app sends for a template; variants where inputs differ. */
    private fun prepare(workflow: WorkflowManager.Workflow): List<Pair<String?, String>> {
        val id = workflow.id
        val isVideo = workflow.type == WorkflowType.TTV || workflow.type == WorkflowType.ITV || workflow.type == WorkflowType.V2V
        val p = params(if (isVideo) "480" else "1216")
        val source = WorkflowManager.WorkflowImages(source = "source_upload.png")
        return when (workflow.type) {
            WorkflowType.TTI, WorkflowType.TTV -> listOf(null to WorkflowManager.prepareWorkflow(id, p)!!)
            WorkflowType.ITI_INPAINTING, WorkflowType.ITV -> listOf(null to WorkflowManager.prepareWorkflow(id, p, source)!!)
            WorkflowType.V2V -> listOf(
                null to WorkflowManager.prepareWorkflow(id, p, WorkflowManager.WorkflowImages(video = "source_upload.mp4"))!!
            )
            WorkflowType.ITI_EDITING -> listOf(
                "no_refs" to WorkflowManager.prepareWorkflow(id, p, source)!!,
                "two_refs" to WorkflowManager.prepareWorkflow(
                    id, p, source.copy(reference1 = "ref1_upload.png", reference2 = "ref2_upload.png")
                )!!
            )
        }
    }

    /** JSON with sorted keys and fixed formatting, so equal content compares equal. */
    private fun canonical(json: String): String = canonicalValue(JSONObject(json), "")

    private fun canonicalValue(value: Any?, indent: String): String = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().sorted().toList()
            if (keys.isEmpty()) "{}" else keys.joinToString(",\n", "{\n", "\n$indent}") { key ->
                "$indent  ${JSONObject.quote(key)}: ${canonicalValue(value.get(key), "$indent  ")}"
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(", ", "[", "]") { canonicalValue(value.get(it), indent) }
        is String -> JSONObject.quote(value)
        JSONObject.NULL, null -> "null"
        else -> value.toString()
    }
}
