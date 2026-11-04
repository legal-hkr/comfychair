package sh.hnet.comfychair.ui.components.generation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.ui.components.config.CommonGenerationState
import sh.hnet.comfychair.ui.components.shared.PromptPresetDropdown
import sh.hnet.comfychair.ui.components.shared.TooltipIconButton
import sh.hnet.comfychair.ui.components.shared.rememberSpellCheckVisualTransformation
import sh.hnet.comfychair.viewmodel.PromptPresetUiState
import sh.hnet.comfychair.viewmodel.PromptPresetViewModel

/** The two prompts of a workflow, as tabs of the prompt editor. */
enum class PromptTab { POSITIVE, NEGATIVE }

private val ComposerShape = RoundedCornerShape(24.dp)

/**
 * The prompt in the dock and its full-screen editor. The dock shows the positive prompt (with the prompt library and
 * clear); tapping it opens the editor. A workflow with only a negative prompt shows that one, opening the editor on its
 * tab; a workflow without prompts shows nothing.
 *
 * @param preview Shown small in the editor, so what is described stays in view
 * @param generateEnabled Whether the editor's Generate may submit (as the dock's Generate button)
 * @param onGenerate Submits a job; the editor closes, back to the screen to follow it
 */
@Composable
fun PromptDock(
    state: CommonGenerationState,
    onPositiveChange: (String) -> Unit,
    onNegativeChange: (String) -> Unit,
    preview: Bitmap?,
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState,
    spellCheckEnabled: Boolean,
    generateEnabled: Boolean,
    onGenerate: () -> Unit
) {
    val hasPositive = state.capabilities.hasPositivePrompt
    val hasNegative = state.capabilities.hasNegativePrompt
    if (!hasPositive && !hasNegative) return
    var editorTab by rememberSaveable { mutableStateOf<PromptTab?>(null) }
    val positiveChange: (String) -> Unit = {
        onPositiveChange(it)
        presetViewModel.clearActivePreset()
    }

    if (hasPositive) {
        PromptComposer(
            text = state.positivePrompt,
            placeholderRes = R.string.hint_describe_generation,
            onOpen = { editorTab = PromptTab.POSITIVE },
            onClear = { positiveChange("") },
            leading = { PromptLibraryButton(state.positivePrompt, presetViewModel, presetUiState) }
        )
    } else {
        PromptComposer(
            text = state.negativePrompt,
            placeholderRes = R.string.hint_negative_prompt,
            onOpen = { editorTab = PromptTab.NEGATIVE },
            onClear = { onNegativeChange("") }
        )
    }

    editorTab?.let { tab ->
        PromptEditor(
            initialTab = tab,
            hasPositive = hasPositive,
            hasNegative = hasNegative,
            positive = state.positivePrompt,
            onPositiveChange = positiveChange,
            negative = state.negativePrompt,
            onNegativeChange = onNegativeChange,
            preview = preview,
            presetViewModel = presetViewModel,
            presetUiState = presetUiState,
            spellCheckEnabled = spellCheckEnabled,
            generateEnabled = generateEnabled,
            onGenerate = {
                onGenerate()
                editorTab = null
            },
            onDismiss = { editorTab = null }
        )
    }
}

/** The prompt as the dock shows it: a filled field of up to three lines; tapping it opens the editor. */
@Composable
private fun PromptComposer(
    text: String,
    placeholderRes: Int,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val placeholder = stringResource(placeholderRes)
    Surface(
        onClick = onOpen,
        shape = ComposerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = if (leading != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            Text(
                text = text.ifEmpty { placeholder },
                style = MaterialTheme.typography.bodyLarge,
                color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
            )
            if (text.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.content_description_clear))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/** The prompt library menu: favorites, the library, saving and resetting the prompt. */
@Composable
private fun PromptLibraryButton(prompt: String, presetViewModel: PromptPresetViewModel, presetUiState: PromptPresetUiState) {
    PromptPresetDropdown(
        favorites = presetUiState.favorites,
        activePresetId = presetUiState.activePresetId,
        currentPromptIsEmpty = prompt.isEmpty(),
        onPresetSelected = { presetViewModel.onPresetSelected(it) },
        onOpenLibrary = { presetViewModel.showLibrary() },
        onSaveCurrentPrompt = { presetViewModel.showSaveDialog(prompt) },
        onResetPrompt = { presetViewModel.resetPrompt() }
    )
}

/**
 * Full-screen prompt editor over the screen, above the keyboard: the result in small, the Positive and Negative
 * prompts as tabs (Negative only when the workflow has it), the text, and a row above the keyboard with the prompt
 * library, saving to it, clearing, and Generate, which also closes it. Done or Back closes it; the text is kept as
 * typed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromptEditor(
    initialTab: PromptTab,
    hasPositive: Boolean,
    hasNegative: Boolean,
    positive: String,
    onPositiveChange: (String) -> Unit,
    negative: String,
    onNegativeChange: (String) -> Unit,
    preview: Bitmap?,
    presetViewModel: PromptPresetViewModel,
    presetUiState: PromptPresetUiState,
    spellCheckEnabled: Boolean,
    generateEnabled: Boolean,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val text = if (tab == PromptTab.POSITIVE) positive else negative
    val onTextChange = if (tab == PromptTab.POSITIVE) onPositiveChange else onNegativeChange

    // The field keeps its own selection; the cursor starts at the end, and changes made elsewhere (a preset, clearing)
    // replace the text
    var field by remember(tab) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) { if (text != field.text) field = TextFieldValue(text, TextRange(text.length)) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(tab) { focusRequester.requestFocus() }
    val transformation = rememberSpellCheckVisualTransformation(text = field.text, enabled = spellCheckEnabled)
    val previewBitmap = remember(preview) { preview?.asImageBitmap() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            // Safe drawing includes the keyboard, so the row above it stays in view
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    previewBitmap?.let {
                        Image(
                            it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        )
                    }
                    if (hasPositive && hasNegative) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                            ToggleButton(
                                checked = tab == PromptTab.POSITIVE,
                                onCheckedChange = { if (it) tab = PromptTab.POSITIVE },
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                            ) { Text(stringResource(R.string.tab_prompt_positive)) }
                            ToggleButton(
                                checked = tab == PromptTab.NEGATIVE,
                                onCheckedChange = { if (it) tab = PromptTab.NEGATIVE },
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                            ) { Text(stringResource(R.string.tab_prompt_negative)) }
                        }
                    } else {
                        Text(
                            stringResource(if (tab == PromptTab.POSITIVE) R.string.tab_prompt_positive else R.string.tab_prompt_negative),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TooltipIconButton(Icons.Rounded.Check, stringResource(R.string.button_done), onDismiss)
                }

                TextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != text) onTextChange(it.text)
                    },
                    placeholder = {
                        Text(stringResource(if (tab == PromptTab.POSITIVE) R.string.hint_describe_generation else R.string.hint_negative_prompt))
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = spellCheckEnabled),
                    shape = RoundedCornerShape(24.dp),
                    // No underline: the rounded container is the field
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .focusRequester(focusRequester)
                )

                // Above the keyboard
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tab == PromptTab.POSITIVE) {
                        PromptLibraryButton(positive, presetViewModel, presetUiState)
                        TooltipIconButton(
                            Icons.Rounded.BookmarkAdd,
                            stringResource(R.string.metadata_save_prompt),
                            { presetViewModel.showSaveDialog(positive) },
                            enabled = positive.isNotBlank()
                        )
                    }
                    TooltipIconButton(
                        Icons.AutoMirrored.Rounded.Backspace,
                        stringResource(R.string.content_description_clear),
                        { onTextChange("") },
                        enabled = text.isNotEmpty()
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onGenerate,
                        enabled = generateEnabled,
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.button_generate))
                    }
                }
            }
        }
    }
}
