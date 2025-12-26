package sh.hnet.comfychair.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R
import sh.hnet.comfychair.WorkflowType

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
 * Uses string resource IDs for internationalization support.
 */
object FieldDisplayRegistry {

    // Map placeholder/field key to (displayNameResId, descriptionResId)
    private val FIELD_RES_IDS: Map<String, Pair<Int, Int>> = mapOf(
        // Prompt fields - map BOTH placeholder names AND internal keys
        "positive_prompt" to Pair(R.string.field_positive_prompt, R.string.field_desc_positive_prompt),
        "positive_text" to Pair(R.string.field_positive_prompt, R.string.field_desc_positive_prompt),
        "negative_prompt" to Pair(R.string.field_negative_prompt, R.string.field_desc_negative_prompt),
        "negative_text" to Pair(R.string.field_negative_prompt, R.string.field_desc_negative_prompt),
        // Model fields
        "ckpt_name" to Pair(R.string.label_checkpoint, R.string.field_desc_checkpoint),
        "unet_name" to Pair(R.string.field_unet_model, R.string.field_desc_unet),
        "vae_name" to Pair(R.string.label_vae, R.string.field_desc_vae),
        "clip_name" to Pair(R.string.label_clip, R.string.field_desc_clip),
        "clip_name1" to Pair(R.string.field_clip1, R.string.field_desc_clip1),
        "clip_name2" to Pair(R.string.field_clip2, R.string.field_desc_clip2),
        // Dimension fields
        "width" to Pair(R.string.label_width, R.string.field_desc_width),
        "height" to Pair(R.string.label_height, R.string.field_desc_height),
        "megapixels" to Pair(R.string.megapixels_label, R.string.field_desc_megapixels),
        // Sampling fields
        "steps" to Pair(R.string.label_steps, R.string.field_desc_steps),
        "cfg" to Pair(R.string.field_cfg_scale, R.string.field_desc_cfg),
        "sampler_name" to Pair(R.string.label_sampler, R.string.field_desc_sampler),
        "scheduler" to Pair(R.string.label_scheduler, R.string.field_desc_scheduler),
        // LoRA fields
        "lora_name" to Pair(R.string.label_lora, R.string.field_desc_lora),
        // High/Low noise model variants (for video workflows)
        "highnoise_unet_name" to Pair(R.string.highnoise_unet_label, R.string.field_desc_unet),
        "lownoise_unet_name" to Pair(R.string.lownoise_unet_label, R.string.field_desc_unet),
        "highnoise_lora_name" to Pair(R.string.highnoise_lora_label, R.string.field_desc_lora),
        "lownoise_lora_name" to Pair(R.string.lownoise_lora_label, R.string.field_desc_lora),
        // Video fields
        "length" to Pair(R.string.length_label, R.string.field_desc_length),
        "frame_rate" to Pair(R.string.field_frame_rate, R.string.field_desc_frame_rate),
        "fps" to Pair(R.string.field_frame_rate, R.string.field_desc_frame_rate),
        // Image fields
        "image" to Pair(R.string.field_input_image, R.string.field_desc_image),
        "image_filename" to Pair(R.string.field_input_image, R.string.field_desc_image)
    )

    /**
     * Get the string resource ID for a field's display name.
     * Returns null if no mapping exists.
     */
    fun getDisplayNameResId(fieldKey: String): Int? = FIELD_RES_IDS[fieldKey]?.first

    /**
     * Get the string resource ID for a field's description.
     * Returns null if no mapping exists.
     */
    fun getDescriptionResId(fieldKey: String): Int? = FIELD_RES_IDS[fieldKey]?.second

    /**
     * Get the display name for a field using Context.
     * Falls back to formatting the field key if no mapping exists.
     */
    fun getDisplayName(context: Context, fieldKey: String): String {
        val resId = getDisplayNameResId(fieldKey)
        return if (resId != null) {
            context.getString(resId)
        } else {
            // Fallback: convert "field_name" to "Field name"
            fieldKey.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Get the description for a field using Context.
     * Falls back to a generic description if no mapping exists.
     */
    fun getDescription(context: Context, fieldKey: String): String {
        val resId = getDescriptionResId(fieldKey)
        return if (resId != null) {
            context.getString(resId)
        } else {
            context.getString(R.string.field_desc_fallback, fieldKey)
        }
    }

    /**
     * Create a RequiredField with localized display name and description.
     */
    fun createRequiredField(context: Context, fieldKey: String): RequiredField {
        return RequiredField(
            fieldKey = fieldKey,
            displayName = getDisplayName(context, fieldKey),
            description = getDescription(context, fieldKey)
        )
    }
}
