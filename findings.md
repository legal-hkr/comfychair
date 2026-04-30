# ComfyChair Bypass Feature - Research Findings

## What is Bypass?
ComfyUI has a `mode` field per node:
- `mode: 0` = Active (normal execution)
- `mode: 2` = Muted (skipped, outputs are empty)
- `mode: 4` = Bypassed (completely skipped AND wires auto-reconnected)

ComfyChair already has full bypass infrastructure in `BypassNodeResolver.applyBypassNodes()` (WorkflowManager.kt:1443) — when a node has `mode: 4`, it's removed from the JSON and all its input/output wires are auto-reconnected. This is the standard ComfyUI mechanism.

## Current Architecture

### Data Layer: Complete ✓
- `WorkflowNode.mode` field (WorkflowGraphModel.kt)
- `WorkflowParser` parses `mode` from JSON (line 85-115)
- `BypassNodeResolver` removes bypassed nodes + reconnects wires (line ~1443)
- `WorkflowEditorViewModel.toggleNodeBypass()` exists (line ~1769) — but UI entry is deep (Edit Mode → node → More menu)

### UI Layer: Missing the right entry point
The desired entry is the **MediaViewerActivity floating toolbar** when viewing a source image.

## How Source Images Work

### Slot → Placeholder Mapping
```
slot 1 (sourceImage)  → {{image_filename}}   → LoadImage node, inputs.images = "filename.png [input]"
slot 2 (sourceImage2) → {{image_filename_2}}
slot 3 (sourceImage3) → {{image_filename_3}}
slot 4 (sourceImage4) → {{image_filename_4}}
```

### prepareEditingWorkflow Flow (ImageToImageViewModel.kt:1920-2098)
1. Upload all source images to ComfyUI server → get filenames
2. Call `WorkflowManager.prepareImageEditingWorkflowById(workflowId, sourceImageFilename=uploadedSource, ...)`
3. `WorkflowManager` replaces placeholders in the workflow template JSON
4. If `sourceImage2Filename == null`, `{{image_filename_2}}` stays in JSON → unresolved placeholder error
5. Returns processed JSON (final step calls `applyBypassedNodes()` which handles `mode=4` nodes)

### Key Problem
The workflow JSON structure varies by workflow template — different LoadImage nodes have different node IDs. There is no fixed mapping from slot number to node ID. We must find the right LoadImage node by:
1. Its `class_type == "LoadImage"`
2. Its `inputs.images` field corresponds to the slot's placeholder

## Existing Replace Feature (Template for Implementation)
When viewing a source image in `MediaViewerActivity`:
1. `ImageToImageScreen` launches `MediaViewerActivity` with `replaceSlot` (1-4)
2. `MediaViewerFloatingToolbar` shows a Replace button
3. On click: `MediaViewerActivity.onClose(replaceSlot)` sets `RESULT_REPLACE=true, RESULT_SLOT=slot`
4. `ImageToImageScreen.mediaViewerReplaceLauncher` receives result, launches image picker
5. Picked image replaces the slot → new image is uploaded next run

## Files to Modify

### UI Layer
1. **`MediaViewerFloatingToolbar.kt`** — Add Bypass toggle button (VisibilityOff icon when bypassed, eye icon when active)
2. **`MediaViewerScreen.kt`** — Accept `isSlotBypassed: Boolean` param, pass to toolbar, handle `onBypass` callback
3. **`MediaViewerActivity.kt`** — Accept `isSlotBypassed: Boolean` in Intent, return `RESULT_BYPASS=true, RESULT_SLOT=slot` when bypass toggled
4. **`ImageToImageScreen.kt`** — Handle `RESULT_BYPASS`, call `imageToImageViewModel.toggleBypassSourceImage(slot)`

### ViewModel Layer
5. **`ImageToImageViewModel.kt`** — Add `toggleBypassSourceImage(slot: Int)` method:
   - Add `bypassedSourceSlots: Set<Int>` to `ImageToImageUiState` (default empty)
   - Toggle slot in the set (add if absent, remove if present)
   - Persist to `WorkflowValues.bypassedSourceSlots`
   - Reload workflow values to update UI

### Persistence Layer
6. **`WorkflowValues.kt`** — Add `bypassedSourceSlots: Set<Int>? = null` field + JSON serialization
7. **`WorkflowValuesStorage.kt`** — Already stores `WorkflowValues`, no changes needed

### Workflow Manager Layer
8. **`WorkflowManager.prepareImageEditingWorkflowById()`** — Add `bypassedSlots: Set<Int> = emptySet()` parameter:
   - For bypassed slots: replace `{{image_filename_N}}` with `""` (empty string — valid for bypassed node)
   - For non-bypassed slots with null image: keep placeholder → **unresolved placeholder error** (existing bug to fix)

### ImageToImageViewModel.prepareEditingWorkflow
9. Skip uploading bypassed source images (don't call `client.uploadImage`)
10. Pass `bypassedSlots` to `WorkflowManager.prepareImageEditingWorkflowById`

## Bypass Button Behavior
- **Toolbar position**: Next to the existing Replace button (icon: `Icons.Filled.VisibilityOff` when active, `Icons.Filled.Visibility` when bypassed)
- **Visual state**: Button highlighted when bypassed (different tint/color)
- **Tap action**: Toggle bypass state immediately (optimistic UI update)
- **Persist**: Saved to `WorkflowValues.bypassedSourceSlots` per workflow
- **Effect**: Next workflow run — bypassed slot's LoadImage node gets `mode=4`, removed from graph, wires auto-reconnected

## Edge Cases
1. **Slot 1 (primary image) bypassed**: Workflow still runs with no primary input — depends on workflow if valid
2. **All slots bypassed**: No images uploaded, workflow may have unresolved placeholders for all → ComfyUI error
3. **Bypass state survives app restart**: Yes, persisted in `WorkflowValues`
4. **Bypass in Inpainting mode**: Same mechanism applies (shares `prepareImageEditingWorkflowById`)
5. **Replace after Bypass**: Replacing an image slot clears its bypass state (toggle off if was bypassed)

## Unresolved Technical Question
What if a non-bypassed slot has no image provided (`sourceImage2 == null`)? Currently the code doesn't handle this — the placeholder remains in the JSON causing an error. This is an existing bug that also needs fixing when implementing bypass (or we require ALL non-bypassed slots to have images).

**Decision needed**: Should providing an image for a non-bypassed slot be required? Or should we treat "no image + not bypassed" as "bypass"? The safest approach: treat `null` (no image provided) as "slot not used" — bypass the node. This means: if user doesn't provide an image for slot 2, it's auto-bypassed (set `mode=4`).

This is actually the CORRECT semantic: "I didn't provide an image for slot 2" = "I don't want to use slot 2" = bypass. The current code (which leaves `{{image_filename_2}}` in JSON) is wrong and should be fixed.
