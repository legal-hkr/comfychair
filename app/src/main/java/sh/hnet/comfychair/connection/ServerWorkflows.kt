package sh.hnet.comfychair.connection

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.util.DebugLogger

/**
 * A workflow saved in ComfyUI's web UI (Workflow › Save), kept in the server's user data.
 *
 * @param path Relative to the user's workflows folder, e.g. `video/wan22_i2v.json`
 * @param modified Last change, in milliseconds since the epoch (0 when the server didn't say)
 */
data class ServerWorkflow(val path: String, val size: Long, val modified: Long) {
    /** The file name without `.json`. */
    val name: String get() = path.substringAfterLast('/').removeSuffix(".json")

    /** The folder path without the file name, empty at the root. */
    val folder: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")

    val isTooLarge: Boolean get() = size > ServerWorkflows.MAX_BYTES
}

/**
 * The server's saved workflows: listing them and downloading one to import. Paths and names are the user's: never
 * logged.
 */
object ServerWorkflows {
    private const val TAG = "ServerWorkflows"

    /** The largest workflow file imported (as the file import reads it whole into memory). */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** The listing is small even with hundreds of workflows; its own cap keeps a broken answer from filling memory. */
    private const val MAX_LIST_BYTES = 4L * 1024 * 1024

    private const val FOLDER = "workflows"

    sealed interface ListResult {
        /** Newest first. */
        data class Ok(val workflows: List<ServerWorkflow>) : ListResult
        /** The server has user accounts (`--multi-user`), which the app doesn't sign into yet. */
        data object MultiUser : ListResult
        data class Failed(val details: String?) : ListResult
    }

    sealed interface DownloadResult {
        data class Ok(val json: String) : DownloadResult
        data object TooLarge : DownloadResult
        data class NotWorkflow(val details: String?) : DownloadResult
        data class Failed(val details: String?) : DownloadResult
    }

    suspend fun list(client: ComfyUIClient): ListResult {
        when (val users = client.getText("/users", MAX_LIST_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> if (isMultiUser(users.text)) return ListResult.MultiUser
            // An older ComfyUI without user accounts at all: one user
            is ComfyUIClient.TextResult.HttpError -> if (users.code != 404) return ListResult.Failed(describe(users))
            else -> return ListResult.Failed(describe(users))
        }
        val query = "/userdata?dir=$FOLDER&recurse=true&split=false&full_info=true"
        return when (val answer = client.getText(query, MAX_LIST_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> try {
                ListResult.Ok(parseList(answer.text))
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Unexpected workflow list")
                ListResult.Failed(e.message)
            }
            // No workflows folder yet: nothing saved
            is ComfyUIClient.TextResult.HttpError -> if (answer.code == 404) ListResult.Ok(emptyList()) else ListResult.Failed(describe(answer))
            else -> ListResult.Failed(describe(answer))
        }
    }

    suspend fun download(client: ComfyUIClient, workflow: ServerWorkflow): DownloadResult {
        if (!isSafePath(workflow.path)) return DownloadResult.NotWorkflow(null)
        // One path segment for the route, slashes encoded, as ComfyUI's frontend asks for it
        val encoded = Uri.encode("$FOLDER/${workflow.path}")
        return when (val answer = client.getText("/userdata/$encoded", MAX_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> {
                val problem = workflowProblem(answer.text)
                if (problem == null) DownloadResult.Ok(answer.text) else DownloadResult.NotWorkflow(problem)
            }
            ComfyUIClient.TextResult.TooLarge -> DownloadResult.TooLarge
            else -> DownloadResult.Failed(describe(answer))
        }
    }

    /** `/users` names the accounts only on a multi-user server. */
    internal fun isMultiUser(usersJson: String): Boolean =
        runCatching { JSONObject(usersJson).has("users") }.getOrDefault(false)

    /** The listing's `.json` files with safe paths, newest first. */
    internal fun parseList(listJson: String): List<ServerWorkflow> {
        val array = JSONArray(listJson)
        return (0 until array.length())
            .mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val path = item.optString("path").replace('\\', '/')
                if (!path.endsWith(".json", ignoreCase = true) || !isSafePath(path)) return@mapNotNull null
                ServerWorkflow(path, item.optLong("size", 0), item.optDouble("modified", 0.0).toLong())
            }
            .sortedByDescending { it.modified }
    }

    /** A relative path inside the workflows folder: no absolute paths, no `..` or empty segments. */
    internal fun isSafePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Null for a workflow the file import accepts (UI format with nodes, or API format); otherwise why not. */
    internal fun workflowProblem(text: String): String? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return e.message
        }
        if (json.optJSONArray("nodes") != null) return null
        val apiFormat = json.length() > 0 && json.keys().asSequence().all { key -> json.optJSONObject(key)?.has("class_type") == true }
        return if (apiFormat) null else "No nodes"
    }

    private fun describe(result: ComfyUIClient.TextResult): String? = when (result) {
        is ComfyUIClient.TextResult.HttpError -> "HTTP ${result.code}"
        is ComfyUIClient.TextResult.Failed -> result.message
        ComfyUIClient.TextResult.TooLarge -> null
        is ComfyUIClient.TextResult.Ok -> null
    }
}
