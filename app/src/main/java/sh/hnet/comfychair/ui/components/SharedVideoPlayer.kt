package sh.hnet.comfychair.ui.components

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sh.hnet.comfychair.cache.InMemoryVideo
import sh.hnet.comfychair.storage.AppSettings

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

    /** Where a video is shown: it decides the sound and the looping (spec §14.4: each surface has its own defaults). */
    enum class Surface { PREVIEW, VIEWER }

    /**
     * What the controls show for the loaded video. The position isn't here: controls poll it while shown.
     *
     * @param hasAudio Null until the tracks are known
     * @param frameRate Frames per second of the video track, null when the container doesn't say
     * @param ended Stopped on the last frame (only with looping off, in the viewer)
     */
    data class State(
        val isLoading: Boolean = true,
        val isPlaying: Boolean = false,
        val durationMs: Long? = null,
        val hasAudio: Boolean? = null,
        val frameRate: Float? = null,
        val muted: Boolean = true,
        val loop: Boolean = true,
        val ended: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var surface = Surface.PREVIEW
    // The preview video the user turned the sound on for; any other preview video plays muted
    private var previewSoundUri: Uri? = null
    // The viewer's sound for this viewer session (on when it opens)
    private var viewerMuted = false
    // Where each video was left in this viewer session, to resume it there after swiping back to its page
    private val viewerPositions = HashMap<Uri, Long>()

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
    @OptIn(UnstableApi::class)
    fun getPlayer(context: Context): ExoPlayer {
        if (exoPlayer == null) {
            // In-memory videos (memory-first mode) are read straight from RAM, see InMemoryVideo
            exoPlayer = ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(InMemoryVideo.dataSourceFactory(context)))
                .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
                volume = 0f
                addListener(stateListener)
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
     * Unregister a consumer. When count reaches 0, schedules a delayed stop
     * to allow for transitions (e.g., switching from preview to fullscreen).
     * Video is stopped (not paused) so it resets to the beginning on next play.
     */
    fun unregisterConsumer() {
        consumerCount--
        if (consumerCount <= 0) {
            consumerCount = 0
            // Schedule delayed stop to allow for transitions
            pendingStopRunnable?.let { handler.removeCallbacks(it) }
            pendingStopRunnable = Runnable {
                if (consumerCount == 0) {
                    rememberViewerPosition()
                    // Stop (not pause) so video resets to beginning on next play
                    exoPlayer?.stop()
                    currentUri = null  // Clear URI so prepareVideo will reload
                }
                pendingStopRunnable = null
            }
            handler.postDelayed(pendingStopRunnable!!, 100)
        }
    }

    /**
     * Prepare a video from the given URI without starting playback.
     * Call startPlayback() after the surface is ready to begin from frame 0.
     * Always resets to the beginning, even if the same URI is already loaded.
     *
     * @param surface Where it's shown: the preview plays muted (unless its sound was turned on for this very video)
     *   and always loops; the viewer plays with this session's sound and loops as its setting says
     */
    fun prepareVideo(context: Context, uri: Uri, surface: Surface = Surface.PREVIEW) {
        // Cancel any pending stop
        pendingStopRunnable?.let { handler.removeCallbacks(it) }
        pendingStopRunnable = null

        val player = getPlayer(context)
        if (uri != currentUri) rememberViewerPosition()
        this.surface = surface
        // Another preview video starts muted again (a new result, another page of results)
        if (surface == Surface.PREVIEW && uri != previewSoundUri) previewSoundUri = null
        player.repeatMode = if (surface == Surface.PREVIEW || AppSettings.isViewerLoop(context)) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        applySound(player, muted = if (surface == Surface.PREVIEW) previewSoundUri != uri else viewerMuted)

        if (currentUri != uri) {
            // New video - stop current, load new
            player.stop()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.playWhenReady = false  // Don't auto-play
            player.prepare()
            currentUri = uri
        } else {
            // Same video - just reset to beginning and ensure it's ready
            player.playWhenReady = false
            player.seekTo(0)
        }
    }

    /**
     * Start playback from the beginning.
     * Call this after the video surface is ready to ensure playback starts from frame 0.
     */
    fun startPlayback() {
        exoPlayer?.let { player ->
            // In the viewer, a video swiped back to resumes where it was left
            val resumeAt = if (surface == Surface.VIEWER) currentUri?.let { viewerPositions.remove(it) } else null
            player.seekTo(resumeAt ?: 0)
            player.playWhenReady = true
            player.play()
        }
    }

    /** Turn the sound of the video shown off or on: in the preview for this video only, in the viewer for its session. */
    fun setMuted(muted: Boolean) {
        val player = exoPlayer ?: return
        when (surface) {
            Surface.PREVIEW -> previewSoundUri = if (muted) null else currentUri
            Surface.VIEWER -> viewerMuted = muted
        }
        applySound(player, muted)
    }

    /** A new viewer session: sound on until the user mutes it. */
    fun startViewerSession() {
        viewerMuted = false
        viewerPositions.clear()
    }

    private fun rememberViewerPosition() {
        val player = exoPlayer ?: return
        val uri = currentUri ?: return
        // One that ended starts over
        if (surface == Surface.VIEWER) viewerPositions[uri] = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
    }

    /** The viewer's loop toggle: remembered app-wide, applied at once when the viewer shows the video. */
    fun setViewerLoop(context: Context, loop: Boolean) {
        AppSettings.setViewerLoop(context, loop)
        if (surface == Surface.VIEWER) exoPlayer?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Play, or pause; a video that ended (looping off) starts again from the beginning. */
    fun togglePlayback() {
        val player = exoPlayer ?: return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    /** Current position, for controls that poll it while shown. */
    fun positionMs(): Long = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

    /** Seek to [positionMs]: [exact] lands on that very frame (frame steps), otherwise the player may pick a near one. */
    @OptIn(UnstableApi::class)
    fun seekTo(positionMs: Long, exact: Boolean = true) {
        val player = exoPlayer ?: return
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** One frame back ([direction] -1) or forward (+1), pausing first; it doesn't wrap past either end. */
    fun stepFrame(direction: Int) {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause()
        val frameMs = 1000.0 / (_state.value.frameRate?.toDouble() ?: DEFAULT_FRAME_RATE)
        val duration = _state.value.durationMs ?: return
        val target = (player.currentPosition + direction * frameMs).toLong().coerceIn(0L, duration)
        seekTo(target, exact = true)
    }

    // Mute by volume (instant, no rebuffering); only a video with sound takes the audio focus, so a muted preview
    // never interrupts another app's audio
    @OptIn(UnstableApi::class)
    private fun applySound(player: ExoPlayer, muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        player.setAudioAttributes(attributes, !muted)
        _state.update { it.copy(muted = muted) }
    }

    private val stateListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            val tracks = player.currentTracks
            val known = !tracks.isEmpty
            val videoFormat = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.takeIf { it.length > 0 }?.getTrackFormat(0)
            _state.value = State(
                isLoading = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE ||
                    duration == null,
                isPlaying = player.isPlaying,
                durationMs = duration,
                hasAudio = if (known) tracks.containsType(C.TRACK_TYPE_AUDIO) else null,
                frameRate = videoFormat?.frameRate?.takeIf { it > 0f },
                muted = player.volume == 0f,
                loop = player.repeatMode != Player.REPEAT_MODE_OFF,
                ended = player.playbackState == Player.STATE_ENDED
            )
        }
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

    // Assumed when the video track doesn't give its frame rate
    private const val DEFAULT_FRAME_RATE = 24.0
}
