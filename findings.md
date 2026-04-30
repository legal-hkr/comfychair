# Findings: 图片历史选择器功能

## 调研日期: 2026-04-30

## 核心需求
在 ImageToImageScreen 的底部工具条中增加按钮，从已生成图片中选择作为源图输入。

---

## 代码库关键发现

### 1. 当前实现状态

**GalleryPickerBottomSheet.kt** 已存在：
- 位置: `ui/components/GalleryPickerBottomSheet.kt`
- 布局: 3列 LazyVerticalGrid
- 使用 `rememberLazyBitmap` 异步加载缩略图
- 空状态显示友好提示

**ImageToImageViewModel.onSourceImageFromGallery** 已存在：
```kotlin
fun onSourceImageFromGallery(context: Context, slot: Int, bitmap: Bitmap)
```
- 将 Bitmap 存入 MediaStateHolder（ItiSource/ItiSource2/ItiSource3/ItiSource4）
- 更新 _uiState.value 对应字段（sourceImage/sourceImage2/sourceImage3/sourceImage4）

### 2. 错误的位置

**底部工具条 GalleryPicker 按钮**（第 780-791 行）— 需要删除：
```kotlin
OutlinedIconButton(
    onClick = { showGalleryPicker = true },
    modifier = Modifier.size(56.dp)
) {
    Icon(Icons.Default.Collections, ...)
}
Spacer(modifier = Modifier.width(8.dp))
```

这个按钮是之前加在 GenerationButton 和 Settings 按钮之间的，Nick 不需要这个位置。

### 3. 正确的入口：MediaViewer "Use as source" 按钮

**MediaViewerFloatingToolbar**（MediaViewerScreen.kt 第 462-468 行）：
```kotlin
IconButton(onClick = onUseAsSource) {
    Icon(Icons.Default.Collections, ...)
}
```

**MediaViewerActivity.onUseAsSourceCallback** 设置后，返回 `ImageToImageScreen` 时会携带 `RESULT_SLOT`。

### 4. 需要修改的代码

**mediaViewerReplaceLauncher**（ImageToImageScreen.kt 第 224-243 行）：
```kotlin
// 当前行为：打开系统文件选择器
when (replaceSlot) {
    1 -> imagePickerLauncher.launch(arrayOf("image/*"))
    2 -> imagePickerLauncher2.launch(arrayOf("image/*"))
    ...
}

// 修改为：打开 GalleryPickerBottomSheet
currentPickerSlot = replaceSlot
showGalleryPicker = true
```

**新增状态**:
```kotlin
var currentPickerSlot by remember { mutableIntStateOf(1) }
```

### 5. GalleryRepository

- 单例: `GalleryRepository.getInstance()`
- 状态: `galleryItems: StateFlow<List<GalleryItem>>`
- 获取 Bitmap: `MediaCache.getBitmap(item.toCacheKey())`

---

## 架构约束

1. **图片来源**: 必须是 `MediaCache` 中已有的 Bitmap（用户已生成的图片）
2. **UI 模式**: ModalBottomSheet，与现有 Options Sheet 风格一致
3. **槽位**: 当前查看的 HorizontalPager page 确定 slot
4. **预览页 fallback**: preview page → slot 1
