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
     * Files should be named: checkpoint_*.json or diffusers_*.json
     */
    private fun loadWorkflows() {
        // List of workflow resource IDs
        val workflowResources = listOf(
            R.raw.checkpoint_default,
            R.raw.diffusers_zimage
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
     * Get list of checkpoint workflow names for dropdown
     */
    fun getCheckpointWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("checkpoint_") }.map { it.name }
    }

    /**
     * Get list of diffusers workflow names for dropdown
     */
    fun getDiffusersWorkflowNames(): List<String> {
        return workflows.filter { it.id.startsWith("diffusers_") }.map { it.name }
    }

    /**
     * Get workflow by name
     */
    fun getWorkflowByName(name: String): Workflow? {
        return workflows.find { it.name == name }
    }

    /**
     * Check if a workflow is a checkpoint workflow
     */
    fun isCheckpointWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.id?.startsWith("checkpoint_") == true
    }

    /**
     * Check if a workflow is a diffusers workflow
     */
    fun isDiffusersWorkflow(workflowName: String): Boolean {
        val workflow = getWorkflowByName(workflowName)
        return workflow?.id?.startsWith("diffusers_") == true
    }

    /**
     * Prepare workflow JSON with actual parameter values
     * Replaces template variables like {{prompt}}, {{width}}, etc.
     *
     * @param workflowName The name of the workflow to use
     * @param prompt User's text prompt
     * @param checkpoint Selected checkpoint model (for checkpoint workflows)
     * @param diffuser Selected diffuser model (for diffusers workflows)
     * @param width Image width
     * @param height Image height
     * @param steps Number of generation steps
     * @return JSON string ready to send to ComfyUI API
     */
    fun prepareWorkflow(
        workflowName: String,
        prompt: String,
        checkpoint: String = "",
        diffuser: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): String? {
        val workflow = getWorkflowByName(workflowName) ?: return null

        // Generate a random seed for each image generation
        val randomSeed = (0..999999999999).random()

        // Replace template variables with actual values
        var processedJson = workflow.jsonContent
        processedJson = processedJson.replace("{{prompt}}", prompt)
        processedJson = processedJson.replace("{{checkpoint}}", checkpoint)
        processedJson = processedJson.replace("{{diffuser}}", diffuser)
        processedJson = processedJson.replace("{{width}}", width.toString())
        processedJson = processedJson.replace("{{height}}", height.toString())
        processedJson = processedJson.replace("{{steps}}", steps.toString())
        // Replace seed with random value (workflows use 0 as placeholder)
        processedJson = processedJson.replace("\"seed\": 0", "\"seed\": $randomSeed")

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
        diffuser: String = "",
        width: Int,
        height: Int,
        steps: Int
    ): JSONObject? {
        val processedJson = prepareWorkflow(workflowName, prompt, checkpoint, diffuser, width, height, steps)
            ?: return null

        val jsonObject = JSONObject(processedJson)
        return jsonObject.optJSONObject("nodes")
    }
}
