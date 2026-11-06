package sh.hnet.comfychair.ui.components.generation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaCache
import sh.hnet.comfychair.cache.MediaCacheKey
import sh.hnet.comfychair.ui.components.MetadataBottomSheet
import sh.hnet.comfychair.ui.components.SourceMedia
import sh.hnet.comfychair.ui.components.SourceTarget
import sh.hnet.comfychair.ui.components.UseAsSource
import sh.hnet.comfychair.ui.components.loadResultBitmap
import sh.hnet.comfychair.ui.components.loadResultVideo
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.GenerationMetadataLoader
import sh.hnet.comfychair.util.GenerationOutput
import sh.hnet.comfychair.util.MediaExport

/**
 * A result the action menu acts on. Everything is loaded on demand into memory; only Save and Share write
 * anything, and only at the user's request.
 *
 * @param loadDetails Generation parameters of the result; null when they can't be read (e.g. a preview restored
 *   without its server file), which hides Details
 */
class ResultMedia(
    val isVideo: Boolean,
    val saveToGallery: suspend (Context) -> Boolean,
    val shareFile: suspend (Context) -> Uri?,
    val loadDetails: (suspend (Context) -> GenerationMetadata?)?
) {
    companion object {
        /** An image shown on the screen; [filename] etc. locate its server file for Details. */
        fun image(bitmap: Bitmap, filename: String?, subfolder: String?, type: String?) = ResultMedia(
            isVideo = false,
            saveToGallery = { context -> MediaExport.saveImage(context, bitmap) },
            shareFile = { context -> MediaExport.shareFile(context, bitmap) },
            loadDetails = filename?.takeIf { it.isNotEmpty() }?.let { name ->
                val load: suspend (Context) -> GenerationMetadata? = {
                    GenerationMetadataLoader.fromServerFile(name, subfolder.orEmpty(), type ?: "output", isVideo = false)
                }
                load
            }
        )

        /** A video shown on the screen, played from RAM or from a stored file. */
        fun video(videoUri: Uri, galleryPrefix: String) = ResultMedia(
            isVideo = true,
            saveToGallery = { context -> MediaExport.saveVideo(context, videoUri, galleryPrefix) },
            shareFile = { context -> MediaExport.shareFile(context, videoUri) },
            loadDetails = { context -> GenerationMetadataLoader.fromVideo(context, videoUri) }
        )

        /**
         * One of several results of a job, loaded from MediaCache (RAM in memory-first mode).
         *
         * @param preloaded The image the screen already shows for this result, if any
         */
        fun output(output: GenerationOutput, galleryPrefix: String, preloaded: Bitmap? = null): ResultMedia {
            if (!output.isVideo) {
                val load: suspend () -> Bitmap? = { preloaded ?: loadResultBitmap(output) }
                return ResultMedia(
                    isVideo = false,
                    saveToGallery = { context -> load()?.let { MediaExport.saveImage(context, it) } ?: false },
                    shareFile = { context -> load()?.let { MediaExport.shareFile(context, it) } },
                    loadDetails = {
                        GenerationMetadataLoader.fromServerFile(output.filename, output.subfolder, output.type, isVideo = false)
                    }
                )
            }
            val key = MediaCacheKey(output.promptId, output.filename)
            val bytes: suspend () -> ByteArray? = { MediaCache.fetchVideoBytes(key, output.subfolder, output.type) }
            return ResultMedia(
                isVideo = true,
                saveToGallery = { context -> bytes()?.let { MediaExport.saveVideo(context, it, galleryPrefix) } ?: false },
                shareFile = { context -> bytes()?.let { MediaExport.shareFile(context, it) } },
                loadDetails = { bytes()?.let { GenerationMetadataLoader.parse(it, isVideo = true) } }
            )
        }
    }
}

/** An action that sends the result to another screen (the menu's primary group). */
class ResultSendAction(val labelRes: Int, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Send actions for an image result: edit it in Image to Image, animate it in Image to Video and, where the screen
 * offers it, use it as a reference image. Each switches screens in one tap and leaves the target's prompt as it is.
 *
 * @param onUseAsReference Null unless Image to Image in Editing mode has a free reference slot
 */
fun imageSendActions(
    onUseAsSource: UseAsSource,
    load: suspend () -> Bitmap?,
    onUseAsReference: (() -> Unit)? = null
): List<ResultSendAction> {
    val loadImage: suspend () -> SourceMedia? = { load()?.let { SourceMedia.Image(it) } }
    return listOfNotNull(
        ResultSendAction(R.string.button_edit_this_image, Icons.Rounded.Edit) { onUseAsSource(SourceTarget.IMAGE_TO_IMAGE, loadImage) },
        ResultSendAction(R.string.button_animate_it, Icons.Rounded.Animation) { onUseAsSource(SourceTarget.IMAGE_TO_VIDEO, loadImage) },
        onUseAsReference?.let { ResultSendAction(R.string.button_use_as_reference, Icons.Rounded.AddPhotoAlternate, it) }
    )
}

/**
 * Send actions for a video result: edit it in Video to Video, or animate on from its last frame in Image to Video.
 * Each switches screens in one tap and leaves the target's prompt as it is.
 */
fun videoSendActions(onUseAsSource: UseAsSource, loadVideo: suspend () -> SourceMedia?): List<ResultSendAction> =
    listOf(
        ResultSendAction(R.string.button_edit_this_video, Icons.Rounded.MovieFilter) { onUseAsSource(SourceTarget.VIDEO_TO_VIDEO, loadVideo) },
        ResultSendAction(R.string.button_animate_last_frame, Icons.Rounded.Animation) { onUseAsSource(SourceTarget.IMAGE_TO_VIDEO, loadVideo) }
    )

/** Send actions for a result of either kind. */
fun resultSendActions(onUseAsSource: UseAsSource, output: GenerationOutput, loadImage: suspend () -> Bitmap?): List<ResultSendAction> =
    if (output.isVideo) videoSendActions(onUseAsSource) { loadResultVideo(output)?.let { SourceMedia.Video(it) } }
    else imageSendActions(onUseAsSource, loadImage)

/**
 * Actions for the result shown in the preview: one small round toggle FAB in the bottom-end corner that opens a menu
 * upward. The send actions come first; Details, Share and Save sit nearest the thumb. While open, a light scrim
 * covers the preview only; tapping it or Back closes the menu.
 *
 * @param resultNumber Position of the result among several, for the content description
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.ResultActionsMenu(
    media: ResultMedia,
    sendActions: List<ResultSendAction>,
    modifier: Modifier = Modifier,
    resultNumber: Int? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    var showDetails by remember { mutableStateOf(false) }
    var detailsLoading by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<GenerationMetadata?>(null) }

    // Standard M3 scrim (as under modal sheets), over the preview only, so the result stays recognisable behind the menu
    val scrimAlpha by animateFloatAsState(
        targetValue = if (expanded) 0.32f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "result actions scrim"
    )
    if (scrimAlpha > 0f) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                // Catches taps meant for the preview below; Back closes the menu for TalkBack users
                .pointerInput(Unit) { detectTapGestures { expanded = false } }
        )
    }

    val description = when {
        resultNumber != null -> stringResource(R.string.content_description_result_actions_numbered, resultNumber)
        media.isVideo -> stringResource(R.string.content_description_video_actions)
        else -> stringResource(R.string.content_description_result_actions)
    }
    fun act(action: suspend () -> Unit) {
        expanded = false
        scope.launch { action() }
    }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(12.dp),
        horizontalAlignment = Alignment.End,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                // Standard toggle FAB colours (primary container, primary when open), so it stands out on any image
                containerSize = { 48.dp },
                // Circle when closed, rounded square when open
                containerCornerRadius = { progress -> lerp(24.dp, 14.dp, progress) },
                // A toggle: TalkBack announces its expanded state itself
                modifier = Modifier.semantics { contentDescription = description }
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.MoreHoriz } }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress })
                )
            }
        }
    ) {
        sendActions.forEach { action ->
            FloatingActionButtonMenuItem(
                onClick = { act { action.onClick() } },
                icon = { Icon(action.icon, contentDescription = null) },
                text = { MenuItemText(stringResource(action.labelRes)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        // Secondary group, separated from the send actions by a small gap
        val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
        val secondaryContent = MaterialTheme.colorScheme.onSecondaryContainer
        val gap = if (sendActions.isNotEmpty()) Modifier.padding(top = 6.dp) else Modifier
        media.loadDetails?.let { load ->
            FloatingActionButtonMenuItem(
                onClick = {
                    act {
                        showDetails = true
                        detailsLoading = true
                        details = load(context)
                        detailsLoading = false
                    }
                },
                icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                text = { MenuItemText(stringResource(R.string.button_details)) },
                modifier = gap,
                containerColor = secondaryContainer,
                contentColor = secondaryContent
            )
        }
        FloatingActionButtonMenuItem(
            onClick = {
                act {
                    val uri = media.shareFile(context)
                    if (uri == null) {
                        val messageRes = if (media.isVideo) R.string.error_share_video else R.string.error_share_image
                        AppErrors.notify(context, messageRes)
                    } else {
                        val kind = if (media.isVideo) MediaExport.Kind.VIDEO else MediaExport.Kind.IMAGE
                        val titleRes = if (media.isVideo) R.string.share_video else R.string.share_image
                        MediaExport.startShare(context, listOf(uri), kind.mimeType, resources.getString(titleRes))
                    }
                }
            },
            icon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            text = { MenuItemText(stringResource(R.string.button_share_result)) },
            modifier = if (media.loadDetails == null) gap else Modifier,
            containerColor = secondaryContainer,
            contentColor = secondaryContent
        )
        FloatingActionButtonMenuItem(
            onClick = {
                act {
                    val messageRes = when {
                        media.saveToGallery(context) ->
                            if (media.isVideo) R.string.msg_video_saved_to_gallery else R.string.msg_image_saved_to_gallery
                        else -> if (media.isVideo) R.string.error_save_video else R.string.error_save_image
                    }
                    AppErrors.notify(context, messageRes)
                }
            },
            icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
            text = { MenuItemText(stringResource(R.string.button_save_result)) },
            containerColor = secondaryContainer,
            contentColor = secondaryContent
        )
    }

    if (showDetails) {
        MetadataBottomSheet(
            metadata = details,
            isLoading = detailsLoading,
            onDismiss = {
                showDetails = false
                details = null
            }
        )
    }
}

@Composable
private fun MenuItemText(text: String) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
