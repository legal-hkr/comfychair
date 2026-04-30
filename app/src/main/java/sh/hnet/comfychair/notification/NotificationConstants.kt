package sh.hnet.comfychair.notification

/**
 * Constants for notification channels and notification IDs.
 */
object NotificationConstants {

    // Notification channel IDs
    const val CHANNEL_ID_FOREGROUND = "comfychair_generation_foreground"
    const val CHANNEL_ID_COMPLETION = "comfychair_generation_completion"

    // Notification IDs
    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_COMPLETION_BASE = 2000

    // WorkManager
    const val WORK_NAME_GENERATION_CHECK = "generation_check_work"
    const val WORK_TAG_GENERATION_CHECK = "generation_check"

    // SharedPreferences keys for tracking notified prompt IDs
    const val PREFS_NAME = "NotificationPrefs"
    const val PREF_NOTIFIED_PROMPT_IDS = "notified_prompt_ids"
    const val PREF_PENDING_GENERATION = "pending_generation_prompt_id"
    const val PREF_PENDING_OWNER = "pending_generation_owner"
    const val PREF_PENDING_CONTENT_TYPE = "pending_generation_content_type"
}
