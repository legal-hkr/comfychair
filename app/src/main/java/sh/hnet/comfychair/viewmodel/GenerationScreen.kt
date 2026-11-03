package sh.hnet.comfychair.viewmodel

import sh.hnet.comfychair.ui.components.shared.WorkflowItemBase

/**
 * A workflow offered in a generation screen's workflow dropdown.
 */
data class WorkflowItem(
    val id: String,                  // Workflow ID (storage key, editor)
    override val name: String,       // User-defined workflow name
    override val displayName: String = name
) : WorkflowItemBase

/**
 * One-time events of the generation screens.
 */
sealed class GenerationScreenEvent {
    data class ShowToast(val messageResId: Int) : GenerationScreenEvent()
    data class ShowToastMessage(val message: String) : GenerationScreenEvent()
}
