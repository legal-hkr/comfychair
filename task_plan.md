# Plan: 图片历史选择器功能

## 目标
在 `ImageToImageScreen` 底部工具条中增加按钮，点击后打开 `ModalBottomSheet`，从 `GalleryRepository` 的已生成图片中选择一张作为源图输入。

---

## Phase 1: 调研结果（完成）

### 关键发现

1. **`ImageToImageScreen.kt` 底部工具条**（行 632-758）
   - `Row` 包含 `GenerationButton(weight=1f)` + 设置按钮 `OutlinedIconButton`
   - 在 `GenerationButton` 和设置按钮之间有 `Spacer(width=8.dp)`

2. **`GalleryRepository`**（单例 `getInstance()`）
   - `galleryItems: StateFlow<List<GalleryItem>>` — 存储所有已生成图片
   - `GalleryItem` 包含 `promptId`, `filename`, `subfolder`, `type`
   - `toCacheKey()` → `MediaCacheKey` → `MediaCache.getBitmap(key)` 获取 Bitmap

3. **`rememberLazyBitmap`**（`LazyBitmap.kt`）
   - 现有 Composable，可直接复用从 `MediaCache` 异步加载 Bitmap

4. **源图选择机制**
   - `ImageToImageViewModel.onSourceImageChange(context, uri)` — slot 1
   - `ImageToImageViewModel.onAdditionalSourceImageChange(context, index, uri)` — slot 2/3/4
   - 接收 `Bitmap` 并存入 `MediaStateHolder`，同步更新 `UiState`

5. **槽位确定**
   - `pagerState.currentPage` + `previewPageIndex` 确定当前查看的是哪个槽位
   - `sourceSlot` 从 HorizontalPager page 推导（0→slot1, 1→slot2, 2→slot3, 3→slot4）
   - 预览页（previewPageIndex）→ fallback 到 slot 1

6. **已有 `ModalBottomSheet` 模式**
   - `showOptionsSheet` 控制设置面板显示，使用 `rememberModalBottomSheetState`
   - 可复用该模式实现图片选择器

---

## Phase 2: 实现计划

### 概述

**功能入口**: MediaViewerFloatingToolbar 中的 "Use as source" 按钮（Icons.Default.Collections）

**当前流程**:
1. 在 MediaViewer 中点击 "Use as source" 按钮
2. 返回 ImageToImageScreen，触发 `mediaViewerReplaceLauncher`
3. `mediaViewerReplaceLauncher` 打开系统文件选择器 `imagePickerLauncher`

**新流程**:
1. 在 MediaViewer 中点击 "Use as source" 按钮
2. 返回 ImageToImageScreen，触发 `mediaViewerReplaceLauncher`
3. `mediaViewerReplaceLauncher` 打开 `GalleryPickerBottomSheet`（替换系统文件选择器）
4. 选择历史图片 → Bitmap 直接存入对应槽位

---

### 步骤 1: 删除底部工具条中的 GalleryPicker 按钮

**位置**: `ImageToImageScreen.kt` 第 780-791 行

**删除内容**:
```kotlin
// Gallery picker button - pick from history
OutlinedIconButton(
    onClick = { showGalleryPicker = true },
    modifier = Modifier.size(56.dp)
) {
    Icon(
        Icons.Default.Collections,
        contentDescription = stringResource(R.string.button_pick_from_gallery)
    )
}

Spacer(modifier = Modifier.width(8.dp))
```

---

### 步骤 2: 修改 `mediaViewerReplaceLauncher` — 改为打开 GalleryPicker

**位置**: `ImageToImageScreen.kt` 第 224-243 行

**原代码**:
```kotlin
val mediaViewerReplaceLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    // ...
    if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
        val replaceSlot = data.getIntExtra(MediaViewerActivity.RESULT_SLOT, -1)
        if (replaceSlot > 0) {
            when (replaceSlot) {
                1 -> imagePickerLauncher.launch(arrayOf("image/*"))
                2 -> imagePickerLauncher2.launch(arrayOf("image/*"))
                3 -> imagePickerLauncher3.launch(arrayOf("image/*"))
                4 -> imagePickerLauncher4.launch(arrayOf("image/*"))
            }
        }
    }
}
```

**改为**:
```kotlin
val mediaViewerReplaceLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    // Always clear callbacks when MediaViewer closes
    MediaViewerActivity.onBypassToggleCallback = null
    MediaViewerActivity.onUseAsSourceCallback = null
    val data = result.data
    if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
        val replaceSlot = data.getIntExtra(MediaViewerActivity.RESULT_SLOT, -1)
        if (replaceSlot > 0) {
            // Set current slot and open gallery picker instead of file picker
            currentPickerSlot = replaceSlot
            showGalleryPicker = true
        }
    }
}
```

**新增状态**:
```kotlin
var currentPickerSlot by remember { mutableIntStateOf(1) }
```

---

### 步骤 3: 修改 `GalleryPickerBottomSheet` 调用 — 使用 `currentPickerSlot`

**位置**: `ImageToImageScreen.kt` 第 814 行附近

**原代码**:
```kotlin
GalleryPickerBottomSheet(
    galleryItems = galleryImages,
    onSelect = { item ->
        val cacheKey = item.toCacheKey()
        val bitmap = MediaCache.getBitmap(cacheKey)
        if (bitmap != null) {
            // 根据当前查看的槽位设置对应源图
            when (currentSourceSlot) {
                1 -> imageToImageViewModel.onSourceImageChange(context, item, bitmap)
                else -> imageToImageViewModel.onAdditionalSourceImageChange(context, currentSourceSlot, bitmap)
            }
        }
        showGalleryPicker = false
    },
    onDismiss = { showGalleryPicker = false },
    sheetState = galleryPickerSheetState
)
```

**改为**:
```kotlin
GalleryPickerBottomSheet(
    galleryItems = galleryImages,
    onSelect = { item ->
        val cacheKey = item.toCacheKey()
        val bitmap = MediaCache.getBitmap(cacheKey)
        if (bitmap != null) {
            // 使用 currentPickerSlot 确定目标槽位
            when (currentPickerSlot) {
                1 -> imageToImageViewModel.onSourceImageFromGallery(context, 1, bitmap)
                else -> imageToImageViewModel.onSourceImageFromGallery(context, currentPickerSlot, bitmap)
            }
        }
        showGalleryPicker = false
    },
    onDismiss = { showGalleryPicker = false },
    sheetState = galleryPickerSheetState
)
```

---

### 步骤 4: 确认 `onSourceImageFromGallery` 方法已存在

**位置**: `ImageToImageViewModel.kt`

检查是否已有方法:
```kotlin
fun onSourceImageFromGallery(context: Context, slot: Int, bitmap: Bitmap)
```

该方法应该：
- 将 `Bitmap` 存入 `MediaStateHolder` 对应 key（ItiSource / ItiSource2 / ItiSource3 / ItiSource4）
- 更新 `_uiState.value` 对应字段（sourceImage / sourceImage2 / sourceImage3 / sourceImage4）

如已存在（根据调研结果应该已存在），则跳过此步骤。

---

### 步骤 5: 清理未使用的 file picker 状态（如有）

当 `showGalleryPicker` 单独使用时（不从 MediaViewer 返回），需要确保 `currentPickerSlot` 有默认值 1。

---

## Phase 3: 验证清单

- [ ] 删除底部工具条中的 GalleryPicker 按钮（第 780-791 行）
- [ ] `mediaViewerReplaceLauncher` 改为设置 `showGalleryPicker = true`
- [ ] MediaViewer 中点击 "Use as source" → 打开 GalleryPickerBottomSheet
- [ ] 选择历史图片 → Bitmap 正确存入对应槽位（slot 1/2/3/4）
- [ ] 空状态（无历史图片）显示友好提示
- [ ] 字符串资源已存在（中英文）

---

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `ui/screens/ImageToImageScreen.kt` | 1. 删除底部工具条 GalleryPicker 按钮<br>2. 修改 `mediaViewerReplaceLauncher` 打开 GalleryPicker<br>3. 修改 BottomSheet 调用使用 `currentPickerSlot` |

---

## 潜在问题 & 注意事项

1. **Material Icons Extended**: 需确认 `PhotoLibrary` 图标是否在基础库中，如果不在，用 `Collections` 或 `Image` 代替
2. **视频过滤**: BottomSheet 只展示图片（`!it.isVideo`）
3. **性能**: `GalleryRepository.galleryItems` 可能很大，考虑限制显示数量（如 `take(50)`）
4. **离线模式**: GalleryRepository 在离线模式下可能无数据，需处理空状态
5. **Bitmap 加载**: `MediaCache.getBitmap()` 是同步的，但 Bitmap 可能不在缓存中（用户刚生成）。需要检查是否在缓存，不在则用 `rememberLazyBitmap` 异步加载后选择
