package sh.hnet.comfychair.queue

import org.json.JSONObject
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.FieldMappingAnalyzer
import sh.hnet.comfychair.workflow.NodeTypeRegistry
import sh.hnet.comfychair.workflow.WorkflowParser

/**
 * The prompts of jobs in the server queue, read from their workflows for the queue sheet (other clients' jobs: the
 * server shares them with every client, as its history; our own have their description). Found as the import finds a
 * workflow's positive prompt; kept in memory only, by job (a job's prompt doesn't change), never logged.
 */
internal object JobPrompts {

    private const val MAX_KEPT = 200

    // Job id -> prompt ("" for none found)
    private val cache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_KEPT
    })

    /** The positive prompt of job [promptId] with [workflow] (API format); null when none is found. */
    fun of(promptId: String, workflow: JSONObject?, registry: NodeTypeRegistry = ConnectionManager.nodeTypeRegistry): String? {
        cache[promptId]?.let { return it.ifEmpty { null } }
        val prompt = workflow?.let { positivePrompt(it, registry) }.orEmpty()
        cache[promptId] = prompt
        return prompt.ifEmpty { null }
    }

    private fun positivePrompt(workflow: JSONObject, registry: NodeTypeRegistry): String? = try {
        val graph = WorkflowParser(logDetails = false).parse(workflow.toString())
        FieldMappingAnalyzer.createPromptFieldMappings(graph, registry)["positive_text"].orEmpty()
            .firstNotNullOfOrNull { (it.currentValue as? String)?.trim()?.takeIf(String::isNotEmpty) }
    } catch (_: Exception) {
        null
    }
}
