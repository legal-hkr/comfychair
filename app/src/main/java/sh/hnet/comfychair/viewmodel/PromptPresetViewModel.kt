package sh.hnet.comfychair.viewmodel

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Job
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.model.ScreenType
import sh.hnet.comfychair.storage.PromptPresetStorage

/**
 * UI state for prompt preset management.
 */
data class PromptPresetUiState(
    val favorites: List<PromptPreset> = emptyList(),
    val allPresets: List<PromptPreset> = emptyList(),
    val availableTags: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val filterFavoritesOnly: Boolean = false,
    val activePresetId: String? = null,
    val showLibrarySideSheet: Boolean = false,
    val showSaveDialog: Boolean = false,
    val editingPreset: PromptPreset? = null,
    val currentPromptForSave: String = ""
)

/**
 * Events emitted by the PromptPresetViewModel.
 */
sealed class PromptPresetEvent {
    data class PresetApplied(val prompt: String) : PromptPresetEvent()
    data class ShowToast(val messageResId: Int) : PromptPresetEvent()
    data object MaxFavoritesReached : PromptPresetEvent()
    data object ResetPrompt : PromptPresetEvent()
}

/**
 * ViewModel for managing prompt presets.
 * Each instance handles presets for a specific screen type.
 */
class PromptPresetViewModel : ViewModel() {
    private var storage: PromptPresetStorage? = null
    private var currentScreenType: ScreenType = ScreenType.TEXT_TO_IMAGE

    private val _uiState = MutableStateFlow(PromptPresetUiState())
    val uiState: StateFlow<PromptPresetUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PromptPresetEvent>()
    val events = _events.asSharedFlow()

    /**
     * Initialize the ViewModel with context and screen type.
     * Must be called before using other methods.
     */
    fun initialize(context: Context, screenType: ScreenType) {
        storage = PromptPresetStorage(context)
        currentScreenType = screenType
        refreshPresets()
        // Writes from elsewhere in the app (a result's details save to any screen's library)
        if (changesJob == null) {
            changesJob = viewModelScope.launch {
                PromptPresetStorage.changes.drop(1).collect { refreshPresets() }
            }
        }
    }

    private var changesJob: Job? = null

    /**
     * Refresh presets from storage.
     * Call this when returning to the screen to catch external changes.
     */
    fun refreshPresets() {
        val storage = storage ?: return
        val newAllPresets = storage.getPresetsForScreen(currentScreenType)
        _uiState.update { state ->
            // Clear activePresetId if the preset no longer exists
            val validActivePresetId = if (state.activePresetId != null &&
                newAllPresets.none { it.id == state.activePresetId }) {
                null
            } else {
                state.activePresetId
            }
            state.copy(
                favorites = storage.getFavoritesForScreen(currentScreenType),
                allPresets = newAllPresets,
                availableTags = storage.getTagsForScreen(currentScreenType),
                activePresetId = validActivePresetId
            )
        }
    }

    /**
     * Update search query for filtering.
     */
    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Toggle a tag in the filter selection.
     */
    fun onTagToggle(tag: String) {
        _uiState.update { state ->
            val newTags = if (tag in state.selectedTags) {
                state.selectedTags - tag
            } else {
                state.selectedTags + tag
            }
            state.copy(selectedTags = newTags)
        }
    }

    /**
     * Clear all tag filters.
     */
    fun clearTagFilters() {
        _uiState.update { it.copy(selectedTags = emptySet()) }
    }

    /**
     * Toggle the favorites-only filter.
     */
    fun onToggleFavoritesFilter() {
        _uiState.update { it.copy(filterFavoritesOnly = !it.filterFavoritesOnly) }
    }

    /**
     * Get presets filtered by current search query, selected tags, and favorites filter.
     */
    fun getFilteredPresets(): List<PromptPreset> {
        val state = _uiState.value
        return state.allPresets.filter { preset ->
            val matchesFavorites = !state.filterFavoritesOnly || preset.isFavorite
            val matchesSearch = state.searchQuery.isEmpty() ||
                preset.name.contains(state.searchQuery, ignoreCase = true) ||
                preset.prompt.contains(state.searchQuery, ignoreCase = true)
            val matchesTags = state.selectedTags.isEmpty() ||
                preset.tags.any { it in state.selectedTags }
            matchesFavorites && matchesSearch && matchesTags
        }
    }

    /**
     * Select and apply a preset.
     * Also dismisses the library side sheet if it was open.
     */
    fun onPresetSelected(presetId: String) {
        val preset = storage?.getPreset(presetId) ?: return
        _uiState.update { state ->
            state.copy(
                activePresetId = presetId,
                showLibrarySideSheet = false,
                searchQuery = "",
                selectedTags = emptySet()
            )
        }
        viewModelScope.launch {
            _events.emit(PromptPresetEvent.PresetApplied(preset.prompt))
        }
    }

    /**
     * Request prompt reset to default.
     * The actual reset is handled by the generation screen's ViewModel.
     */
    fun resetPrompt() {
        _uiState.update { it.copy(activePresetId = null) }
        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ResetPrompt)
        }
    }

    /**
     * Toggle favorite status of a preset.
     */
    fun onToggleFavorite(presetId: String) {
        val success = storage?.toggleFavorite(presetId) ?: return
        if (!success) {
            viewModelScope.launch {
                _events.emit(PromptPresetEvent.MaxFavoritesReached)
            }
        }
        refreshPresets()
    }

    /**
     * Save a new preset or update an existing one.
     */
    fun onSavePreset(name: String, prompt: String, tags: List<String>) {
        val storage = storage ?: return
        val editing = _uiState.value.editingPreset

        if (editing != null) {
            // Update existing preset
            val updated = editing.copy(
                name = name,
                prompt = prompt,
                tags = tags
            )
            storage.updatePreset(updated)
        } else {
            // Create new preset
            val preset = PromptPreset.create(
                screenType = currentScreenType,
                name = name,
                prompt = prompt,
                tags = tags
            )
            storage.addPreset(preset)
        }

        _uiState.update { it.copy(showSaveDialog = false, editingPreset = null) }
        refreshPresets()

        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ShowToast(R.string.msg_prompt_preset_saved))
        }
    }

    /**
     * Duplicate a preset with a "(n)" suffix where n is the next available number.
     */
    fun onDuplicatePreset(presetId: String) {
        val original = storage?.getPreset(presetId) ?: return
        val existingNames = _uiState.value.allPresets.map { it.name }.toSet()
        val duplicateName = generateUniqueName(original.name, existingNames)

        val duplicate = PromptPreset.create(
            screenType = original.screenType,
            name = duplicateName,
            prompt = original.prompt,
            tags = original.tags
        )
        storage?.addPreset(duplicate)
        refreshPresets()

        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ShowToast(R.string.msg_prompt_preset_duplicated))
        }
    }

    /**
     * Generate a unique name by appending (n) where n is the next available number.
     * The original name is kept intact, e.g. "My Preset (2)" becomes "My Preset (2) (1)".
     */
    private fun generateUniqueName(originalName: String, existingNames: Set<String>): String {
        // Find all numbers already used with this exact base name
        val numberPattern = Regex("""^\Q$originalName\E \((\d+)\)$""")
        val usedNumbers = existingNames.mapNotNull { name ->
            numberPattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()

        // Find the next available number starting from 1
        var nextNumber = 1
        while (nextNumber in usedNumbers) {
            nextNumber++
        }

        return "$originalName ($nextNumber)"
    }

    /**
     * Delete a preset.
     */
    fun onDeletePreset(presetId: String) {
        storage?.deletePreset(presetId)
        if (_uiState.value.activePresetId == presetId) {
            _uiState.update { it.copy(activePresetId = null) }
        }
        refreshPresets()

        viewModelScope.launch {
            _events.emit(PromptPresetEvent.ShowToast(R.string.msg_prompt_preset_deleted))
        }
    }

    /**
     * Show the save dialog for creating a new preset.
     */
    fun showSaveDialog(currentPrompt: String) {
        _uiState.update { state ->
            state.copy(
                showSaveDialog = true,
                editingPreset = null,
                currentPromptForSave = currentPrompt
            )
        }
    }

    /**
     * Show the edit dialog for an existing preset.
     */
    fun showEditDialog(presetId: String) {
        val preset = storage?.getPreset(presetId) ?: return
        _uiState.update { state ->
            state.copy(
                showSaveDialog = true,
                editingPreset = preset,
                currentPromptForSave = preset.prompt
            )
        }
    }

    /**
     * Dismiss the save/edit dialog.
     */
    fun dismissSaveDialog() {
        _uiState.update { state ->
            state.copy(
                showSaveDialog = false,
                editingPreset = null,
                currentPromptForSave = ""
            )
        }
    }

    /**
     * Show the prompt library side sheet.
     */
    fun showLibrary() {
        _uiState.update { it.copy(showLibrarySideSheet = true) }
    }

    /**
     * Dismiss the prompt library side sheet.
     */
    fun dismissLibrary() {
        _uiState.update { state ->
            state.copy(
                showLibrarySideSheet = false,
                searchQuery = "",
                selectedTags = emptySet(),
                filterFavoritesOnly = false
            )
        }
    }

    /**
     * Clear the active preset indicator.
     * Called when user modifies the prompt manually.
     */
    fun clearActivePreset() {
        _uiState.update { it.copy(activePresetId = null) }
    }

    /**
     * Check if a name is already taken for the current screen type.
     */
    fun isNameTaken(name: String, excludeId: String? = null): Boolean {
        return storage?.isNameTaken(name, currentScreenType, excludeId) ?: false
    }
}
