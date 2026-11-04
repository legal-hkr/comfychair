package sh.hnet.comfychair.ui.components.shared

/**
 * A workflow as the workflow menus list it: its name (the key used to select it) and the name shown.
 */
interface WorkflowItemBase {
    val name: String
    val displayName: String
}
