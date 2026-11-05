package sh.hnet.comfychair.storage

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.Context
import org.json.JSONArray
import sh.hnet.comfychair.model.PromptPreset
import sh.hnet.comfychair.model.ScreenType

/**
 * Storage for prompt presets using SharedPreferences.
 * Each screen type has its own separate preset library.
 */
class PromptPresetStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "PromptPresetPrefs"
        private const val KEY_PRESETS_JSON = "presets_json"
        const val MAX_FAVORITES_PER_SCREEN = 8

        private val _changes = MutableStateFlow(0)

        /** Bumped on every write, wherever it comes from (e.g. saving a prompt from a result's details), so open screens refresh. */
        val changes: StateFlow<Int> = _changes.asStateFlow()

        /** For writes that bypass this class (a backup restore writes the preferences directly). */
        fun notifyChanged() = _changes.update { it + 1 }
    }

    /**
     * Get all presets across all screen types.
     */
    fun getPresets(): List<PromptPreset> {
        val json = prefs.getString(KEY_PRESETS_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                PromptPreset.fromJson(array.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get presets for a specific screen type.
     */
    fun getPresetsForScreen(screenType: ScreenType): List<PromptPreset> {
        return getPresets()
            .filter { it.screenType == screenType }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Get favorite presets for a specific screen type.
     * Returns at most MAX_FAVORITES_PER_SCREEN presets, sorted by creation date (newest first).
     */
    fun getFavoritesForScreen(screenType: ScreenType): List<PromptPreset> {
        return getPresetsForScreen(screenType)
            .filter { it.isFavorite }
            .take(MAX_FAVORITES_PER_SCREEN)
    }

    /**
     * Get all unique tags used by presets for a specific screen type.
     * Tags are derived dynamically from existing presets.
     */
    fun getTagsForScreen(screenType: ScreenType): Set<String> {
        return getPresetsForScreen(screenType)
            .flatMap { it.tags }
            .toSet()
    }

    /**
     * Save all presets to storage.
     */
    private fun savePresets(presets: List<PromptPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS_JSON, array.toString()).apply()
        _changes.update { it + 1 }
    }

    /**
     * Add a new preset.
     */
    fun addPreset(preset: PromptPreset) {
        val presets = getPresets().toMutableList()
        presets.add(preset)
        savePresets(presets)
    }

    /**
     * Update an existing preset.
     */
    fun updatePreset(preset: PromptPreset) {
        val presets = getPresets().map {
            if (it.id == preset.id) preset else it
        }
        savePresets(presets)
    }

    /**
     * Delete a preset by ID.
     */
    fun deletePreset(presetId: String) {
        val presets = getPresets().filter { it.id != presetId }
        savePresets(presets)
    }

    /**
     * Clear all presets from storage.
     * Called when user resets all prompts and library from App Settings.
     */
    fun clearAll() {
        prefs.edit().remove(KEY_PRESETS_JSON).apply()
        _changes.update { it + 1 }
    }

    /**
     * Get a preset by ID.
     */
    fun getPreset(presetId: String): PromptPreset? {
        return getPresets().find { it.id == presetId }
    }

    /**
     * Toggle the favorite status of a preset.
     * Returns true if successful, false if max favorites reached when trying to favorite.
     */
    fun toggleFavorite(presetId: String): Boolean {
        val preset = getPreset(presetId) ?: return false

        // If unfavoriting, always allow
        if (preset.isFavorite) {
            updatePreset(preset.copy(isFavorite = false))
            return true
        }

        // If favoriting, check max limit
        val currentFavorites = getFavoritesForScreen(preset.screenType)
        if (currentFavorites.size >= MAX_FAVORITES_PER_SCREEN) {
            return false // Max favorites reached
        }

        updatePreset(preset.copy(isFavorite = true))
        return true
    }

    /**
     * Check if a preset name is already taken for a screen type.
     * Optionally exclude a specific preset ID (for editing).
     */
    fun isNameTaken(name: String, screenType: ScreenType, excludeId: String? = null): Boolean {
        return getPresetsForScreen(screenType).any {
            it.name.equals(name, ignoreCase = true) &&
                (excludeId == null || it.id != excludeId)
        }
    }

    /**
     * Count favorites for a screen type.
     */
    fun getFavoriteCount(screenType: ScreenType): Int {
        return getPresetsForScreen(screenType).count { it.isFavorite }
    }
}
