package sh.hnet.comfychair.viewmodel.base

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.storage.WorkflowValuesStorage
import sh.hnet.comfychair.util.ValidationUtils
import sh.hnet.comfychair.viewmodel.GenerationViewModel

/**
 * Base ViewModel for generation screens (Text-to-Image, Image-to-Image, Text-to-Video, Image-to-Video).
 * Provides common state management, dependencies, and utility methods.
 *
 * @param UiState The UI state type for this ViewModel
 * @param Event The event type for this ViewModel
 */
abstract class BaseGenerationViewModel<UiState, Event> : ViewModel() {

    // State management - subclasses must provide initial state
    protected abstract val initialState: UiState

    protected val _uiState: MutableStateFlow<UiState> by lazy {
        MutableStateFlow(initialState)
    }
    val uiState: StateFlow<UiState> by lazy {
        _uiState.asStateFlow()
    }

    protected val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Common dependencies
    protected var applicationContext: Context? = null
        private set
    protected var comfyUIClient: ComfyUIClient? = null
        private set
    protected var workflowValuesStorage: WorkflowValuesStorage? = null
        private set

    // Reference to GenerationViewModel for event handling
    protected var generationViewModelRef: GenerationViewModel? = null

    // Track which promptId we've already cleared preview for (prevents duplicate clears on navigation)
    protected var lastClearedForPromptId: String? = null

    // Initialization flag
    private var isInitialized = false

    /**
     * Initialize the ViewModel with common dependencies.
     * Subclasses should override onInitialize() to perform their specific initialization.
     */
    fun initialize(context: Context, client: ComfyUIClient) {
        if (isInitialized) return

        applicationContext = context.applicationContext
        comfyUIClient = client
        WorkflowManager.ensureInitialized(context)
        workflowValuesStorage = WorkflowValuesStorage(context)

        onInitialize()
        isInitialized = true
    }

    /**
     * Called after common initialization is complete.
     * Subclasses should override this to perform their specific initialization
     * (e.g., loading workflows, restoring preferences, restoring last generated media).
     */
    protected abstract fun onInitialize()

    /**
     * Check if the current configuration is valid for generation.
     */
    abstract fun hasValidConfiguration(): Boolean

    /**
     * Validate model selection against available models.
     * Returns current if valid, otherwise defaults to first available.
     */
    protected fun validateModelSelection(current: String, available: List<String>): String {
        return ValidationUtils.validateModelSelection(current, available)
    }

    /**
     * Check if the ViewModel has been initialized.
     */
    fun isInitialized(): Boolean = isInitialized
}
