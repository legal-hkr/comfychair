package sh.hnet.comfychair.ui.screens

import android.content.Context
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.AppLogo
import sh.hnet.comfychair.ui.components.ServerFormFields
import sh.hnet.comfychair.ui.components.ServerFormState
import sh.hnet.comfychair.ui.components.dialogs.FormPage
import sh.hnet.comfychair.ui.components.rememberServerFormMessages
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsRadioRow

/** The welcome wizard's pages. New pages (e.g. a tour of the app) are added here and pushed onto the page list. */
enum class WizardPage { WELCOME, ADD_SERVER, RESTORE }

/** What the user chose to do on the welcome page. */
enum class WelcomeChoice { ADD_SERVER, RESTORE }

object FirstRun {
    /**
     * Whether the app starts with no configuration at all: no server and no app setting stored (a fresh install, or
     * its data cleared). No "wizard done" flag: the wizard ends by saving a server, which makes this false.
     */
    fun isNeeded(context: Context): Boolean =
        ServerStorage(context).getServers().isEmpty() && !AppSettings.hasAnyValue(context)
}

private val pagesSaver: Saver<List<WizardPage>, Any> = listSaver(
    save = { pages -> pages.map { it.name } },
    restore = { names -> names.map { WizardPage.valueOf(it) } }
)

/**
 * The welcome wizard (first run): a list of pages, the first one the welcome page; the user moves forward by pushing a
 * page and back by popping it (Back on the welcome page leaves the app, as the login screen does). It can't be
 * skipped: it ends only when a server has been saved or a backup restored, by calling [onFinished]; the login screen
 * then shows with that server selected.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeWizard(onFinished: () -> Unit) {
    val context = LocalContext.current
    var pages by rememberSaveable(stateSaver = pagesSaver) { mutableStateOf(listOf(WizardPage.WELCOME)) }
    var choice by rememberSaveable { mutableStateOf<WelcomeChoice?>(null) }
    // The server form belongs to the wizard, not its page: going back to the welcome page keeps what was typed
    val serverForm = remember { ServerFormState(server = null, existingCredentials = AuthCredentials.None) }

    // Forward: the next page comes in from the end; back (also driven by the predictive back gesture) the reverse
    var goingBack by remember { mutableStateOf(false) }
    val transitionState = remember { SeekableTransitionState(pages.last()) }
    val transition = rememberTransition(transitionState, label = "wizard")
    val scope = rememberCoroutineScope()
    LaunchedEffect(pages.last()) { transitionState.animateTo(pages.last()) }

    fun push(page: WizardPage) {
        goingBack = false
        pages = pages + page
    }

    PredictiveBackHandler(enabled = pages.size > 1) { progress ->
        val previous = pages[pages.size - 2]
        goingBack = true
        try {
            progress.collect { event -> transitionState.seekTo(event.progress, targetState = previous) }
            pages = pages.dropLast(1)
        } catch (e: CancellationException) {
            scope.launch { transitionState.animateTo(pages.last()) }
            throw e
        }
    }

    val motion = MaterialTheme.motionScheme
    val axisOffsetPx = with(LocalDensity.current) { 30.dp.roundToPx() }
    transition.AnimatedContent(
        transitionSpec = {
            val back = goingBack
            (slideInHorizontally(motion.defaultSpatialSpec()) { if (back) -axisOffsetPx else axisOffsetPx } +
                fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(
                    slideOutHorizontally(motion.defaultSpatialSpec()) { if (back) axisOffsetPx else -axisOffsetPx } +
                        fadeOut(motion.fastEffectsSpec())
                )
                .using(SizeTransform(clip = false))
        }
    ) { page ->
        when (page) {
            WizardPage.WELCOME -> WelcomePage(
                choice = choice,
                onChoose = { choice = it },
                onNext = {
                    when (choice) {
                        WelcomeChoice.ADD_SERVER -> push(WizardPage.ADD_SERVER)
                        WelcomeChoice.RESTORE -> push(WizardPage.RESTORE)
                        null -> Unit
                    }
                }
            )
            WizardPage.ADD_SERVER -> AddServerPage(
                state = serverForm,
                onBack = {
                    goingBack = true
                    pages = pages.dropLast(1)
                },
                onSaved = onFinished
            )
            WizardPage.RESTORE -> RestorePage(
                onBack = {
                    goingBack = true
                    pages = pages.dropLast(1)
                },
                onRestored = onFinished
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WelcomePage(choice: WelcomeChoice?, onChoose: (WelcomeChoice) -> Unit, onNext: () -> Unit) {
    FormPage(
        title = null,
        confirmLabel = stringResource(R.string.button_next),
        onConfirm = onNext,
        confirmEnabled = choice != null,
        contentSpacing = 0.dp
    ) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(
            text = stringResource(R.string.title_welcome),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier
                .padding(horizontal = TITLE_INSET)
                .semantics { heading() }
        )
        Text(
            text = stringResource(R.string.label_welcome_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp)
        )
        Text(
            text = stringResource(R.string.label_welcome_question),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(start = TITLE_INSET, end = TITLE_INSET, top = 32.dp, bottom = 12.dp)
                .semantics { heading() }
        )
        // One choice, nothing preselected; the rows of the login screen's server list
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                row { shapes ->
                    SettingsRadioRow(
                        shapes = shapes,
                        title = stringResource(R.string.label_welcome_add_server),
                        supporting = stringResource(R.string.desc_welcome_add_server),
                        selected = choice == WelcomeChoice.ADD_SERVER,
                        onSelect = { onChoose(WelcomeChoice.ADD_SERVER) },
                        trailing = { Icon(Icons.Rounded.Dns, contentDescription = null) }
                    )
                }
                row { shapes ->
                    SettingsRadioRow(
                        shapes = shapes,
                        title = stringResource(R.string.label_welcome_restore),
                        supporting = stringResource(R.string.desc_welcome_restore),
                        selected = choice == WelcomeChoice.RESTORE,
                        onSelect = { onChoose(WelcomeChoice.RESTORE) },
                        trailing = { Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null) }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The server form as a page: Save validates and saves the server, selected, without connecting. */
@Composable
private fun AddServerPage(state: ServerFormState, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val messages = rememberServerFormMessages()
    val serverStorage = remember { ServerStorage(context.applicationContext) }
    FormPage(
        title = stringResource(R.string.title_welcome_add_server),
        navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
        navigationLabel = stringResource(R.string.content_description_back),
        onNavigate = onBack,
        confirmLabel = stringResource(R.string.button_save),
        onConfirm = {
            if (state.validate(messages, serverStorage::isServerNameTaken)) {
                val server = Server.create(state.name.trim(), state.hostname.trim(), state.port.trim().toInt(), state.authType)
                serverStorage.addServer(server)
                CredentialStorage(context.applicationContext).saveCredentials(server.id, state.buildCredentials())
                serverStorage.setSelectedServerId(server.id)
                onSaved()
            }
        }
    ) {
        ServerFormFields(state)
    }
}

/** Restoring a backup: built in stage 3; for now only the page's frame. */
@Composable
private fun RestorePage(onBack: () -> Unit, onRestored: () -> Unit) {
    FormPage(
        title = stringResource(R.string.title_welcome_restore),
        navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
        navigationLabel = stringResource(R.string.content_description_back),
        onNavigate = onBack,
        confirmLabel = stringResource(R.string.button_backup_choose_file),
        onConfirm = {},
        confirmEnabled = false
    ) {}
}

/** Start inset of the title and texts, which the logo's mark lines up with (as on the login screen). */
private val TITLE_INSET = 8.dp
