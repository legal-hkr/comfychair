package sh.hnet.comfychair.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import sh.hnet.comfychair.MediaViewerActivity
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.util.DebugLogger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Helper class for creating and managing ComfyChair notifications.
 *
 * Supports:
 * - Foreground notification during generation
 * - Completion notification with image preview (BigPictureStyle)
 * - PendingIntent to open MediaViewerActivity on notification tap
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"

    /**
     * Create notification channels for Android O+.
     * Must be called before posting any notifications.
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Foreground channel - for ongoing generation status
        val foregroundChannel = android.app.NotificationChannel(
            NotificationConstants.CHANNEL_ID_FOREGROUND,
            context.getString(R.string.notification_channel_foreground_name),
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_foreground_desc)
            setShowBadge(false)
        }

        // Completion channel - for generation complete notifications with image
        val completionChannel = android.app.NotificationChannel(
            NotificationConstants.CHANNEL_ID_COMPLETION,
            context.getString(R.string.notification_channel_completion_name),
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_completion_desc)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannels(listOf(foregroundChannel, completionChannel))
    }

    /**
     * Show the foreground notification indicating generation is in progress.
     */
    fun showGeneratingNotification(context: Context, ownerId: String, contentType: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent to open the app when notification is tapped
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (contentType) {
            "VIDEO" -> context.getString(R.string.notification_generating_video)
            else -> context.getString(R.string.notification_generating_image)
        }

        val notification = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID_FOREGROUND)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_generating_progress))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(NotificationConstants.NOTIFICATION_ID_FOREGROUND, notification)
        DebugLogger.d(TAG, "Showing foreground notification for $ownerId")
    }

    /**
     * Cancel the foreground notification.
     */
    fun cancelGeneratingNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NotificationConstants.NOTIFICATION_ID_FOREGROUND)
        DebugLogger.d(TAG, "Cancelled foreground notification")
    }

    /**
     * Show the completion notification with image preview.
     * Uses BigPictureStyle to display the generated image.
     */
    fun showCompletionNotification(
        context: Context,
        promptId: String,
        ownerId: String,
        contentType: String,
        isVideo: Boolean = false
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Determine which bitmap key to use based on ownerId
        val mediaKey = when (ownerId) {
            "TEXT_TO_IMAGE" -> MediaStateHolder.MediaKey.TtiPreview
            "IMAGE_TO_IMAGE" -> MediaStateHolder.MediaKey.ItiPreview
            "TEXT_TO_VIDEO" -> MediaStateHolder.MediaKey.TtvPreview
            "IMAGE_TO_VIDEO" -> MediaStateHolder.MediaKey.ItvPreview
            else -> {
                DebugLogger.w(TAG, "Unknown ownerId: $ownerId, cannot show completion notification")
                return
            }
        }

        // Try to get the bitmap from MediaStateHolder
        val bitmap = MediaStateHolder.getBitmap(mediaKey, context)

        if (bitmap == null) {
            DebugLogger.w(TAG, "No bitmap found for $mediaKey, showing notification without image")
            showCompletionNotificationWithoutImage(context, promptId, ownerId, contentType, isVideo)
            return
        }

        // Save bitmap to cache file for notification
        val bitmapFile = saveBitmapToCacheFile(context, bitmap, promptId)
        if (bitmapFile == null) {
            DebugLogger.w(TAG, "Failed to save bitmap to cache, showing notification without image")
            showCompletionNotificationWithoutImage(context, promptId, ownerId, contentType, isVideo)
            return
        }

        // Create content URI via FileProvider
        val bitmapUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            bitmapFile
        )

        // Intent to open MediaViewerActivity in single mode
        val viewIntent = Intent(context, MediaViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MediaViewerActivity.EXTRA_MODE, MediaViewerActivity.MODE_SINGLE)
            putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, isVideo)
            // Pass bitmap cache key so MediaViewer can retrieve it
            putExtra(EXTRA_MEDIA_OWNER_ID, ownerId)
            putExtra(EXTRA_MEDIA_PROMPT_ID, promptId)
        }
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            promptId.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isVideo) {
            context.getString(R.string.notification_video_ready)
        } else {
            context.getString(R.string.notification_image_ready)
        }

        val bigPictureStyle = NotificationCompat.BigPictureStyle()
            .bigPicture(bitmap)
            .bigLargeIcon(null as Bitmap?)

        val notification = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID_COMPLETION)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_tap_to_view))
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(bitmap)
            .setStyle(bigPictureStyle)
            .setContentIntent(viewPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notificationId = NotificationConstants.NOTIFICATION_ID_COMPLETION_BASE + (promptId.hashCode() % 1000)
        notificationManager.notify(notificationId, notification)
        DebugLogger.i(TAG, "Showed completion notification for promptId ${Obfuscator.promptId(promptId)}")
    }

    /**
     * Show completion notification without image (fallback when bitmap unavailable).
     */
    private fun showCompletionNotificationWithoutImage(
        context: Context,
        promptId: String,
        ownerId: String,
        contentType: String,
        isVideo: Boolean
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val viewIntent = Intent(context, MediaViewerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MediaViewerActivity.EXTRA_MODE, MediaViewerActivity.MODE_SINGLE)
            putExtra(MediaViewerActivity.EXTRA_IS_VIDEO, isVideo)
            putExtra(EXTRA_MEDIA_OWNER_ID, ownerId)
            putExtra(EXTRA_MEDIA_PROMPT_ID, promptId)
        }
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            promptId.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isVideo) {
            context.getString(R.string.notification_video_ready)
        } else {
            context.getString(R.string.notification_image_ready)
        }

        val notification = NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID_COMPLETION)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notification_tap_to_view))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(viewPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notificationId = NotificationConstants.NOTIFICATION_ID_COMPLETION_BASE + (promptId.hashCode() % 1000)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Save bitmap to a cache file for use with FileProvider.
     * Returns the File, or null on failure.
     */
    private fun saveBitmapToCacheFile(context: Context, bitmap: Bitmap, promptId: String): File? {
        return try {
            val filename = "notification_${promptId.hashCode()}.png"
            val file = File(context.cacheDir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            DebugLogger.d(TAG, "Saved notification bitmap to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to save notification bitmap: ${e.message}")
            null
        }
    }

    /**
     * Save pending generation info for WorkManager to pick up if app is killed.
     */
    fun savePendingGeneration(context: Context, promptId: String, ownerId: String, contentType: String) {
        val prefs = context.getSharedPreferences(NotificationConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(NotificationConstants.PREF_PENDING_GENERATION, promptId)
            putString(NotificationConstants.PREF_PENDING_OWNER, ownerId)
            putString(NotificationConstants.PREF_PENDING_CONTENT_TYPE, contentType)
            apply()
        }
        DebugLogger.d(TAG, "Saved pending generation: promptId=${Obfuscator.promptId(promptId)}, owner=$ownerId")
    }

    /**
     * Clear pending generation info (called when notified).
     */
    fun clearPendingGeneration(context: Context) {
        val prefs = context.getSharedPreferences(NotificationConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove(NotificationConstants.PREF_PENDING_GENERATION)
            remove(NotificationConstants.PREF_PENDING_OWNER)
            remove(NotificationConstants.PREF_PENDING_CONTENT_TYPE)
            apply()
        }
    }

    /**
     * Check if we have pending generation info saved.
     */
    fun hasPendingGeneration(context: Context): Boolean {
        val prefs = context.getSharedPreferences(NotificationConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(NotificationConstants.PREF_PENDING_GENERATION, null) != null
    }

    /**
     * Get pending generation info.
     */
    fun getPendingGeneration(context: Context): PendingGeneration? {
        val prefs = context.getSharedPreferences(NotificationConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val promptId = prefs.getString(NotificationConstants.PREF_PENDING_GENERATION, null) ?: return null
        val ownerId = prefs.getString(NotificationConstants.PREF_PENDING_OWNER, null) ?: return null
        val contentType = prefs.getString(NotificationConstants.PREF_PENDING_CONTENT_TYPE, "IMAGE") ?: "IMAGE"
        return PendingGeneration(promptId, ownerId, contentType)
    }

    data class PendingGeneration(
        val promptId: String,
        val ownerId: String,
        val contentType: String
    )

    /**
     * Record that we've already sent a notification for this promptId.
     * Used to prevent duplicate notifications.
     */
    fun markNotified(context: Context, promptId: String) {
        val prefs = context.getSharedPreferences(NotificationConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val notifiedIds = prefs.getStringSet(NotificationConstants.PREF_NOTIFIED_PROMPT_IDS, mutableSetOf())!!
            .toMutableSet()
        notifiedIds.add(promptId)
        // Keep only last 50 to prevent unbounded growth
        if (notifiedIds.size > 50) {
            val toRemove = notifiedIds.take(notifiedIds.size - 50)
            notifiedIds.removeAll(toRemove.toSet())
        }
        prefs.edit().putStringSet(NotificationConstants.PREF_NOTIFIED_PROMPT_IDS, notifiedIds).apply()
    }

    /**
     * Check if we've already notified for this promptId.
     */
    fun hasNotified(context: Context, promptId: String): Boolean {
        val prefs = context.getSharedPreferences(NotificationConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(NotificationConstants.PREF_NOTIFIED_PROMPT_IDS, emptySet())!!.contains(promptId)
    }

    // Extra key for passing ownerId to MediaViewerActivity
    const val EXTRA_MEDIA_OWNER_ID = "media_owner_id"
    const val EXTRA_MEDIA_PROMPT_ID = "media_prompt_id"

    /**
     * Called when a generation completes (image or video).
     * This is the main entry point for triggering notifications.
     *
     * Flow:
     * 1. Save pending generation info (for WorkManager fallback)
     * 2. Cancel foreground notification
     * 3. Show completion notification with image preview
     * 4. Mark as notified to prevent duplicates
     * 5. Clear pending generation
     */
    fun onGenerationComplete(
        context: Context,
        promptId: String,
        ownerId: String,
        contentType: String,
        isVideo: Boolean
    ) {
        DebugLogger.i(TAG, "Generation complete: promptId=${Obfuscator.promptId(promptId)}, ownerId=$ownerId, isVideo=$isVideo")

        // Check for duplicate notification
        if (hasNotified(context, promptId)) {
            DebugLogger.d(TAG, "Already notified for this promptId, skipping")
            return
        }

        // Save pending generation info for WorkManager fallback
        savePendingGeneration(context, promptId, ownerId, contentType)

        // Cancel foreground notification
        cancelGeneratingNotification(context)

        // Show completion notification with image preview
        showCompletionNotification(context, promptId, ownerId, contentType, isVideo)

        // Mark as notified
        markNotified(context, promptId)

        // Clear pending generation
        clearPendingGeneration(context)
    }
}

// Obfuscator utility for logging prompt IDs
private object Obfuscator {
    fun promptId(id: String): String = if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else id
}
