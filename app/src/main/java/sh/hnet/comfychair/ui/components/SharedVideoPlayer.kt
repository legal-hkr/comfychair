package sh.hnet.comfychair.ui.components

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Singleton manager for a shared ExoPlayer instance.
 *
 * Since only one video plays at a time in the app, we can reuse a single
 * ExoPlayer instance across all video playback locations. This eliminates
 * the initialization delay and surface setup issues that occur when creating
 * new ExoPlayer instances.
 *
 * Uses reference counting to track active consumers. Playback is only paused
 * when all consumers have released the player.
 *
 * Benefits:
 * - No stretched first frame during player initialization
 * - Faster video switching (no player creation overhead)
 * - Lower memory usage (single player instance)
 * - Smooth transitions between preview and fullscreen modes
 */
object SharedVideoPlayer {

    private var exoPlayer: ExoPlayer? = null
    private var currentUri: Uri? = null
    private var isInitialized = false
    private var consumerCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null

    /**
     * Get or create the shared ExoPlayer instance.
     * Must be called from a context that has access to Application context.
     */
    fun getPlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = false
            }
            isInitialized = true
        }
        return exoPlayer!!
    }

    /**
     * Register a consumer (VideoPlayer component) as active.
     * Call this when a VideoPlayer enters composition.
     * Cancels any pending stop since there's an active consumer.
     */
    fun registerConsumer(context: Context): ExoPlayer {
        consumerCount++
        // Cancel any pending stop since someone is using the player
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
        return getPlayer(context)
    }

    /**
     * Unregister a consumer. When count reaches 0, schedules a delayed pause
     * to allow for transitions (e.g., switching from preview to fullscreen).
     */
    fun unregisterConsumer() {
        consumerCount--
        if (consumerCount <= 0) {
            consumerCount = 0
            // Schedule delayed pause to allow for transitions
            pendingStopRunnable?.let { handler.removeCallbacks(it) }
            pendingStopRunnable = Runnable {
                if (consumerCount == 0) {
                    exoPlayer?.pause()
                }
                pendingStopRunnable = null
            }
            handler.postDelayed(pendingStopRunnable!!, 100)
        }
    }

    /**
     * Prepare and play a video from the given URI.
     * If the URI is the same as currently loaded, just resumes playback.
     */
    fun playVideo(context: Context, uri: Uri) {
        // Cancel any pending stop
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null

        val player = getPlayer(context)

        if (currentUri != uri) {
            // New video - stop current, load new
            player.stop()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            currentUri = uri
        }

        player.playWhenReady = true
        player.play()
    }

    /**
     * Pause playback without releasing resources.
     * Called when a consumer is done but video might be reused.
     */
    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * Resume playback if a video is loaded and there are active consumers.
     */
    fun resume() {
        exoPlayer?.let { player ->
            if (currentUri != null && consumerCount > 0) {
                player.play()
            }
        }
    }

    /**
     * Check if a specific URI is currently loaded.
     */
    fun isCurrentUri(uri: Uri?): Boolean {
        return uri != null && currentUri == uri
    }

    /**
     * Get the currently loaded URI.
     */
    fun getCurrentUri(): Uri? = currentUri

    /**
     * Release all resources. Call when app is being destroyed.
     */
    fun release() {
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null
        exoPlayer?.release()
        exoPlayer = null
        currentUri = null
        isInitialized = false
        consumerCount = 0
    }

    /**
     * Check if player is initialized.
     */
    fun isReady(): Boolean = isInitialized && exoPlayer != null
}
