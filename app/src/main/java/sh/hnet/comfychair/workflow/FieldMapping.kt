package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.WorkflowType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a required field that needs to be mapped to a node in the workflow.
 */
data class RequiredField(
    val fieldKey: String,       // e.g., "text", "unet_name"
    val displayName: String,    // Human-readable name for UI
    val description: String     // Help text explaining what this field is for
)

/**
 * A candidate node that could fulfill a required field.
 */
data class FieldCandidate(
    val nodeId: String,
    val nodeName: String,       // Display title from _meta or class_type
    val classType: String,
    val inputKey: String,
    val currentValue: Any?
)

/**
 * Mapping state for a single required field.
 */
data class FieldMappingState(
    val field: RequiredField,
    val candidates: List<FieldCandidate>,
    val selectedCandidateIndex: Int = 0  // Default to first candidate
) {
    val selectedCandidate: FieldCandidate?
        get() = candidates.getOrNull(selectedCandidateIndex)

    val hasMultipleCandidates: Boolean
        get() = candidates.size > 1

    /** True if a valid candidate is selected */
    val isMapped: Boolean
        get() = selectedCandidate != null

    /** True if candidates exist but selection was cleared (node stolen by another field) */
    val needsRemapping: Boolean
        get() = candidates.isNotEmpty() && selectedCandidateIndex < 0
}

/**
 * Complete mapping state for a workflow being uploaded.
 */
data class WorkflowMappingState(
    val workflowType: WorkflowType,
    val fieldMappings: List<FieldMappingState>
) {
    val allFieldsMapped: Boolean
        get() = fieldMappings.all { it.isMapped }

    val unmappedFields: List<RequiredField>
        get() = fieldMappings.filter { !it.isMapped }.map { it.field }

    /**
     * Serialize to JSON for passing via Intent.
     */
    fun toJson(): String {
        val json = JSONObject().apply {
            put("workflowType", workflowType.name)
            put("fieldMappings", JSONArray().apply {
                fieldMappings.forEach { mapping ->
                    put(JSONObject().apply {
                        put("fieldKey", mapping.field.fieldKey)
                        put("displayName", mapping.field.displayName)
                        put("description", mapping.field.description)
                        put("selectedCandidateIndex", mapping.selectedCandidateIndex)
                        put("candidates", JSONArray().apply {
                            mapping.candidates.forEach { candidate ->
                                put(JSONObject().apply {
                                    put("nodeId", candidate.nodeId)
                                    put("nodeName", candidate.nodeName)
                                    put("classType", candidate.classType)
                                    put("inputKey", candidate.inputKey)
                                    put("currentValue", candidate.currentValue?.toString() ?: "")
                                })
                            }
                        })
                    })
                }
            })
        }
        return json.toString()
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(jsonString: String): WorkflowMappingState? {
            return try {
                val json = JSONObject(jsonString)
                val workflowType = WorkflowType.valueOf(json.getString("workflowType"))
                val mappingsArray = json.getJSONArray("fieldMappings")

                val fieldMappings = (0 until mappingsArray.length()).map { i ->
                    val mappingJson = mappingsArray.getJSONObject(i)
                    val field = RequiredField(
                        fieldKey = mappingJson.getString("fieldKey"),
                        displayName = mappingJson.getString("displayName"),
                        description = mappingJson.getString("description")
                    )

                    val candidatesArray = mappingJson.getJSONArray("candidates")
                    val candidates = (0 until candidatesArray.length()).map { j ->
                        val candidateJson = candidatesArray.getJSONObject(j)
                        FieldCandidate(
                            nodeId = candidateJson.getString("nodeId"),
                            nodeName = candidateJson.getString("nodeName"),
                            classType = candidateJson.getString("classType"),
                            inputKey = candidateJson.getString("inputKey"),
                            currentValue = candidateJson.optString("currentValue", "").ifEmpty { null }
                        )
                    }

                    FieldMappingState(
                        field = field,
                        candidates = candidates,
                        selectedCandidateIndex = mappingJson.getInt("selectedCandidateIndex")
                    )
                }

                WorkflowMappingState(workflowType, fieldMappings)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Pending workflow upload data to pass to the previewer.
 */
data class PendingWorkflowUpload(
    val jsonContent: String,
    val name: String,
    val description: String,
    val type: WorkflowType,
    val mappingState: WorkflowMappingState?
) {
    /**
     * Serialize to JSON for passing via Intent.
     */
    fun toJson(): String {
        val json = JSONObject().apply {
            put("jsonContent", jsonContent)
            put("name", name)
            put("description", description)
            put("type", type.name)
            put("mappingState", mappingState?.toJson() ?: "")
        }
        return json.toString()
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(jsonString: String): PendingWorkflowUpload? {
            return try {
                val json = JSONObject(jsonString)
                val mappingStateJson = json.optString("mappingState", "")
                val mappingState = if (mappingStateJson.isNotEmpty()) {
                    WorkflowMappingState.fromJson(mappingStateJson)
                } else {
                    null
                }

                PendingWorkflowUpload(
                    jsonContent = json.getString("jsonContent"),
                    name = json.getString("name"),
                    description = json.getString("description"),
                    type = WorkflowType.valueOf(json.getString("type")),
                    mappingState = mappingState
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Result of node availability validation against the server.
 */
sealed class NodeValidationResult {
    object Success : NodeValidationResult()
    data class MissingNodes(val missing: List<String>) : NodeValidationResult()
    data class ParseError(val message: String) : NodeValidationResult()
}

/**
 * Registry of display names and descriptions for required fields.
 */
object FieldDisplayRegistry {

    private val FIELD_INFO = mapOf(
        "positive_text" to Pair("Positive Prompt", "The text prompt for generation"),
        "negative_text" to Pair("Negative Prompt", "Text describing what to avoid in generation"),
        "ckpt_name" to Pair("Checkpoint", "The checkpoint model to use"),
        "unet_name" to Pair("UNET Model", "The diffusion model to use"),
        "vae_name" to Pair("VAE", "The VAE encoder/decoder"),
        "clip_name" to Pair("CLIP", "The CLIP text encoder"),
        "clip_name1" to Pair("CLIP 1 (T5)", "First CLIP text encoder (T5-XXL for Flux)"),
        "clip_name2" to Pair("CLIP 2 (L)", "Second CLIP text encoder (L for Flux)"),
        "width" to Pair("Width", "Output image width"),
        "height" to Pair("Height", "Output image height"),
        "steps" to Pair("Steps", "Number of sampling steps"),
        "cfg" to Pair("CFG Scale", "Classifier-free guidance scale"),
        "sampler_name" to Pair("Sampler", "Sampling algorithm"),
        "scheduler" to Pair("Scheduler", "Noise scheduling method"),
        "megapixels" to Pair("Megapixels", "Target size in megapixels"),
        "lora_name" to Pair("LoRA", "LoRA adapter model"),
        "length" to Pair("Length", "Video length in frames"),
        "frame_rate" to Pair("Frame Rate", "Video frames per second"),
        "image" to Pair("Input Image", "Source image for generation")
    )

    fun getDisplayName(fieldKey: String): String {
        return FIELD_INFO[fieldKey]?.first ?: fieldKey.replaceFirstChar { it.uppercase() }
    }

    fun getDescription(fieldKey: String): String {
        return FIELD_INFO[fieldKey]?.second ?: "Required field: $fieldKey"
    }

    fun createRequiredField(fieldKey: String): RequiredField {
        return RequiredField(
            fieldKey = fieldKey,
            displayName = getDisplayName(fieldKey),
            description = getDescription(fieldKey)
        )
    }
}
