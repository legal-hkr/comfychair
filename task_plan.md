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

### 步骤 1: 创建 `GalleryPickerBottomSheet.kt` Composable

**位置**: `ui/components/GalleryPickerBottomSheet.kt`

**功能**:
- 接收 `galleryItems: List<GalleryItem>`, `onSelect: (GalleryItem) -> Unit`, `onDismiss: () -> Unit`
- 使用 `LazyVerticalGrid`（2列）展示缩略图
- 每项用 `rememberLazyBitmap` 加载缩略图
- 点击项 → 调用 `onSelect(item)` → 自动 dismiss

**UI 布局**:
```
ModalBottomSheet
  ├── Header: "选择历史图片" + 关闭按钮
  └── LazyVerticalGrid (2列)
        ├── GalleryItemThumbnail (有图: Image; 无图: placeholder)
        └── EmptyState (无历史图片时)
```

**样式**:
- 参考 `GalleryScreen.kt` 的 `GalleryItemCard` 样式
- 正方形 `aspectRatio(1f)` 缩略图，`RoundedCornerShape(8.dp)`
- `8.dp` 间距

---

### 步骤 2: 修改 `ImageToImageScreen.kt` — 添加按钮

**位置**: 底部工具条 Row 中（`GenerationButton` 和设置按钮之间）

**新增状态**:
```kotlin
var showGalleryPicker by remember { mutableStateOf(false) }
val galleryPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
```

**新增按钮**（在 Spacer 前）:
```kotlin
OutlinedIconButton(
    onClick = { showGalleryPicker = true },
    modifier = Modifier.size(56.dp)
) {
    Icon(
        Icons.Default.PhotoLibrary,  // 或 Icons.Default.Collections
        contentDescription = stringResource(R.string.button_pick_from_gallery)
    )
}
```

**按钮位置**: GenerationButton 和 Spacer(8.dp) 之间（与现有 Spacer 并列）

**字符串资源**（`res/values/strings.xml`）:
```xml
<string name="button_pick_from_gallery">从历史记录选择</string>
<string name="title_gallery_picker">选择历史图片</string>
<string name="msg_gallery_picker_empty">暂无历史图片</string>
```

---

### 步骤 3: 修改 `ImageToImageScreen.kt` — 集成 BottomSheet

**位置**: `ImageToImageScreen` composable 函数末尾（`showOptionsSheet` 附近）

**新增逻辑**:
```kotlin
// Gallery picker bottom sheet
if (showGalleryPicker) {
    GalleryPickerBottomSheet(
        galleryItems = galleryRepository.galleryItems.value.filter { !it.isVideo },
        onSelect = { item ->
            // 从 MediaCache 获取 Bitmap
            val bitmap = MediaCache.getBitmap(item.toCacheKey())
            if (bitmap != null) {
                // 根据当前查看的槽位设置对应源图
                // sourceSlot 由 pagerState.currentPage 推导（见 ImageToImageScreen.kt 行 451-457）
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
}
```

**⚠️ 问题**: `onSourceImageChange` 接收 `Uri`，但历史图片在 `MediaCache` 中而非 URI。需要新增方法或扩展现有方法接收 `Bitmap`。

**解决方案**: 在 `ImageToImageViewModel` 中添加新方法:
```kotlin
fun onSourceImageFromGallery(context: Context, slot: Int, bitmap: Bitmap)
```

该方法将 `Bitmap` 直接存入 `MediaStateHolder` 并更新 `UiState`，无需经过 URI 解析。

---

### 步骤 4: 修改 `ImageToImageViewModel.kt` — 添加 `Bitmap` 接收方法

**新增方法**:
```kotlin
/**
 * Handle source image selection from gallery (already a Bitmap from MediaCache).
 * @param slot 1-4
 */
fun onSourceImageFromGallery(context: Context, slot: Int, bitmap: Bitmap) {
    viewModelScope.launch(Dispatchers.IO) {
        val key = when (slot) {
            1 -> MediaStateHolder.MediaKey.ItiSource
            2 -> MediaStateHolder.MediaKey.ItiSource2
            3 -> MediaStateHolder.MediaKey.ItiSource3
            4 -> MediaStateHolder.MediaKey.ItiSource4
            else -> return@launch
        }
        MediaStateHolder.putBitmap(key, bitmap, context)
        withContext(Dispatchers.Main) {
            val update = when (slot) {
                1 -> _uiState.value.copy(sourceImage = bitmap)
                2 -> _uiState.value.copy(sourceImage2 = bitmap)
                3 -> _uiState.value.copy(sourceImage3 = bitmap)
                4 -> _uiState.value.copy(sourceImage4 = bitmap)
                else -> return@withContext
            }
            _uiState.value = update
        }
    }
}
```

---

### 步骤 5: 确认当前槽位 `currentSourceSlot` 的计算

**代码位置**: HorizontalPager page → sourceSlot 推导（行 451-457）

```kotlin
val sourceSlot = when (sourcePageIndex) {
    0 -> 1
    1 -> 2
    2 -> 3
    3 -> 4
    else -> 0  // preview page
}
```

在 `ImageToImageScreen.kt` 中，`currentSourceSlot` 需要在 `ImageToImageScreen` composable 层计算，并传递给 BottomSheet。

**计算逻辑**:
```kotlin
val sourcePageIndex = if (pagerState.currentPage < previewPageIndex) pagerState.currentPage else -1
val currentSourceSlot = when (sourcePageIndex) {
    0 -> 1
    1 -> 2
    2 -> 3
    3 -> 4
    else -> 1  // preview page → default to slot 1
}
```

---

### 步骤 6: 添加 `Icons.Default.PhotoLibrary` import

**位置**: `ImageToImageScreen.kt` imports 区块

```kotlin
import androidx.compose.material.icons.filled.PhotoLibrary
```

（需确认 Material Icons Extended 是否已在依赖中）

---

## Phase 3: 验证清单

- [ ] 底部工具条显示新按钮
- [ ] 点击按钮打开 GalleryPickerBottomSheet
- [ ] BottomSheet 展示历史图片缩略图（来自 GalleryRepository）
- [ ] 点击缩略图 → 关闭 BottomSheet → 源图更新
- [ ] 预览页 fallback 到 slot 1
- [ ] source image 2/3/4 槽位正确路由
- [ ] 空状态（无历史图片）显示友好提示
- [ ] 字符串资源中英文都添加

---

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `ui/components/GalleryPickerBottomSheet.kt` | 新增 |
| `viewmodel/ImageToImageViewModel.kt` | 新增 `onSourceImageFromGallery` 方法 |
| `ui/screens/ImageToImageScreen.kt` | 添加按钮 + BottomSheet 集成 |
| `res/values/strings.xml` | 添加字符串资源 |
| `res/values-zh/strings.xml` | 添加中文字符串资源 |

---

## 潜在问题 & 注意事项

1. **Material Icons Extended**: 需确认 `PhotoLibrary` 图标是否在基础库中，如果不在，用 `Collections` 或 `Image` 代替
2. **视频过滤**: BottomSheet 只展示图片（`!it.isVideo`）
3. **性能**: `GalleryRepository.galleryItems` 可能很大，考虑限制显示数量（如 `take(50)`）
4. **离线模式**: GalleryRepository 在离线模式下可能无数据，需处理空状态
5. **Bitmap 加载**: `MediaCache.getBitmap()` 是同步的，但 Bitmap 可能不在缓存中（用户刚生成）。需要检查是否在缓存，不在则用 `rememberLazyBitmap` 异步加载后选择
