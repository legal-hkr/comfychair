package sh.hnet.comfychair.navigation

/**
 * Navigation routes for the main container (generation screens)
 */
sealed class MainRoute(val route: String) {
    data object TextToImage : MainRoute("text_to_image")
    data object TextToVideo : MainRoute("text_to_video")
    data object ImageToVideo : MainRoute("image_to_video")
    data object Inpainting : MainRoute("inpainting")
    data object Gallery : MainRoute("gallery")
}

/**
 * Navigation routes for the settings container
 */
sealed class SettingsRoute(val route: String) {
    data object Application : SettingsRoute("application_settings")
    data object Server : SettingsRoute("server_settings")
    data object Workflows : SettingsRoute("workflows_settings")
}
