# Findings: 图片历史选择器功能

## 调研日期: 2026-04-30

## 核心需求
在 ImageToImageScreen 的底部工具条中增加按钮，从已生成图片中选择作为源图输入。

---

## 代码库关键发现

### 1. ImageToImageScreen.kt 底部工具条

**位置**: 行 632-758

```
Row (fillMaxWidth, padding 16dp bottom)
  ├── GenerationButton (weight=1f)
  ├── Spacer(width=8.dp)
  └── OutlinedIconButton (gear/settings icon) → toggle showOptionsSheet
```

按用户偏好，应该在 GenerationButton 和 Spacer 之间添加新按钮。

### 2. HorizontalPager 结构

**位置**: 行 434-576

- Slot 1 = page 0 (sourceImage)
- Slot 2 = page 1 (sourceImage2) — 如果 `additionalImageSlotCount >= 1`
- Slot 3 = page 2 (sourceImage3) — 如果 `additionalImageSlotCount >= 2`
- Slot 4 = page 3 (sourceImage4) — 如果 `additionalImageSlotCount >= 3`
- Preview = page `previewPageIndex` (previewImage)

`sourceSlot` 推导（行 451-457）:
```kotlin
val sourceSlot = when (sourcePageIndex) {
    0 -> 1; 1 -> 2; 2 -> 3; 3 -> 4; else -> 0
}
```

### 3. GalleryRepository

**单例**: `GalleryRepository.getInstance()`
**状态**: `galleryItems: StateFlow<List<GalleryItem>>`

`GalleryItem`:
```kotlin
data class GalleryItem(
    val promptId: String,
    val filename: String,
    val subfolder: String,
    val type: String,
    val isVideo: Boolean,
    val index: Int = 0
)
```

获取 Bitmap: `MediaCache.getBitmap(item.toCacheKey())`

### 4. ImageToImageViewModel 源图加载

现有方法接收 `Uri`:
```kotlin
fun onSourceImageChange(context: Context, uri: Uri) // slot 1
fun onAdditionalSourceImageChange(context: Context, index: Int, uri: Uri) // slot 2/3/4
```

从 Uri 解码 Bitmap 后存入 `MediaStateHolder`。历史图片已存于 `MediaCache`，需要新增接收 `Bitmap` 的方法。

### 5. MediaCache

- `getBitmap(key: MediaCacheKey): Bitmap?` — 同步查找缓存
- `rememberLazyBitmap(cacheKey, isVideo, subfolder, type)` — Composable 异步加载

### 6. MediaStateHolder

`putBitmap(key: MediaKey, bitmap: Bitmap, context)` — 存储 Bitmap 到指定 key

可用 key: `ItiSource`, `ItiSource2`, `ItiSource3`, `ItiSource4`

### 7. 已有 ModalBottomSheet 模式

`showOptionsSheet: Boolean` + `optionsSheetState: SheetState` 控制设置面板。

### 8. 字符串资源

`res/values/strings.xml` 中暂无相关字符串，需要新增。

---

## 架构约束

1. **图片来源**: 必须是 `MediaCache` 中已有的 Bitmap（用户已生成的图片）
2. **UI 模式**: ModalBottomSheet，与现有 Options Sheet 风格一致
3. **槽位**: 当前查看的 HorizontalPager page 确定 slot
4. **预览页 fallback**: preview page → slot 1
