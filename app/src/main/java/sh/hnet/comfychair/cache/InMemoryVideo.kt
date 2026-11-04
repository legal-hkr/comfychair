package sh.hnet.comfychair.cache

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Playback of generated videos straight from RAM.
 *
 * In memory-first mode generated media must never be written to disk, but video consumers
 * (ExoPlayer, MediaMetadataRetriever, saving to the gallery) work with URIs. This object hands
 * out in-app URIs ("comfychair-memory://video/<id>") that resolve to bytes held by
 * MediaStateHolder or MediaCache, and helpers that read such URIs without touching the disk.
 * Other URIs (disk-first mode) are passed through unchanged.
 *
 * The URIs are only meaningful inside this process; [sh.hnet.comfychair.util.MediaExport.shareFile] converts one into a file
 * for other apps when the user explicitly shares a video.
 */
object InMemoryVideo {
    private const val SCHEME = "comfychair-memory"
    private const val AUTHORITY = "video"
    private const val SHARE_FILE_PREFIX = "share_"

    // URI id -> provider of the current bytes (null once evicted from the cache)
    private val sources = ConcurrentHashMap<String, () -> ByteArray?>()

    /**
     * Returns an in-memory URI for a video. [bytes] is evaluated on every read, so a video
     * evicted from its cache stops being playable instead of being kept alive here.
     */
    fun uriFor(id: String, bytes: () -> ByteArray?): Uri {
        sources[id] = bytes
        return Uri.Builder().scheme(SCHEME).authority(AUTHORITY).appendPath(id).build()
    }

    fun isInMemory(uri: Uri?): Boolean = uri?.scheme == SCHEME

    /** Bytes of an in-memory URI, or null for other URIs or evicted videos. */
    fun bytes(uri: Uri?): ByteArray? {
        if (!isInMemory(uri)) return null
        return uri?.lastPathSegment?.let { sources[it] }?.invoke()
    }

    /** Opens any video URI for reading; in-memory URIs are read from RAM. */
    fun openInputStream(context: Context, uri: Uri): InputStream? {
        if (isInMemory(uri)) return bytes(uri)?.inputStream()
        return context.contentResolver.openInputStream(uri)
    }

    /** Points a MediaMetadataRetriever at any video URI; in-memory URIs are read from RAM. */
    fun setDataSource(retriever: MediaMetadataRetriever, context: Context, uri: Uri) {
        if (isInMemory(uri)) {
            val bytes = bytes(uri) ?: throw IOException("Video is no longer in memory: $uri")
            retriever.setDataSource(ByteArrayMediaDataSource(bytes))
        } else {
            retriever.setDataSource(context, uri)
        }
    }

    /**
     * Deletes media files left in the cache directory: share files from earlier sessions, and
     * playback/metadata temp files written by versions before in-memory playback.
     */
    fun deleteShareFiles(context: Context) {
        context.cacheDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith(SHARE_FILE_PREFIX) || name.startsWith("playback_") || name.startsWith("temp_meta_")) {
                file.delete()
            }
        }
    }

    /**
     * DataSource factory for ExoPlayer: in-memory URIs are served from RAM, everything else by
     * the default data source.
     */
    @OptIn(UnstableApi::class)
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val fallbackFactory = DefaultDataSource.Factory(context.applicationContext)
        return DataSource.Factory { InMemoryAwareDataSource(fallbackFactory.createDataSource()) }
    }

    @OptIn(UnstableApi::class)
    private class InMemoryAwareDataSource(private val fallback: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            fallback.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (isInMemory(dataSpec.uri)) {
                ByteArrayDataSource(bytes(dataSpec.uri) ?: throw IOException("Video is no longer in memory: ${dataSpec.uri}"))
            } else {
                fallback
            }
            current = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: throw IOException("Data source is not open")

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }

    /** MediaDataSource over a byte array, for MediaMetadataRetriever without a file. */
    class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() {}
    }
}
