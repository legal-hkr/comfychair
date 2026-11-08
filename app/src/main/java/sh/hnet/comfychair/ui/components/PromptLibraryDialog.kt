package sh.hnet.comfychair.ui.components

import sh.hnet.comfychair.ui.components.settings.groupShapes
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.ui.platform.LocalResources
import sh.hnet.comfychair.ui.components.shared.highlighted
import sh.hnet.comfychair.ui.components.shared.SearchFilterChips
import sh.hnet.comfychair.ui.components.shared.SearchFilter
import sh.hnet.comfychair.ui.components.shared.SearchField
import sh.hnet.comfychair.ui.components.shared.SearchEmptyState
import sh.hnet.comfychair.ui.components.shared.SearchCountLine
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.ui.components.dialogs.ConfirmDialog
import sh.hnet.comfychair.ui.components.shared.NoOverscrollContainer

/**
 * The prompt library, full screen: search, a Favourites chip and tag chips, then the prompts as segmented rows. Tapping
 * a prompt uses it and closes; holding it opens Edit, Duplicate and Delete (confirmed). Empty, it offers to save the
 * screen's current prompt.
 *
 * @param onSaveCurrent Saves the screen's current prompt; null when it's empty
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PromptLibraryDialog(
    presets: List<PromptPreset>,
    availableTags: Set<String>,
    searchQuery: String,
    selectedTags: Set<String>,
    filterFavoritesOnly: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onTagToggle: (String) -> Unit,
    onToggleFavoritesFilter: () -> Unit,
    onPresetSelected: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onDuplicatePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearFilters: () -> Unit,
    totalCount: Int,
    tagCounts: Map<String, Int>,
    onSaveCurrent: (() -> Unit)? = null
) {
    var presetToDelete by remember { mutableStateOf<PromptPreset?>(null) }
    val filtersOn = selectedTags.isNotEmpty() || filterFavoritesOnly
    val filtering = searchQuery.isNotBlank() || filtersOn
    // The field's text, handed to the view model as typed
    val search = rememberTextFieldState(searchQuery)
    val currentOnQuery by rememberUpdatedState(onSearchQueryChange)
    LaunchedEffect(search) { snapshotFlow { search.text.toString() }.collect { currentOnQuery(it) } }
    // Favourites first, then the tags, most used first
    val resources = LocalResources.current
    val favouritesLabel = stringResource(R.string.prompt_library_filter_favorites)
    val filters = buildList {
        add(SearchFilter("favourites", favouritesLabel, filterFavoritesOnly, onToggleFavoritesFilter) {
            Icon(Icons.Rounded.StarOutline, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
        })
        availableTags.sortedWith(compareByDescending<String> { tagCounts[it] ?: 0 }.thenBy { it.lowercase() }).forEach { tag ->
            add(SearchFilter("tag_$tag", tag, tag in selectedTags, { onTagToggle(tag) }))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.content_description_close))
                    }
                    Text(
                        stringResource(R.string.title_prompt_library),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                SearchField(
                    state = search,
                    placeholder = pluralStringResource(R.plurals.prompts_search_hint, totalCount, totalCount),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SearchFilterChips(
                    filters = filters,
                    moreLabel = { resources.getString(R.string.prompts_more_tags, it) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
                if (filtering) {
                    SearchCountLine(
                        stringResource(R.string.prompts_count, presets.size),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                }

                if (presets.isEmpty() && filtering) {
                    SearchEmptyState(
                        title = if (searchQuery.isNotBlank()) stringResource(R.string.prompts_none_title, searchQuery.trim())
                            else stringResource(R.string.msg_prompt_library_no_match),
                        body = if (filtersOn) stringResource(R.string.prompts_none_body) else null,
                        liftLabel = if (filtersOn) stringResource(R.string.prompts_search_all) else null,
                        liftIcon = Icons.Rounded.FilterAltOff,
                        onLift = onClearFilters
                    )
                } else if (presets.isEmpty()) {
                    LibraryEmptyState(filtering = false, onSaveCurrent = onSaveCurrent, modifier = Modifier.weight(1f))
                } else {
                    NoOverscrollContainer(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            // The room under the filters scrolls with the rows, so they pass right under them
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(presets, key = { _, preset -> preset.id }) { index, preset ->
                                PresetRow(
                                    preset = preset,
                                    highlight = searchQuery.trim().ifEmpty { null },
                                    shapes = groupShapes(index, presets.size),
                                    onUse = {
                                        onPresetSelected(preset.id)
                                        onDismiss()
                                    },
                                    onToggleFavorite = { onToggleFavorite(preset.id) },
                                    onEdit = { onEditPreset(preset.id) },
                                    onDuplicate = { onDuplicatePreset(preset.id) },
                                    onDelete = { presetToDelete = preset }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    presetToDelete?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.title_delete_named, preset.name),
            text = stringResource(R.string.msg_delete_prompt),
            confirmLabel = stringResource(R.string.button_delete),
            icon = Icons.Rounded.Delete,
            destructive = true,
            onConfirm = { onDeletePreset(preset.id) },
            onDismiss = { presetToDelete = null }
        )
    }
}

/** No prompts yet (offer to save the current one), or none matching the filters. */
@Composable
private fun LibraryEmptyState(filtering: Boolean, onSaveCurrent: (() -> Unit)?, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Icon(
            if (filtering) Icons.Rounded.SearchOff else Icons.Rounded.BookmarkBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (filtering) R.string.msg_prompt_library_no_match else R.string.msg_prompt_library_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!filtering && onSaveCurrent != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onSaveCurrent, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.button_save_current_prompt)) }
        }
    }
}

/**
 * A prompt: its name and one line of it, and a star for favourites. Tap uses it; holding it opens its menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PresetRow(
    preset: PromptPreset,
    highlight: String?,
    shapes: ListItemShapes,
    onUse: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.button_more_options)
    Box {
        SegmentedListItem(
            onClick = onUse,
            onLongClick = { showMenu = true },
            onLongClickLabel = menuLabel,
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            supportingContent = { Text(preset.prompt, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (preset.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = stringResource(R.string.prompt_preset_toggle_favorite),
                        tint = if (preset.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(highlighted(preset.name, highlight), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                listOf(
                    Triple(R.string.prompt_preset_edit, Icons.Rounded.Edit, onEdit),
                    Triple(R.string.prompt_preset_duplicate, Icons.Rounded.ContentCopy, onDuplicate)
                ).forEachIndexed { index, (labelRes, icon, action) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        shape = MenuDefaults.itemShape(index, 2).shape,
                        onClick = {
                            showMenu = false
                            action()
                        }
                    )
                }
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                val error = MaterialTheme.colorScheme.error
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.prompt_preset_delete), color = error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = error) },
                    shape = MenuDefaults.itemShape(0, 1).shape,
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}
