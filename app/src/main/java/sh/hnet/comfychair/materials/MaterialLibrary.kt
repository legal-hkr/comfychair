package sh.hnet.comfychair.materials

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "MaterialLibrary"

data class MaterialItem(
    val id: String,
    val displayName: String,
    val filename: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Long
) {
    val cacheKey: String get() = id
}

object MaterialLibrary {
    private const val DIR_NAME = "materials"
    private const val INDEX_FILE = "index.json"

    private fun getRootDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private fun getIndexFile(context: Context): File = File(getRootDir(context), INDEX_FILE)
    private fun getImageFile(context: Context, filename: String): File = File(getRootDir(context), filename)

    suspend fun listMaterials(context: Context): List<MaterialItem> = withContext(Dispatchers.IO) {
        val indexFile = getIndexFile(context)
        if (!indexFile.exists()) return@withContext emptyList()
        try {
            val json = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until json.length()) {
                    val obj = json.optJSONObject(i) ?: continue
                    val filename = obj.optString("filename")
                    val imageFile = getImageFile(context, filename)
                    if (!imageFile.exists()) continue
                    add(
                        MaterialItem(
                            id = obj.optString("id"),
                            displayName = obj.optString("displayName"),
                            filename = filename,
                            width = obj.optInt("width"),
                            height = obj.optInt("height"),
                            sizeBytes = obj.optLong("sizeBytes"),
                            createdAt = obj.optLong("createdAt")
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to read material index: ${e.message}")
            emptyList()
        }
    }

    suspend fun importImages(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0
        val current = listMaterials(context).toMutableList()
        var imported = 0
        for (uri in uris) {
            try {
                // Downsample to max 2048px on the longest edge before loading into memory
                val (w, h) = run {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { opts.run { BitmapFactory.decodeStream(it, null, this) } }
                    Pair(opts.outWidth, opts.outHeight)
                }
                val targetPx = 2048
                var sampleSize = 1
                while (maxOf(w / sampleSize, h / sampleSize) > targetPx) sampleSize *= 2
                val bmpOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bmpOpts)
                } ?: continue
                if (bitmap == null) continue
                val id = UUID.randomUUID().toString()
                val filename = "$id.jpg"
                val outFile = getImageFile(context, filename)
                FileOutputStream(outFile).use { output ->
                    // Re-encode as JPEG 85% to save space; only keep the original dimensions
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                }
                val displayName = resolveDisplayName(context.contentResolver, uri) ?: filename
                current.add(
                    MaterialItem(
                        id = id,
                        displayName = displayName,
                        filename = filename,
                        width = bitmap.width,
                        height = bitmap.height,
                        sizeBytes = outFile.length(),
                        createdAt = System.currentTimeMillis()
                    )
                )
                bitmap.recycle()
                imported += 1
            } catch (e: OutOfMemoryError) {
                DebugLogger.w(TAG, "OOM importing material, skipping: ${e.message}")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Failed to import material: ${e.message}")
            }
        }
        writeIndex(context, current)
        imported
    }

    suspend fun deleteMaterials(context: Context, ids: Set<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val current = listMaterials(context)
        val remaining = current.filterNot { it.id in ids }
        val deleted = current.size - remaining.size
        current.filter { it.id in ids }.forEach { item ->
            runCatching { getImageFile(context, item.filename).delete() }
        }
        writeIndex(context, remaining)
        deleted
    }

    suspend fun loadBitmap(context: Context, item: MaterialItem): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(getImageFile(context, item.filename).absolutePath) }.getOrNull()
    }

    /**
     * Saves a bitmap to the material library.
     * Returns the saved MaterialItem, or null on failure.
     */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap, displayName: String): MaterialItem? = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val filename = "$id.jpg"
            val outFile = getImageFile(context, filename)
            FileOutputStream(outFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            val current = listMaterials(context).toMutableList()
            val item = MaterialItem(
                id = id,
                displayName = displayName,
                filename = filename,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = outFile.length(),
                createdAt = System.currentTimeMillis()
            )
            current.add(item)
            writeIndex(context, current)
            item
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to save bitmap: ${e.message}")
            null
        }
    }

    fun getContentUri(context: Context, item: MaterialItem): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            getImageFile(context, item.filename)
        )
    }

    private fun writeIndex(context: Context, items: List<MaterialItem>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("displayName", item.displayName)
                    put("filename", item.filename)
                    put("width", item.width)
                    put("height", item.height)
                    put("sizeBytes", item.sizeBytes)
                    put("createdAt", item.createdAt)
                }
            )
        }
        getIndexFile(context).writeText(json.toString())
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }
}
