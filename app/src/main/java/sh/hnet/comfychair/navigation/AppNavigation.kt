package sh.hnet.comfychair.navigation

/**
 * Navigation routes of the main activity: the generation screens and the Gallery and Settings tabs.
 */
sealed class MainRoute(val route: String) {
    data object TextToImage : MainRoute("text_to_image")
    data object ImageToImage : MainRoute("image_to_image")
    data object TextToVideo : MainRoute("text_to_video")
    data object ImageToVideo : MainRoute("image_to_video")
    data object Gallery : MainRoute("gallery")
    data object Settings : MainRoute("settings")
}

/**
 * Settings pages, opened from the Settings tab's list.
 */
sealed class SettingsRoute(val route: String) {
    // Server
    data object Server : SettingsRoute("server_settings")
    data object Workflows : SettingsRoute("workflows_settings")

    // App
    data object Connection : SettingsRoute("connection_settings")
    data object Generation : SettingsRoute("generation_settings")
    data object MediaPrivacy : SettingsRoute("media_privacy_settings")
    data object Language : SettingsRoute("language_settings")
    data object WorkflowEditor : SettingsRoute("workflow_editor_settings")

    // Data
    data object Backup : SettingsRoute("backup_settings")
    data object Cache : SettingsRoute("cache_settings")
    data object DebugLog : SettingsRoute("debug_log_settings")
    data object Reset : SettingsRoute("reset_settings")

    data object About : SettingsRoute("about_settings")

    companion object {
        val all: List<SettingsRoute>
            get() = listOf(
                Server, Workflows, Connection, Generation, MediaPrivacy, Language, WorkflowEditor,
                Backup, Cache, DebugLog, Reset, About
            )
    }
}
