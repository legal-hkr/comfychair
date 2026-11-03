package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * One file produced by a generation job, as listed in the job's /history outputs.
 *
 * @param type "output" for saved files, "temp" for preview files (PreviewImage and similar)
 * @param durationMs Video duration if the server reported it (SaveVideo metadata), otherwise null
 */
data class GenerationOutput(
    val promptId: String,
    val nodeId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val durationMs: Long? = null
) {
    /** Stable identifier of the file on the server, e.g. for UI keys and thumbnail caching. */
    val id: String get() = "$type/$subfolder/$filename"
}

/**
 * Single place that reads the result files of a job from its /history "outputs" object,
 * used by all generation screens, background fetching and the gallery.
 */
object GenerationOutputs {
    // Arrays holding files in a node output: "images" (SaveImage, PreviewImage and also SaveVideo),
    // "gifs" (VideoHelperSuite), "videos" (some custom nodes)
    private val MEDIA_ARRAYS = listOf("images", "gifs", "videos")

    private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".gif", ".avi", ".mov", ".mkv")

    /**
     * Returns the files of a job in server order (output node, then index within the node).
     *
     * @param outputs The "outputs" object of a job in /history
     * @param promptId ID of the job the outputs belong to
     * @param preferSaved If true, temporary previews are dropped as long as the job saved at
     *        least one file, so intermediate PreviewImage results don't show up as results
     */
    fun parse(outputs: JSONObject?, promptId: String, preferSaved: Boolean = true): List<GenerationOutput> {
        if (outputs == null) return emptyList()

        val files = mutableListOf<GenerationOutput>()
        val seen = mutableSetOf<Triple<String, String, String>>()

        for (nodeId in outputs.keys()) {
            val nodeOutput = outputs.optJSONObject(nodeId) ?: continue
            for (arrayKey in MEDIA_ARRAYS) {
                val array = nodeOutput.optJSONArray(arrayKey) ?: continue
                for (i in 0 until array.length()) {
                    val file = array.optJSONObject(i) ?: continue
                    val filename = file.optString("filename", "")
                    if (filename.isEmpty()) continue
                    val subfolder = file.optString("subfolder", "")
                    val type = file.optString("type", "output")
                    if (!seen.add(Triple(filename, subfolder, type))) continue

                    // SaveVideo reports its files in "images", marked with metadata.kind = "video",
                    // with the duration in seconds
                    val metadata = file.optJSONObject("metadata")
                    val isVideo = metadata?.optString("kind") == "video" ||
                            VIDEO_EXTENSIONS.any { filename.lowercase().endsWith(it) }
                    val durationMs = metadata?.optDouble("duration")
                        ?.takeIf { isVideo && !it.isNaN() && it > 0 }
                        ?.let { (it * 1000).toLong() }
                    files.add(GenerationOutput(promptId, nodeId, filename, subfolder, type, isVideo, durationMs))
                }
            }
        }

        if (preferSaved && files.any { it.type == "output" }) {
            return files.filter { it.type == "output" }
        }
        return files
    }

    /** Serializes a result list (filenames only, no media). */
    fun toJson(outputs: List<GenerationOutput>): JSONArray = JSONArray().apply {
        outputs.forEach { output ->
            put(JSONObject().apply {
                put("promptId", output.promptId)
                put("nodeId", output.nodeId)
                put("filename", output.filename)
                put("subfolder", output.subfolder)
                put("type", output.type)
                put("isVideo", output.isVideo)
                output.durationMs?.let { put("durationMs", it) }
            })
        }
    }

    /** Reads a result list written by [toJson]; malformed entries are skipped. */
    fun fromJson(array: JSONArray?): List<GenerationOutput> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val json = array.optJSONObject(i) ?: return@mapNotNull null
            val filename = json.optString("filename").ifEmpty { return@mapNotNull null }
            GenerationOutput(
                promptId = json.optString("promptId"),
                nodeId = json.optString("nodeId"),
                filename = filename,
                subfolder = json.optString("subfolder"),
                type = json.optString("type", "output"),
                isVideo = json.optBoolean("isVideo"),
                durationMs = if (json.has("durationMs")) json.optLong("durationMs") else null
            )
        }
    }

    /**
     * Returns the files of job [promptId] from a /history response.
     */
    fun fromHistory(historyJson: JSONObject?, promptId: String, preferSaved: Boolean = true): List<GenerationOutput> =
        parse(historyJson?.optJSONObject(promptId)?.optJSONObject("outputs"), promptId, preferSaved)
}
