package sh.hnet.comfychair.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.PromptPreset

/**
 * The prompt library menu (Expressive grouped menu, like the workflow menu): favorites with the one in use checked, then
 * the library and saving the current prompt, and apart, resetting the prompt.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PromptPresetDropdown(
    favorites: List<PromptPreset>,
    activePresetId: String?,
    currentPromptIsEmpty: Boolean,
    onPresetSelected: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onSaveCurrentPrompt: () -> Unit,
    onResetPrompt: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val close = { expanded = false }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = if (activePresetId != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                contentDescription = stringResource(R.string.button_prompt_presets),
                tint = if (activePresetId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val groupCount = if (favorites.isNotEmpty()) 3 else 2
        DropdownMenuPopup(expanded = expanded, onDismissRequest = close, modifier = rememberMenuScrollModifier()) {
            var group = 0
            if (favorites.isNotEmpty()) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(group++, groupCount)) {
                    Text(
                        text = stringResource(R.string.prompt_library_filter_favorites),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                    )
                    favorites.forEachIndexed { index, preset ->
                        val selected = preset.id == activePresetId
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        preset.prompt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            shape = MenuDefaults.itemShape(index, favorites.size).shape,
                            leadingIcon = {
                                if (selected) Icon(Icons.Rounded.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                close()
                                onPresetSelected(preset.id)
                            },
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .semantics {
                                    role = Role.RadioButton
                                    this.selected = selected
                                }
                        )
                    }
                }
                Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            }
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(group++, groupCount)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.prompt_presets_open_library)) },
                    shape = MenuDefaults.itemShape(0, 2).shape,
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, contentDescription = null) },
                    onClick = {
                        close()
                        onOpenLibrary()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.prompt_presets_save_current)) },
                    shape = MenuDefaults.itemShape(1, 2).shape,
                    enabled = !currentPromptIsEmpty,
                    leadingIcon = { Icon(Icons.Rounded.BookmarkAdd, contentDescription = null) },
                    onClick = {
                        close()
                        onSaveCurrentPrompt()
                    }
                )
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(group, groupCount)) {
                val error = MaterialTheme.colorScheme.error
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.prompt_preset_reset_prompt), color = error) },
                    shape = MenuDefaults.itemShape(0, 1).shape,
                    leadingIcon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = error) },
                    onClick = {
                        close()
                        onResetPrompt()
                    }
                )
            }
        }
    }
}
