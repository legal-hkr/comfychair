# Plan: ComfyChair Source Image Bypass Feature

## Context
- **Feature**: Add "Bypass" toggle to MediaViewerActivity floating toolbar for source image slots
- **Mechanism**: ComfyUI `mode=4` (bypass) + `BypassNodeResolver` auto-reconnect wires
- **Scope**: ImageToImage editing (multi-image slots 1-4)
- **Approach**: Bypass slot = skip image upload + set LoadImage node `mode=4`

---

## Phase 1: Data Model (3 files)

### 1.1 `WorkflowValues.kt`
**Add `bypassedSourceSlots` field**
```kotlin
// Node attribute edits (JSON serialized using NodeAttributeEdits.toJson/fromJson)
val nodeAttributeEdits: String? = null,

// Bypassed source image slots (for multi-image workflows)
// Set<Int> serialized as JSON array, e.g. [2, 4]
val bypassedSourceSlots: Set<Int>? = null
```
Also add serialization/deserialization in the companion object.

### 1.2 `ImageToImageUiState.kt` (or inline in ViewModel)
**Add `bypassedSourceSlots` to UI state**
```kotlin
val bypassedSourceSlots: Set<Int> = emptySet()
```

---

## Phase 2: ViewModel (1 file)

### 2.1 `ImageToImageViewModel.kt`
**Add `toggleBypassSourceImage(slot: Int)` method**
```kotlin
fun toggleBypassSourceImage(slot: Int) {
    val current = _uiState.value.bypassedSourceSlots
    val updated = if (slot in current) current - slot else current + slot
    _uiState.update { it.copy(bypassedSourceSlots = updated) }
    // Persist to WorkflowValues via saveEditingWorkflowValues()
}
```

**Also update `loadWorkflowValues()`** to restore `bypassedSourceSlots` from persisted `WorkflowValues`.

---

## Phase 3: Intent & Result Protocol (2 files)

### 3.1 `MediaViewerActivity.kt`
**Modify `Intent.createSingleImageIntent()`** to accept `isSlotBypassed: Boolean` param:
```kotlin
companion object {
    fun createSingleImageIntent(
        context: Context,
        bitmap: Bitmap,
        replaceSlot: Int,
        isSlotBypassed: Boolean = false  // NEW
    ): Intent { ... }
}
```

**Add new result constants** and modify `onClose()`:
```kotlin
const val RESULT_BYPASS = "comfychair.RESULT_BYPASS"
const val RESULT_SLOT = "comfychair.RESULT_SLOT"
```
When bypass is toggled in toolbar: `intent.putExtra(RESULT_BYPASS, true); intent.putExtra(RESULT_SLOT, slot)`

### 3.2 `ImageToImageScreen.kt`
**Extend `mediaViewerReplaceLauncher`** to handle bypass:
```kotlin
mediaViewerReplaceLauncher = registerForActivityResult(...) { result ->
    if (result.data?.getBooleanExtra(MediaViewerActivity.RESULT_BYPASS, false) == true) {
        val slot = result.data?.getIntExtra(MediaViewerActivity.RESULT_SLOT, 1) ?: 1
        imageToImageViewModel.toggleBypassSourceImage(slot)
    }
    // existing replace logic...
}
```

---

## Phase 4: Toolbar UI (2 files)

### 4.1 `MediaViewerFloatingToolbar.kt`
**Add Bypass button** next to Replace button:
```kotlin
@Composable
fun MediaViewerFloatingToolbar(
    ...
    isBypassed: Boolean,           // NEW
    onBypass: () -> Unit,           // NEW
) {
    // ...
    ToolbarButton(
        icon = if (isBypassed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
        label = if (isBypassed) "启用" else "禁用",
        tint = if (isBypassed) MaterialTheme.colorScheme.error else LocalContentColor.current,
        onClick = onBypass
    )
}
```

### 4.2 `MediaViewerScreen.kt`
**Accept and pass `isBypassed` and `onBypass`** from parent (MediaViewerActivity):
```kotlin
@Composable
fun MediaViewerScreen(
    ...
    isSlotBypassed: Boolean = false,   // NEW
    onBypassSlot: () -> Unit = {},     // NEW
) {
    MediaViewerFloatingToolbar(
        isBypassed = isSlotBypassed,
        onBypass = onBypassSlot,
        ...
    )
}
```

---

## Phase 5: Workflow Integration (2 files)

### 5.1 `WorkflowManager.kt` — `prepareImageEditingWorkflowById()`
**Add `bypassedSlots: Set<Int>` parameter**
```kotlin
fun prepareImageEditingWorkflowById(
    ...
    sourceImage2Filename: String? = null,
    sourceImage3Filename: String? = null,
    sourceImage4Filename: String? = null,
    bypassedSlots: Set<Int> = emptySet(),  // NEW
): String?
```

**Handle bypassed slots** (instead of just null):
```kotlin
// Source image 2
val slot2Filename = when {
    2 in bypassedSlots -> ""        // Bypassed → empty, node will be bypassed
    sourceImage2Filename != null -> "${sourceImage2Filename} [input]"
    else -> "{{image_filename_2}}"  // Not provided → keep placeholder (existing bug)
}
// Similar for slots 3, 4

// If NOT bypassed but filename is null → treat as bypass (fix existing bug)
val effectiveSlot2Bypassed = 2 in bypassedSlots || sourceImage2Filename == null
if (effectiveSlot2Bypassed) {
    processedJson = processedJson.replace("{{image_filename_2}}", "")
}
```

### 5.2 `ImageToImageViewModel.kt` — `prepareEditingWorkflow()`
**Skip uploading bypassed images** and pass `bypassedSlots`:
```kotlin
// Don't upload if bypassed
val uploadedSource2: String? = if (2 in state.bypassedSourceSlots) {
    null  // Skip upload
} else {
    // existing upload code
}

// Pass to WorkflowManager
val bypassedSlots = state.bypassedSourceSlots

val baseWorkflow = WorkflowManager.prepareImageEditingWorkflowById(
    ...
    sourceImage2Filename = uploadedSource2,
    bypassedSlots = bypassedSlots,  // NEW
)
```

---

## Phase 6: Fix Existing Bug

### 6.1 Null source image → auto-bypass
When `sourceImage2 == null` and NOT bypassed, the current code leaves `{{image_filename_2}}` in the JSON. Fix: treat null source image as bypass (set `mode=4` and replace with `""`).

This is handled implicitly in Phase 5.2 — if `uploadedSource2 == null` (because sourceImage2 is null), we pass `null` and the manager treats it as bypassed.

---

## Summary of File Changes

| File | Changes |
|------|---------|
| `WorkflowValues.kt` | Add `bypassedSourceSlots: Set<Int>?` field + JSON serialization |
| `ImageToImageUiState.kt` | Add `bypassedSourceSlots: Set<Int>` field |
| `ImageToImageViewModel.kt` | Add `toggleBypassSourceImage()`, skip uploads, pass `bypassedSlots` |
| `MediaViewerActivity.kt` | Add `isSlotBypassed` Intent param, `RESULT_BYPASS` result |
| `MediaViewerScreen.kt` | Accept `isSlotBypassed` + `onBypassSlot` |
| `MediaViewerFloatingToolbar.kt` | Add Bypass toggle button with icon |
| `ImageToImageScreen.kt` | Handle `RESULT_BYPASS` result, call `toggleBypassSourceImage()` |
| `WorkflowManager.kt` | Add `bypassedSlots: Set<Int>` param, handle empty strings |

## Verification
1. Open ImageToImage with a multi-image workflow (e.g., IPAdapter with 2 reference images)
2. Tap source image 2 → MediaViewer opens
3. Toggle Bypass button in toolbar → button shows as bypassed (red tint)
4. Press back → return to ImageToImage
5. Run workflow → source image 2 NOT uploaded, LoadImage node bypassed, wires reconnected
6. Check ComfyUI queue → node is not executed

## Risks
- **Different workflow templates**: LoadImage nodes may have different field names (unlikely — ComfyUI standard is `images`)
- **Bypass state sync**: If user replaces image after bypass, should clear bypass — handled by existing replace flow clearing the slot
- **Edge case: all slots bypassed**: No primary image — depends on workflow, let ComfyUI error naturally
