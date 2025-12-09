package sh.hnet.comfychair

import android.content.Context
import org.json.JSONObject
import java.io.InputStream

/**
 * WorkflowManager - Manages ComfyUI workflow JSON files
 *
 * This class handles:
 * - Loading workflow JSON files from res/raw
 * - Extracting workflow names and descriptions
 * - Replacing template variables with actual values
 * - Providing workflow data for API calls
 */
class WorkflowManager(private val context: Context) {

    // Workflow data class
    data class Workflow(
        val id: String,
        val name: String,
        val description: String,
        val jsonContent: String
    )

    // Available workflows loaded from res/raw
    private val workflows = mutableListOf<Workflow>()

    init {
        loadWorkflows()
    }

    /**
     * Load all workflow JSON files from res/raw
     * Files should be named: tti_checkpoint_*.json, tti_unet_*.json, iip_checkpoint_*.json, iip_unet_*.json, or ttv_unet_*.json
     */
    private fun loadWorkflows() {
        // List of workflow resource IDs
        val workflowResources = listOf(
            R.raw.tti_checkpoint_default,
            R.raw.tti_unet_zimage,
            R.raw.iip_checkpoint_default,
            R.raw.iip_unet_zimage,
            R.raw.ttv_unet_wan22_lightx2v
        )

        for (resId in workflowResources) {
            try {
                val inputStream: InputStream = context.resources.openRawResource(resId)
                val jsonContent = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonContent)

                // Extract workflow metadata
                val name = jsonObject.optString("name", "Unnamed Workflow")
                val description = jsonObject.optString("description", "")
                val id = context.resources.getResourceEntryName(resId)

                workflows.add(Workflow(id, name, description, jsonContent))
            } catch (e: Exception) {
                println("Error loading workflow from resource $resId: ${e.message}")
            }
        }
    }

    /**
     * Get list of workflow names for dropdown
     */
    fun getWorkflowNames(): List<String> {
        return workflows.map { it.name }
    }

    /**
     * Get list of text-to-image checkpoint workflow names for dropdown
     */
    fun getCheckpointWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("tti_checkpoint_") }.map { it.name }
    }

    /**
     * Get list of text-to-image UNET workflow names for dropdown
     */
    fun getUNETWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("tti_unet_") }.map { it.name }
    }

    /**
     * Get list of inpainting checkpoint workflow names for dropdown
     */
    fun getInpaintingCheckpointWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("iip_checkpoint_") }.map { it.name }
    }

    /**
     * Get list of inpainting UNET workflow names for dropdown
     */
    fun getInpaintingUNETWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("iip_unet_") }.map { it.name }
    }

    /**
     * Get list of text-to-video UNET workflow names for dropdown
     */
    fun getVideoUNETWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("ttv_unet_") }.map { it.name }
    }

    /**
     * Get workflow by name
     */
    fun getWorkflowByName(name: String): Workflow? {
        return workflows.find { it.name == name }
    }

    /**
     * Check if a workflow is a text-to-image checkpoint workflow
     */
    fun isCheckpointWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.id?.startsWith("tti_checkpoint_") == true
    }

    /**
     * Check if a workflow is a text-to-image UNET workflow
     */
    fun isUNETWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.id?.startsWith("tti_unet_") == true
    }

    /**
     * Check if a workflow is an inpainting checkpoint workflow
     */
    fun isInpaintingCheckpointWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.id?.startsWith("iip_checkpoint_") == true
    }

    /**
     * Check if a workflow is an inpainting UNET workflow
     */
    fun isInpaintingUNETWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.id?.startsWith("iip_unet_") == true
    }

    /**
     * Escape special characters in a string for safe JSON insertion
     * This ensures prompts with special characters don't break the workflow JSON
     *
     * @param input The string to escape
     * @return The escaped string safe for JSON
     */
    private fun escapeForJson(input: String): String {
        return input
            .replace("\\", "\\\\")  // Backslash must be first
            .replace("\"", "\\\"")  // Double quote
            .replace("\n", "\\n")   // Newline
            .replace("\r", "\\r")   // Carriage return
            .replace("\t", "\\t")   // Tab
            .replace("\b", "\\b")   // Backspace
            .replace("\u000C", "\\f") // Form feed
    }

    /**
     * Prepare workflow JSON with actual parameter values
     * Replaces template variables like {{prompt}}, {{width}}, etc.
     *
     * @param workflowName The name of the workflow to use
     * @param prompt User's text prompt
     * @param checkpoint Selected checkpoint model (for checkpoint workflows)
     * @param unet Selected UNET model (for UNET workflows)
     * @param vae Selected VAE model (for UNET workflows)
     * @param clip Selected CLIP model (for UNET workflows)
     * @param width Image width
     * @param height Image height
     * @param steps Number of generation steps
     * @return JSON string ready to send to ComfyUI API
     */
    fun prepareWorkflow(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): String? {
        val workflow = getWorkflowByName(workflowName) ?: return null

        // Generate a random seed for each image generation
        val randomSeed = (0..999999999999).random()

        // Escape special characters in prompt for safe JSON insertion
        val escapedPrompt = escapeForJson(prompt)

        // Replace template variables with actual values
        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{checkpoint}}", checkpoint)
        processedJson = processedJson.replace("{{unet_name}}", unet)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        // Replace seed with random value (workflows use 0 as placeholder)
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        return processedJson
    }

    /**
     * Prepare inpainting workflow JSON with actual parameter values
     * Replaces template variables including image filename and megapixels
     *
     * @param workflowName The name of the workflow to use
     * @param prompt User's text prompt
     * @param checkpoint Selected checkpoint model (for checkpoint workflows)
     * @param unet Selected UNET model (for UNET workflows)
     * @param vae Selected VAE model (for UNET workflows)
     * @param clip Selected CLIP model (for UNET workflows)
     * @param megapixels Target megapixels for image scaling (for checkpoint inpainting)
     * @param steps Number of generation steps
     * @param imageFilename The uploaded image filename in ComfyUI input folder
     * @return JSON string ready to send to ComfyUI API
     */
    fun prepareInpaintingWorkflow(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        megapixels: Float = 1.0f,
        steps: Int,
        imageFilename: String
    ): String? {
        val workflow = getWorkflowByName(workflowName) ?: return null

        // Generate a random seed for each image generation
        val randomSeed = (0..999999999999).random()

        // Escape special characters in prompt for safe JSON insertion
        val escapedPrompt = escapeForJson(prompt)

        // Replace template variables with actual values
        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{checkpoint}}", checkpoint)
        processedJson = processedJson.replace("{{unet_name}}", unet)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
        processedJson = processedJson.replace("{{megapixels}}", megapixels.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        // Replace uploaded image placeholder with actual filename
        processedJson = processedJson.replace("uploaded_image.png [input]", "$imageFilename [input]")
        // Replace seed with random value (workflows use 42 as placeholder in inpainting)
        processedJson = processedJson.replace("\"seed\": 42", "\"seed\": $randomSeed")
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

        return processedJson
    }

    /**
     * Prepare video workflow JSON with actual parameter values
     * Replaces template variables for video generation
     *
     * @param workflowName The name of the workflow to use
     * @param prompt User's text prompt
     * @param highnoiseUnet High noise UNET model
     * @param lownoiseUnet Low noise UNET model
     * @param highnoiseLora High noise LoRA model
     * @param lownoiseLora Low noise LoRA model
     * @param vae Selected VAE model
     * @param clip Selected CLIP model
     * @param width Video width
     * @param height Video height
     * @param length Video length in frames
     * @param fps Frames per second (1-120)
     * @return JSON string ready to send to ComfyUI API
     */
    fun prepareVideoWorkflow(
        workflowName: String,
        prompt: String,
        highnoiseUnet: String,
        lownoiseUnet: String,
        highnoiseLora: String,
        lownoiseLora: String,
        vae: String,
        clip: String,
        width: Int,
        height: Int,
        length: Int,
        fps: Int = 16
    ): String? {
        val workflow = getWorkflowByName(workflowName) ?: return null

        // Generate a random seed for each video generation
        val randomSeed = (0..999999999999).random()

        // Escape special characters in prompt for safe JSON insertion
        val escapedPrompt = escapeForJson(prompt)

        // Replace template variables with actual values
        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", escapedPrompt)
        processedJson = processedJson.replace("{{highnoise_unet_name}}", highnoiseUnet)
        processedJson = processedJson.replace("{{lownoise_unet_name}}", lownoiseUnet)
        processedJson = processedJson.replace("{{highnoise_lora_name}}", highnoiseLora)
        processedJson = processedJson.replace("{{lownoise_lora_name}}", lownoiseLora)
        processedJson = processedJson.replace("{{vae_name}}", vae)
        processedJson = processedJson.replace("{{clip_name}}", clip)
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{length}}", length.toString())
        processedJson = processedJson.replace("{{fps}}", fps.toString())
        // Replace seed with random value (workflows use 42 as placeholder)
        processedJson = processedJson.replace("\"noise_seed\": 42", "\"noise_seed\": $randomSeed")
        processedJson = processedJson.replace("\"noise_seed\": 0", "\"noise_seed\": $randomSeed")

        return processedJson
    }

    /**
     * Get workflow JSON nodes (without metadata)
     * Returns just the "nodes" section needed for ComfyUI API
     */
    fun getWorkflowNodes(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        unet: String = "",
        vae: String = "",
        clip: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): JSONObject? {
        val processedJson = prepareWorkflow(workflowName, prompt, checkpoint, unet, vae, clip, width, height, steps)
            ?: return null

        val jsonObject = JSONObject(processedJson)
        return jsonObject.optJSONObject("nodes")
    }
}
