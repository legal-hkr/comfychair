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
    data object Workflows : SettingsRoute("workflows_settings")
    data object Application : SettingsRoute("application_settings")
    data object Server : SettingsRoute("server_settings")
    data object About : SettingsRoute("about_settings")
}
