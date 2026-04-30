package sh.hnet.comfychair.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import sh.hnet.comfychair.MainActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.connection.WebSocketMessage
import sh.hnet.comfychair.connection.WebSocketState
import sh.hnet.comfychair.util.DebugLogger

/**
 * Foreground service that monitors generation progress and shows notifications.
 *
 * This service subscribes DIRECTLY to ConnectionManager.webSocketMessages,
 * making it independent of the app's ViewModel lifecycle. Even if the app
 * process is killed, this service can receive completion events through
 * the WorkManager fallback (polling) mechanism.
 *
 * Lifecycle:
 * - Started when app connects to a server (so it's ready when generation begins)
 * - Subscribes to ConnectionManager's WebSocket messages directly
 * - When generation completes (ExecutionComplete/ExecutionSuccess), shows completion notification
 * - Stops itself after completion notification is shown (or on error)
 *
 * This service ensures notifications are delivered even when the app is in the background
 * or the screen is off, using the notification as a persistent indicator.
 */
class GenerationForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var webSocketMessagesJob: Job? = null
    private var webSocketStateJob: Job? = null

    // Pending generation info from SharedPreferences (survives service restarts)
    private var pendingPromptId: String? = null
    private var pendingOwnerId: String? = null
    private var pendingContentType: String? = null

    companion object {
        private const val TAG = "GenFgService"

        // Intent action to start this service
        const val ACTION_START = "sh.hnet.comfychair.action.START_GENERATION_SERVICE"

        // Intent action to notify service of new generation
        const val ACTION_GENERATION_STARTED = "sh.hnet.comfychair.action.GENERATION_STARTED"
        const val EXTRA_OWNER_ID = "owner_id"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_PROMPT_ID = "prompt_id"

        /**
         * Start the foreground service.
         * Safe to call multiple times - service will manage its own state.
         */
        fun start(context: Context) {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Notify the service that a new generation has started.
         * This is used to show the foreground notification with correct content type.
         * Also saves pending generation info to SharedPreferences so the service
         * can show completion notification even if it's restarted.
         */
        fun notifyGenerationStarted(context: Context, ownerId: String, contentType: String, promptId: String) {
            // Save to SharedPreferences FIRST (so it survives service restarts)
            NotificationHelper.savePendingGeneration(context, promptId, ownerId, contentType)

            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_GENERATION_STARTED
                putExtra(EXTRA_OWNER_ID, ownerId)
                putExtra(EXTRA_CONTENT_TYPE, contentType)
                putExtra(EXTRA_PROMPT_ID, promptId)
            }
            context.startService(intent)
        }

        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.d(TAG, "onCreate")

        // Create notification channels
        NotificationHelper.createNotificationChannels(this)

        // Start in foreground immediately with a placeholder notification
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // Just started - subscribe to WebSocket
                subscribeToWebSocket()
            }
            ACTION_GENERATION_STARTED -> {
                val ownerId = intent.getStringExtra(EXTRA_OWNER_ID)
                val contentType = intent.getStringExtra(EXTRA_CONTENT_TYPE) ?: "IMAGE"
                val promptId = intent.getStringExtra(EXTRA_PROMPT_ID)

                if (ownerId != null && promptId != null) {
                    DebugLogger.d(TAG, "Generation started: ownerId=$ownerId, contentType=$contentType, promptId=${Obfuscator.promptId(promptId)}")

                    // Update pending info (already saved to SharedPreferences by caller)
                    pendingOwnerId = ownerId
                    pendingContentType = contentType
                    pendingPromptId = promptId

                    subscribeToWebSocket()

                    // Update foreground notification to show "generating" state
                    val isVideo = contentType == "VIDEO"
                    showGeneratingNotification(ownerId, if (isVideo) "VIDEO" else "IMAGE")
                }
            }
            else -> {
                // Regular start - try to restore pending generation and subscribe
                restorePendingGeneration()
                subscribeToWebSocket()
            }
        }

        // START_STICKY: we want the service to keep running
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        DebugLogger.d(TAG, "onDestroy")
        serviceScope.cancel()
    }

    /**
     * Restore pending generation info from SharedPreferences.
     * Called on service startup to recover state after app process was killed.
     */
    private fun restorePendingGeneration() {
        val pending = NotificationHelper.getPendingGeneration(this)
        if (pending != null) {
            pendingPromptId = pending.promptId
            pendingOwnerId = pending.ownerId
            pendingContentType = pending.contentType
            DebugLogger.d(TAG, "Restored pending generation: promptId=${Obfuscator.promptId(pending.promptId)}, owner=${pending.ownerId}")
        }
    }

    /**
     * Subscribe to ConnectionManager's WebSocket messages and state.
     * This is the core of the background monitoring - it works independently
     * of the app's ViewModel lifecycle.
     */
    private fun subscribeToWebSocket() {
        // Guard: only subscribe once
        if (webSocketMessagesJob != null && webSocketStateJob != null) {
            DebugLogger.d(TAG, "Already subscribed to WebSocket")
            return
        }

        // Subscribe to WebSocket messages
        webSocketMessagesJob = serviceScope.launch {
            ConnectionManager.webSocketMessages.collect { message ->
                handleWebSocketMessage(message)
            }
        }

        // Subscribe to WebSocket state for auto-reconnect handling
        webSocketStateJob = serviceScope.launch {
            ConnectionManager.webSocketState.collectLatest { state ->
                handleWebSocketState(state)
            }
        }

        DebugLogger.d(TAG, "Subscribed to ConnectionManager WebSocket")
    }

    /**
     * Handle incoming WebSocket messages.
     * Look for generation completion events matching our pending promptId.
     */
    private fun handleWebSocketMessage(message: WebSocketMessage) {
        val promptId = pendingPromptId
        val ownerId = pendingOwnerId
        val contentType = pendingContentType

        when (message) {
            is WebSocketMessage.ExecutionComplete -> {
                DebugLogger.d(TAG, "WS: ExecutionComplete for promptId=${Obfuscator.promptId(message.promptId)}")
                if (message.promptId == promptId && promptId != null && ownerId != null) {
                    DebugLogger.i(TAG, "Generation complete detected for pending job")
                    showCompletionNotification(ownerId, contentType ?: "IMAGE", promptId)
                }
            }
            is WebSocketMessage.ExecutionSuccess -> {
                DebugLogger.d(TAG, "WS: ExecutionSuccess for promptId=${Obfuscator.promptId(message.promptId)}")
                if (message.promptId == promptId && promptId != null && ownerId != null) {
                    DebugLogger.i(TAG, "Generation success detected for pending job")
                    showCompletionNotification(ownerId, contentType ?: "IMAGE", promptId)
                }
            }
            is WebSocketMessage.ExecutionError -> {
                DebugLogger.i(TAG, "WS: ExecutionError for promptId=${Obfuscator.promptId(message.promptId ?: "")}: ${message.message}")
                // If this is our pending job, show error and stop
                if (message.promptId == promptId && promptId != null) {
                    DebugLogger.i(TAG, "Generation error for pending job, stopping service")
                    NotificationHelper.cancelGeneratingNotification(this)
                    stopSelf()
                }
            }
            is WebSocketMessage.ExecutionStart -> {
                DebugLogger.d(TAG, "WS: ExecutionStart for promptId=${Obfuscator.promptId(message.promptId)}")
                // If we don't have pending info yet but this matches, update our state
                if (message.promptId == promptId && promptId != null) {
                    DebugLogger.d(TAG, "ExecutionStart matches pending job")
                }
            }
            else -> {
                // Ignore: Progress, Status, Executing, PreviewImage, etc.
            }
        }
    }

    /**
     * Handle WebSocket state changes.
     * When connected, ensure we're subscribed. When disconnected, the
     * WorkManager fallback will handle polling.
     */
    private fun handleWebSocketState(state: WebSocketState) {
        when (state) {
            is WebSocketState.Connected -> {
                DebugLogger.d(TAG, "WebSocket connected")
                // Resubscribe to messages if needed
                if (webSocketMessagesJob == null || webSocketMessagesJob?.isActive != true) {
                    subscribeToWebSocket()
                }
            }
            is WebSocketState.Reconnecting -> {
                DebugLogger.d(TAG, "WebSocket reconnecting: attempt ${state.attempt}/${state.maxAttempts}")
            }
            is WebSocketState.Failed -> {
                DebugLogger.w(TAG, "WebSocket failed: ${state.reason}")
                // Don't stop service - WorkManager will eventually poll and show notification
            }
            is WebSocketState.Disconnected -> {
                DebugLogger.d(TAG, "WebSocket disconnected")
            }
            is WebSocketState.Connecting -> {
                DebugLogger.d(TAG, "WebSocket connecting")
            }
        }
    }

    /**
     * Show the foreground "generating..." notification.
     */
    private fun showGeneratingNotification(ownerId: String, contentType: String) {
        NotificationHelper.showGeneratingNotification(this, ownerId, contentType)
    }

    /**
     * Show completion notification and stop the foreground service.
     */
    private fun showCompletionNotification(ownerId: String, contentType: String, promptId: String) {
        // Check if we've already notified for this promptId
        if (NotificationHelper.hasNotified(this, promptId)) {
            DebugLogger.d(TAG, "Already notified for promptId ${Obfuscator.promptId(promptId)}, skipping")
            stopSelf()
            return
        }

        // Mark as notified to prevent duplicate notifications
        NotificationHelper.markNotified(this, promptId)

        // Cancel the foreground notification
        NotificationHelper.cancelGeneratingNotification(this)

        // Clear pending generation info
        NotificationHelper.clearPendingGeneration(this)
        pendingPromptId = null
        pendingOwnerId = null
        pendingContentType = null

        // Show completion notification
        val isVideo = contentType == "VIDEO"
        NotificationHelper.showCompletionNotification(
            context = this,
            promptId = promptId,
            ownerId = ownerId,
            contentType = contentType,
            isVideo = isVideo
        )

        // Stop the foreground service
        stopSelf()
    }

    /**
     * Start the service in foreground with an initial notification.
     */
    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationConstants.NOTIFICATION_ID_FOREGROUND,
                createForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NotificationConstants.NOTIFICATION_ID_FOREGROUND,
                createForegroundNotification()
            )
        }
    }

    /**
     * Create the initial foreground notification.
     */
    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationConstants.CHANNEL_ID_FOREGROUND)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_generating_progress))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private object Obfuscator {
        fun promptId(id: String): String = if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else id
    }
}
