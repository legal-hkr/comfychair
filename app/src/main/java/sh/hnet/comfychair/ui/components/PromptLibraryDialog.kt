package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SearchBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
    onSaveCurrent: (() -> Unit)? = null
) {
    var presetToDelete by remember { mutableStateOf<PromptPreset?>(null) }
    val filtering = searchQuery.isNotEmpty() || selectedTags.isNotEmpty() || filterFavoritesOnly

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

                SearchBarDefaults.InputField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearch = { },
                    expanded = false,
                    onExpandedChange = { },
                    placeholder = { Text(stringResource(R.string.placeholder_prompt_library_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.button_clear_search))
                            }
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(SearchBarDefaults.inputFieldShape)
                )

                // Favourites first, then the tags
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    item(key = "favourites") {
                        FilterChip(
                            selected = filterFavoritesOnly,
                            onClick = onToggleFavoritesFilter,
                            label = { Text(stringResource(R.string.prompt_library_filter_favorites)) },
                            leadingIcon = {
                                Icon(
                                    if (filterFavoritesOnly) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                    items(availableTags.sorted(), key = { "tag_$it" }) { tag ->
                        val selected = tag in selectedTags
                        FilterChip(
                            selected = selected,
                            onClick = { onTagToggle(tag) },
                            label = { Text(tag) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null
                        )
                    }
                }

                if (presets.isEmpty()) {
                    LibraryEmptyState(filtering = filtering, onSaveCurrent = onSaveCurrent, modifier = Modifier.weight(1f))
                } else {
                    NoOverscrollContainer(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(presets, key = { _, preset -> preset.id }) { index, preset ->
                                PresetRow(
                                    preset = preset,
                                    shape = ListItemDefaults.segmentedShapes(index, presets.size).shape,
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
            title = stringResource(R.string.title_delete_workflow_named, preset.name),
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
            FilledTonalButton(onClick = onSaveCurrent) { Text(stringResource(R.string.button_save_current_prompt)) }
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
    shape: Shape,
    onUse: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.button_more_options)
    Box {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onUse, onLongClick = { showMenu = true }, onLongClickLabel = menuLabel)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        preset.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (preset.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = stringResource(R.string.prompt_preset_toggle_favorite),
                        tint = if (preset.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
