package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.viewmodel.GalleryItem
import java.io.File

/**
 * Persistent local storage for gallery data.
 *
 * Stores gallery metadata and images at:
 *   /sdcard/Android/data/<package_name>/gallery/
 *     ├── metadata/
 *     │   ├── index.json          # Index of all gallery items (for fast loading)
 *     │   └── {promptId}.json     # Full history JSON per promptId
 *     └── images/
 *         └── {filename}          # Image/video files by original filename
 *
 * This storage survives server restarts and provides offline access.
 * No special permissions required (uses getExternalFilesDir, accessible since API 19).
 */
object LocalGalleryStorage {
    private const val TAG = "LocalGalleryStorage"
    private const val GALLERY_DIR = "gallery"
    private const val METADATA_DIR = "metadata"
    private const val IMAGES_DIR = "images"
    private const val INDEX_FILE = "index.json"
    private const val INDEX_VERSION = 1

    // ── Directory helpers ──────────────────────────────────────────────

    /**
     * Get the root gallery directory: /sdcard/Android/data/<package>/gallery/
     */
    private fun getGalleryDir(context: Context): File {
        return File(context.getExternalFilesDir(null), GALLERY_DIR).apply { mkdirs() }
    }

    /**
     * Get the metadata directory: .../gallery/metadata/
     */
    private fun getMetadataDir(context: Context): File {
        return File(getGalleryDir(context), METADATA_DIR).apply { mkdirs() }
    }

    /**
     * Get the images directory: .../gallery/images/
     */
    private fun getImagesDir(context: Context): File {
        return File(getGalleryDir(context), IMAGES_DIR).apply { mkdirs() }
    }

    // ── Index operations ───────────────────────────────────────────────

    /**
     * Load the gallery index file.
     * Returns a list of [GalleryItem] sorted newest-first, or empty list on failure.
     */
    fun loadIndex(context: Context): List<GalleryItem> {
        return try {
            val file = File(getMetadataDir(context), INDEX_FILE)
            if (!file.exists()) {
                DebugLogger.d(TAG, "No index file found")
                return emptyList()
            }

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != INDEX_VERSION) {
                DebugLogger.w(TAG, "Index version mismatch: got $version, expected $INDEX_VERSION")
                // Try to parse anyway for backward compatibility
            }

            val itemsArray = json.optJSONArray("items") ?: return emptyList()
            val items = mutableListOf<GalleryItem>()

            for (i in 0 until itemsArray.length()) {
                val itemJson = itemsArray.getJSONObject(i)
                items.add(GalleryItem(
                    promptId = itemJson.getString("promptId"),
                    filename = itemJson.getString("filename"),
                    subfolder = itemJson.optString("subfolder", ""),
                    type = itemJson.optString("type", "output"),
                    isVideo = itemJson.optBoolean("isVideo", false),
                    index = itemJson.optInt("index", i),
                    timestamp = itemJson.optLong("timestamp", 0L),
                    localCacheExists = checkImageExists(context, itemJson.getString("filename"))
                ))
            }

            // Sort newest-first by timestamp, falling back to index
            items.sortedWith(compareByDescending<GalleryItem> { it.timestamp }.thenByDescending { it.index })
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load index: ${e.message}")
            emptyList()
        }
    }

    /**
     * Save the gallery index file.
     */
    fun saveIndex(context: Context, items: List<GalleryItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val itemJson = JSONObject().apply {
                    put("promptId", item.promptId)
                    put("filename", item.filename)
                    put("subfolder", item.subfolder)
                    put("type", item.type)
                    put("isVideo", item.isVideo)
                    put("index", item.index)
                    put("timestamp", item.timestamp)
                }
                jsonArray.put(itemJson)
            }

            val indexJson = JSONObject().apply {
                put("version", INDEX_VERSION)
                put("lastSyncTimestamp", System.currentTimeMillis())
                put("items", jsonArray)
            }

            val file = File(getMetadataDir(context), INDEX_FILE)
            file.writeText(indexJson.toString())
            DebugLogger.d(TAG, "Index saved: ${items.size} items")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save index: ${e.message}")
        }
    }

    /**
     * Get the set of all prompt IDs currently in the local index.
     */
    fun getLocalPromptIds(context: Context): Set<String> {
        return try {
            val file = File(getMetadataDir(context), INDEX_FILE)
            if (!file.exists()) return emptySet()

            val json = JSONObject(file.readText())
            val itemsArray = json.optJSONArray("items") ?: return emptySet()
            val ids = mutableSetOf<String>()
            for (i in 0 until itemsArray.length()) {
                ids.add(itemsArray.getJSONObject(i).getString("promptId"))
            }
            ids
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to get local prompt IDs: ${e.message}")
            emptySet()
        }
    }

    // ── Individual metadata operations ─────────────────────────────────

    /**
     * Save full history JSON for a single prompt ID.
     */
    fun saveHistory(context: Context, promptId: String, historyJson: JSONObject) {
        try {
            val file = getMetadataFile(context, promptId)
            file.writeText(historyJson.toString())
            DebugLogger.d(TAG, "History saved for prompt: $promptId (${historyJson.length()})")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save history for $promptId: ${e.message}")
        }
    }

    /**
     * Load full history JSON for a single prompt ID.
     */
    fun loadHistory(context: Context, promptId: String): JSONObject? {
        return try {
            val file = getMetadataFile(context, promptId)
            if (file.exists()) {
                JSONObject(file.readText())
            } else null
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load history for $promptId: ${e.message}")
            null
        }
    }

    /**
     * Delete metadata file for a single prompt ID.
     */
    fun deleteHistory(context: Context, promptId: String) {
        try {
            val file = getMetadataFile(context, promptId)
            if (file.exists()) {
                file.delete()
                DebugLogger.d(TAG, "History deleted for prompt: $promptId")
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to delete history for $promptId: ${e.message}")
        }
    }

    // ── Image operations ───────────────────────────────────────────────

    /**
     * Save image/video bytes to the local images directory.
     */
    fun saveImage(context: Context, filename: String, bytes: ByteArray) {
        try {
            val file = File(getImagesDir(context), sanitizeFilename(filename))
            file.writeBytes(bytes)
            DebugLogger.d(TAG, "Image saved: $filename (${bytes.size} bytes)")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save image $filename: ${e.message}")
        }
    }

    /**
     * Load image/video bytes from the local images directory.
     */
    fun loadImageBytes(context: Context, filename: String): ByteArray? {
        return try {
            val file = File(getImagesDir(context), sanitizeFilename(filename))
            if (file.exists()) file.readBytes() else null
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to load image $filename: ${e.message}")
            null
        }
    }

    /**
     * Delete an image/video file from the local images directory.
     */
    fun deleteImage(context: Context, filename: String) {
        try {
            val file = File(getImagesDir(context), sanitizeFilename(filename))
            if (file.exists()) {
                file.delete()
                DebugLogger.d(TAG, "Image deleted: $filename")
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to delete image $filename: ${e.message}")
        }
    }

    /**
     * Check if an image/video file exists locally.
     */
    fun checkImageExists(context: Context, filename: String): Boolean {
        return File(getImagesDir(context), sanitizeFilename(filename)).exists()
    }

    /**
     * Get the local image file path for a given filename.
     */
    fun getImageFile(context: Context, filename: String): File {
        return File(getImagesDir(context), sanitizeFilename(filename))
    }

    // ── Bulk operations ────────────────────────────────────────────────

    /**
     * Get the set of all prompt IDs that have locally cached images.
     */
    fun getPromptIdsWithLocalImages(context: Context): Set<String> {
        // Load index and check which items have local image files
        return loadIndex(context)
            .filter { checkImageExists(context, it.filename) }
            .map { it.promptId }
            .toSet()
    }

    /**
     * Delete everything in the gallery directory (metadata + images).
     */
    fun clearAll(context: Context) {
        try {
            val galleryDir = getGalleryDir(context)
            galleryDir.deleteRecursively()
            // Recreate empty directories
            getMetadataDir(context)
            getImagesDir(context)
            DebugLogger.i(TAG, "All gallery data cleared")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to clear gallery: ${e.message}")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────

    /**
     * Get the metadata file for a specific prompt ID: .../gallery/metadata/{promptId}.json
     */
    internal fun getMetadataFile(context: Context, promptId: String): File {
        val metadataDir = getMetadataDir(context)
        val safeId = sanitizeFilename(promptId)
        return File(metadataDir, "$safeId.json")
    }

    /**
     * Sanitize a filename to prevent path traversal and remove unsafe characters.
     */
    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\.\.+"""), "_")
            .take(255)
    }
}
