package sh.hnet.comfychair.ui.navigation

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.navigation.MainRoute
import sh.hnet.comfychair.navigation.SettingsRoute
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.ui.components.LocalAppSnackbarHostState
import sh.hnet.comfychair.ui.components.LocalCoveredByMediaViewer
import sh.hnet.comfychair.ui.components.LocalMediaOrigins
import sh.hnet.comfychair.ui.components.LocalMediaViewer
import sh.hnet.comfychair.ui.components.MainNavigationBar
import sh.hnet.comfychair.ui.components.MediaOrigins
import sh.hnet.comfychair.ui.components.MediaViewerOpener
import sh.hnet.comfychair.ui.components.MediaViewerOverlay
import sh.hnet.comfychair.ui.components.SourceMedia
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSource
import sh.hnet.comfychair.ui.screens.AboutSettingsScreen
import sh.hnet.comfychair.ui.screens.WhatsNewSettingsScreen
import sh.hnet.comfychair.ui.screens.GalleryScreen
import sh.hnet.comfychair.ui.screens.ImageToImageScreen
import sh.hnet.comfychair.ui.screens.ImageToVideoScreen
import sh.hnet.comfychair.ui.screens.ServerSettingsScreen
import sh.hnet.comfychair.ui.screens.SettingsRootScreen
import sh.hnet.comfychair.ui.screens.TextToImageScreen
import sh.hnet.comfychair.ui.screens.TextToVideoScreen
import sh.hnet.comfychair.ui.screens.VideoToVideoScreen
import sh.hnet.comfychair.ui.screens.WorkflowDetailPage
import sh.hnet.comfychair.ui.screens.WorkflowsSettingsScreen
import sh.hnet.comfychair.ui.screens.settings.BackupSettingsPage
import sh.hnet.comfychair.ui.screens.settings.CacheSettingsPage
import sh.hnet.comfychair.ui.screens.settings.ConnectionSettingsPage
import sh.hnet.comfychair.ui.screens.settings.DebugLogSettingsPage
import sh.hnet.comfychair.ui.screens.settings.GenerationSettingsPage
import sh.hnet.comfychair.ui.screens.settings.LanguageSettingsPage
import sh.hnet.comfychair.ui.screens.settings.MediaPrivacySettingsPage
import sh.hnet.comfychair.ui.screens.settings.ResetSettingsPage
import sh.hnet.comfychair.ui.screens.settings.SettingsEventsHost
import sh.hnet.comfychair.ui.screens.settings.WorkflowEditorSettingsPage
import sh.hnet.comfychair.util.VideoFrames
import sh.hnet.comfychair.viewmodel.GalleryViewModel
import sh.hnet.comfychair.viewmodel.GenerationViewModel
import sh.hnet.comfychair.viewmodel.ImageToImageViewModel
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.MediaViewerViewModel
import sh.hnet.comfychair.viewmodel.SettingsViewModel
import sh.hnet.comfychair.viewmodel.TextToImageViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import sh.hnet.comfychair.viewmodel.VideoToVideoViewModel
import sh.hnet.comfychair.viewmodel.WorkflowManagementViewModel

/**
 * Main navigation host: the generation screens (Image and Video tabs), the Gallery tab and the Settings tab with its
 * pages, above the navigation bar.
 *
 * @param onLeftSettings Called with the route shown when the user leaves the Settings tab (a change made there may
 *   need the generation screens reloaded)
 */
@Composable
fun MainNavHost(
    generationViewModel: GenerationViewModel,
    textToImageViewModel: TextToImageViewModel,
    imageToImageViewModel: ImageToImageViewModel,
    textToVideoViewModel: TextToVideoViewModel,
    imageToVideoViewModel: ImageToVideoViewModel,
    videoToVideoViewModel: VideoToVideoViewModel,
    galleryViewModel: GalleryViewModel,
    mediaViewerViewModel: MediaViewerViewModel,
    settingsViewModel: SettingsViewModel,
    workflowManagementViewModel: WorkflowManagementViewModel,
    onLogout: () -> Unit,
    onLeftSettings: (route: String) -> Unit,
    startDestination: String = MainRoute.TextToImage.route,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Sends a generated medium to a screen as its source and switches to that screen the way the navigation bar
    // does. The image screens take an image or a video's last frame, Video to Video a video (at most 100 MB).
    val useAsSource: UseAsSource = { target, load ->
        scope.launch {
            fun toast(messageRes: Int) = Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
            val media = load()
            if (media == null) {
                toast(if (target == SourceTarget.VIDEO_TO_VIDEO) R.string.error_source_video_unreadable else R.string.error_failed_load_image)
                return@launch
            }
            if (media is SourceMedia.TooLargeVideo ||
                (target == SourceTarget.VIDEO_TO_VIDEO && media is SourceMedia.Video &&
                    media.bytes.size > VideoToVideoViewModel.MAX_SOURCE_BYTES)) {
                toast(R.string.error_source_video_too_large)
                return@launch
            }
            val route = when (target) {
                SourceTarget.VIDEO_TO_VIDEO -> {
                    val video = media as? SourceMedia.Video ?: return@launch
                    if (!videoToVideoViewModel.useAsSource(context, video.bytes)) {
                        toast(R.string.error_source_video_unreadable)
                        return@launch
                    }
                    MainRoute.VideoToVideo
                }
                SourceTarget.IMAGE_TO_IMAGE, SourceTarget.IMAGE_TO_VIDEO -> {
                    val bitmap = when (media) {
                        is SourceMedia.Image -> media.bitmap
                        is SourceMedia.Video -> withContext(Dispatchers.IO) { VideoFrames.lastFrame(media.bytes) }
                        SourceMedia.TooLargeVideo -> null
                    }
                    if (bitmap == null) {
                        toast(R.string.error_last_frame_unreadable)
                        return@launch
                    }
                    if (target == SourceTarget.IMAGE_TO_IMAGE) {
                        imageToImageViewModel.useAsSource(context, bitmap)
                        MainRoute.ImageToImage
                    } else {
                        imageToVideoViewModel.useAsSource(context, bitmap)
                        MainRoute.ImageToVideo
                    }
                }
            }
            navController.navigateToScreen(route)
        }
    }

    // The media viewer, over everything below (navigation bar included)
    val viewerOpen by mediaViewerViewModel.isOpen.collectAsState()
    val mediaViewer = remember(mediaViewerViewModel) { MediaViewerOpener(context, mediaViewerViewModel) }
    val mediaOrigins = remember { MediaOrigins() }

    val resources = LocalResources.current
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val currentScreen = mainRouteOf(currentRoute)
    val currentTab = currentScreen?.outputTab

    // A change in Settings may need the generation screens reloaded: tell the activity when the user leaves the tab
    var wasInSettings by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        val inSettings = isSettingsRoute(currentRoute)
        if (wasInSettings && !inSettings && currentRoute != null) onLeftSettings(currentRoute)
        wasInSettings = inSettings
    }

    // The source used last in each tab, so the tab opens where the user left it
    var lastImageRoute by rememberSaveable { mutableStateOf(MainRoute.TextToImage.route) }
    var lastVideoRoute by rememberSaveable { mutableStateOf(MainRoute.TextToVideo.route) }
    LaunchedEffect(currentScreen) {
        when (currentScreen?.outputTab) {
            OutputTab.IMAGE -> lastImageRoute = currentScreen.route
            OutputTab.VIDEO -> lastVideoRoute = currentScreen.route
            null -> Unit
        }
    }

    // Results of your jobs that finished on a screen not shown: a dot on its tab until visited, and a notice
    val queueState by JobRegistry.queueState.collectAsState()
    val unseenResults = remember { mutableStateListOf<OutputTab>() }
    LaunchedEffect(currentTab) { currentTab?.let { unseenResults.remove(it) } }
    val ownJobsPerTab = OutputTab.entries.associateWith { tab ->
        queueState.ownJobs.values.count {
            (it.status == JobStatus.PENDING || it.status == JobStatus.EXECUTING) && routeForOwner(it.ownerId)?.outputTab == tab
        }
    }
    val transitions = rememberScreenTransitions()
    val snackbarHostState = remember { SnackbarHostState() }
    // Read when a result arrives, not when the collector started
    val shownScreen by rememberUpdatedState(currentScreen)
    LaunchedEffect(Unit) {
        JobRegistry.readyOwners.collect { ownerId ->
            val route = routeForOwner(ownerId) ?: return@collect
            val tab = route.outputTab ?: return@collect
            if (route == shownScreen) return@collect
            if (tab != shownScreen?.outputTab && tab !in unseenResults) unseenResults.add(tab)
            launch {
                val message = resources.getString(if (tab == OutputTab.VIDEO) R.string.msg_video_ready else R.string.msg_image_ready)
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = resources.getString(R.string.button_view),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) navController.navigateToScreen(route)
            }
        }
    }

    // The gallery's selection toolbar takes the navigation bar's place
    val galleryState by galleryViewModel.uiState.collectAsState()
    val gallerySelecting = currentScreen == MainRoute.Gallery && galleryState.isSelectionMode

    CompositionLocalProvider(
        LocalMediaViewer provides mediaViewer,
        LocalMediaOrigins provides mediaOrigins,
        LocalAppSnackbarHostState provides snackbarHostState
    ) {
        Box(Modifier.fillMaxSize()) {
            // Video players under the viewer step aside while it's open (the player is shared)
            CompositionLocalProvider(LocalCoveredByMediaViewer provides viewerOpen) {
                Scaffold(
                    bottomBar = {
                        AnimatedVisibility(
                            visible = !gallerySelecting,
                            enter = expandVertically(expandFrom = Alignment.Top),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            MainNavigationBar(
                                navController = navController,
                                lastImageRoute = mainRouteOf(lastImageRoute) ?: MainRoute.TextToImage,
                                lastVideoRoute = mainRouteOf(lastVideoRoute) ?: MainRoute.TextToVideo,
                                ownJobs = ownJobsPerTab,
                                unseenResults = unseenResults.toSet()
                            )
                        }
                    },
                    snackbarHost = {
                        // Above the generation screens' controls (prompt and Generate), so it doesn't cover them
                        SnackbarHost(snackbarHostState) { data ->
                            // Rounded and inset like the preview; standard snackbar colours
                            Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    },
                    modifier = modifier.imePadding()
                ) { paddingValues ->
                    // The source switch on each screen shows the other screens of its tab
                    CompositionLocalProvider(LocalScreenNavigator provides { route: MainRoute -> navController.navigateToScreen(route) }) {
                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            modifier = Modifier.padding(paddingValues),
                            enterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route, back = false) },
                            exitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route, back = false) },
                            popEnterTransition = { transitions.enter(initialState.destination.route, targetState.destination.route, back = true) },
                            popExitTransition = { transitions.exit(initialState.destination.route, targetState.destination.route, back = true) }
                        ) {
                            composable(MainRoute.TextToImage.route) {
                                TextToImageScreen(
                                    generationViewModel = generationViewModel,
                                    textToImageViewModel = textToImageViewModel,
                                    onUseAsSource = useAsSource
                                )
                            }

                            composable(MainRoute.ImageToImage.route) {
                                ImageToImageScreen(
                                    generationViewModel = generationViewModel,
                                    imageToImageViewModel = imageToImageViewModel,
                                    onUseAsSource = useAsSource
                                )
                            }

                            composable(MainRoute.TextToVideo.route) {
                                TextToVideoScreen(
                                    generationViewModel = generationViewModel,
                                    textToVideoViewModel = textToVideoViewModel,
                                    onUseAsSource = useAsSource
                                )
                            }

                            // Server history; the same GenerationViewModel as the generation screens
                            composable(MainRoute.Gallery.route) {
                                GalleryScreen(
                                    generationViewModel = generationViewModel,
                                    galleryViewModel = galleryViewModel
                                )
                            }

                            // Settings tab: the list, and the pages it opens (Back returns to the list)
                            composable(MainRoute.Settings.route) {
                                SettingsRootScreen(settingsViewModel, onOpenPage = { page -> navController.navigate(page.route) })
                            }
                            composable(SettingsRoute.Server.route) {
                                ServerSettingsScreen(
                                    viewModel = settingsViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onLogout = onLogout
                                )
                            }
                            composable(SettingsRoute.Workflows.route) {
                                WorkflowsSettingsScreen(
                                    viewModel = workflowManagementViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onOpenDetails = { id -> navController.navigate(SettingsRoute.WorkflowDetail.of(id)) }
                                )
                            }
                            composable(
                                SettingsRoute.WorkflowDetail.route,
                                arguments = listOf(navArgument(SettingsRoute.WorkflowDetail.ARG) { type = NavType.StringType })
                            ) { entry ->
                                WorkflowDetailPage(
                                    viewModel = workflowManagementViewModel,
                                    workflowId = entry.arguments?.getString(SettingsRoute.WorkflowDetail.ARG).orEmpty(),
                                    onNavigateBack = { navController.popBackStack() },
                                    // A copy replaces this page, so Back returns to the list
                                    onOpenDetails = { id ->
                                        navController.navigate(SettingsRoute.WorkflowDetail.of(id)) {
                                            popUpTo(SettingsRoute.WorkflowDetail.route) { inclusive = true }
                                        }
                                    }
                                )
                            }
                            // The app's and the data's topic pages
                            val back: () -> Unit = { navController.popBackStack() }
                            composable(SettingsRoute.Connection.route) { ConnectionSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.Generation.route) { GenerationSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.MediaPrivacy.route) { MediaPrivacySettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.Language.route) { LanguageSettingsPage(back) }
                            composable(SettingsRoute.WorkflowEditor.route) { WorkflowEditorSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.Backup.route) { BackupSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.Cache.route) { CacheSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.DebugLog.route) { DebugLogSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.Reset.route) { ResetSettingsPage(settingsViewModel, back) }
                            composable(SettingsRoute.About.route) {
                                AboutSettingsScreen(
                                    onNavigateBack = back,
                                    onOpenWhatsNew = { navController.navigate(SettingsRoute.WhatsNew.route) }
                                )
                            }
                            composable(SettingsRoute.WhatsNew.route) { WhatsNewSettingsScreen(back) }

                            composable(MainRoute.ImageToVideo.route) {
                                ImageToVideoScreen(
                                    generationViewModel = generationViewModel,
                                    imageToVideoViewModel = imageToVideoViewModel,
                                    onUseAsSource = useAsSource
                                )
                            }

                            composable(MainRoute.VideoToVideo.route) {
                                VideoToVideoScreen(
                                    generationViewModel = generationViewModel,
                                    videoToVideoViewModel = videoToVideoViewModel,
                                    onUseAsSource = useAsSource
                                )
                            }
                        }
                    }
                }
            }
            // Messages and the restore confirmation of every settings page
            SettingsEventsHost(settingsViewModel)
            MediaViewerOverlay(mediaViewerViewModel, onUseAsSource = { target, media -> useAsSource(target) { media } })
        }
    }
}

/**
 * Transitions between screens, timed by the theme's (standard) motion scheme:
 * - another tab: fade through (the old screen fades out quickly, the new one fades in growing slightly);
 * - another source in the same tab: a quick crossfade, as it's a choice within one place;
 * - Settings list and its pages: shared axis X (the page comes in from the end, and goes back there on Back).
 */
private class ScreenTransitions(
    private val fastFade: FiniteAnimationSpec<Float>,
    private val fade: FiniteAnimationSpec<Float>,
    private val scale: FiniteAnimationSpec<Float>,
    private val slide: FiniteAnimationSpec<IntOffset>,
    private val axisOffsetPx: Int
) {
    private enum class Kind { TAB, SOURCE, AXIS }

    private fun kind(from: String?, to: String?): Kind {
        val fromScreen = mainRouteOf(from)
        val toScreen = mainRouteOf(to)
        return when {
            isSettingsRoute(from) && isSettingsRoute(to) -> Kind.AXIS
            fromScreen?.outputTab != null && fromScreen.outputTab == toScreen?.outputTab -> Kind.SOURCE
            else -> Kind.TAB
        }
    }

    fun enter(from: String?, to: String?, back: Boolean): EnterTransition = when (kind(from, to)) {
        Kind.TAB -> fadeIn(fade) + scaleIn(scale, initialScale = 0.92f)
        Kind.SOURCE -> fadeIn(fastFade)
        Kind.AXIS -> slideInHorizontally(slide) { if (back) -axisOffsetPx else axisOffsetPx } + fadeIn(fade)
    }

    fun exit(from: String?, to: String?, back: Boolean): ExitTransition = when (kind(from, to)) {
        Kind.TAB, Kind.SOURCE -> fadeOut(fastFade)
        Kind.AXIS -> slideOutHorizontally(slide) { if (back) axisOffsetPx else -axisOffsetPx } + fadeOut(fastFade)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberScreenTransitions(): ScreenTransitions {
    val motion = MaterialTheme.motionScheme
    val axisOffsetPx = with(LocalDensity.current) { 30.dp.roundToPx() }
    return remember(motion, axisOffsetPx) {
        ScreenTransitions(
            fastFade = motion.fastEffectsSpec(),
            fade = motion.defaultEffectsSpec(),
            scale = motion.defaultSpatialSpec(),
            slide = motion.defaultSpatialSpec(),
            axisOffsetPx = axisOffsetPx
        )
    }
}
