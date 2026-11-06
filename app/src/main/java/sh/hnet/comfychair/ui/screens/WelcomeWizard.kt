package sh.hnet.comfychair.ui.screens

import android.content.Context
import android.icu.text.ListFormatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.AuthCredentials
import sh.hnet.comfychair.model.Server
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.BackupCheck
import sh.hnet.comfychair.storage.BackupManager
import sh.hnet.comfychair.storage.BackupRestore
import sh.hnet.comfychair.storage.CredentialStorage
import sh.hnet.comfychair.storage.RestoreResult
import sh.hnet.comfychair.storage.ServerStorage
import sh.hnet.comfychair.ui.components.AppLogo
import sh.hnet.comfychair.ui.components.ErrorDetailsDialog
import sh.hnet.comfychair.ui.components.ServerFormFields
import sh.hnet.comfychair.ui.components.ServerFormState
import sh.hnet.comfychair.ui.components.dialogs.FormPage
import sh.hnet.comfychair.ui.components.rememberServerFormMessages
import sh.hnet.comfychair.ui.components.settings.SettingsGroup
import sh.hnet.comfychair.ui.components.settings.SettingsRadioRow
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors

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

/** Where the restore page is: nothing picked, a checked file ready to restore, or a refusal or failure to show. */
private sealed interface RestoreStep {
    data object NoFile : RestoreStep
    data class Ready(val file: BackupRestore.BackupFile, val contents: BackupCheck.Valid) : RestoreStep
    /** The file was refused before restoring; [check] says why (null: it couldn't be opened). */
    data class Refused(val fileName: String, val check: BackupCheck?) : RestoreStep
    data class Failed(val details: String?) : RestoreStep
}

/**
 * Restoring a backup: what comes back (and that credentials don't), the picked file with what it holds – on a fresh
 * install there is nothing to overwrite, so the file card is the confirmation – then Restore. While reading or
 * restoring, the button is busy and Back waits. A refused file or a failure is shown on the page; another file can be
 * picked or the user can go back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RestorePage(onBack: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<RestoreStep>(RestoreStep.NoFile) }
    var busy by remember { mutableStateOf(false) }
    var shownError by remember { mutableStateOf<AppError?>(null) }
    val unnamed = stringResource(R.string.label_backup_unnamed_file)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val file = BackupRestore.read(context, uri)
            step = when (val check = file?.let { BackupRestore.check(context, it.json) }) {
                null -> RestoreStep.Refused(unnamed, null)
                is BackupCheck.Valid -> RestoreStep.Ready(file, check)
                else -> RestoreStep.Refused(file.displayName ?: unnamed, check)
            }
            busy = false
        }
    }
    val pick = { picker.launch("application/json") }

    // Back waits while the file is read or restored (registered after the wizard's handler, so it comes first)
    BackHandler(enabled = busy) {}

    val ready = step as? RestoreStep.Ready
    FormPage(
        title = stringResource(R.string.title_welcome_restore),
        navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
        navigationLabel = stringResource(R.string.content_description_back),
        onNavigate = onBack,
        confirmLabel = stringResource(
            when (step) {
                RestoreStep.NoFile -> R.string.button_backup_choose_file
                is RestoreStep.Ready -> R.string.button_restore
                else -> R.string.button_backup_choose_another
            }
        ),
        confirmIcon = if (ready != null) Icons.Rounded.SettingsBackupRestore else Icons.Rounded.FolderOpen,
        busy = busy,
        contentSpacing = 16.dp,
        onConfirm = {
            if (ready == null) {
                pick()
            } else {
                busy = true
                scope.launch {
                    when (val result = BackupRestore.restore(context, ready.file.json)) {
                        is RestoreResult.Success -> {
                            // A partial restore was reported with the skipped items
                            if (result.skippedWorkflows.isEmpty() && result.skippedPrompts.isEmpty()) {
                                AppErrors.notify(context, R.string.msg_backup_restored)
                            }
                            onRestored()
                        }
                        // Checked before restoring, so only if the file changed in between
                        is RestoreResult.Failure -> step = RestoreStep.Refused(ready.file.displayName ?: unnamed, BackupManager(context).check(ready.file.json))
                        is RestoreResult.Crashed -> step = RestoreStep.Failed(result.details)
                    }
                    busy = false
                }
            }
        }
    ) {
        Text(
            text = stringResource(R.string.label_backup_brings_back),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BackupContentsCard()
        NoCredentialsNote()
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()))
            }
        ) { shown ->
            when (shown) {
                RestoreStep.NoFile -> Unit
                is RestoreStep.Ready -> BackupFileCard(shown.file.displayName ?: unnamed, shown.contents, enabled = !busy, onChange = pick)
                is RestoreStep.Refused -> {
                    val (title, body) = when (shown.check) {
                        null, BackupCheck.Unreadable -> R.string.error_backup_unreadable_title to R.string.error_backup_unreadable_body
                        BackupCheck.NotBackup -> R.string.error_backup_not_backup_title to R.string.error_backup_not_backup_body
                        BackupCheck.UnsupportedVersion -> R.string.error_backup_unsupported_title to R.string.error_backup_unsupported_body
                        BackupCheck.NoServers, is BackupCheck.Valid -> R.string.error_backup_no_servers_title to R.string.error_backup_no_servers_body
                    }
                    RestoreErrorCard(stringResource(title), stringResource(body, shown.fileName))
                }
                is RestoreStep.Failed -> {
                    val title = stringResource(R.string.error_backup_failed_title)
                    RestoreErrorCard(
                        title = title,
                        body = stringResource(R.string.error_backup_failed_body),
                        onShow = shown.details?.let { details -> { shownError = AppError(title, text = details) } }
                    )
                }
            }
        }
    }
    shownError?.let { ErrorDetailsDialog(it, onDismiss = { shownError = null }) }
}

/** What a backup brings back: a plain list in one card, not interactive. */
@Composable
private fun BackupContentsCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            BackupContentsRow(Icons.Rounded.Dns, stringResource(R.string.label_backup_item_servers), stringResource(R.string.desc_backup_item_servers))
            BackupContentsRow(Icons.Rounded.AccountTree, stringResource(R.string.label_backup_item_workflows))
            BackupContentsRow(Icons.Rounded.Bookmark, stringResource(R.string.label_backup_item_prompts))
            BackupContentsRow(Icons.Rounded.Tune, stringResource(R.string.label_backup_item_settings))
        }
    }
}

@Composable
private fun BackupContentsRow(icon: ImageVector, title: String, supporting: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Backups carry no passwords or tokens: said before a file is picked. */
@Composable
private fun NoCredentialsNote() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Icon(Icons.Rounded.KeyOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)) {
                    append(stringResource(R.string.label_backup_no_credentials_title))
                }
                append(" ")
                append(stringResource(R.string.label_backup_no_credentials_body))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The picked file: its name, what it holds, and Change to pick another. */
@Composable
private fun BackupFileCard(name: String, contents: BackupCheck.Valid, enabled: Boolean, onChange: () -> Unit) {
    val resources = LocalResources.current
    val parts = buildList {
        if (contents.servers > 0) add(resources.getQuantityString(R.plurals.label_backup_servers_count, contents.servers, contents.servers))
        if (contents.workflows > 0) add(resources.getQuantityString(R.plurals.label_backup_workflows_count, contents.workflows, contents.workflows))
        if (contents.prompts > 0) add(resources.getQuantityString(R.plurals.label_backup_prompts_count, contents.prompts, contents.prompts))
    }
    val summary = if (parts.isEmpty()) stringResource(R.string.label_backup_settings_only) else ListFormatter.getInstance().format(parts)
    val description = stringResource(R.string.content_description_backup_file_contents, name, summary)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = description }
            ) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onChange, enabled = enabled) {
                Text(stringResource(R.string.button_backup_change))
            }
        }
    }
}

/** A refused file or a failed restore, in place of the file card; announced when it appears. */
@Composable
private fun RestoreErrorCard(title: String, body: String, onShow: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (onShow != null) {
                    TextButton(onClick = onShow, modifier = Modifier.offset(x = (-12).dp)) {
                        Text(stringResource(R.string.button_view))
                    }
                }
            }
        }
    }
}

/** Start inset of the title and texts, which the logo's mark lines up with (as on the login screen). */
private val TITLE_INSET = 8.dp
